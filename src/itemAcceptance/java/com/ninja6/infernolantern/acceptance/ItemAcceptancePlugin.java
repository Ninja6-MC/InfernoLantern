package com.ninja6.infernolantern.acceptance;

import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.item.PickupValidationContext;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Separate, default-disabled disposable fixture listener. No commands or persistent data. */
public final class ItemAcceptancePlugin extends JavaPlugin implements Listener {
    private final PaperItemIdentity identity = new PaperItemIdentity();
    private final Map<EntityPickupItemEvent, AcceptanceSession.Boundary> observed = new IdentityHashMap<>();
    private AcceptanceSession session;
    private BukkitTask expiry;
    private boolean armed;
    private int finalCancelled, finalAllowed;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled", false)) {
            getLogger().info("Acceptance helper unarmed; no listeners registered."); return;
        }
        try {
            Properties build = new Properties();
            try (var metadata = getResource("acceptance-build.properties")) {
                if (metadata == null) throw new IllegalStateException("Missing helper build metadata");
                build.load(metadata);
            }
            var core = getServer().getPluginManager().getPlugin("InfernoLantern");
            if (core == null || !core.getDescription().getVersion().equals(build.getProperty("core-version")))
                throw new IllegalStateException("Helper/core candidate mismatch");
            PickupValidationContext.current(null); // Link before arming; older candidates must stay unarmed.
            var scenario = AcceptanceSession.Scenario.valueOf(getConfig().getString("scenario", "").toUpperCase(Locale.ROOT).replace('-', '_'));
            int ttl = getConfig().getInt("ttl-seconds", 120);
            int max = getConfig().getInt("max-events", 512);
            session = new AcceptanceSession(scenario, System.nanoTime(), ttl, max);
            // Consume the arm flag before registering. A clean restart requires explicit re-arming.
            getConfig().set("enabled", false); saveConfig();
            if (YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml")).getBoolean("enabled", true))
                throw new IllegalStateException("Arm flag was not consumed");
            getServer().getPluginManager().registerEvents(this, this);
            armed = true;
            expiry = getServer().getScheduler().runTaskLater(this, () -> finish("expired"), ttl * 20L);
            getLogger().info("Acceptance helper armed; scenario=" + scenario + "; ttl-seconds=" + ttl + "; max-events=" + max);
        } catch (Exception | LinkageError invalid) {
            getLogger().warning("Acceptance helper unarmed; invalid configuration or unavailable listener context.");
            finish("startup-failed");
        }
    }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void pickup(EntityPickupItemEvent event) {
        if (!armed) return;
        if (!session.active(System.nanoTime())) { finish("expired-or-limit"); return; }
        if (!(event.getEntity() instanceof Player)) return;
        var token = identity.read(event.getItem().getItemStack());
        if (token.isEmpty() || !token.get().variant().equals("prototype.item-fixture")) return;
        // Only read event metadata on the owning event thread; never scan an inventory/world.
        AcceptanceSession.Boundary boundary = PickupValidationContext.current(event)
                .map(stage -> AcceptanceSession.Boundary.valueOf(stage.name()))
                .orElse(AcceptanceSession.Boundary.ORIGINAL);
        boolean cancel = session.observe(token.get().tower(), boundary, System.nanoTime());
        if (!session.selected(token.get().tower())) return;
        observed.put(event, boundary);
        if (cancel) {
            event.setCancelled(true);
            getLogger().info("Acceptance helper cancelled boundary=" + boundary + "; " + session.counters());
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void result(EntityPickupItemEvent event) {
        var boundary = observed.remove(event);
        if (boundary == null) return;
        if (event.isCancelled()) finalCancelled++; else finalAllowed++;
        // Native events are intentionally cancelled by InfernoLantern even on successful pickup.
        getLogger().info("Acceptance helper event result; boundary=" + boundary + "; cancelled=" + event.isCancelled()
                + "; final-cancelled=" + finalCancelled + "; final-uncancelled=" + finalAllowed);
    }
    private void finish(String reason) {
        boolean wasArmed = armed; armed = false;
        if (session != null) session.close();
        HandlerList.unregisterAll((Listener) this);
        if (expiry != null) { expiry.cancel(); expiry = null; }
        observed.clear();
        if (wasArmed) getLogger().info("Acceptance helper unarmed; reason=" + reason + "; " + session.counters()
                + "; final-cancelled=" + finalCancelled + "; final-uncancelled=" + finalAllowed
                + "; later pickups are outside this scenario.");
    }
    @Override public void onDisable() { finish("disabled"); }
}
