package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.CommitPermit;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Java pack delivery and server-managed readiness on Paper's primary thread. */
public final class PaperPackSessions implements Listener, AutoCloseable {
    private static final Component PROMPT = Component.text("Load the resource packs to use InfernoLantern.");
    public enum Status { LOADED, REFUSED, FAILED, MISSING }

    /** Captured by the trusted delivery owner and returned with its external observations. */
    public static final class Attempt {
        private Attempt() {}
    }

    private static final class Session {
        final Player player;
        final PackPlan plan;
        final Attempt attempt = new Attempt();
        final CommitPermit permit = new CommitPermit();
        final Set<UUID> sent = new HashSet<>();
        final Set<UUID> loaded = new HashSet<>();
        boolean ready;
        boolean failed;

        Session(Player player, PackPlan plan) {
            this.player = player;
            this.plan = plan;
        }

        void revoke() {
            ready = false;
            failed = true;
            permit.revoke();
        }
    }

    private final Map<UUID, Session> players = new HashMap<>();
    private boolean closed;

    /**
     * Call for the current connection after the owner has resolved the verified plan.
     * Start again on a plan replacement before admitting further actions.
     */
    public Attempt open(Player player, PackPlan plan) {
        requireOwner();
        Objects.requireNonNull(plan);
        if (closed || !player.isOnline()) throw new IllegalStateException("Player pack connection is unavailable");
        Session session = new Session(player, plan);
        Session previous = players.put(player.getUniqueId(), session);
        try {
            if (previous != null) {
                previous.revoke();
                if (previous.player == player && !previous.sent.isEmpty()) {
                    player.removeResourcePacks(previous.sent);
                }
            }
            if (current(player) == session && plan.identity().pluginDelivered()) {
                var packs = plan.downloads().stream().map(download -> {
                    UUID requestId = UUID.randomUUID();
                    session.sent.add(requestId);
                    return ResourcePackInfo.resourcePackInfo(requestId, download.uri(), download.sha1());
                }).toList();
                player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(packs)
                        .replace(false).required(plan.required()).prompt(PROMPT).build());
            }
        } catch (RuntimeException deliveryFailure) {
            if (current(player) == session) apply(session, Status.FAILED);
        }
        return session.attempt;
    }

    /** The native caller must also revalidate its independent access, lifecycle and protection rules. */
    public CommitPermit readiness(Player player, PackPlan expected, PackPlan.Models models) {
        requireOwner();
        Session session = current(player);
        return session != null && session.ready && session.plan.equals(expected) && session.plan.models().contains(models)
                ? session.permit : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void status(PlayerResourcePackStatusEvent event) {
        requireOwner();
        Session session = current(event.getPlayer());
        if (session == null || session.failed || !session.sent.contains(event.getID())) return;
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                session.loaded.add(event.getID());
                if (session.loaded.equals(session.sent)) apply(session, Status.LOADED);
            }
            case DECLINED -> apply(session, Status.REFUSED);
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> apply(session, Status.FAILED);
            case ACCEPTED, DOWNLOADED -> { /* Progress does not establish readiness. */ }
        }
    }

    /**
     * Accept an observation from the trusted server-managed Java or bridge owner, only for
     * this attempt and the exact loaded archive stack and mapping digest. This is not a packet listener.
     */
    public boolean managedStatus(Player player, Attempt attempt, PackPlan.Identity identity, Status status) {
        requireOwner();
        Session session = current(player);
        if (session == null || session.failed || session.attempt != attempt || session.plan.identity().pluginDelivered()
                || !session.plan.identity().equals(identity)) return false;
        apply(session, Objects.requireNonNull(status));
        return true;
    }

    /** Apply the delivery owner's timeout only while this attempt is still pending. */
    public void timedOut(Player player, Attempt attempt) {
        requireOwner();
        Session session = current(player);
        if (session != null && session.attempt == attempt && !session.ready && !session.failed) {
            apply(session, Status.MISSING);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        disconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) {
        if (!event.isCancelled()) disconnect(event.getPlayer());
    }

    private void apply(Session session, Status status) {
        if (session.failed) return;
        if (status == Status.LOADED) {
            session.ready = true;
            return;
        }
        session.revoke();
        Component feedback = Component.text(switch (status) {
            case REFUSED -> "InfernoLantern resource packs were declined.";
            case FAILED -> "InfernoLantern resource packs could not be loaded.";
            case MISSING -> "InfernoLantern resource packs are unavailable for this connection.";
            case LOADED -> throw new AssertionError(status);
        });
        if (session.plan.required()) session.player.kick(feedback);
        else session.player.sendMessage(feedback);
    }

    private Session current(Player player) {
        Session session = players.get(player.getUniqueId());
        return !closed && player.isOnline() && session != null && session.player == player ? session : null;
    }

    private void disconnect(Player player) {
        requireOwner();
        Session session = players.get(player.getUniqueId());
        if (session != null && session.player == player) {
            session.revoke();
            players.remove(player.getUniqueId());
        }
    }

    private static void requireOwner() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Paper owning thread required");
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        var retired = List.copyOf(players.values());
        retired.forEach(Session::revoke);
        players.clear();
        RuntimeException failure = null;
        for (Session session : retired) {
            if (!session.sent.isEmpty() && session.player.isOnline()) {
                try {
                    session.player.removeResourcePacks(session.sent);
                } catch (RuntimeException unavailable) {
                    if (failure == null) failure = unavailable;
                }
            }
        }
        if (failure != null) throw failure;
    }
}
