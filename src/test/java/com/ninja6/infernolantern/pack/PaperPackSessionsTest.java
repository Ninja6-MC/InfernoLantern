package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.runtime.NativeCatalogRuntime.JavaPack;
import io.papermc.paper.configuration.ServerConfiguration;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.ninja6.infernolantern.pack.PackPlan.Edition.BEDROCK;
import static com.ninja6.infernolantern.pack.PackPlan.Edition.JAVA;
import static com.ninja6.infernolantern.pack.PackPlan.Mode.COMBINED;
import static com.ninja6.infernolantern.pack.PackPlan.Mode.DEFAULT;
import static com.ninja6.infernolantern.pack.PackPlan.Mode.STACKED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.*;
import static org.junit.jupiter.api.Assertions.*;

final class PaperPackSessionsTest {
    private static final PackPlan.Archive BASE = new PackPlan.Archive(PackPlan.Slot.BASE, "1".repeat(64));
    private static final PackPlan.Archive OVERRIDE = new PackPlan.Archive(PackPlan.Slot.OVERRIDE, "2".repeat(64));
    private static final PackPlan.Archive MERGED = new PackPlan.Archive(PackPlan.Slot.COMBINED, "3".repeat(64));
    private static final PackPlan.Models EMBER = new PackPlan.Models(
            "infernolantern:generic_ember_compact", "infernolantern:generic_ember_expanded");
    private static final PackPlan.Models FROST = new PackPlan.Models(
            "infernolantern:generic_frost_compact", "infernolantern:generic_frost_expanded");
    private Field server;
    private Object oldServer;
    private final PaperPackSessions owner = new PaperPackSessions();
    private List<Player> online = List.of();
    private Plugin[] installed = {};
    private List<RegisteredServiceProvider<CombinedJavaPackProvider>> services = List.of();
    private boolean proxied;
    private Runnable checkProxy = () -> {};

