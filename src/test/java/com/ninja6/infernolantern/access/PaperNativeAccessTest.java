package com.ninja6.infernolantern.access;

import com.ninja6.infernolantern.catalog.CatalogConfig;
import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.RepositoryRecords.Component;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.repository.SqliteTestConnections;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.structure.StructureLifecycle.Action.*;
import static org.junit.jupiter.api.Assertions.*;

final class PaperNativeAccessTest {
    private static final String USE = "infernolantern.use";
    private static final String VARIANT_USE = "example.lantern";
    private static final PaperNativeAccess.PhysicalAccess SAFE = (p, a, s, t, restoring) -> true;
    private static final PaperNativeAccess.PhysicalAccess DENIED = (p, a, s, t, restoring) -> false;
    private static final String[] ON = {"override", "on"};
    private static final String[] OFF = {"override", "off"};
    private static final String[] STATUS = {"override", "status"};

    @TempDir Path temp;
    private SqliteRepository repository;
    private CatalogService catalog;
    private CatalogService.Snapshot settings;
    private OverrideSessions sessions;
    private PaperNativeAccess access;
    private StructureLifecycle structures;
    private final Owner owner = new Owner();
    private final UUID worldId = UUID.randomUUID();
    private final Position base = new Position(worldId, 1, 70, 1, "north");
    private final Queue<String> log = new ConcurrentLinkedQueue<>();
    private Field bukkitServer;
    private Object previousServer;
    private World world;

