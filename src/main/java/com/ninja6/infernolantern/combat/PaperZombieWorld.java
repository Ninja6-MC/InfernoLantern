package com.ninja6.infernolantern.combat;

import com.ninja6.infernolantern.health.TowerHealth;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

import static com.ninja6.infernolantern.combat.ZombieCombat.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.TowerSnapshot;

/** Paper owner-thread effects and native event observation for ordinary zombie combat. */
public final class PaperZombieWorld implements ZombieCombat.WorldPort, Listener, AutoCloseable {
    static final String TAG = "infernolantern-combat-proxy";
    private static final int PARTICLES = 12;
    private static final long MAX_LOS_CHUNKS = 256;

    /** Runtime bindings establish current protection, loaded candidates and the admitted physical layout. */
    public interface Access {
        Conditions inspect(TowerSnapshot tower);
        boolean allows(Action action, TowerSnapshot tower, Villager proxy, Zombie target);
        boolean proxyFits(TowerSnapshot tower, Location origin);
        List<UUID> candidates(TowerSnapshot tower, Location origin, double range, int limit);
        TowerHealth.DefeatEffects defeatEffects();
    }

    private record Spawned(Villager entity, UUID token, Location origin, double health) {}
    private record Incoming(MeleeProof proof, double damage) {}
    private static final class Attack {
        final Spawned proxy;
        final Zombie zombie;
        EntityDamageByEntityEvent observed;

        Attack(Spawned proxy, Zombie zombie) {
            this.proxy = proxy;
            this.zombie = zombie;
        }

        boolean accepted() {
            return observed != null && positiveUncancelled(observed);
        }
    }

    private final Access access;
    private final Map<UUID, Spawned> proxies = new HashMap<>();
    private final Map<EntityDamageEvent, Incoming> incoming = new WeakHashMap<>();
    private ZombieCombat combat;
    private BukkitTask ticks;
    private long tick;
    private Attack attacking;
    private Zombie prioritizing;
    private UUID priorityProxy;

    public PaperZombieWorld(Access access) {
        this.access = Objects.requireNonNull(access);
    }

