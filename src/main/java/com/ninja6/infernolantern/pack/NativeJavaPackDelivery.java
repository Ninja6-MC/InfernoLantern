package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.runtime.NativeCatalogRuntime.JavaPack;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns Java pack delivery on the Paper thread for a direct Java-only server. */
public final class NativeJavaPackDelivery implements Listener, AutoCloseable {
    private static final long TIMEOUT_TICKS = 1_200;

    private final Server server;
    private final JavaPack pack;
    private final BiConsumer<Runnable, Long> schedule;
    private final PaperPackSessions sessions;
    private boolean started;
    private boolean blocked;
    private boolean closed;

    public NativeJavaPackDelivery(Server server, JavaPack pack, BiConsumer<Runnable, Long> schedule) {
        this.server = server;
        this.pack = pack;
        this.schedule = schedule;
        sessions = new PaperPackSessions(this::topologyAllowsDelivery, pack.availability());
    }

    public void start(Consumer<Listener> register) {
        if (started || closed) return;
        if (server.getServerConfig().isProxyEnabled()
                || Arrays.stream(server.getPluginManager().getPlugins()).anyMatch(NativeJavaPackDelivery::bridge)) {
            block();
            return;
        }
        register.accept(sessions);
        register.accept(this);
        started = true;
        List.copyOf(server.getOnlinePlayers()).forEach(this::offer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { offer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pluginEnabled(PluginEnableEvent event) {
        if (bridge(event.getPlugin())) block();
    }

    private static boolean bridge(Plugin plugin) {
        String name = plugin.getName().toLowerCase(Locale.ROOT);
        return name.equals("geyser") || name.startsWith("geyser-")
                || name.equals("floodgate") || name.startsWith("floodgate-");
    }

    private void offer(Player player) {
        if (!sessions.admitDelivery() || !player.isOnline() || sessions.hasAttempt(player)) return;
        var attempt = sessions.open(player, pack.plan());
        try {
            schedule.accept(() -> sessions.timedOut(player, attempt), TIMEOUT_TICKS);
        } catch (RuntimeException unavailable) {
            sessions.timedOut(player, attempt);
        }
    }

    /** The gameplay owner must additionally verify permissions, world policy, and other action rules. */
    public CommitPermit readiness(Player player, PackPlan.Models models) {
        if (!sessions.admitDelivery()) return null;
        var connection = sessions.readiness(player, pack.plan(), models);
        return connection == null ? null : CommitPermit.allOf(connection, pack.availability());
    }

    private boolean topologyAllowsDelivery() {
        if (!started || closed) return false;
        if (server.getServerConfig().isProxyEnabled()) {
            block();
            return false;
        }
        return !closed;
    }

    public String status() {
        if (!sessions.admitDelivery()) return blocked ? "blocked by a detected bridge or proxy" : "unavailable";
        String policy = pack.plan().required() ? "required" : "optional";
        String shape = pack.plan().identity().mode() == PackPlan.Mode.STACKED ? "pack stack" : "pack";
        if (pack.localOverride()) return "offering the " + policy + " pack stack with a local override";
        return "offering the " + policy + " bundled " + shape;
    }

    private void block() {
        blocked = true;
        close();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(sessions);
        sessions.close();
    }
}
