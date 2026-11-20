package com.ninja6.infernolantern.access;

import com.ninja6.infernolantern.repository.CommitPermit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Paper-thread connection identity and explicit administrative override. */
public final class OverrideSessions implements Listener, AutoCloseable {
    static final String PERMISSION = "infernolantern.admin.override";
    private static final String USAGE = " override on|off|status";
    private static final List<String> ACTIONS = List.of("on", "off", "status");
    private static final String SCOPE = "Override covers expansion cooldown, controller access to compaction, switches and repair, and expanded recovery.";

    private static final class Connection {
        final Player player;
        final UUID actor;
        final UUID id = UUID.randomUUID();
        final CommitPermit permit = new CommitPermit();
        CommitPermit override;

        Connection(Player player) {
            this.player = player;
            this.actor = player.getUniqueId();
        }

        void disableOverride() {
            if (override != null) {
                override.revoke();
                override = null;
            }
        }

        void close() {
            permit.revoke();
            disableOverride();
        }
    }

    public static final class Session {
        private final Connection connection;
        private final CommitPermit override;

        private Session(Connection connection) {
            this.connection = connection;
            this.override = connection.override;
        }

        public UUID actor() { return connection.actor; }
        public UUID id() { return connection.id; }
        Player player() { return connection.player; }
        public CommitPermit connectionPermit() { return connection.permit; }
        CommitPermit commitPermit(boolean bypass) {
            return bypass ? Objects.requireNonNull(override) : connection.permit;
        }
    }

    private final Map<UUID, Connection> connections = new HashMap<>();
    private boolean closed;

    public void start(Iterable<? extends Player> online) {
        requireOwner();
        for (Player player : online) connect(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { connect(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) {
        if (!event.isCancelled()) disconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { disconnect(event.getPlayer()); }

    private void connect(Player player) {
        requireOwner();
        if (closed) return;
        Connection previous = connections.get(player.getUniqueId());
        if (previous != null) previous.close();
        connections.put(player.getUniqueId(), new Connection(player));
    }

    private void disconnect(Player player) {
        requireOwner();
        Connection connection = connections.get(player.getUniqueId());
        if (connection != null && connection.player == player) {
            connection.close();
            connections.remove(player.getUniqueId());
        }
    }

    private Connection connection(Player player) {
        requireOwner();
        if (closed) return null;
        Connection connection = connections.get(player.getUniqueId());
        if (connection == null || connection.player != player) return null;
        if (!player.isOnline()) {
            disconnect(player);
            return null;
        }
        if (!player.hasPermission(PERMISSION)) connection.disableOverride();
        return connection;
    }

    public Session capture(Player player) {
        Connection connection = connection(player);
        return connection == null ? null : new Session(connection);
    }

    public UUID currentSession(Player player) {
        Connection connection = connection(player);
        return connection == null ? null : connection.id;
    }

    boolean current(Session session) {
        return session != null && connection(session.player()) == session.connection;
    }

    boolean enabled(Session session) {
        Connection connection = connection(session.player());
        return connection == session.connection && session.override != null && session.override == connection.override;
    }

    public boolean available(CommandSender sender) {
        return sender instanceof Player player && connection(player) != null && player.hasPermission(PERMISSION);
    }

    public void help(CommandSender sender, String label) {
        if (available(sender)) sender.sendMessage("/" + label + USAGE);
    }

    public List<String> complete(CommandSender sender, String prefix) {
        if (!available(sender)) return List.of();
        String lowercase = prefix.toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(action -> action.startsWith(lowercase)).toList();
    }

    public void command(CommandSender sender, String label, String[] args) {
        Connection connection = sender instanceof Player player ? connection(player) : null;
        if (connection == null || !connection.player.hasPermission(PERMISSION)) {
            sender.sendMessage("Override requires an authorized player session.");
            return;
        }
        Player player = connection.player;
        String action = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "on" -> {
                if (connection.override == null) connection.override = new CommitPermit();
                player.sendMessage("Override enabled for this session. " + SCOPE);
            }
            case "off" -> {
                connection.disableOverride();
                player.sendMessage("Override disabled.");
            }
            case "status" -> player.sendMessage("Override is " + (connection.override == null ? "off" : "on") + ". " + SCOPE);
            default -> player.sendMessage("Use /" + label + USAGE + ".");
        }
    }

    void requireOwner() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Paper owning thread required");
    }

    @Override public void close() {
        requireOwner();
        closed = true;
        connections.values().forEach(Connection::close);
        connections.clear();
    }
}