    public static void requireOwner() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Combat requires the Paper owner");
    }

    /** Install only once the runtime can provide loaded deployments and current access. */
    public void install(JavaPlugin plugin, ZombieCombat combat) {
        requireOwner();
        bind(combat);
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            ticks = Bukkit.getScheduler().runTaskTimer(plugin, () -> combat.tick(++tick), 1, 1);
        } catch (RuntimeException failed) {
            close();
            throw failed;
        }
    }

    void bind(ZombieCombat combat) {
        if (this.combat != null) throw new IllegalStateException("Combat already bound");
        this.combat = Objects.requireNonNull(combat);
    }

    @Override public void close() {
        requireOwner();
        if (ticks != null) ticks.cancel();
        try {
            if (combat != null) combat.close();
        } finally {
            HandlerList.unregisterAll(this);
            incoming.clear();
        }
    }

    @Override public Conditions inspect(TowerSnapshot tower) {
        requireOwner();
        var position = tower.state().tower().position();
        World world = Bukkit.getWorld(position.world());
        return world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4) ? null : access.inspect(tower);
    }

    @Override public UUID spawnProxy(TowerSnapshot tower, UUID token) {
        requireOwner();
        var position = tower.state().tower().position();
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return null;
        Location origin = new Location(world, position.x() + .5, position.y() + 1.0, position.z() + .5);
        if (!access.proxyFits(tower, origin.clone())) return null;
        Villager proxy = world.spawn(origin, Villager.class, entity -> {
            entity.addScoreboardTag(TAG);
            entity.setAdult();
            entity.setAgeLock(true);
            entity.setAI(false);
            entity.setGravity(false);
            entity.setCollidable(false);
            entity.setInvisible(true);
            entity.setSilent(true);
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
            entity.setCanPickupItems(false);
        });
        UUID id = proxy.getUniqueId();
        proxies.put(id, new Spawned(proxy, token, origin, proxy.getHealth()));
        return id;
    }

    @Override public void removeProxy(UUID proxy, UUID token) {
        requireOwner();
        Spawned found = proxies.get(proxy);
        if (found != null && found.token().equals(token)) {
            proxies.remove(proxy);
            found.entity().remove();
        }
    }

    @Override public List<Candidate> candidates(TowerSnapshot tower, double range, int limit) {
        requireOwner();
        var position = tower.state().tower().position();
        World world = Bukkit.getWorld(position.world());
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return List.of();
        Location origin = new Location(world, position.x() + .5, position.y() + 1.0, position.z() + .5);
        List<UUID> ids = access.candidates(tower, origin.clone(), range, limit);
        if (ids.size() > limit) return List.of();
        List<Candidate> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Zombie zombie = zombie(id);
            if (!isLoaded(zombie) || zombie.getWorld() != world) continue;
            result.add(new Candidate(id, zombie.getLocation().distanceSquared(origin)));
        }
        return List.copyOf(result);
    }

    @Override public boolean reachable(TowerSnapshot tower, UUID proxy, UUID target, double range) {
        Spawned found = healthy(proxy);
        Zombie zombie = zombie(target);
        if (found == null || !isLoaded(zombie) || zombie.getWorld() != found.origin().getWorld()) return false;
        return zombie.getLocation().distanceSquared(found.origin()) <= range * range
                && loadedCorridor(found.origin(), zombie.getLocation()) && found.entity().hasLineOfSight(zombie);
    }

    @Override public boolean allows(Action action, TowerSnapshot tower, UUID proxy, UUID target) {
        Spawned pair = healthy(proxy);
        Zombie zombie = zombie(target);
        return pair != null && isLoaded(zombie) && zombie.getWorld() == pair.origin().getWorld()
                && access.allows(action, tower, pair.entity(), zombie);
    }

    @Override public boolean hasOwnedTarget(UUID zombie, UUID proxy) {
        Zombie found = zombie(zombie);
        Spawned owned = proxies.get(proxy);
        return found != null && owned != null && found.getTarget() == owned.entity();
    }

    @Override public boolean hasOtherTarget(UUID zombie, UUID proxy) {
        Zombie found = zombie(zombie);
        if (found == null) return false;
        LivingEntity target = found.getTarget();
        return target != null && !hasOwnedTarget(zombie, proxy);
    }

    @Override public void prioritize(UUID zombie, UUID proxy) {
        Zombie found = zombie(zombie);
        Spawned pair = healthy(proxy);
        if (pair == null || !isLoaded(found) || found.getTarget() != null || prioritizing != null) return;
        prioritizing = found;
        priorityProxy = proxy;
        try {
            found.setTarget(pair.entity());
        } finally {
            prioritizing = null;
            priorityProxy = null;
        }
    }

    @Override public void releaseTarget(UUID zombie, UUID proxy) {
        Zombie found = zombie(zombie);
        if (found != null && hasOwnedTarget(zombie, proxy)) found.setTarget(null);
    }

    @Override public boolean attack(UUID proxy, UUID target, double damage) {
        Spawned pair = healthy(proxy);
        Zombie zombie = zombie(target);
        if (attacking != null || pair == null || !isLoaded(zombie)) return false;
        Attack attempt = new Attack(pair, zombie);
        Location from = pair.entity().getEyeLocation();
        Location to = zombie.getEyeLocation();
        attacking = attempt;
        try {
            var source = DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(pair.entity())
                    .withCausingEntity(pair.entity()).build();
            zombie.damage(damage, source);
        } finally {
            attacking = null;
        }
        if (!attempt.accepted()) return false;
        try {
            if (loadedCorridor(from, to)) beam(from, to);
        } catch (RuntimeException ignored) {
            // Particle delivery must not discard an already accepted attack receipt.
        }
        return true;
    }

    private static void beam(Location point, Location to) {
        var step = to.toVector().subtract(point.toVector()).multiply(1.0 / PARTICLES);
        for (int index = 0; index < PARTICLES; index++) {
            point.add(step);
            point.getWorld().spawnParticle(Particle.FLAME, point, 1, 0, 0, 0, 0);
        }
    }

    @Override public TowerHealth.DefeatEffects defeatEffects() {
        return access.defeatEffects();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void shield(EntityDamageEvent event) {
        Attack active = attacking;
        if (active != null && event instanceof EntityDamageByEntityEvent attack
                && matchesBeam(attack, active.proxy.entity(), active.zombie)
                && (combat == null || !combat.permitsBeam(active.proxy.entity().getUniqueId(), active.zombie.getUniqueId()))) {
            event.setCancelled(true);
        }
        if (!managed(event.getEntity())) return;
        if (event instanceof EntityDamageByEntityEvent melee && ordinary(melee.getDamager())
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamageSource().getCausingEntity() == melee.getDamager()
                && event.getDamageSource().getDirectEntity() == melee.getDamager()) {
            Spawned pair = proxies.get(event.getEntity().getUniqueId());
            if (pair != null && event.getEntity() == pair.entity() && combat != null && !incoming.containsKey(event)) {
                var proof = combat.captureMelee(pair.entity().getUniqueId(), melee.getDamager().getUniqueId());
                incoming.put(event, proof == null ? null : new Incoming(proof, event.getFinalDamage()));
            }
        }
        event.setDamage(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void damageObserved(EntityDamageEvent event) {
        if (incoming.containsKey(event)) {
            Incoming melee = incoming.put(event, null);
            if (melee != null && !event.isCancelled() && event.getFinalDamage() == 0 && combat != null) {
                combat.melee(melee.proof(), melee.damage());
            }
        }
        Attack attempt = attacking;
        if (attempt != null && attempt.observed == null && event instanceof EntityDamageByEntityEvent attack
                && matchesBeam(attack, attempt.proxy.entity(), attempt.zombie)) {
            attempt.observed = attack;
        }
    }

    static boolean matchesBeam(EntityDamageByEntityEvent event, Villager proxy, Zombie zombie) {
        return event.getDamager() == proxy && event.getEntity() == zombie
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamageSource().getCausingEntity() == proxy
                && event.getDamageSource().getDirectEntity() == proxy;
    }

    static boolean positiveUncancelled(EntityDamageEvent event) {
        double amount = event.getFinalDamage();
        return !event.isCancelled() && Double.isFinite(amount) && amount > 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void target(EntityTargetLivingEntityEvent event) {
        if (!managed(event.getTarget())) return;
        UUID target = event.getTarget().getUniqueId();
        UUID zombie = event.getEntity().getUniqueId();
        boolean ownedPriority = prioritizing == event.getEntity() && target.equals(priorityProxy);
        TargetReason reason = ownedPriority ? TargetReason.PRIORITY
                : event.getReason() == EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY ? TargetReason.RETALIATION : TargetReason.OTHER;
        Attack active = attacking;
        boolean acceptedBeam = active != null && active.zombie == event.getEntity() && active.proxy.entity() == event.getTarget()
                && active.accepted();
        if (combat == null || !ordinary(event.getEntity()) || !combat.permitsTarget(target, zombie, reason, acceptedBeam)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void targetObserved(EntityTargetLivingEntityEvent event) {
        if (combat != null && ordinary(event.getEntity())) {
            UUID target = managed(event.getTarget()) ? event.getTarget().getUniqueId() : null;
            combat.targetChanged(event.getEntity().getUniqueId(), target);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void combust(EntityCombustEvent event) {
        if (managed(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(EntityTeleportEvent event) {
        if (managed(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void potion(EntityPotionEffectEvent event) {
        if (managed(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void knockback(EntityKnockbackEvent event) {
        if (managed(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void transform(EntityTransformEvent event) {
        if (managed(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEntityEvent event) {
        if (managed(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(EntityDeathEvent event) {
        if (!managed(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setCancelled(true);
        event.setReviveHealth(event.getEntity().getMaxHealth());
        if (combat != null) combat.proxyGone(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        if (combat == null) return;
        for (Entity entity : event.getEntities()) {
            if (managed(entity)) combat.proxyGone(entity.getUniqueId());
            else if (entity instanceof Zombie) combat.zombieGone(entity.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveEvent event) {
        if (combat != null && event.getEntity() instanceof Zombie) combat.zombieGone(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void load(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (managed(entity) && !proxies.containsKey(entity.getUniqueId())) entity.remove();
        }
    }

    private Spawned healthy(UUID id) {
        Spawned pair = proxies.get(id);
        if (pair == null || !isLoaded(pair.entity()) || !managed(pair.entity())
                || pair.entity().getHealth() != pair.health()
                || pair.entity().getWorld() != pair.origin().getWorld()
                || pair.entity().getLocation().distanceSquared(pair.origin()) > .01) return null;
        return pair;
    }

    static boolean loadedCorridor(Location from, Location to) {
        int fromX = from.getBlockX() >> 4;
        int toX = to.getBlockX() >> 4;
        int fromZ = from.getBlockZ() >> 4;
        int toZ = to.getBlockZ() >> 4;
        long width = Math.abs((long) fromX - toX) + 1;
        long depth = Math.abs((long) fromZ - toZ) + 1;
        if (from.getWorld() != to.getWorld() || width * depth > MAX_LOS_CHUNKS) return false;
        for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) {
            for (int z = Math.min(fromZ, toZ); z <= Math.max(fromZ, toZ); z++) {
                if (!from.getWorld().isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    private static boolean isLoaded(Entity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) return false;
        Location location = entity.getLocation();
        return entity.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static Zombie zombie(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        return ordinary(entity) ? (Zombie) entity : null;
    }

    static boolean ordinary(Entity entity) {
        return entity instanceof Zombie zombie && entity.getType() == EntityType.ZOMBIE && !zombie.isBaby();
    }

    private static boolean managed(Entity entity) {
        return entity instanceof Villager && entity.getScoreboardTags().contains(TAG);
    }
}
