package com.ninja6.infernolantern.prototype;

import org.bukkit.*;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** One disposable native-AI experiment at a time. No production combat policy. */
final class ZombiePrototype implements Listener {
    static final String TAG = "infernolantern-prototype-target";
    // All distances, HP, damage and tick counts here are temporary experiment safeguards.
    private static final double RANGE = 6;
    private final JavaPlugin plugin;
    private final BukkitTask task;
    private Session session;
    private long seen, blocked, debits, outgoing, releases, failures, retaliationTargets, deniedTargets;
    private String result = "idle";
    private double lastMeleeDamage;
    private long deathAttempts, suppressedDrops, suppressedXp;
    private final Map<EntityDamageEvent, Incoming> incoming = new IdentityHashMap<>();
    private final Set<EntityDamageEvent> outgoingEvents = Collections.newSetFromMap(new WeakHashMap<>());
    private static final class Session {
        final Location origin;
        final Villager proxy;
        final PrototypeHealth health = new PrototypeHealth();
        final Runnable exhausted, stopped;
        Zombie zombie;
        boolean attempted, hit, retaliation;
        int age, quiet;
        Session(Location origin, Villager proxy, Runnable exhausted, Runnable stopped) {
            this.origin = origin; this.proxy = proxy; this.exhausted = exhausted; this.stopped = stopped;
        }
    }
    private record Incoming(Session session, double damage) {}