    @BeforeEach void setup() throws Exception {
        Thread testThread = Thread.currentThread();
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        previousServer = bukkitServer.get(null);
        bukkitServer.set(null, proxy(Server.class, (name, args) -> switch (name) {
            case "isPrimaryThread" -> Thread.currentThread() == testThread;
            default -> throw new AssertionError(name);
        }));
        world = proxy(World.class, (name, args) -> switch (name) {
            case "getUID" -> worldId;
            case "getName" -> "example";
            case "getEnvironment" -> World.Environment.NORMAL;
            default -> throw new AssertionError(name);
        });

        repository = get(SqliteRepository.open(temp.resolve("native-access.db"), 64));
        catalog = new CatalogService(repository, owner, d -> {}, s -> CompletableFuture.completedFuture(null));
        settings = get(catalog.reload(config(null, true, false))).snapshot();
        sessions = new OverrideSessions();
        Logger audit = Logger.getAnonymousLogger();
        audit.setUseParentHandlers(false);
        audit.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { log.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        access = new PaperNativeAccess(sessions, catalog, audit);
        structures = new StructureLifecycle(repository, owner);
    }

    @AfterEach void teardown() throws Exception {
        try {
            structures.close();
            sessions.close();
            catalog.close();
            get(repository.closeAsync());
        } finally { bukkitServer.set(null, previousServer); }
    }

    @Test void commandHelpCompletionPermissionAndToggleArePrivateAndPerConnection() {
        var admin = new Client(true);
        var ordinary = new Client(false);
        CommandSender console = proxy(CommandSender.class, (name, args) -> {
            assertEquals("sendMessage", name);
            assertEquals("Override requires an authorized player session.", args[0]);
            return null;
        });
        sessions.command(console, "ilantern", ON);
        sessions.help(ordinary.player, "ilantern");
        assertTrue(ordinary.messages.isEmpty());
        assertEquals(List.of(), sessions.complete(ordinary.player, ""));
        sessions.command(ordinary.player, "ilantern", ON);
        assertFalse(sessions.enabled(ordinary.session()));

        sessions.help(admin.player, "ilantern");
        assertEquals("/ilantern override on|off|status", admin.last());
        assertEquals(List.of("on", "off"), sessions.complete(admin.player, "O"));
        sessions.command(admin.player, "ilantern", STATUS);
        assertTrue(admin.last().startsWith("Override is off."));
        sessions.command(admin.player, "ilantern", new String[]{"override", "on", "extra"});
        assertFalse(sessions.enabled(admin.session()));
        sessions.command(admin.player, "ilantern", ON);
        var enabled = admin.session();
        assertTrue(sessions.enabled(enabled));
        assertFalse(sessions.enabled(ordinary.session()));
        sessions.command(admin.player, "infernolantern", STATUS);
        assertTrue(admin.last().startsWith("Override is on."));
        sessions.command(admin.player, "ilantern", OFF);
        assertFalse(sessions.enabled(enabled));
        sessions.command(admin.player, "ilantern", ON);
        assertFalse(sessions.enabled(enabled));
        assertTrue(sessions.enabled(admin.session()));
    }

    @Test void logoutCancelledKickKickReconnectRevocationAndDisableInvalidateTheRightTicket() {
        var original = new Client(true);
        original.enable();
        var active = original.session();
        var kick = new PlayerKickEvent(original.player, net.kyori.adventure.text.Component.empty(),
                net.kyori.adventure.text.Component.empty(), PlayerKickEvent.Cause.PLUGIN);
        kick.setCancelled(true);
        sessions.kick(kick);
        assertTrue(sessions.enabled(active));

        original.permissions.remove(OverrideSessions.PERMISSION);
        assertFalse(sessions.enabled(active));
        original.permissions.add(OverrideSessions.PERMISSION);
        assertFalse(sessions.enabled(original.session()));
        original.enable();
        kick.setCancelled(false);
        sessions.kick(kick);
        assertNull(original.session());

        var reconnect = new Client(original.actor, true);
        assertNotEquals(active.id(), reconnect.session().id());
        assertFalse(sessions.enabled(reconnect.session()));
        sessions.quit(new PlayerQuitEvent(original.player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.KICKED));
        assertNotNull(reconnect.session());
        assertFalse(sessions.current(active));

        reconnect.enable();
        var newTicket = reconnect.session();
        sessions.quit(new PlayerQuitEvent(reconnect.player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(sessions.current(newTicket));
        assertNull(reconnect.session());
        sessions.close();
        assertNull(reconnect.session());
    }

    @Test void expansionCooldownReportsRemainingTimeOnlyToEligibleRequester() {
        var requester = new Client(false);
        var observer = new Client(false);
        var compact = snapshot(Form.COMPACT, null, 80, 100, 700, 200);
        assertTrue(structure(requester, compact, EXPAND, 600, SAFE).isEmpty());
        assertEquals(List.of("Lantern expansion cooldown: 100 ms remaining."), requester.messages);
        assertTrue(observer.messages.isEmpty());
        assertTrue(structure(observer, compact, EXPAND, 600, (player, action, before, target, restoring) -> false).isEmpty());
        assertTrue(observer.messages.isEmpty());
        assertTrue(structure(requester, compact, EXPAND, 700, SAFE).isPresent());
    }

    @Test void formAndControllerMatrixNeverPermitsOrdinaryExpandedBreaking() {
        var controller = new Client(false);
        var eligible = new Client(false);
        var admin = new Client(true);
        TowerSnapshot portable = snapshot(Form.PORTABLE, null, 80, 100, 700, 200);
        TowerSnapshot compact = snapshot(Form.COMPACT, null, 80, 100, 700, 200);
        TowerSnapshot expanded = snapshot(Form.EXPANDED, controller.actor, 80, 100, 0, 200);
        assertTrue(structure(eligible, portable, PLACE, 0, SAFE).isPresent());
        assertTrue(structure(eligible, compact, BREAK, 0, SAFE).isPresent());
        assertTrue(structure(eligible, compact, EXPAND, 700, SAFE).isPresent());
        assertTrue(structure(controller, expanded, COMPACT, 700, SAFE).isPresent());
        assertTrue(structure(controller, expanded, BREAK, 700, SAFE).isEmpty());
        assertTrue(structure(eligible, expanded, COMPACT, 700, SAFE).isEmpty());
        assertTrue(eligible.messages.isEmpty());
        assertTrue(structure(admin, compact, EXPAND, 699, SAFE).isEmpty());
        assertTrue(admin.last().contains("/infernolantern override on"));
        assertTrue(structure(admin, expanded, ADMIN_RECOVER, 700, SAFE).isEmpty());

        admin.enable();
        assertTrue(structure(admin, expanded, BREAK, 700, SAFE).isEmpty());
        assertTrue(structure(admin, expanded, ADMIN_RECOVER, 700, SAFE).orElseThrow().request().policy().explicitOverride());
        assertTrue(structure(admin, expanded, COMPACT, 700, SAFE).orElseThrow().request().policy().explicitOverride());
        assertTrue(structure(admin, compact, EXPAND, 699, SAFE).orElseThrow().request().policy().explicitOverride());
        assertFalse(structure(admin, compact, EXPAND, 700, SAFE).orElseThrow().request().policy().explicitOverride());
        assertTrue(structure(admin, expanded, ADMIN_RECOVER, 700, DENIED).isEmpty());
        assertTrue(structure(admin, snapshot(Form.COMPACT, null, 49, 100, 700, 200), EXPAND, 0, SAFE).isEmpty());

        assertEquals(PaperNativeAccess.Decision.ALLOWED, repair(controller, expanded, 200, SAFE));
        assertEquals(PaperNativeAccess.Decision.OVERRIDE_REQUIRED, repair(eligible, expanded, 200, SAFE));
        assertEquals(PaperNativeAccess.Decision.CONTROLLER, repair(admin, expanded, 200, SAFE));
        assertEquals(PaperNativeAccess.Decision.DENIED, repair(admin, expanded, 199, SAFE));
        assertEquals(PaperNativeAccess.Decision.DENIED, repair(admin, expanded, 200, DENIED));
        assertEquals(PaperNativeAccess.Decision.ALLOWED, repair(eligible, compact, 200, SAFE));
        assertEquals(PaperNativeAccess.Decision.DENIED,
                access.repair(admin.session(), settings, expanded, 0, 200, SAFE));
    }

    @Test void repairAuthorizationBindsViewerAndCurrentCombatPermit() {
        var controller = new Client(false);
        var outsider = new Client(false);
        var admin = new Client(true);
        var expanded = snapshot(Form.EXPANDED, controller.actor, 80, 100, 0, 200);
        var idle = new CommitPermit();
        assertTrue(access.repairAccess(outsider.session(), settings, expanded, 200, SAFE, () -> idle).isEmpty());
        assertTrue(access.repairAccess(controller.session(), settings, expanded, 200, SAFE, () -> null).isEmpty());
        assertTrue(access.repairAccess(controller.session(), settings, expanded, 199, SAFE, () -> idle).isEmpty());

        admin.enable();
        var override = access.repairAccess(admin.session(), settings, expanded, 200, SAFE, () -> idle).orElseThrow();
        var authorization = override.authorize(expanded, 200);
        assertEquals(admin.actor, authorization.actor());
        assertEquals(admin.session().id(), authorization.session());
        authorization.permit().requireValid();
        sessions.command(admin.player, "infernolantern", new String[]{"override", "off"});
        assertThrows(RepositoryException.CommitRevoked.class, authorization.permit()::requireValid);
        assertNull(override.authorize(expanded, 200));

        var normal = access.repairAccess(controller.session(), settings, expanded, 200, SAFE, () -> idle).orElseThrow();
        var current = normal.authorize(expanded, 200);
        assertNotNull(current);
        idle.revoke();
        assertThrows(RepositoryException.CommitRevoked.class, current.permit()::requireValid);
    }

    @Test void basePermissionVariantPermissionWorldAndRetirementStayIndependent() throws Exception {
        var admin = new Client(true);
        admin.enable();
        var compact = snapshot(Form.COMPACT, null, 80, 100, 0, 0);
        var expanded = snapshot(Form.EXPANDED, admin.actor, 80, 100, 0, 0);
        settings = get(catalog.reload(config(VARIANT_USE, true, false))).snapshot();
        assertTrue(structure(admin, compact, EXPAND, 0, SAFE).isEmpty());
        admin.permissions.add(VARIANT_USE);
        assertTrue(structure(admin, compact, EXPAND, 0, SAFE).isPresent());
        admin.permissions.remove(USE);
        assertTrue(structure(admin, expanded, ADMIN_RECOVER, 0, SAFE).isEmpty());
        admin.permissions.add(USE);

        settings = get(catalog.reload(config(VARIANT_USE, true, true))).snapshot();
        assertTrue(structure(admin, compact, EXPAND, 0, SAFE).isEmpty());
        settings = get(catalog.reload(Map.of("schema", 1, "variants", Map.of()))).snapshot();
        assertTrue(structure(admin, compact, EXPAND, 0, SAFE).isEmpty());
        assertTrue(structure(admin, compact, BREAK, 0, SAFE).isPresent());
        assertTrue(structure(admin, expanded, COMPACT, 0, SAFE).isPresent());
        assertTrue(structure(admin, expanded, ADMIN_RECOVER, 0, SAFE).isPresent());
        assertEquals(PaperNativeAccess.Decision.DENIED, repair(admin, expanded, 0, SAFE));
        admin.permissions.remove(VARIANT_USE);
        assertTrue(structure(admin, compact, BREAK, 0, SAFE).isEmpty());
    }

    @Test void boundStructureValidatesAgainAtCommitAfterPermissionRevocation() throws Exception {
        var admin = new Client(true);
        admin.enable();
        TowerSnapshot row = persisted(Form.COMPACT, null, 700);
        var grant = structure(admin, row, EXPAND, 100, SAFE).orElseThrow();
        assertThrows(ExecutionException.class,
                () -> get(CompletableFuture.supplyAsync(() -> grant.allowed(grant.request(), row.state()))));
        Effects effects = new Effects(grant);
        var running = structures.execute(grant.request(), effects);
        owner.runNext();
        owner.runNext();
        owner.runNext();
        owner.await();
        assertEquals(Phase.EFFECTS_APPLIED, get(repository.operation(grant.request().operation())).orElseThrow().phase());
        admin.permissions.remove(OverrideSessions.PERMISSION);
        assertEquals(StructureLifecycle.Outcome.ROLLED_BACK, owner.drain(running).outcome());
        assertEquals(Form.COMPACT, stored(row).state().tower().form());
        assertTrue(effects.restored);
        assertFalse(effects.activated);
    }

    @Test void turningOverrideOffDuringSqliteWaitRejectsStructureCompletionAndRestores() throws Exception {
        var admin = new Client(true);
        admin.enable();
        TowerSnapshot before = persisted(Form.COMPACT, null, 700);
        var grant = structure(admin, before, EXPAND, 100, SAFE).orElseThrow();
        Effects effects = new Effects(grant);
        var operation = structures.execute(grant.request(), effects);
        owner.runNext();
        owner.runNext();
        owner.runNext();
        owner.await();
        try (Connection writer = pauseSqliteWriter()) {
            owner.runNext();
            sessions.command(admin.player, "ilantern", OFF);
            admin.enable();
            writer.setAutoCommit(true);
        }
        assertEquals(StructureLifecycle.Outcome.ROLLED_BACK, owner.drain(operation).outcome());
        assertEquals(Phase.ROLLED_BACK, get(repository.operation(grant.request().operation())).orElseThrow().phase());
        assertEquals(before.state(), stored(before).state());
        assertTrue(effects.restored);
        assertFalse(effects.activated);
        assertTrue(log.isEmpty());
    }

    @Test void completedStructureBindsControllerAndRecordsTheAppliedBypass() throws Exception {
        var admin = new Client(true);
        admin.enable();
        TowerSnapshot row = persisted(Form.COMPACT, null, 700);
        var grant = structure(admin, row, EXPAND, 100, SAFE).orElseThrow();
        assertEquals(StructureLifecycle.Outcome.COMMITTED,
                owner.drain(structures.execute(grant.request(), new Effects(grant))).outcome());
        Operation operation = get(repository.operation(grant.request().operation())).orElseThrow();
        String evidence = RecoveryEnvelope.original(operation.intent().effects()).content();
        assertTrue(evidence.contains("explicitOverride=true"));
        assertTrue(evidence.contains("actor=" + admin.actor));
        sessions.command(admin.player, "ilantern", OFF);
        Tower tower = stored(row).state().tower();
        assertEquals(Form.EXPANDED, tower.form());
        assertEquals(admin.actor, tower.controller());
        assertEquals(700, tower.expandAfter());
        assertEquals(80, tower.hp());
    }

    @Test void switchPersistsOnlyForCurrentBoundAccessAndAuditsAppliedOverride() throws Exception {
        var controller = new Client(false);
        var other = new Client(false);
        var admin = new Client(true);
        TowerSnapshot before = persisted(Form.EXPANDED, controller.actor, 0);
        assertThrows(ExecutionException.class, () -> get(switchLight(other, before)));
        assertThrows(ExecutionException.class, () -> get(switchLight(admin, before)));
        assertEquals(before, stored(before));
        admin.enable();
        var stale = switchLight(admin, before);
        owner.await();
        sessions.command(admin.player, "ilantern", OFF);
        sessions.command(admin.player, "ilantern", ON);
        assertThrows(ExecutionException.class, () -> owner.drain(stale));
        assertEquals(before, stored(before));
        assertTrue(log.isEmpty());

        TowerSnapshot switched = owner.drain(switchLight(admin, before));
        assertTrue(switched.state().tower().light());
        assertFalse(switched.state().tower().defence());
        assertEquals(controller.actor, switched.state().tower().controller());
        assertTrue(log.remove().contains("actor=" + admin.actor + " tower=" + before.state().tower().id() + " action=LIGHT"));
        assertThrows(ExecutionException.class, () -> get(switchLight(admin, before)));
        assertTrue(log.isEmpty());
    }

    @Test void controllerCanChangeDefenceWithoutChangingLightAndRevokedEligibilityFailsCommit() throws Exception {
        var controller = new Client(false);
        controller.permissions.add(VARIANT_USE);
        settings = get(catalog.reload(config(VARIANT_USE, true, false))).snapshot();
        TowerSnapshot before = persisted(Form.EXPANDED, controller.actor, 0);
        var stale = switchLight(controller, before);
        owner.await();
        controller.permissions.remove(VARIANT_USE);
        assertThrows(ExecutionException.class, () -> owner.drain(stale));
        assertEquals(before, stored(before));
        controller.permissions.add(VARIANT_USE);
        TowerSnapshot changed = owner.drain(access.switchPreference(controller.session(), settings, before,
                CatalogConfig.Action.DEFENCE, true, SAFE));
        assertFalse(changed.state().tower().light());
        assertTrue(changed.state().tower().defence());
        assertTrue(log.isEmpty());
    }

    @Test void turningOverrideOffDuringSqliteWaitRejectsSwitchWithoutAudit() throws Exception {
        var controller = new Client(false);
        var admin = new Client(true);
        admin.enable();
        var before = persisted(Form.EXPANDED, controller.actor, 0);
        var queued = switchLight(admin, before);
        owner.await();
        try (Connection writer = pauseSqliteWriter()) {
            owner.runNext();
            sessions.command(admin.player, "ilantern", OFF);
            admin.enable();
            writer.setAutoCommit(true);
        }
        ExecutionException failure = assertThrows(ExecutionException.class, () -> owner.drain(queued));
        assertInstanceOf(RepositoryException.CommitRevoked.class, failure.getCause());
        assertEquals(before, stored(before));
        assertTrue(log.isEmpty());
    }

    @Test void reconnectDuringSqliteWaitRejectsOrdinarySwitch() throws Exception {
        var controller = new Client(false);
        var before = persisted(Form.EXPANDED, controller.actor, 0);
        var queued = switchLight(controller, before);
        owner.await();
        try (Connection writer = pauseSqliteWriter()) {
            owner.runNext();
            sessions.quit(new PlayerQuitEvent(controller.player, (net.kyori.adventure.text.Component) null,
                    PlayerQuitEvent.QuitReason.DISCONNECTED));
            new Client(controller.actor, false);
            writer.setAutoCommit(true);
        }
        ExecutionException failure = assertThrows(ExecutionException.class, () -> owner.drain(queued));
        assertInstanceOf(RepositoryException.CommitRevoked.class, failure.getCause());
        assertEquals(before, stored(before));
        assertTrue(log.isEmpty());
    }

    @Test void revocationAfterDurableSwitchDoesNotErasePreferenceOrAudit() throws Exception {
        var controller = new Client(false);
        var admin = new Client(true);
        admin.enable();
        var before = persisted(Form.EXPANDED, controller.actor, 0);
        var committed = switchLight(admin, before);
        owner.runNext();
        owner.await(); // The already-committed preference is waiting for effect handoff.
        assertTrue(stored(before).state().tower().light());
        sessions.command(admin.player, "ilantern", OFF);
        assertThrows(ExecutionException.class, () -> owner.drain(committed));
        assertTrue(stored(before).state().tower().light());
        assertEquals(1, log.size());
    }

    @Test void reloadAndUnavailableCapabilityRejectQueuedOrFreshSwitch() throws Exception {
        var controller = new Client(true);
        controller.enable();
        TowerSnapshot before = persisted(Form.EXPANDED, controller.actor, 0);
        var stale = switchLight(controller, before);
        owner.await();
        settings = get(catalog.reload(config(null, false, false))).snapshot();
        assertThrows(ExecutionException.class, () -> owner.drain(stale));
        assertThrows(ExecutionException.class, () -> get(switchLight(controller, before)));
        assertEquals(before, stored(before));
        assertTrue(log.isEmpty());
    }

    @Test void managedStructureCompletionGuardRejectsPolicyChangedAfterOwnerVerification() throws Exception {
        var controller = new Client(false);
        var before = persisted(Form.COMPACT, null, 0);
        var grant = structure(controller, before, EXPAND, 100, SAFE).orElseThrow();
        var effects = new Effects(grant) {
            @Override public boolean validate(StructureLifecycle.Claim claim) {
                if (!super.validate(claim)) return false;
                if (++validations == 3) {
                    try { settings = get(catalog.reload(config(VARIANT_USE, true, false))).snapshot(); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return true;
            }
        };
        assertEquals(StructureLifecycle.Outcome.ROLLED_BACK, owner.drain(structures.execute(grant.request(), effects)).outcome());
        assertEquals(Form.COMPACT, stored(before).state().tower().form());
        assertTrue(effects.restored);
        assertFalse(effects.activated);
    }

    private PaperNativeAccess.Decision repair(Client actor, TowerSnapshot row, long now, PaperNativeAccess.PhysicalAccess physical) {
        return access.repair(actor.session(), settings, row, row.state().tower().deploymentRevision(), now, physical);
    }

    private java.util.Optional<PaperNativeAccess.StructureAccess> structure(Client actor, TowerSnapshot row,
            StructureLifecycle.Action action, long now, PaperNativeAccess.PhysicalAccess physical) {
        return access.structure(actor.session(), settings, UUID.randomUUID(), row, action, base, now, physical);
    }

    private CompletableFuture<TowerSnapshot> switchLight(Client actor, TowerSnapshot row) {
        return access.switchPreference(actor.session(), settings, row, CatalogConfig.Action.LIGHT, true, SAFE);
    }

    private Connection pauseSqliteWriter() throws SQLException {
        Connection writer = SqliteTestConnections.open(temp.resolve("native-access.db"));
        try {
            writer.setAutoCommit(false); // The private JDBC connection acquires an IMMEDIATE writer lock.
            return writer;
        } catch (SQLException failure) {
            writer.close();
            throw failure;
        }
    }

    private TowerSnapshot stored(TowerSnapshot row) throws Exception {
        return get(repository.tower(row.state().tower().id())).orElseThrow();
    }

    private TowerSnapshot persisted(Form form, UUID controller, long cooldown) throws Exception {
        var snapshot = snapshot(form, controller, 80, 100, cooldown, 0);
        Tower placed = snapshot.state().tower();
        get(repository.createTower(new Tower(placed.id(), "generic", 80, 100, Form.PORTABLE,
                1, 0, null, null, false, false, cooldown, 0)));
        var intent = new Intent(UUID.randomUUID(), placed.id(), 0, 1, 0, "synthetic-setup",
                snapshot.state(), new Document(1, "synthetic setup"));
        Operation op = get(repository.beginCatalogIntent(intent, settings.revision()));
        op = get(repository.advanceOperation(intent.id(), op.rowVersion(), Phase.INTENT, Phase.EFFECTS_APPLIED, "synthetic setup"));
        get(repository.completeCatalogOperation(intent.id(), op.rowVersion(), settings.revision(), "synthetic setup"));
        return stored(snapshot);
    }

    private TowerSnapshot snapshot(Form form, UUID controller, double hp, double max, long cooldown, long cooling) {
        boolean placed = form != Form.PORTABLE;
        Tower tower = new Tower(UUID.randomUUID(), "generic", hp, max, form, placed ? 2 : 1, placed ? 1 : 0,
                controller, placed ? base : null, false, false, cooldown, cooling);
        return new TowerSnapshot(new State(tower, null, placed ? manifest(form == Form.COMPACT ? 1 : 3) : List.of()), 0, null);
    }

    private List<Component> manifest(int height) {
        var components = new ArrayList<Component>();
        for (int offset = 0; offset < height; offset++) {
            components.add(new Component("cell." + offset,
                    ComponentKind.BLOCK, worldId, base.x(), base.y() + offset, base.z(), "minecraft:air",
                    offset < 2 ? "minecraft:barrier[waterlogged=false]" : "minecraft:air", null, null));
        }
        return components;
    }

    private static Map<String, ?> config(String permission, boolean light, boolean denyExpansionWorld) {
        var variant = new LinkedHashMap<String, Object>();
        variant.put("capabilities", Map.of("light", light));
        variant.put("player-switches", Map.of("light", false, "defence", false));
        variant.put("light-level", 15);
        variant.put("maximum-hp", 100);
        variant.put("expansion-cooldown-ms", 1000);
        variant.put("geometry", Map.of("compact-model", "example:compact", "expanded-model", "example:expanded",
                "width", 1, "height", 3, "depth", 1, "light-x", 0, "light-y", 2, "light-z", 0));
        if (permission != null) variant.put("permission", permission);
        if (denyExpansionWorld) variant.put("world-rules", Map.of("expand", Map.of("deny-worlds", List.of("name:example"))));
        return Map.of("schema", 1, "variants", Map.of("generic", variant));
    }

    private class Effects implements StructureLifecycle.WorldEffects {
        private final PaperNativeAccess.StructureAccess grant;
        private State before;
        boolean restored;
        boolean activated;
        int validations;

        Effects(PaperNativeAccess.StructureAccess grant) { this.grant = grant; }

        @Override public StructureLifecycle.Preparation prepare(StructureLifecycle.Request request, TowerSnapshot row) {
            before = row.state();
            return grant.allowed(request, before) ? new StructureLifecycle.Preparation(manifest(3), null,
                    new Document(1, "synthetic world proof")) : null;
        }
        @Override public boolean validate(StructureLifecycle.Claim claim) { return grant.allowed(claim.request(), before); }
        @Override public CommitPermit commitPermit() { return grant.commitPermit(); }
        @Override public boolean apply(StructureLifecycle.Claim claim) { return true; }
        @Override public boolean verify(StructureLifecycle.Claim claim) { return true; }
        @Override public boolean rollback(StructureLifecycle.Claim claim) { return restored = grant.mayRestore(claim.request(), before); }
        @Override public void discard(StructureLifecycle.Request request, StructureLifecycle.Preparation prepared) {}
        @Override public boolean activate(StructureLifecycle.Claim claim, TowerSnapshot completed) { return activated = true; }
    }

    private final class Client {
        final UUID actor;
        final Player player;
        final Set<String> permissions = new HashSet<>(Set.of(USE));
        final List<String> messages = new ArrayList<>();

        Client(boolean admin) { this(UUID.randomUUID(), admin); }

        Client(UUID actor, boolean admin) {
            this.actor = actor;
            if (admin) permissions.add(OverrideSessions.PERMISSION);
            player = proxy(Player.class, (name, args) -> switch (name) {
                case "getUniqueId" -> actor;
                case "getWorld" -> world;
                case "isOnline" -> true;
                case "hasPermission" -> permissions.contains(args[0]);
                case "sendMessage" -> { messages.add((String) args[0]); yield null; }
                default -> throw new AssertionError(name);
            });
            sessions.join(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        }

        void enable() { sessions.command(player, "ilantern", ON); }
        OverrideSessions.Session session() { return sessions.capture(player); }
        String last() { return messages.getLast(); }
    }

    private interface Call { Object answer(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.answer(method.getName(), args)));
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static final class Owner implements WorldScheduler {
        private final Queue<Runnable> work = new ConcurrentLinkedQueue<>();

        @Override public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> action) {
            var result = new CompletableFuture<T>();
            work.add(() -> {
                try { result.complete(action.get()); }
                catch (Throwable problem) { result.completeExceptionally(problem); }
            });
            return result;
        }

        void await() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (work.isEmpty()) {
                if (System.nanoTime() > deadline) fail("No owner callback");
                Thread.sleep(1);
            }
        }

        void runNext() throws InterruptedException {
            await();
            work.remove().run();
        }

        <T> T drain(CompletableFuture<T> future) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!future.isDone()) {
                Runnable task = work.poll();
                if (task != null) task.run();
                else Thread.sleep(1);
                if (System.nanoTime() > deadline) fail("Owner work did not finish");
            }
            return get(future);
        }
    }
}