    @BeforeEach void installOwnerThread() throws ReflectiveOperationException {
        server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        oldServer = server.get(null);
        Thread thread = Thread.currentThread();
        server.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isPrimaryThread" -> Thread.currentThread() == thread;
                    case "getOnlinePlayers" -> online;
                    case "getPlayer" -> online.stream().filter(p -> p.getUniqueId().equals(arguments[0])).findFirst().orElse(null);
                    case "getServicesManager" -> Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                            new Class<?>[]{ServicesManager.class}, (manager, lookup, params) -> {
                                if (lookup.getName().equals("getRegistrations") && params[0] == CombinedJavaPackProvider.class) {
                                    return services;
                                }
                                throw new AssertionError(lookup.getName());
                            });
                    case "getServerConfig" -> Proxy.newProxyInstance(ServerConfiguration.class.getClassLoader(),
                            new Class<?>[]{ServerConfiguration.class}, (config, setting, params) -> {
                                if (!setting.getName().equals("isProxyEnabled")) throw new AssertionError(setting.getName());
                                checkProxy.run();
                                return proxied;
                            });
                    case "getPluginManager" -> Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                            new Class<?>[]{PluginManager.class}, (manager, lookup, params) -> {
                                if (lookup.getName().equals("getPlugins")) return installed;
                                if (lookup.getName().equals("getPlugin")) {
                                    return java.util.Arrays.stream(installed).filter(plugin -> plugin.getName().equals(params[0]))
                                            .findFirst().orElse(null);
                                }
                                throw new AssertionError(lookup.getName());
                            });
                    default -> throw new AssertionError(method.getName());
                }));
    }

    @AfterEach void close() throws ReflectiveOperationException {
        try {
            owner.close();
        } finally {
            server.set(null, oldServer);
        }
    }

    @Test void javaDefaultRequiresSuccessfulLoadAndTheVerifiedModelPair() {
        Client client = new Client();
        PackPlan plan = pluginPlan(false, false);
        owner.open(client.player, plan);
        UUID request = client.last().packs().getFirst().id();
        assertFalse(client.last().replace());
        assertFalse(client.last().required());
        client.status(request, DOWNLOADED);
        assertNull(owner.readiness(client.player, plan, EMBER));
        client.status(UUID.randomUUID(), SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, plan, EMBER));
        client.status(request, SUCCESSFULLY_LOADED);
        assertNotNull(owner.readiness(client.player, plan, EMBER));
        assertNull(owner.readiness(client.player, plan, FROST));
        assertNull(owner.readiness(client.player, pluginPlan(true, false), EMBER));
    }

    @Test void stackedDeliveryIsOneOrderedBatchAndRequiresBoth() {
        Client client = new Client();
        PackPlan plan = pluginPlan(true, true);
        owner.open(client.player, plan);
        assertEquals(1, client.requests.size());
        assertFalse(client.last().required());
        assertEquals(Component.text("InfernoLantern requires these packs. Declining or failing to load them "
                + "disconnects you while this offer is active."), client.last().prompt());
        assertFalse(client.last().replace());
        assertEquals(plan.downloads().stream().map(PackPlan.Download::uri).toList(),
                client.last().packs().stream().map(ResourcePackInfo::uri).toList());
        assertEquals(plan.downloads().stream().map(PackPlan.Download::sha1).toList(),
                client.last().packs().stream().map(ResourcePackInfo::hash).toList());
        UUID base = client.last().packs().getFirst().id(), overlay = client.last().packs().getLast().id();
        client.status(base, SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, plan, EMBER));
        client.status(overlay, SUCCESSFULLY_LOADED);
        var permit = owner.readiness(client.player, plan, EMBER);
        assertNotNull(permit);
        client.status(overlay, DISCARDED);
        assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
        assertNull(owner.readiness(client.player, plan, EMBER));
        assertEquals(List.of(Component.text("InfernoLantern resource packs could not be loaded.")), client.kicks);
        client.status(overlay, SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, plan, EMBER));
    }

    @Test void mandatoryRefusalKicksAndOptionalMissingOrFailureGivesFeedback() {
        Client client = new Client();
        PackPlan mandatory = pluginPlan(true, false);
        owner.open(client.player, mandatory);
        client.status(client.last().packs().getFirst().id(), DECLINED);
        assertNull(owner.readiness(client.player, mandatory, EMBER));
        assertEquals(List.of(Component.text("InfernoLantern resource packs were declined.")), client.kicks);

        PackPlan optional = pluginPlan(false, false);
        owner.open(client.player, optional);
        client.status(client.last().packs().getFirst().id(), FAILED_DOWNLOAD);
        assertEquals(List.of(Component.text("InfernoLantern resource packs could not be loaded.")), client.messages);
        assertNull(owner.readiness(client.player, optional, EMBER));

        var attempt = owner.open(client.player, optional);
        owner.timedOut(client.player, attempt);
        owner.timedOut(client.player, attempt);
        client.status(client.last().packs().getFirst().id(), SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, optional, EMBER));
        assertEquals(Component.text("InfernoLantern resource packs are unavailable for this connection."),
                client.messages.getLast());
        assertEquals(2, client.messages.size());
    }

    @Test void staleIdsAndAttemptsCannotSatisfySamePlanReplacement() {
        Client client = new Client();
        PackPlan plan = pluginPlan(false, false);
        var old = owner.open(client.player, plan);
        UUID previous = client.last().packs().getFirst().id();
        client.status(previous, SUCCESSFULLY_LOADED);
        var permit = owner.readiness(client.player, plan, EMBER);
        assertNotNull(permit);
        owner.open(client.player, plan);
        assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
        assertEquals(List.of(List.of(previous)), client.removed);
        UUID replacement = client.last().packs().getFirst().id();
        assertNotEquals(previous, replacement);
        owner.timedOut(client.player, old);
        client.status(previous, SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, plan, EMBER));
        client.status(replacement, SUCCESSFULLY_LOADED);
        assertNotNull(owner.readiness(client.player, plan, EMBER));
    }

    @Test void serverManagedJavaAndBedrockNeedExactIdentityAndNeverSendJavaPacks() {
        Client client = new Client();
        PackPlan.Identity combined = new PackPlan.Identity(JAVA, COMBINED, List.of(MERGED), null);
        PackPlan managed = new PackPlan(combined, Set.of(EMBER, FROST), List.of(), true);
        var attempt = owner.open(client.player, managed);
        assertNull(owner.readiness(client.player, managed, EMBER));
        client.status(UUID.randomUUID(), SUCCESSFULLY_LOADED);
        assertNull(owner.readiness(client.player, managed, EMBER));
        var baseOnly = new PackPlan.Identity(JAVA, DEFAULT, List.of(BASE), null);
        assertFalse(owner.managedStatus(client.player, attempt, baseOnly, PaperPackSessions.Status.LOADED));
        assertTrue(owner.managedStatus(client.player, attempt, combined, PaperPackSessions.Status.LOADED));
        var permit = owner.readiness(client.player, managed, FROST);
        assertNotNull(permit);

        PackPlan.Identity bridgeIdentity = new PackPlan.Identity(BEDROCK, STACKED, List.of(BASE, OVERRIDE), "4".repeat(64));
        PackPlan bridge = new PackPlan(bridgeIdentity, Set.of(EMBER), List.of(), true);
        var bridgeAttempt = owner.open(client.player, bridge);
        assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
        assertFalse(owner.managedStatus(client.player, attempt, bridgeIdentity, PaperPackSessions.Status.LOADED));
        assertFalse(owner.managedStatus(client.player, bridgeAttempt,
                new PackPlan.Identity(BEDROCK, STACKED, List.of(BASE, OVERRIDE), "5".repeat(64)),
                PaperPackSessions.Status.LOADED));
        assertNull(owner.readiness(client.player, bridge, EMBER));
        assertTrue(owner.managedStatus(client.player, bridgeAttempt, bridgeIdentity, PaperPackSessions.Status.LOADED));
        assertNotNull(owner.readiness(client.player, bridge, EMBER));
        assertTrue(owner.managedStatus(client.player, bridgeAttempt, bridgeIdentity, PaperPackSessions.Status.REFUSED));
        assertNull(owner.readiness(client.player, bridge, EMBER));
        assertEquals(1, client.kicks.size());
        assertTrue(client.requests.isEmpty());
    }

    @Test void reconnectAndQuitRevokeTheirOwnConnectionOnly() {
        Client client = new Client();
        PackPlan plan = pluginPlan(false, false);
        owner.open(client.player, plan);
        client.status(client.last().packs().getFirst().id(), SUCCESSFULLY_LOADED);
        var permit = owner.readiness(client.player, plan, EMBER);
        assertNotNull(permit);

        Client next = new Client(client.id);
        owner.open(next.player, plan);
        next.status(next.last().packs().getFirst().id(), SUCCESSFULLY_LOADED);
        assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
        owner.quit(new PlayerQuitEvent(client.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        var newPermit = owner.readiness(next.player, plan, EMBER);
        assertNotNull(newPermit);
        owner.quit(new PlayerQuitEvent(next.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThrows(RepositoryException.CommitRevoked.class, newPermit::requireValid);
    }

    @Test void shutdownRevokesEveryPermitAndRemovesOnlyItsOwnPacks() {
        Client client = new Client();
        PackPlan plan = pluginPlan(false, true);
        owner.open(client.player, plan);
        var ids = client.last().packs().stream().map(ResourcePackInfo::id).toList();
        for (UUID id : ids) client.status(id, SUCCESSFULLY_LOADED);
        var permit = owner.readiness(client.player, plan, EMBER);
        assertNotNull(permit);
        owner.close();
        assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
        assertEquals(1, client.removed.size());
        assertEquals(Set.copyOf(ids), Set.copyOf(client.removed.getFirst()));
    }

    @Test void nativeDeliveryRegistersAndOffersToCurrentAndJoiningConnections() {
        Client before = new Client();
        online = List.of(before.player);
        var registered = new ArrayList<Listener>();
        var timeouts = new ArrayList<Runnable>();
        var availability = new CommitPermit();
        try (var delivery = new NativeJavaPackDelivery(Bukkit.getServer(), new JavaPack(pluginPlan(false, false),
                availability), (task, delay) -> {
                    assertEquals(1_200L, delay);
                    timeouts.add(task);
                })) {
            delivery.start(registered::add);
            assertEquals("offering the optional bundled pack", delivery.status());
            var listener = assertInstanceOf(PaperPackSessions.class, registered.getFirst());
            assertSame(delivery, registered.get(1));
            assertEquals(1, before.requests.size());
            assertFalse(before.last().required());
            UUID original = before.last().packs().getFirst().id();
            listener.status(new PlayerResourcePackStatusEvent(before.player, original, ACCEPTED));
            assertNull(delivery.readiness(before.player, EMBER));
            listener.status(new PlayerResourcePackStatusEvent(before.player, original, SUCCESSFULLY_LOADED));
            var permit = delivery.readiness(before.player, EMBER);
            assertNotNull(permit);
            assertNull(delivery.readiness(before.player, FROST));
            timeouts.getFirst().run();
            permit.requireValid();

            Client reconnected = new Client(before.id);
            var join = new PlayerJoinEvent(reconnected.player, Component.empty());
            delivery.join(join);
            delivery.join(join);
            assertEquals(1, reconnected.requests.size());
            assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
            assertNull(delivery.readiness(reconnected.player, EMBER));
            timeouts.getFirst().run();
            assertTrue(reconnected.messages.isEmpty());
            listener.status(new PlayerResourcePackStatusEvent(reconnected.player, original, SUCCESSFULLY_LOADED));
            assertNull(delivery.readiness(reconnected.player, EMBER));
            var fresh = reconnected.last().packs().getFirst().id();
            listener.status(new PlayerResourcePackStatusEvent(reconnected.player, fresh, SUCCESSFULLY_LOADED));
            var freshPermit = delivery.readiness(reconnected.player, EMBER);
            assertNotNull(freshPermit);

            Client refuses = new Client();
            delivery.join(new PlayerJoinEvent(refuses.player, Component.empty()));
            listener.status(new PlayerResourcePackStatusEvent(refuses.player, refuses.last().packs().getFirst().id(), DECLINED));
            assertNull(delivery.readiness(refuses.player, EMBER));
            assertEquals(1, refuses.messages.size());
            assertTrue(refuses.kicks.isEmpty());

            Client pending = new Client();
            delivery.join(new PlayerJoinEvent(pending.player, Component.empty()));
            timeouts.getLast().run();
            assertEquals(1, pending.messages.size());
            listener.status(new PlayerResourcePackStatusEvent(pending.player, pending.last().packs().getFirst().id(), SUCCESSFULLY_LOADED));
            assertNull(delivery.readiness(pending.player, EMBER));

            availability.revoke();
            assertThrows(RepositoryException.CommitRevoked.class, freshPermit::requireValid);
            assertNull(delivery.readiness(reconnected.player, EMBER));
            delivery.close();
            assertEquals(List.of(List.of(fresh)), reconnected.removed);
            timeouts.forEach(Runnable::run);
            assertEquals(1, pending.messages.size());
        }
    }

    @Test void requiredNativeStackKicksForFailureOnlyWhileDeliveryIsAvailable() {
        var registered = new ArrayList<Listener>();
        var timeouts = new ArrayList<Runnable>();
        var availability = new CommitPermit();
        PackPlan plan = pluginPlan(true, true);
        var verified = new JavaPack(plan, availability);
        try (var delivery = new NativeJavaPackDelivery(Bukkit.getServer(), verified, (task, delay) -> timeouts.add(task))) {
            delivery.start(registered::add);
            assertEquals("offering the required bundled pack stack", delivery.status());
            var listener = assertInstanceOf(PaperPackSessions.class, registered.getFirst());

            for (var status : List.of(DECLINED, FAILED_DOWNLOAD)) {
                Client client = new Client();
                delivery.join(new PlayerJoinEvent(client.player, Component.empty()));
                var request = client.last();
                assertFalse(request.required());
                assertEquals(plan.downloads().stream().map(PackPlan.Download::uri).toList(),
                        request.packs().stream().map(ResourcePackInfo::uri).toList());
                listener.status(new PlayerResourcePackStatusEvent(client.player, request.packs().getFirst().id(),
                        SUCCESSFULLY_LOADED));
                assertNull(delivery.readiness(client.player, EMBER));
                listener.status(new PlayerResourcePackStatusEvent(client.player, request.packs().getLast().id(), status));
                listener.status(new PlayerResourcePackStatusEvent(client.player, request.packs().getLast().id(), status));
                listener.status(new PlayerResourcePackStatusEvent(client.player, request.packs().getFirst().id(), FAILED_DOWNLOAD));
                assertEquals(1, client.kicks.size());
                assertNull(delivery.readiness(client.player, EMBER));
                assertTrue(client.messages.isEmpty());
            }

            Client timedOut = new Client();
            delivery.join(new PlayerJoinEvent(timedOut.player, Component.empty()));
            timeouts.getLast().run();
            timeouts.getLast().run();
            assertEquals(1, timedOut.kicks.size());

            Client late = new Client();
            delivery.join(new PlayerJoinEvent(late.player, Component.empty()));
            Client pending = new Client();
            delivery.join(new PlayerJoinEvent(pending.player, Component.empty()));
            availability.revoke();
            var refusal = new PlayerResourcePackStatusEvent(late.player, late.last().packs().getLast().id(), DECLINED);
            listener.status(refusal);
            listener.status(refusal);
            timeouts.getLast().run();
            assertTrue(late.kicks.isEmpty());
            assertTrue(pending.kicks.isEmpty());
            assertNull(delivery.readiness(late.player, EMBER));
            assertNull(delivery.readiness(pending.player, EMBER));
        }
    }

    @Test void catalogRevocationDuringProxyCheckPreventsRequiredEnforcement() {
        var listeners = new ArrayList<Listener>();
        var availability = new CommitPermit();
        var verified = new JavaPack(pluginPlan(true, true), availability);
        try (var delivery = new NativeJavaPackDelivery(Bukkit.getServer(), verified, (task, delay) -> {})) {
            delivery.start(listeners::add);
            Client client = new Client();
            delivery.join(new PlayerJoinEvent(client.player, Component.empty()));
            checkProxy = () -> CompletableFuture.runAsync(availability::revoke).join();
            var listener = assertInstanceOf(PaperPackSessions.class, listeners.getFirst());
            listener.status(new PlayerResourcePackStatusEvent(client.player, client.last().packs().getFirst().id(), DECLINED));
            assertThrows(RepositoryException.CommitRevoked.class, availability::requireValid);
            assertTrue(client.kicks.isEmpty(), () -> "Kick after catalog revocation: " + client.kicks);
            assertNull(delivery.readiness(client.player, EMBER));
        }
    }

    @Test void nativeDeliveryBlocksKnownBridgesAndPaperProxyMode() {
        Client client = new Client();
        online = List.of(client.player);
        for (String name : List.of("Geyser-Spigot", "floodgate")) {
            installed = new Plugin[]{plugin(name)};
            assertNativeDeliveryBlocked(client);
        }
        installed = new Plugin[0];
        proxied = true;
        assertNativeDeliveryBlocked(client);
    }

    private void assertNativeDeliveryBlocked(Client client) {
        var verified = new JavaPack(pluginPlan(true, true), new CommitPermit());
        try (var delivery = new NativeJavaPackDelivery(Bukkit.getServer(), verified,
                (task, delay) -> fail("Blocked topology cannot schedule a pack timeout"))) {
            delivery.start(listener -> fail("Blocked topology cannot register delivery"));
            delivery.join(new PlayerJoinEvent(client.player, Component.empty()));
            assertEquals("blocked by a detected bridge or proxy", delivery.status());
            assertNull(delivery.readiness(client.player, EMBER));
            assertTrue(client.requests.isEmpty());
            assertTrue(client.kicks.isEmpty());
        }
    }

    @Test void enablingBridgeRevokesLoadedReadinessAndStopsFurtherOffers() {
        Client client = new Client();
        online = List.of(client.player);
        var registered = new ArrayList<Listener>();
        var verified = new JavaPack(pluginPlan(false, false), new CommitPermit());
        try (var delivery = new NativeJavaPackDelivery(Bukkit.getServer(), verified, (task, delay) -> {})) {
            delivery.start(registered::add);
            var id = client.last().packs().getFirst().id();
            assertInstanceOf(PaperPackSessions.class, registered.getFirst())
                    .status(new PlayerResourcePackStatusEvent(client.player, id, SUCCESSFULLY_LOADED));
            var permit = delivery.readiness(client.player, EMBER);
            assertNotNull(permit);
            delivery.pluginEnabled(new PluginEnableEvent(plugin("Unrelated")));
            permit.requireValid();
            delivery.pluginEnabled(new PluginEnableEvent(plugin("Geyser-Spigot")));
            assertThrows(RepositoryException.CommitRevoked.class, permit::requireValid);
            assertEquals(List.of(List.of(id)), client.removed);
            Client fresh = new Client();
            delivery.join(new PlayerJoinEvent(fresh.player, Component.empty()));
            assertTrue(fresh.requests.isEmpty());
            assertEquals("blocked by a detected bridge or proxy", delivery.status());
        }
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("isEnabled")) return true;
                    throw new AssertionError(method.getName());
                });
    }

    @Test void nativeCombinedObservesOnlyExactCurrentReceiptsFromItsConfiguredOwner() {
        var client = new Client();
        online = List.of(client.player);
        var installedOwner = plugin("PackOwner");
        var other = plugin("OtherOwner");
        installed = new Plugin[]{installedOwner, other};
        var plan = combinedPlan();
        var lifetime = new CommitPermit();
        var receiptPermit = new CommitPermit();
        var observed = new java.util.concurrent.atomic.AtomicReference<CombinedJavaPackProvider.Receipt>();
        var registration = registration(installedOwner, (player, expected) -> {
            assertSame(client.player, player);
            assertEquals(plan.identity(), expected);
            return observed.get();
        });
        services = List.of(registration(other, (player, expected) -> fail("Unconfigured provider was called")));

        try (var delivery = combinedDelivery(plan, lifetime)) {
            var registered = new ArrayList<Listener>();
            delivery.start(registered::add);
            assertEquals(List.of(delivery), registered);
            assertEquals("combined pack provider unavailable", delivery.status());
            assertNull(delivery.readiness(client.player, EMBER));
            services = List.of(services.getFirst(), registration);
            delivery.serviceRegistered(new ServiceRegisterEvent(registration));
            assertEquals("observing the server-managed combined pack (0/1 checked connections ready)", delivery.status());

            delivery.join(new PlayerJoinEvent(client.player, Component.empty()));
            observed.set(new CombinedJavaPackProvider.Receipt(new Client(client.id).player, plan.identity(),
                    PaperPackSessions.Status.LOADED, receiptPermit));
            assertNull(delivery.readiness(client.player, EMBER));
            observed.set(new CombinedJavaPackProvider.Receipt(client.player, pluginPlan(false, false).identity(),
                    PaperPackSessions.Status.LOADED, receiptPermit));
            assertNull(delivery.readiness(client.player, EMBER));
            observed.set(new CombinedJavaPackProvider.Receipt(client.player, plan.identity(),
                    PaperPackSessions.Status.REFUSED, receiptPermit));
            assertNull(delivery.readiness(client.player, EMBER));
            assertTrue(client.messages.isEmpty());
            assertTrue(client.kicks.isEmpty());

            observed.set(new CombinedJavaPackProvider.Receipt(client.player, plan.identity(),
                    PaperPackSessions.Status.LOADED, receiptPermit));
            var grant = delivery.readiness(client.player, EMBER);
            assertNotNull(grant);
            assertNull(delivery.readiness(client.player, FROST));
            assertEquals("observing the server-managed combined pack (1/1 checked connections ready)", delivery.status());
            receiptPermit.revoke();
            assertThrows(RepositoryException.CommitRevoked.class, grant::requireValid);
            assertNull(delivery.readiness(client.player, EMBER));

            observed.set(new CombinedJavaPackProvider.Receipt(client.player, plan.identity(),
                    PaperPackSessions.Status.LOADED, new CommitPermit()));
            var lastGrant = delivery.readiness(client.player, EMBER);
            assertNotNull(lastGrant);
            lifetime.revoke();
            assertThrows(RepositoryException.CommitRevoked.class, lastGrant::requireValid);
            assertNull(delivery.readiness(client.player, EMBER));
        }
        assertTrue(client.requests.isEmpty());
        assertTrue(client.removed.isEmpty());
    }

    @Test void nativeCombinedRevokesOnProviderLifecycleAndDisconnect() {
        var client = new Client();
        var rejoined = new Client(client.id);
        online = List.of(client.player);
        var ownerPlugin = plugin("PackOwner");
        installed = new Plugin[]{ownerPlugin};
        var plan = combinedPlan();
        var selected = registration(ownerPlugin, (player, expected) ->
                new CombinedJavaPackProvider.Receipt(player, expected, PaperPackSessions.Status.LOADED, new CommitPermit()));
        services = List.of(selected);

        try (var delivery = combinedDelivery(plan, new CommitPermit())) {
            delivery.start(listener -> {});
            var previous = delivery.readiness(client.player, EMBER);
            assertNotNull(previous);
            var duplicate = registration(ownerPlugin, selected.getProvider());
            services = List.of(selected, duplicate);
            delivery.serviceRegistered(new ServiceRegisterEvent(duplicate));
            assertThrows(RepositoryException.CommitRevoked.class, previous::requireValid);
            assertNull(delivery.readiness(client.player, EMBER));

            services = List.of(selected);
            delivery.serviceUnregistered(new ServiceUnregisterEvent(duplicate));
            var beforeDisable = delivery.readiness(client.player, EMBER);
            assertNotNull(beforeDisable);
            delivery.pluginDisabling(new PluginDisableEvent(ownerPlugin));
            assertThrows(RepositoryException.CommitRevoked.class, beforeDisable::requireValid);
            assertNull(delivery.readiness(client.player, EMBER));

            delivery.pluginEnabled(new PluginEnableEvent(ownerPlugin));
            var beforeQuit = delivery.readiness(client.player, EMBER);
            assertNotNull(beforeQuit);
            delivery.quit(new PlayerQuitEvent(client.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            assertThrows(RepositoryException.CommitRevoked.class, beforeQuit::requireValid);
            online = List.of(rejoined.player);
            delivery.join(new PlayerJoinEvent(rejoined.player, Component.empty()));
            assertNull(delivery.readiness(client.player, EMBER));
            var beforeReplacement = delivery.readiness(rejoined.player, EMBER);
            assertNotNull(beforeReplacement);
            services = List.of();
            delivery.serviceUnregistered(new ServiceUnregisterEvent(selected));
            assertThrows(RepositoryException.CommitRevoked.class, beforeReplacement::requireValid);
            assertNull(delivery.readiness(rejoined.player, EMBER));
        }
    }

    @Test void nativeCombinedCannotReadmitAQuitConnectionStillInTheOnlineRegistry() {
        var client = new Client();
        online = List.of(client.player);
        var ownerPlugin = plugin("PackOwner");
        installed = new Plugin[]{ownerPlugin};
        var plan = combinedPlan();
        var observations = new java.util.concurrent.atomic.AtomicInteger();
        var receipt = new CombinedJavaPackProvider.Receipt(client.player, plan.identity(), PaperPackSessions.Status.LOADED,
                new CommitPermit());
        services = List.of(registration(ownerPlugin, (player, expected) -> {
            observations.incrementAndGet();
            return receipt;
        }));

        try (var delivery = combinedDelivery(plan, new CommitPermit())) {
            delivery.start(listener -> {});
            var grant = delivery.readiness(client.player, EMBER);
            assertNotNull(grant);

            client.connected = false;
            delivery.quit(new PlayerQuitEvent(client.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            assertThrows(RepositoryException.CommitRevoked.class, grant::requireValid);
            assertTrue(client.player.isOnline());
            assertSame(client.player, Bukkit.getServer().getPlayer(client.id));
            assertEquals("observing the server-managed combined pack (0/1 checked connections ready)", delivery.status());
            assertNull(delivery.readiness(client.player, EMBER));
            assertEquals(1, observations.get());
        }
    }

    @Test void nativeCombinedRejectsReceiptWhenTheConnectionClosesDuringObservation() {
        var client = new Client();
        online = List.of(client.player);
        var ownerPlugin = plugin("PackOwner");
        installed = new Plugin[]{ownerPlugin};
        var plan = combinedPlan();
        var receipts = new java.util.concurrent.atomic.AtomicInteger();
        var receipt = new CombinedJavaPackProvider.Receipt(client.player, plan.identity(), PaperPackSessions.Status.LOADED,
                new CommitPermit());
        services = List.of(registration(ownerPlugin, (player, expected) -> {
            if (receipts.incrementAndGet() == 2) client.connected = false;
            return receipt;
        }));

        try (var delivery = combinedDelivery(plan, new CommitPermit())) {
            delivery.start(listener -> {});
            var beforeClose = delivery.readiness(client.player, EMBER);
            assertNotNull(beforeClose);
            assertNull(delivery.readiness(client.player, EMBER));
            assertThrows(RepositoryException.CommitRevoked.class, beforeClose::requireValid);
            assertTrue(client.player.isOnline());
        }
    }

    @Test void nativeCombinedOnlyFencesTheExactConnectionOfAnAcceptedKick() {
        var client = new Client();
        var rejoined = new Client(client.id);
        online = List.of(client.player);
        var ownerPlugin = plugin("PackOwner");
        installed = new Plugin[]{ownerPlugin};
        var plan = combinedPlan();
        var oldPermit = new CommitPermit();
        var newPermit = new CommitPermit();
        services = List.of(registration(ownerPlugin, (player, expected) -> new CombinedJavaPackProvider.Receipt(
                player, expected, PaperPackSessions.Status.LOADED, player == client.player ? oldPermit : newPermit)));

        try (var delivery = combinedDelivery(plan, new CommitPermit())) {
            delivery.start(listener -> {});
            var grant = delivery.readiness(client.player, EMBER);
            assertNotNull(grant);
            var event = new PlayerKickEvent(client.player, Component.empty(), Component.empty(), PlayerKickEvent.Cause.UNKNOWN);
            event.setCancelled(true);
            delivery.kick(event);
            grant.requireValid();
            assertSame(grant, delivery.readiness(client.player, EMBER));

            event.setCancelled(false);
            delivery.kick(event);
            assertThrows(RepositoryException.CommitRevoked.class, grant::requireValid);
            assertTrue(client.player.isConnected());
            assertNull(delivery.readiness(client.player, EMBER));

            online = List.of(rejoined.player);
            delivery.join(new PlayerJoinEvent(rejoined.player, Component.empty()));
            var rejoinedGrant = delivery.readiness(rejoined.player, EMBER);
            assertNotNull(rejoinedGrant);
            client.connected = false;
            delivery.quit(new PlayerQuitEvent(client.player, Component.empty(), PlayerQuitEvent.QuitReason.KICKED));
            rejoinedGrant.requireValid();
            assertSame(rejoinedGrant, delivery.readiness(rejoined.player, EMBER));
        }
    }

    @Test void nativeCombinedDiscardsObservationIfProviderUnregistersDuringCallback() {
        var client = new Client();
        online = List.of(client.player);
        var installedOwner = plugin("PackOwner");
        installed = new Plugin[]{installedOwner};
        var plan = combinedPlan();
        try (var delivery = combinedDelivery(plan, new CommitPermit())) {
            var selected = registration(installedOwner, (player, expected) -> {
                var registration = services.getFirst();
                services = List.of();
                delivery.serviceUnregistered(new ServiceUnregisterEvent(registration));
                assertNull(delivery.readiness(player, EMBER));
                return new CombinedJavaPackProvider.Receipt(player, expected, PaperPackSessions.Status.LOADED, new CommitPermit());
            });
            services = List.of(selected);
            delivery.start(listener -> {});
            assertNull(delivery.readiness(client.player, EMBER));
            assertEquals("combined pack provider unavailable", delivery.status());
        }
    }

    private static NativeJavaPackDelivery combinedDelivery(PackPlan plan, CommitPermit lifetime) {
        return new NativeJavaPackDelivery(Bukkit.getServer(), new JavaPack(plan, lifetime),
                (task, delay) -> fail("Combined delivery scheduled a timeout"), "PackOwner");
    }

    private static PackPlan combinedPlan() {
        return new PackPlan(new PackPlan.Identity(JAVA, COMBINED, List.of(MERGED), null), Set.of(EMBER), List.of(), false);
    }

    private static RegisteredServiceProvider<CombinedJavaPackProvider> registration(Plugin owner, CombinedJavaPackProvider provider) {
        return new RegisteredServiceProvider<>(CombinedJavaPackProvider.class, provider, ServicePriority.Normal, owner);
    }

    @Test void incompleteCombinedAndReversedStackAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PackPlan.Identity(JAVA, COMBINED, List.of(OVERRIDE), null));
        assertThrows(IllegalArgumentException.class, () -> new PackPlan.Identity(JAVA, STACKED, List.of(OVERRIDE, BASE), null));
        assertThrows(IllegalArgumentException.class, () -> new PackPlan.Identity(BEDROCK, DEFAULT, List.of(BASE), null));
    }

    private static PackPlan pluginPlan(boolean required, boolean stacked) {
        var archives = stacked ? List.of(BASE, OVERRIDE) : List.of(BASE);
        return new PackPlan(new PackPlan.Identity(JAVA, stacked ? STACKED : DEFAULT, archives, null), Set.of(EMBER),
                archives.stream().map(archive -> new PackPlan.Download(archive,
                        URI.create("https://example.invalid/" + archive.slot() + ".zip"),
                        archive.sha256().substring(0, 40))).toList(), required);
    }

    private final class Client {
        final UUID id;
        final List<ResourcePackRequest> requests = new ArrayList<>();
        final List<Component> kicks = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();
        final List<List<?>> removed = new ArrayList<>();
        final Player player;
        boolean connected = true;

        Client() {
            this(UUID.randomUUID());
        }

        Client(UUID id) {
            this.id = id;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "isOnline" -> true;
                        case "isConnected" -> connected;
                        case "sendResourcePacks" -> {
                            requests.add((ResourcePackRequest) arguments[0]);
                            yield null;
                        }
                        case "removeResourcePacks" -> {
                            removed.add(List.copyOf((Set<?>) arguments[0]));
                            yield null;
                        }
                        case "sendMessage" -> {
                            messages.add((Component) arguments[0]);
                            yield null;
                        }
                        case "kick" -> {
                            kicks.add((Component) arguments[0]);
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
        }

        ResourcePackRequest last() {
            return requests.getLast();
        }

        void status(UUID id, PlayerResourcePackStatusEvent.Status status) {
            owner.status(new PlayerResourcePackStatusEvent(player, id, status));
        }
    }
}
