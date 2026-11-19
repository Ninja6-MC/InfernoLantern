package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.health.TowerHealth;
import com.ninja6.infernolantern.repair.RepairQuote;
import com.ninja6.infernolantern.repair.RepairService;
import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.repair.RepairService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRepairTest {
    @TempDir Path temporary;

    private static final String RESOURCE = "minecraft:iron_ingot";
    private final Owner owner = new Owner();
    private final AtomicLong time = new AtomicLong(1_000);
    private SqliteRepository repository;
    private CatalogService catalog;
    private CatalogService.Snapshot settings;
    private RepairService service;

    @BeforeEach void start() throws Exception {
        repository = get(SqliteRepository.open(databasePath(), 32));
        catalog = new CatalogService(repository, owner, definition -> {}, published -> CompletableFuture.completedFuture(null));
        settings = get(catalog.reload(repairConfig(10, 200))).snapshot();
        service = new RepairService(repository, catalog, owner, time::get);
    }

    @AfterEach void close() throws Exception {
        catalog.close();
        get(repository.closeAsync());
    }

    private Path databasePath() { return temporary.resolve("repair.db"); }

    private static Map<String,Object> repairConfig(double rate, long cooling) {
        var variant = variant();
        variant.put("repair", Map.of("resources", Map.of(RESOURCE, rate), "cooling-ms", cooling));
        return config(Map.of("test", variant));
    }

    private TowerSnapshot read(TowerSnapshot before) throws Exception {
        return get(repository.tower(before.state().tower().id())).orElseThrow();
    }

    @Test void legacyPayloadStaysVersionOneAndMixedRepairPolicyRoundTrips() {
        var legacy = CatalogConfig.parse(config());
        assertEquals(1, CatalogCodec.candidate(legacy).version());
        assertEquals(legacy, CatalogCodec.candidate(CatalogCodec.candidate(legacy)));
        var legacyDefinition = legacy.variants().get("test");
        assertEquals(1, CatalogCodec.definition(legacyDefinition).version());
        assertNull(CatalogCodec.definition(CatalogCodec.definition(legacyDefinition)).repair());

        var updated = CatalogConfig.parse(repairConfig(10, 200));
        var mixed = new CatalogConfig.Candidate(true, true,
                Map.of("test", updated.variants().get("test"), "legacy", CatalogConfig.parse(config(Map.of("legacy", variant()))).variants().get("legacy")));
        assertEquals(2, CatalogCodec.candidate(mixed).version());
        assertEquals(mixed, CatalogCodec.candidate(CatalogCodec.candidate(mixed)));
        assertEquals(updated.variants().get("test"), CatalogCodec.definition(CatalogCodec.definition(updated.variants().get("test"))));
        assertThrows(IllegalArgumentException.class, () -> CatalogConfig.parse(repairConfig(0, 200)));
        assertThrows(IllegalArgumentException.class, () -> CatalogConfig.parse(repairConfig(Double.NaN, 200)));
        assertThrows(IllegalArgumentException.class, () -> CatalogConfig.parse(repairConfig(10, -1)));
    }

    @Test void deterministicWholeItemQuoteOnlyUsesTheHelpfulAmount() {
        var fractional = RepairQuote.calculate(0.1, 0.3, 0.1, 64).orElseThrow();
        assertEquals(2, fractional.items());
        assertEquals(new BigDecimal("0.2"), fractional.hpGain());
        assertFalse(fractional.partialFinalItem());
        var last = RepairQuote.calculate(95, 100, 10, 64).orElseThrow();
        assertEquals(1, last.items());
        assertEquals(100, last.resultingHp());
        assertEquals(0, last.hpGain().compareTo(BigDecimal.valueOf(5)));
        assertTrue(last.partialFinalItem());
        assertTrue(RepairQuote.calculate(100, 100, 10, 64).isEmpty());
        assertTrue(RepairQuote.calculate(90, 100, Double.MIN_VALUE, 64).isEmpty());
    }

    @Test void confirmedFinalPartialItemSettlesExactDebitAndDurableHpOnce() throws Exception {
        var before = placed(repository, Form.COMPACT);
        var inventory = new Inventory(8);
        var preview = quote(before, inventory);
        assertEquals(3, preview.details().items());
        assertEquals(0, preview.details().hpGain().compareTo(BigDecimal.valueOf(25)));
        assertTrue(preview.details().partialFinalItem());
        assertEquals(8, inventory.quantity);
        assertTrue(get(repository.escrow(preview.operation())).isEmpty());

        assertEquals(COMMITTED, owner.drain(preview.confirm()));
        assertEquals(REJECTED, get(preview.confirm()));
        owner.run();
        assertEquals(5, inventory.quantity);
        assertEquals(1, inventory.debits);
        assertTrue(inventory.released);
        var escrow = get(repository.escrow(preview.operation())).orElseThrow();
        assertEquals(PaymentPhase.COMMITTED, escrow.payment());
        assertEquals(HpPhase.APPLIED, escrow.hpPhase());
        assertEquals(Phase.COMPLETE, get(repository.operation(preview.operation())).orElseThrow().phase());
        var row = read(before);
        assertEquals(100, row.state().tower().hp());
        assertEquals(before.state().tower().expandAfter(), row.state().tower().expandAfter());
        assertEquals(before.state().tower().repairAfter(), row.state().tower().repairAfter());
        assertTrue(row.mutationReady());
        var more = new Inventory(3);
        assertTrue(owner.drain(service.preview(settings, row, more, more.access())).isEmpty());
        assertEquals(3, more.quantity);
        assertTrue(more.released);
    }

    @Test void staleCatalogAndMenuCloseLeaveTheOriginalItemsUntouched() throws Exception {
        var before = placed(repository, Form.COMPACT);
        var first = new Inventory(4);
        var closed = quote(before, first);
        closed.cancel();
        owner.run();
        assertEquals(REJECTED, get(closed.confirm()));
        assertTrue(first.released);
        assertEquals(4, first.quantity);

        var second = new Inventory(4);
        var stale = quote(before, second);
        get(catalog.reload(repairConfig(12, 200)));
        assertEquals(REJECTED, owner.drain(stale.confirm()));
        assertEquals(4, second.quantity);
        assertEquals(0, second.debits);
        assertTrue(get(repository.operation(stale.operation())).isEmpty());
    }

    @Test void twoViewersCannotPayForTheSameTowerAndContributorCannotPayForBothAtOnce() throws Exception {
        var tower = placed(repository, Form.COMPACT);
        var otherTower = placed(repository, Form.COMPACT, 5);
        var first = new Inventory(4);
        var contender = new Inventory(4);
        var sameContributor = new Inventory(4, first.actor, first.session);
        var winner = quote(tower, first);
        var competing = quote(tower, contender);
        var crossTower = quote(otherTower, sameContributor);
        var a = winner.confirm();
        owner.run();
        owner.await(); // The first SQLite admission queued its owner debit. Leave it pending.
        assertTrue(get(repository.escrow(winner.operation())).isPresent());
        var b = competing.confirm();
        var c = crossTower.confirm();
        owner.run(); // first debit
        owner.run(); // competing authorization
        owner.run(); // cross-tower authorization
        assertEquals(REJECTED, get(b));
        assertEquals(REJECTED, get(c));
        assertEquals(COMMITTED, owner.drain(a));
        assertEquals(4, contender.quantity);
        assertEquals(4, sameContributor.quantity);
        assertEquals(1, first.quantity);
    }

    @Test void menuRevokedAfterFinalOwnerProofCannotCommitQueuedPaymentOrHp() throws Exception {
        var before = placed(repository, Form.COMPACT);
        var inventory = new Inventory(4);
        var preview = quote(before, inventory);
        var result = preview.confirm();
        owner.run();
        owner.await();
        owner.run();
        owner.await(); // EFFECTS_APPLIED and final owner proof queued.
        assertEquals(1, inventory.quantity);
        try (var writer = SqliteTestConnections.open(databasePath())) {
            writer.setAutoCommit(false);
            owner.run();
            preview.cancel();
            writer.rollback();
        }
        assertEquals(RESTORED, owner.drain(result));
        assertEquals(4, inventory.quantity);
        assertEquals(75, read(before).state().tower().hp());
        assertEquals(Phase.ROLLED_BACK, get(repository.operation(preview.operation())).orElseThrow().phase());
        assertEquals(PaymentPhase.REFUNDED, get(repository.escrow(preview.operation())).orElseThrow().payment());
        assertEquals(0, inventory.receipts);
    }

    @Test void disconnectedOrCombatInvalidatedPayerCannotCommitWhileTheWriterWaits() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var inventory = new Inventory(4);
        var preview = quote(before, inventory);
        var result = preview.confirm();
        owner.run();
        owner.await();
        owner.run();
        owner.await();
        try (var writer = SqliteTestConnections.open(databasePath())) {
            writer.setAutoCommit(false);
            owner.run();
            inventory.access.revoke();
            writer.rollback();
        }
        assertEquals(RESTORED, owner.drain(result));
        assertEquals(4, inventory.quantity);
        assertEquals(75, read(before).state().tower().hp());
        assertEquals(0, inventory.receipts);
    }

    @Test void changedDeploymentRejectsAnEarlierMenuEvenForTheSameController() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var source = new Inventory(4);
        var stale = quote(before, source);
        var tower = before.state().tower();
        var newer = new Tower(tower.id(), tower.variantId(), tower.hp(), tower.maximumHp(), tower.form(),
                tower.generation(), tower.deploymentRevision() + 2, tower.controller(), tower.position(), tower.light(),
                tower.defence(), tower.expandAfter(), tower.repairAfter());
        var id = UUID.randomUUID();
        var intent = new Intent(id, tower.id(), before.rowVersion(), tower.generation(), tower.deploymentRevision(),
                "fixture-redeployed", new State(newer, null, before.state().components()), new Document(1, "Fixture redeployed"));
        var operation = get(repository.beginCatalogIntent(intent, settings.revision()));
        var applied = get(repository.advanceOperation(id, operation.rowVersion(), Phase.INTENT, Phase.EFFECTS_APPLIED, "Fixture verified"));
        get(repository.completeOperation(id, applied.rowVersion(), "Fixture deployed again"));
        assertEquals(REJECTED, owner.drain(stale.confirm()));
        assertEquals(4, source.quantity);
        assertEquals(0, source.debits);
        assertTrue(get(repository.escrow(stale.operation())).isEmpty());
    }

    @Test void missingSlotProofAfterDebitQuarantinesWithoutRefundOrHealing() throws Exception {
        var before = placed(repository, Form.COMPACT);
        var inventory = new Inventory(4);
        var preview = quote(before, inventory);
        var result = preview.confirm();
        owner.run();
        owner.await();
        owner.run();
        owner.await();
        inventory.lease.revoke();
        inventory.restoreAllowed = false;
        assertEquals(QUARANTINED, owner.drain(result));
        assertEquals(1, inventory.quantity);
        var row = read(before);
        assertEquals(75, row.state().tower().hp());
        assertEquals(preview.operation(), row.activeOperation());
        assertEquals(Phase.RECONCILING, get(repository.operation(preview.operation())).orElseThrow().phase());
        assertEquals(PaymentPhase.RESERVED, get(repository.escrow(preview.operation())).orElseThrow().payment());
    }

    @Test void sqlFailureAfterDebitDoesNotSplitPaymentAndHpOrReplayOnReopen() throws Exception {
        var before = placed(repository, Form.COMPACT);
        try (var writer = SqliteTestConnections.open(databasePath()); var statement = writer.createStatement()) {
            statement.execute("CREATE TRIGGER reject_repair BEFORE UPDATE ON repair_escrows WHEN NEW.hp_phase='APPLIED' "
                    + "BEGIN SELECT RAISE(ABORT,'fixture settlement failure'); END");
            var inventory = new Inventory(4);
            var preview = quote(before, inventory);
            assertEquals(QUARANTINED, owner.drain(preview.confirm()));
            assertEquals(1, inventory.quantity);
            assertEquals(75, read(before).state().tower().hp());
            assertEquals(PaymentPhase.RESERVED, get(repository.escrow(preview.operation())).orElseThrow().payment());
            statement.execute("DROP TRIGGER reject_repair");
            get(repository.closeAsync());
            repository = get(SqliteRepository.open(databasePath(), 32));
            assertEquals(Phase.RECONCILING, get(repository.operation(preview.operation())).orElseThrow().phase());
            assertEquals(HpPhase.NOT_APPLIED, get(repository.escrow(preview.operation())).orElseThrow().hpPhase());
            assertEquals(75, read(before).state().tower().hp());
        }
    }

    @Test void damageAndAttackCannotRaceAnAdmittedRepairAndCoolingInvalidatesOldQuotes() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var inventory = new Inventory(3);
        var preview = quote(before, inventory);
        var pending = preview.confirm();
        owner.run();
        owner.await();
        assertThrows(ExecutionException.class, () -> get(repository.recordRepairCooling(settings.revision(), before, time.get(), null)));
        assertThrows(ExecutionException.class, () -> get(repository.damageTower(settings.revision(), before.state().tower().id(),
                before.rowVersion(), before.state().tower().generation(), before.state().tower().deploymentRevision(), 10, 1_200, null)));
        preview.cancel();
        assertEquals(RESTORED, owner.drain(pending));
        assertEquals(3, inventory.quantity);
        var ready = read(before);
        var quote = quote(ready, new Inventory(3));
        var cooled = get(repository.recordRepairCooling(settings.revision(), ready, time.get(), null));
        assertEquals(1_200, cooled.state().tower().repairAfter());
        assertEquals(REJECTED, owner.drain(quote.confirm()));
        assertTrue(owner.drain(service.preview(settings, cooled, new Inventory(3), inventory.access())).isEmpty());
        assertEquals(75, cooled.state().tower().hp());

        time.set(1_300);
        var structures = new StructureLifecycle(repository, owner, time::get);
        try {
            var health = new TowerHealth(repository, catalog, owner, structures, time::get);
            var injured = owner.drain(health.damage(settings, cooled, new TowerHealth.Damage(10, 0),
                    row -> new TowerHealth.Admission(null), UUID.randomUUID(), row -> fail("Positive health remains deployed")));
            assertEquals(1_500, injured.settled().state().tower().repairAfter());
        } finally {
            structures.close();
        }
    }

    @Test void mismatchedContributorSessionFullOrExpandedZeroDoesNotAcquireAnEscrow() throws Exception {
        var before = placed(repository, Form.EXPANDED);
        var source = new Inventory(5);
        assertTrue(owner.drain(service.preview(settings, before, source, new Inventory(5).access())).isEmpty());
        assertTrue(source.released);
        var defeated = get(repository.damageTower(settings.revision(), before.state().tower().id(), before.rowVersion(),
                before.state().tower().generation(), before.state().tower().deploymentRevision(), 100, 0, null));
        var refused = new Inventory(5);
        assertTrue(owner.drain(service.preview(settings, defeated, refused, refused.access())).isEmpty());
        assertEquals(5, refused.quantity);
        assertTrue(get(repository.pendingOperations(null, 10)).isEmpty());
    }

    private RepairService.Preview quote(TowerSnapshot row, Inventory inventory) throws Exception {
        return owner.drain(service.preview(settings, row, inventory, inventory.access())).orElseThrow();
    }

    private final class Inventory implements RepairService.Inventory {
        final UUID actor;
        final UUID session;
        final CommitPermit access = new CommitPermit();
        final CommitPermit lease = new CommitPermit();
        int quantity;
        int before;
        int debits;
        int receipts;
        boolean released;
        boolean restoreAllowed = true;

        Inventory(int quantity) { this(quantity, UUID.randomUUID(), UUID.randomUUID()); }

        Inventory(int quantity, UUID actor, UUID session) {
            this.quantity = quantity;
            this.actor = actor;
            this.session = session;
        }

        RepairService.Access access() {
            return new RepairService.Access() {
                @Override public RepairService.Authorization authorize(TowerSnapshot observed, long now) {
                    owner.assertOwner();
                    return new RepairService.Authorization(actor, session, access);
                }

                @Override public void committed(Operation receipt) { receipts++; }
            };
        }

        @Override public String resource() { owner.assertOwner(); return RESOURCE; }

        @Override public int offered() { owner.assertOwner(); return quantity; }

        @Override public RepairPayment quote(int items) {
            owner.assertOwner();
            before = quantity;
            return new RepairPayment(actor, session, 0, RESOURCE, items, bytes(quantity), bytes(quantity - items), bytes(items));
        }

        @Override public boolean sourceMatches(RepairPayment payment) { owner.assertOwner(); return quantity == before; }

        @Override public CommitPermit slotPermit() { owner.assertOwner(); return lease; }

        @Override public boolean debit(RepairPayment payment) {
            owner.assertOwner();
            if (!sourceMatches(payment)) return false;
            quantity -= payment.items();
            debits++;
            return true;
        }

        @Override public boolean debitMatches(RepairPayment payment) {
            owner.assertOwner();
            return quantity == before - payment.items();
        }

        @Override public boolean restore(RepairPayment payment) {
            owner.assertOwner();
            if (!restoreAllowed) return false;
            if (debits == 0) return true;
            if (!debitMatches(payment)) return false;
            quantity = before;
            return true;
        }

        @Override public void release() { released = true; lease.revoke(); }

        private ResourceBytes bytes(int count) {
            return new ResourceBytes(count == 0 ? new byte[0] : new byte[]{(byte)count});
        }
    }
}