    ZombiePrototype(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    }
    boolean enabled() { return plugin.getConfig().getBoolean("prototype.enabled", false)
            && plugin.getConfig().getBoolean("prototype.combat-experiment.enabled", false); }
    void start(Location base, Runnable exhausted, Runnable stopped) {
        if (!enabled()) throw new IllegalStateException("Enable prototype.combat-experiment.enabled and restart for zombie testing.");
        if (session != null) throw new IllegalStateException("Only one zombie experiment may run at a time; switch its Defence off first.");
        Location origin = base.clone().add(0, 1, 0);
        Villager proxy = origin.getWorld().spawn(origin, Villager.class, entity -> {
            entity.addScoreboardTag(TAG);
            entity.setAdult(); entity.setAgeLock(true);
            entity.setAI(false); entity.setGravity(false); entity.setCollidable(false);
            entity.setInvisible(true); entity.setSilent(true); entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false); entity.setCanPickupItems(false);
        });
        session = new Session(origin, proxy, exhausted, stopped);
        result = "waiting-for-one-zombie";
    }
    void stop(Location base) {
        if (session != null && session.origin.equals(base.clone().add(0, 1, 0))) stop("released");
    }
    private void stop(String reason) {
        Session old = session;
        if (old == null) return;
        session = null; old.health.close();
        // Never clear another plugin's newer target. No goals are installed or removed.
        for (Entity entity : old.origin.getWorld().getNearbyEntities(old.origin, RANGE + 2, RANGE + 2, RANGE + 2))
            if (entity instanceof Zombie zombie) releaseOwnedTarget(zombie, old.proxy);
        if (old.zombie != null && old.zombie.isValid()) releaseOwnedTarget(old.zombie, old.proxy);
        old.proxy.remove(); old.stopped.run(); releases++; result = reason;
    }
    static void releaseOwnedTarget(Mob mob, LivingEntity proxy) {
        if (mob.getTarget() == proxy) mob.setTarget(null);
    }
    static boolean acceptedOutgoing(EntityDamageEvent event, LivingEntity proxy, Zombie zombie) {
        return event instanceof EntityDamageByEntityEvent attack && attack.getDamager() == proxy
                && attack.getEntity() == zombie && !event.isCancelled() && event.getFinalDamage() > 0;
    }
    void close() { task.cancel(); stop("shutdown"); incoming.clear(); }
    String status() {
        return "; zombie experiment=" + result + "; proxy=" + (session == null ? 0 : 1)
                + "; experiment HP=" + (session == null ? "none" : session.health.hp())
                + "; native melee events=" + seen + "; denied=" + blocked + "; HP debits=" + debits
                + "; native retaliation targets=" + retaliationTargets + "; denied proxy targets=" + deniedTargets
                + "; last accepted melee damage=" + lastMeleeDamage
                + "; outgoing hits=" + outgoing + "; releases=" + releases + "; failures=" + failures
                + "; proxy death attempts=" + deathAttempts + "; suppressed drop stacks=" + suppressedDrops
                + "; suppressed XP=" + suppressedXp;
    }
    private void tick() {
        Session current = session;
        if (current == null) return;
        if (current.proxy.getHealth() != 20) { failures++; stop("native-health-changed-failure"); return; }
        if (!enabled() || !current.proxy.isValid()) { stop("disabled-or-proxy-missing"); return; }
        current.age += 10;
        if (current.age >= 1200) { stop("experiment-expired"); return; }
        // Any displacement invalidates this representation; never move it outside the volume.
        if (current.proxy.getLocation().distanceSquared(current.origin) > .01) { failures++; stop("proxy-displaced-failure"); return; }
        if (current.zombie == null) {
            current.zombie = current.origin.getWorld().getNearbyEntities(current.origin, RANGE, RANGE, RANGE).stream()
                    .filter(entity -> entity.getType() == EntityType.ZOMBIE && entity.isValid() && !entity.isDead())
                    .map(Zombie.class::cast)
                    .filter(zombie -> !zombie.isBaby() && zombie.getLocation().distanceSquared(current.origin) <= RANGE * RANGE)
                    .min(Comparator.comparingDouble(zombie -> zombie.getLocation().distanceSquared(current.origin))).orElse(null);
            if (current.zombie == null) return;
        }
        Zombie zombie = current.zombie;
        if (!zombie.isValid() || zombie.isDead() || zombie.getWorld() != current.origin.getWorld()
                || zombie.getLocation().distanceSquared(current.origin) > RANGE * RANGE) { stop("target-gone-or-out-of-range"); return; }
        if (!current.attempted) {
            if (!current.proxy.hasLineOfSight(zombie)) { current.quiet += 10; }
            else {
                current.attempted = true;
                // Real damage with the living proxy as source. Native zombie goals own retaliation.
                zombie.damage(1, current.proxy);
                if (!current.hit) { stop("outgoing-denied-or-no-positive-damage"); return; }
            }
        } else {
            if (zombie.getTarget() != null && zombie.getTarget() != current.proxy) { stop("external-target-preserved"); return; }
            current.quiet += 10;
        }
        if (current.quiet >= 200) { stop("unreachable-or-no-native-melee"); }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void guard(EntityDamageEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(TAG)) return;
        Session current = session;
        if (current != null && event.getEntity() == current.proxy && event instanceof EntityDamageByEntityEvent attack
                && attack.getDamager() == current.zombie && current.hit && current.retaliation
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            seen++;
            incoming.put(event, new Incoming(current, event.getFinalDamage()));
        }
        // Zero native health damage before MONITOR; protection cancellation stays authoritative.
        event.setDamage(0);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void observe(EntityDamageEvent event) {
        Incoming candidate = incoming.remove(event);
        if (candidate != null) {
            Session current = candidate.session();
            if (session == current && current.health.debit(event, candidate.damage(), event.isCancelled())) {
                debits++; lastMeleeDamage = candidate.damage(); current.quiet = 0; result = "native-melee-observed";
                if (current.health.hp() == 0) {
                    stop("experiment-HP-exhausted");
                    Bukkit.getScheduler().runTask(plugin, current.exhausted);
                }
            } else blocked++;
        }
        Session current = session;
        if (current == null || !acceptedOutgoing(event, current.proxy, current.zombie) || !outgoingEvents.add(event)) return;
        current.hit = true; current.quiet = 0; outgoing++; result = "actual-outgoing-hit-observed";
        Location from = current.origin.clone().add(0, 1.5, 0);
        var step = current.zombie.getEyeLocation().toVector().subtract(from.toVector()).multiply(1.0 / 12);
        for (int i = 0; i < 12; i++) {
            from.add(step);
            from.getWorld().spawnParticle(Particle.FLAME, from, 1, 0, 0, 0, 0);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void target(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null || !event.getTarget().getScoreboardTags().contains(TAG)) return;
        Session current = session;
        if (current == null || event.getTarget() != current.proxy || event.getEntity() != current.zombie
                || !current.hit || (!current.retaliation && event.getReason() != EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY)) {
            deniedTargets++; event.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void targetObserved(EntityTargetLivingEntityEvent event) {
        Session current = session;
        if (current != null && event.getTarget() == current.proxy && event.getEntity() == current.zombie
                && current.hit && event.getReason() == EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY) {
            current.retaliation = true; retaliationTargets++; result = "native-TARGET_ATTACKED_ENTITY-observed";
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void combust(EntityCombustEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void teleport(EntityTeleportEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void potion(EntityPotionEffectEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void knockback(EntityKnockbackEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void transform(EntityTransformEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void death(EntityDeathEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(TAG)) return;
        deathAttempts++; suppressedDrops += event.getDrops().size(); suppressedXp += event.getDroppedExp();
        event.getDrops().clear(); event.setDroppedExp(0); event.setCancelled(true); event.setReviveHealth(20);
        failures++;
        if (session != null && event.getEntity() == session.proxy) stop("independent-death-attempt-failure");
        else event.getEntity().remove();
    }
}
