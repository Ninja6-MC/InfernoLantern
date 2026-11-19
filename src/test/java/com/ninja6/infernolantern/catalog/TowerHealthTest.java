package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.health.TargetHealth;
import com.ninja6.infernolantern.health.TowerHealth;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.structure.StructureLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class TowerHealthTest {
    @TempDir Path directory;
    final Owner owner = new Owner();
    final AtomicLong clock = new AtomicLong(1_000);
    SqliteRepository repository;
    CatalogService catalog;
    StructureLifecycle structures;
    TowerHealth health;
    CatalogService.Snapshot settings;

    @BeforeEach void open() throws Exception {
        repository = get(SqliteRepository.open(directory.resolve("health.db"), 256));
        catalog = new CatalogService(repository, owner, definition -> {}, published -> CompletableFuture.completedFuture(null));
        structures = new StructureLifecycle(repository, owner, clock::get);
        health = new TowerHealth(repository, catalog, owner, structures, clock::get);
        settings = get(catalog.reload(config())).snapshot();
    }

    @AfterEach void close() throws Exception {
        structures.close();
        catalog.close();
        get(repository.closeAsync());
    }

    TowerSnapshot read(UUID tower) throws Exception {
        return get(repository.tower(tower)).orElseThrow();
    }

    TowerHealth.Admission allowedDamage(TowerSnapshot row) {
        owner.assertOwner();
        return new TowerHealth.Admission(null);
    }

    class CompactEffects implements WorldEffects {
        boolean succeeds = true;
        boolean safeRestore = true;
        int applied;
        int restored;

        @Override public Preparation prepare(Request request, TowerSnapshot row) {
            owner.assertOwner();
            assertEquals(Action.DEFEAT, request.action());
            assertNull(request.actor());
            assertNull(request.session());
            assertFalse(request.policy().explicitOverride());
            assertEquals(0, row.state().tower().hp());
            return new Preparation(List.of(row.state().components().getFirst()), null, new Document(1, "Fixture effects"));
        }

        @Override public boolean validate(Claim claim) { owner.assertOwner(); return true; }
        @Override public boolean apply(Claim claim) { owner.assertOwner(); applied++; return succeeds; }
        @Override public boolean verify(Claim claim) { owner.assertOwner(); return true; }
        @Override public boolean rollback(Claim claim) { owner.assertOwner(); restored++; return safeRestore; }
        @Override public void discard(Request request, Preparation prepared) { owner.assertOwner(); }
        @Override public boolean activate(Claim claim, TowerSnapshot completed) { owner.assertOwner(); return true; }
    }

    @Test void positiveDamageBelowThresholdKeepsServicesAndNeverCompacts() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var updated = owner.drain(health.damage(settings, before, new TowerHealth.Damage(35, 200), this::allowedDamage,
                UUID.randomUUID(), row -> fail("Positive HP cannot compact")));
        assertNull(updated.defeat());
        var saved = updated.settled().state().tower();
        assertEquals(Form.EXPANDED, saved.form());
        assertEquals(40, saved.hp());
        assertEquals(170, saved.expandAfter());
        assertEquals(1_200, saved.repairAfter());
        assertEquals(ACTOR, saved.controller());
        assertEquals(new CatalogService.Effective(true, true, 15),
                CatalogService.effective(settings, updated.settled(), world(), allowed()));
        assertTrue(get(repository.pendingOperations(null, 10)).isEmpty());
    }

    @Test void zeroRemainsInactiveAfterCompensatedFailureAndFreshRetrySetsOneTimer() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var id = before.state().tower().id();
        var failure = new CompactEffects();
        failure.succeeds = false;
        UUID first = UUID.randomUUID();
        var damage = owner.drain(health.damage(settings, before, new TowerHealth.Damage(1_000, 400), this::allowedDamage,
                first, row -> {
                    owner.assertOwner();
                    assertEquals(new CatalogService.Effective(false, false, 0),
                            CatalogService.effective(settings, row, world(), allowed()));
                    return failure;
                }));
        assertEquals(Outcome.ROLLED_BACK, damage.defeat().outcome());
        assertEquals(1, failure.restored);
        var failed = read(id);
        assertTrue(failed.mutationReady());
        assertEquals(0, failed.state().tower().hp());
        assertEquals(ACTOR, failed.state().tower().controller());
        assertEquals(170, failed.state().tower().expandAfter());
        assertEquals(1_400, failed.state().tower().repairAfter());

        close();
        open();
        clock.set(2_000);
        var recoveredAtBoot = read(id);
        assertEquals(failed, recoveredAtBoot);
        assertEquals(Form.EXPANDED, recoveredAtBoot.state().tower().form());
        var success = new CompactEffects();
        var operation = UUID.randomUUID();
        assertEquals(Outcome.COMMITTED,
                owner.drain(health.compactDefeated(settings, recoveredAtBoot, operation, row -> success)).outcome());
        var compact = read(id);
        assertEquals(Form.COMPACT, compact.state().tower().form());
        assertEquals(0, compact.state().tower().hp());
        assertNull(compact.state().tower().controller());
        assertEquals(2, compact.state().tower().generation());
        assertEquals(2, compact.state().tower().deploymentRevision());
        assertEquals(62_000, compact.state().tower().expandAfter());
        assertEquals(1_400, compact.state().tower().repairAfter());
        assertTrue(compact.state().tower().light());
        assertTrue(compact.state().tower().defence());
        assertEquals(1, get(repository.structureCells(id)).size());

        assertEquals(Outcome.REJECTED,
                owner.drain(health.compactDefeated(settings, compact, UUID.randomUUID(), row -> fail("Already compact"))).outcome());
        assertEquals(compact, read(id));
        assertEquals(1, success.applied);
    }

    @Test void delayedDefeatStartsCooldownAtVerifiedExitAndSameReceiptDoesNotRestartIt() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var op = UUID.randomUUID();
        var delayed = new CompactEffects() {
            @Override public boolean apply(Claim claim) {
                assertEquals(170, claim.operation().intent().after().tower().expandAfter());
                clock.set(121_000);
                return super.apply(claim);
            }
            @Override public boolean activate(Claim claim, TowerSnapshot completed) {
                assertEquals(completed.state(), claim.operation().intent().after());
                return super.activate(claim, completed);
            }
        };
        var hit = owner.drain(health.damage(settings, before, new TowerHealth.Damage(75, 200), this::allowedDamage,
                op, zero -> delayed));
        assertEquals(Outcome.COMMITTED, hit.defeat().outcome());
        var compact = read(before.state().tower().id());
        assertEquals(181_000, compact.state().tower().expandAfter());
        assertEquals(1_200, compact.state().tower().repairAfter());
        var receipt = get(repository.operation(op)).orElseThrow();
        assertEquals(compact.state(), receipt.intent().after());
        clock.set(300_000);
        var replay = new CompactEffects();
        assertEquals(Outcome.COMMITTED, owner.drain(health.compactDefeated(settings, hit.settled(), op, row -> replay)).outcome());
        assertEquals(0, replay.applied);
        assertEquals(receipt, get(repository.operation(op)).orElseThrow());
        assertEquals(compact, read(before.state().tower().id()));
    }

    @Test void timedExitAdmissionRejectsADurationOutsideTheManagedDefinition() throws Exception {
        var row=placed(repository,Form.EXPANDED);
        var tower=row.state().tower();
        var compact=new Tower(tower.id(),tower.variantId(),tower.hp(),tower.maximumHp(),Form.COMPACT,tower.generation(),
                tower.deploymentRevision()+1,null,tower.position(),tower.light(),tower.defence(),tower.expandAfter(),tower.repairAfter());
        var after=new State(compact,null,List.of(row.state().components().getFirst()));
        var anchor=new com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor(WORLD,0,0);
        var recipe=RecoveryEnvelope.timedStructure(STRUCTURE_EXIT_COMPACT,anchor,new Document(1,"Fixture"),1);
        var intent=new Intent(UUID.randomUUID(),tower.id(),row.rowVersion(),tower.generation(),tower.deploymentRevision(),
                STRUCTURE_EXIT_COMPACT,after,recipe);
        assertThrows(ExecutionException.class,()->get(repository.beginStructureIntent(intent,settings.revision())));
        assertTrue(get(repository.operation(intent.id())).isEmpty());
        assertEquals(row,read(tower.id()));
    }

    @Test void genericCompletionCannotPublishADeadlineFromPendingExitEvidence() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var op = UUID.randomUUID();
        var hit = health.damage(settings, before, new TowerHealth.Damage(75, 200), this::allowedDamage,
                op, zero -> new CompactEffects());
        // Authorization, factory, prepare, apply and verify, then leave the commit callback queued.
        for (int i = 0; i < 5; i++) {
            owner.await();
            owner.run();
        }
        owner.await();
        var applied = get(repository.operation(op)).orElseThrow();
        assertEquals(Phase.EFFECTS_APPLIED, applied.phase());
        assertEquals(170, applied.intent().after().tower().expandAfter());
        assertThrows(ExecutionException.class, () -> get(repository.completeOperation(op, applied.rowVersion(), "Fixture")));

        try (var writer = SqliteTestConnections.open(directory.resolve("health.db"))) {
            writer.setAutoCommit(false);
            owner.run();
            clock.set(241_000);
            writer.setAutoCommit(true);
        }
        assertEquals(Outcome.COMMITTED, owner.drain(hit).defeat().outcome());
        assertEquals(301_000, read(before.state().tower().id()).state().tower().expandAfter());
    }

    @Test void uncertainDefeatRetainsZeroAndRejectsRetryWithoutPhysicalReplay() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var denied = new CompactEffects();
        denied.succeeds = false;
        denied.safeRestore = false;
        var operation = UUID.randomUUID();
        var damage = owner.drain(health.damage(settings, before, new TowerHealth.Damage(75, 0), this::allowedDamage,
                operation, row -> denied));
        assertEquals(Outcome.QUARANTINED, damage.defeat().outcome());
        var pending = read(before.state().tower().id());
        assertEquals(0, pending.state().tower().hp());
        assertFalse(pending.mutationReady());
        assertEquals(operation, pending.activeOperation());
        assertEquals(Phase.RECONCILING, get(repository.operation(operation)).orElseThrow().phase());
        assertEquals(Outcome.REJECTED, owner.drain(health.compactDefeated(settings, pending, UUID.randomUUID(),
                row -> fail("Pending operation forbids retry"))).outcome());
        assertEquals(1, denied.applied);
    }

    @Test void deniedStaleAndRevokedDamageCannotDebitHealthOrAlterRepairLock() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        UUID id = before.state().tower().id();
        var hit = new TowerHealth.Damage(10, 200);
        var operation = UUID.randomUUID();
        TowerHealth.DefeatEffects never = row -> fail("No defeat");
        assertThrows(ExecutionException.class, () -> owner.drain(health.damage(settings, before, hit, row -> null, operation, never)));
        CommitPermit revoked = new CommitPermit();
        revoked.revoke();
        assertThrows(ExecutionException.class, () -> owner.drain(health.damage(settings, before, hit,
                row -> new TowerHealth.Admission(revoked), operation, never)));
        assertEquals(before, read(id));

        var result = owner.drain(health.damage(settings, before, hit, this::allowedDamage, operation, never));
        assertThrows(ExecutionException.class, () -> owner.drain(health.damage(settings, before, hit,
                row -> fail("Stale source cannot reach owner authorization"), operation, never)));
        assertEquals(result.settled(), read(id));
    }

    @Test void catalogPublicationSupersedesQueuedDamageForTheOldMaximum() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var oldSettings = settings;
        var hit = health.damage(oldSettings, before, new TowerHealth.Damage(100, 200),
                row -> fail("Old catalog cannot authorize damage"), UUID.randomUUID(), row -> fail("No defeat"));
        owner.await();
        var smaller = variant();
        smaller.put("maximum-hp", 50);
        settings = get(catalog.reload(config(Map.of("test", smaller)))).snapshot();
        assertThrows(ExecutionException.class, () -> owner.drain(hit));
        var current = read(before.state().tower().id());
        assertEquals(50, current.state().tower().hp());
        assertEquals(50, current.state().tower().maximumHp());
        assertEquals(230, current.state().tower().repairAfter());
        assertEquals(before.rowVersion() + 1, current.rowVersion());
    }

    @Test void capPublicationAndRowMutationsInvalidateQueuedPrivateSamples() throws Exception {
        TargetHealth view = new TargetHealth(repository, owner);
        var row = placed(repository, Form.EXPANDED);
        var viewer = UUID.randomUUID();
        var samples = new ArrayList<TargetHealth.Sample>();
        var audience = new TargetHealth.Audience() {
            @Override public boolean targets(UUID id, TowerSnapshot deployed) { owner.assertOwner(); return viewer.equals(id); }
            @Override public void display(UUID id, TargetHealth.Sample sample) {
                owner.assertOwner();
                assertEquals(viewer, id);
                samples.add(sample);
            }
        };

        var queued = view.show(viewer, row, audience);
        owner.await();
        var smaller = variant();
        smaller.put("maximum-hp", 50);
        settings = get(catalog.reload(config(Map.of("test", smaller)))).snapshot();
        assertFalse(owner.drain(queued));
        assertNull(samples.removeFirst());

        var clamped = read(row.state().tower().id());
        assertTrue(owner.drain(view.show(viewer, clamped, audience)));
        assertEquals(new TargetHealth.Sample(50, 50), samples.removeFirst());

        var stale = view.show(viewer, clamped, audience);
        owner.await();
        // Apply a durable hit while the render waits in the owner queue.
        var t = clamped.state().tower();
        get(repository.damageTower(settings.revision(), t.id(), clamped.rowVersion(), t.generation(),
                t.deploymentRevision(), 10, 400, null));
        assertFalse(owner.drain(stale));
        assertNull(samples.removeFirst());
        assertTrue(samples.isEmpty());
    }

    @Test void failedDisplaySchedulingReleasesTheBoundedPermitAndShutdownInvalidatesIt() throws Exception {
        var row = placed(repository, Form.EXPANDED);
        get(repository.closeAsync());
        repository = get(SqliteRepository.open(directory.resolve("health.db"), 1));
        TargetHealth view = new TargetHealth(repository, owner);
        var audience = new TargetHealth.Audience() {
            @Override public boolean targets(UUID id, TowerSnapshot deployed) { return fail("Unavailable owner"); }
            @Override public void display(UUID id, TargetHealth.Sample sample) { fail("Unavailable owner"); }
        };
        owner.available = false;
        assertThrows(ExecutionException.class, () -> owner.drain(view.show(UUID.randomUUID(), row, audience)));
        owner.available = true;

        var permit = get(repository.observeTower(row)).orElseThrow();
        assertTrue(permit.current());
        assertTrue(get(repository.observeTower(row)).isEmpty());
        var closing = repository.closeAsync();
        assertFalse(permit.current());
        permit.close();
        get(closing);
    }

    @Test void targetProofCannotPublishAfterItCausesASynchronousRowChange() throws Exception {
        TargetHealth view = new TargetHealth(repository, owner);
        var row = placed(repository, Form.EXPANDED);
        var viewer = UUID.randomUUID();
        var audience = new TargetHealth.Audience() {
            @Override public boolean targets(UUID id, TowerSnapshot deployed) {
                owner.assertOwner();
                var t = deployed.state().tower();
                try {
                    get(repository.damageTower(settings.revision(), t.id(), deployed.rowVersion(), t.generation(),
                            t.deploymentRevision(), 5, 400, null));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return true;
            }
            @Override public void display(UUID id, TargetHealth.Sample sample) { owner.assertOwner(); assertNull(sample); }
        };
        assertFalse(owner.drain(view.show(viewer, row, audience)));
    }

    @Test void privateHealthTracksOnlyCurrentDeployedTargetAndClearsOnLoss() throws Exception {
        TargetHealth view = new TargetHealth(repository, owner);
        var row = placed(repository, Form.EXPANDED);
        var viewer = UUID.randomUUID();
        var other = UUID.randomUUID();
        var samples = new HashMap<UUID, TargetHealth.Sample>();
        var target = new java.util.concurrent.atomic.AtomicReference<>(viewer);
        var audience = new TargetHealth.Audience() {
            @Override public boolean targets(UUID id, TowerSnapshot deployed) {
                owner.assertOwner();
                assertEquals(row, deployed);
                return id.equals(target.get());
            }
            @Override public void display(UUID id, TargetHealth.Sample sample) {
                owner.assertOwner();
                if (sample == null) samples.remove(id);
                else samples.put(id, sample);
            }
        };
        assertTrue(owner.drain(view.show(viewer, row, audience)));
        assertFalse(owner.drain(view.show(other, row, audience)));
        assertEquals(Map.of(viewer, new TargetHealth.Sample(75, 100)), samples);
        var queued = view.show(viewer, row, audience);
        owner.await();
        target.set(other);
        assertFalse(owner.drain(queued));
        assertTrue(samples.isEmpty());

        assertTrue(owner.drain(view.show(other, row, audience)));
        owner.drain(view.clear(other, new com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor(WORLD, 0, 0), audience));
        assertTrue(samples.isEmpty());

        var changed = owner.drain(health.damage(settings, row, new TowerHealth.Damage(10, 0), this::allowedDamage,
                UUID.randomUUID(), deployed -> fail("No defeat"))).settled();
        assertNotEquals(row, changed);
        assertFalse(owner.drain(view.show(other, row, audience)));
        assertTrue(samples.isEmpty());
    }
}
