package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemLifecycleTest {
    @TempDir Path directory;
    SqliteRepository repository;
    ItemLifecycle service;
    final UUID towerId = new UUID(0, 1), world = new UUID(0, 2), entity = new UUID(0, 3);
    final WorldScheduler.Anchor anchor = new WorldScheduler.Anchor(world, 0, 0);
    final OwnerScheduler scheduler = new OwnerScheduler();
    final AtomicInteger physicalEffects = new AtomicInteger();
    final ItemLifecycle.Effects successful = new ItemLifecycle.Effects() {
        public boolean apply(ItemLifecycle.Claim claim) { scheduler.assertOwner(); physicalEffects.incrementAndGet(); return true; }
        public boolean verify(ItemLifecycle.Claim claim) { scheduler.assertOwner(); return true; }
    };
    @BeforeEach void setup() throws Exception {
        repository = await(SqliteRepository.open(directory.resolve("items.db"), 256));
        await(repository.createVariant(new Variant("fixture", 1, VariantStatus.ACTIVE, new Document(1, "generic fixture"), 0)));
        await(repository.createTower(new Tower(towerId, "fixture", 17, 100, Form.PORTABLE, 1, 0, null, null, true, false, 33, 44)));
        service = new ItemLifecycle(repository, scheduler);
    }
    @AfterEach void close() throws Exception { service.close(); await(repository.closeAsync()); }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    TowerSnapshot current() throws Exception { return await(repository.tower(towerId)).orElseThrow(); }
    ItemToken token(long generation) { return new ItemToken(1, "fixture", towerId, generation); }
    ItemLifecycle.Request request(ItemLifecycle.Action action, ItemToken token, UUID entity) {
        return new ItemLifecycle.Request(UUID.randomUUID(), action, token, 1, entity, anchor, null, new Document(1, "synthetic exact source snapshot"));
    }
    ItemLifecycle.Result drain(CompletableFuture<ItemLifecycle.Result> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone()) { scheduler.runOne(); if (System.nanoTime() > deadline) fail("Timed out"); }
        return await(future);
    }
    void pendingOwner() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (scheduler.actions.isEmpty()) { if (System.nanoTime() > deadline) fail("Timed out waiting for owner callback"); Thread.sleep(1); }
    }
    @Test void exactPortableCopiesRaceWithOneDurableWinnerAndOnePhysicalEffect() throws Exception {
        var futures = new ArrayList<CompletableFuture<ItemLifecycle.Result>>();
        for (int i = 0; i < 32; i++) futures.add(service.execute(request(ItemLifecycle.Action.DROP, token(1), UUID.randomUUID()), successful));
        int winners = 0;
        for (var future : futures) if (drain(future).outcome() == ItemLifecycle.Outcome.COMMITTED) winners++;
        assertEquals(1, winners); assertEquals(1, physicalEffects.get());
        var state = current().state(); assertEquals(Form.GROUND, state.tower().form()); assertEquals(2, state.tower().generation());
        assertEquals(17, state.tower().hp()); assertEquals(33, state.tower().expandAfter()); assertEquals(44, state.tower().repairAfter());
    }
    @Test void pickupRotatesTokenAndStaleExpiryCannotExpirePortableOrLaterGround() throws Exception {
        assertEquals(ItemLifecycle.Outcome.COMMITTED, drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful)).outcome());
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.LOSS, token(2), UUID.randomUUID()), successful)).outcome());
        assertEquals(ItemLifecycle.Outcome.COMMITTED, drain(service.execute(request(ItemLifecycle.Action.PICKUP, token(2), entity), successful)).outcome());
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.LOSS, token(2), entity), successful)).outcome());
        UUID laterEntity = new UUID(0, 4);
        drain(service.execute(request(ItemLifecycle.Action.DROP, token(3), laterEntity), successful));
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.LOSS, token(4), entity), successful)).outcome());
        assertEquals(laterEntity, current().state().ground().entity());
        assertEquals(ItemLifecycle.Outcome.COMMITTED, drain(service.execute(request(ItemLifecycle.Action.LOSS, token(4), laterEntity), successful)).outcome());
        assertEquals(Form.LOST, current().state().tower().form());
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful)).outcome());
    }
    @Test void restoreLikeCopyIsRejectedWithoutCallingPhysicalAdapter() throws Exception {
        drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful));
        drain(service.execute(request(ItemLifecycle.Action.PICKUP, token(2), entity), successful));
        int before = physicalEffects.get();
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful)).outcome());
        assertEquals(before, physicalEffects.get()); assertEquals(Form.PORTABLE, current().state().tower().form());
        assertEquals(3, current().state().tower().generation()); assertTrue(ItemLifecycle.REJECTION.contains("contact an admin"));
    }
    @Test void disconnectedOrChangedSourceAtCallbackRemainsFrozenWithoutRefund() throws Exception {
        var future = service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), new ItemLifecycle.Effects() {
            public boolean apply(ItemLifecycle.Claim claim) { return false; }
            public boolean verify(ItemLifecycle.Claim claim) { fail("No verification after failed preconditions"); return true; }
        });
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(future).outcome());
        assertEquals(Form.PORTABLE, current().state().tower().form()); assertFalse(current().mutationReady());
        assertEquals(Phase.RECONCILING, await(repository.pendingOperations(null, 10)).getFirst().phase());
        assertEquals(0, physicalEffects.get());
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful)).outcome());
    }
    @Test void changedSessionAfterPhysicalEffectQuarantinesBeforeCompletion() throws Exception {
        var future = service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), new ItemLifecycle.Effects() {
            public boolean apply(ItemLifecycle.Claim claim) { physicalEffects.incrementAndGet(); return true; }
            public boolean verify(ItemLifecycle.Claim claim) { return false; }
        });
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(future).outcome());
        assertFalse(current().mutationReady()); assertEquals(1, physicalEffects.get());
    }
    @Test void unloadedChunkIsNotLossAndCannotApplyOwnerEffects() throws Exception {
        var request = request(ItemLifecycle.Action.DROP, token(1), entity);
        var future = service.execute(request, successful); pendingOwner(); scheduler.available = false;
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(future).outcome());
        assertEquals(0, physicalEffects.get()); assertEquals(Form.PORTABLE, current().state().tower().form());
        assertEquals(Phase.RECONCILING, await(repository.operation(request.operation())).orElseThrow().phase());
    }
    @Test void unexpectedRemovalQuarantinesCurrentEntityButUnloadWithoutRemovalDoesNothing() throws Exception {
        drain(service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful));
        TowerSnapshot beforeUnload = current();
        // Suspending scheduling/availability does not run a disappearance or LOST transition.
        scheduler.available = false; assertEquals(beforeUnload, current()); scheduler.available = true;
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(service.unexpectedRemoval(request(ItemLifecycle.Action.LOSS, token(2), entity))).outcome());
        assertEquals(Form.GROUND, current().state().tower().form()); assertFalse(current().mutationReady());
    }
    @Test void completedReceiptDoesNotReplayAndPendingRestartRequiresReconciliation() throws Exception {
        var request = request(ItemLifecycle.Action.DROP, token(1), entity);
        drain(service.execute(request, successful)); int effects = physicalEffects.get();
        assertEquals(ItemLifecycle.Outcome.COMMITTED, drain(service.execute(request, successful)).outcome());
        assertEquals(effects, physicalEffects.get());
        var pending = request(ItemLifecycle.Action.PICKUP, token(2), entity);
        var future = service.execute(pending, successful); pendingOwner(); service.close();
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(future).outcome());
        await(repository.closeAsync()); repository = await(SqliteRepository.open(directory.resolve("items.db"), 256));
        service = new ItemLifecycle(repository, scheduler);
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(service.execute(pending, successful)).outcome());
        assertEquals(effects, physicalEffects.get());
    }
    @Test void ownerExceptionAfterPartialEffectsIsQuarantined() throws Exception {
        var future = service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), new ItemLifecycle.Effects() {
            public boolean apply(ItemLifecycle.Claim claim) { physicalEffects.incrementAndGet(); throw new IllegalStateException("synthetic post-removal failure"); }
            public boolean verify(ItemLifecycle.Claim claim) { return true; }
        });
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(future).outcome());
        assertEquals(1, physicalEffects.get()); assertFalse(current().mutationReady());
    }
    State compact() {
        return new State(new Tower(towerId, "fixture", 17, 100, Form.COMPACT, 2, 1, null,
                new Position(world, 4, 70, 4, "north"), true, false, 33, 44), null,
                List.of(new Component("base", ComponentKind.BLOCK, world, 4, 70, 4, "minecraft:air", "minecraft:barrier[waterlogged=false]", null, null)));
    }
    ItemLifecycle.Request place() {
        return new ItemLifecycle.Request(UUID.randomUUID(), ItemLifecycle.Action.PLACE, token(1), 1, null, anchor, compact(), new Document(1, "synthetic placement source/volume snapshot"));
    }
    @Test void exactDuplicatePlacementClaimsHaveOneWinnerAndRestoredSourceIsUntouched() throws Exception {
        var requests = new ArrayList<CompletableFuture<ItemLifecycle.Result>>();
        for (int i = 0; i < 24; i++) requests.add(service.execute(place(), successful));
        int winners = 0;
        for (var request : requests) if (drain(request).outcome() == ItemLifecycle.Outcome.COMMITTED) winners++;
        assertEquals(1, winners); assertEquals(1, physicalEffects.get()); assertEquals(compact(), current().state());
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(place(), successful)).outcome());
        assertEquals(1, physicalEffects.get());
    }
    @Test void placementAndDropCompeteForTheSameGeneration() throws Exception {
        var placement = service.execute(place(), successful);
        var drop = service.execute(request(ItemLifecycle.Action.DROP, token(1), entity), successful);
        var results = List.of(drain(placement).outcome(), drain(drop).outcome());
        assertEquals(1, results.stream().filter(result -> result == ItemLifecycle.Outcome.COMMITTED).count());
        assertEquals(1, physicalEffects.get()); assertEquals(2, current().state().tower().generation());
    }
    @Test void placementCannotUseLoreHealthOrDifferentWorld() throws Exception {
        State expected = compact(); Tower t = expected.tower();
        Tower altered = new Tower(t.id(), t.variantId(), 100, t.maximumHp(), t.form(), t.generation(), t.deploymentRevision(), null,
                t.position(), t.light(), t.defence(), t.expandAfter(), t.repairAfter());
        var request = new ItemLifecycle.Request(UUID.randomUUID(), ItemLifecycle.Action.PLACE, token(1), 1, null, anchor,
                new State(altered, null, expected.components()), new Document(1, "forged cached HP"));
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(request, successful)).outcome());
        assertEquals(17, current().state().tower().hp()); assertEquals(0, physicalEffects.get());
        var wrongWorld = new ItemLifecycle.Request(UUID.randomUUID(), ItemLifecycle.Action.PLACE, token(1), 1, null,
                new WorldScheduler.Anchor(new UUID(0, 99), 0, 0), compact(), new Document(1, "wrong anchor"));
        assertEquals(ItemLifecycle.Outcome.REJECTED, drain(service.execute(wrongWorld, successful)).outcome());
    }
    @Test void completionStorageFailureLeavesQuarantinedOwnershipAndNeverReplays() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("items.db")); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_item_completion BEFORE UPDATE ON towers WHEN NEW.form='GROUND' BEGIN SELECT RAISE(ABORT,'synthetic item completion failure'); END");
        }
        var request = request(ItemLifecycle.Action.DROP, token(1), entity);
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(service.execute(request, successful)).outcome());
        assertEquals(1, physicalEffects.get()); assertFalse(current().mutationReady());
        assertEquals(Form.PORTABLE, current().state().tower().form());
        assertEquals(Phase.RECONCILING, await(repository.operation(request.operation())).orElseThrow().phase());
        assertEquals(ItemLifecycle.Outcome.QUARANTINED, drain(service.execute(request, successful)).outcome());
        assertEquals(1, physicalEffects.get());
    }
    @Test void metadataAndStackBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ItemToken(2, "fixture", towerId, 1));
        assertThrows(IllegalArgumentException.class, () -> new ItemToken(1, "invalid variant", towerId, 1));
        assertThrows(ArithmeticException.class, () -> token(Long.MAX_VALUE).next());
        assertThrows(IllegalArgumentException.class, () -> new ItemLifecycle.Request(UUID.randomUUID(), ItemLifecycle.Action.DROP, token(1), 2, entity, anchor, null, new Document(1, "stack")));
        assertTrue(GroundPolicy.DEFAULT.survivesFireAndLava()); assertTrue(GroundPolicy.DEFAULT.despawns());
    }
    static final class OwnerScheduler implements WorldScheduler {
        final Thread owner = Thread.currentThread();
        final BlockingQueue<Runnable> actions = new LinkedBlockingQueue<>();
        boolean available = true;
        void assertOwner() { assertSame(owner, Thread.currentThread()); }
        public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action) {
            var future = new CompletableFuture<T>();
            actions.add(() -> {
                assertOwner();
                if (!available) future.completeExceptionally(new IllegalStateException("Synthetic chunk unloaded"));
                else try { future.complete(action.get()); } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            return future;
        }
        void runOne() throws InterruptedException { Runnable action = actions.poll(5, TimeUnit.MILLISECONDS); if (action != null) action.run(); }
    }
}
