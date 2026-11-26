package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException.CommitRevoked;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Verifies externally observed receipts without sending, removing or enforcing a pack. */
final class CombinedJavaPackReadiness {
    private static final int STATUS_LIMIT = 128;

    private record Grant(Player player, CommitPermit receipt, CommitPermit local, CommitPermit admission) {}

    private final Server server;
    private final PackPlan plan;
    private final String providerName;
    private final Map<UUID, Grant> grants = new HashMap<>();
    private RegisteredServiceProvider<CombinedJavaPackProvider> provider;
    private Plugin disabling;
    private long generation;
    private boolean observing;
    private boolean closed;

    CombinedJavaPackReadiness(Server server, PackPlan plan, String providerName) {
        this.server = server;
        this.plan = plan;
        this.providerName = providerName;
    }

    CommitPermit readiness(Player player, PackPlan.Models models, BooleanSupplier admit, CommitPermit availability) {
        if (closed || observing || !plan.models().contains(models) || !admit.getAsBoolean()) return null;
        UUID id = player.getUniqueId();
        if (!current(player, id)) return null;
        refreshProvider();
        var selected = provider;
        if (selected == null) return null;
        long observedGeneration = generation;
        CombinedJavaPackProvider.Receipt receipt;
        observing = true;
        try {
            receipt = selected.getProvider().observe(player, plan.identity());
        } catch (RuntimeException unavailable) {
            receipt = null;
        } finally {
            observing = false;
        }
        refreshProvider();
        if (!current(player, id) || !admit.getAsBoolean() || observedGeneration != generation) return null;

        if (receipt == null || receipt.player() != player || !plan.identity().equals(receipt.identity())
                || receipt.status() != PaperPackSessions.Status.LOADED) {
            discard(id);
            return null;
        }
        Grant grant = grants.get(id);
        if (grant == null || grant.player() != player || grant.receipt() != receipt.permit()) {
            discard(id);
            var local = new CommitPermit();
            grant = new Grant(player, receipt.permit(), local, CommitPermit.allOf(local, availability, receipt.permit()));
            grants.put(id, grant);
        }
        try {
            grant.admission().requireValid();
            return grant.admission();
        } catch (CommitRevoked revoked) {
            discard(id);
            return null;
        }
    }

    String status(BooleanSupplier admit, CommitPermit availability) {
        refreshProvider();
        if (provider == null) return "combined pack provider unavailable";
        long observedGeneration = generation;
        int ready = 0;
        int checked = 0;
        PackPlan.Models model = plan.models().iterator().next();
        var players = server.getOnlinePlayers().stream().limit(STATUS_LIMIT + 1).toList();
        for (Player player : players) {
            if (checked == STATUS_LIMIT) break;
            if (readiness(player, model, admit, availability) != null) ready++;
            checked++;
        }
        if (!admit.getAsBoolean()) return "unavailable";
        if (provider == null) return "combined pack provider unavailable";
        if (observedGeneration != generation) return "combined pack connections changed during inspection";
        return "observing the server-managed combined pack (" + ready + "/" + checked + " checked connections ready"
                + (players.size() > STATUS_LIMIT ? ", limited sample" : "") + ")";
    }

    void joined(Player player) {
        generation++;
        UUID id = player.getUniqueId();
        Grant existing = grants.get(id);
        if (existing != null && existing.player() != player) discard(id);
    }

    void disconnected(Player player) {
        generation++;
        UUID id = player.getUniqueId();
        Grant existing = grants.get(id);
        if (existing != null && existing.player() == player) discard(id);
    }

    void serviceChanged(RegisteredServiceProvider<?> changed, boolean removed) {
        if (removed && changed == provider) replaceProvider(null);
        else refreshProvider();
    }

    void pluginDisabling(Plugin plugin) {
        if (!plugin.getName().equals(providerName)) return;
        disabling = plugin;
        replaceProvider(null);
    }

    void pluginEnabled(Plugin plugin) {
        if (disabling == plugin) disabling = null;
        if (plugin.getName().equals(providerName)) refreshProvider();
    }

    void close() {
        closed = true;
        replaceProvider(null);
    }

    private boolean current(Player player, UUID id) {
        return !closed && player.isOnline() && server.getPlayer(id) == player;
    }

    private void refreshProvider() {
        if (closed) return;
        Plugin owner = server.getPluginManager().getPlugin(providerName);
        RegisteredServiceProvider<CombinedJavaPackProvider> next = null;
        if (owner != null && owner != disabling && owner.isEnabled() && owner.getName().equals(providerName)) {
            for (var entry : server.getServicesManager().getRegistrations(CombinedJavaPackProvider.class)) {
                if (entry.getPlugin() != owner) continue;
                if (next != null) {
                    next = null;
                    break;
                }
                next = entry;
            }
        }
        replaceProvider(next);
    }

    private void replaceProvider(RegisteredServiceProvider<CombinedJavaPackProvider> next) {
        if (provider == next) return;
        for (var grant : grants.values()) grant.local().revoke();
        grants.clear();
        provider = next;
        generation++;
    }

    private void discard(UUID id) {
        Grant old = grants.remove(id);
        if (old != null) old.local().revoke();
    }
}
