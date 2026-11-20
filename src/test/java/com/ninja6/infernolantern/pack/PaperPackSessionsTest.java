package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.RepositoryException;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
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

    @BeforeEach void installOwnerThread() throws ReflectiveOperationException {
        server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        oldServer = server.get(null);
        Thread thread = Thread.currentThread();
        server.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("isPrimaryThread")) return Thread.currentThread() == thread;
                    throw new AssertionError(method.getName());
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
        assertTrue(client.last().required());
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

        Client() {
            this(UUID.randomUUID());
        }

        Client(UUID id) {
            this.id = id;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "isOnline" -> true;
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
