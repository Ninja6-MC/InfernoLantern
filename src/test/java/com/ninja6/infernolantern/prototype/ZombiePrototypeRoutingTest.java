package com.ninja6.infernolantern.prototype;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Event/ownership contracts only, never evidence of native AI or client behavior. */
class ZombiePrototypeRoutingTest {
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            return null;
        }));
    }
    @Test void outgoingRequiresPositiveUncancelledDamageFromTheOwnedProxyToTheLease() {
        Villager proxy = stub(Villager.class);
        Zombie zombie = stub(Zombie.class);
        var event = new EntityDamageByEntityEvent(proxy, zombie, DamageCause.ENTITY_ATTACK, stub(DamageSource.class), 1);
        assertTrue(ZombiePrototype.acceptedOutgoing(event, proxy, zombie));
        event.setCancelled(true);
        assertFalse(ZombiePrototype.acceptedOutgoing(event, proxy, zombie));
        event.setCancelled(false);
        event.setDamage(0);
        assertFalse(ZombiePrototype.acceptedOutgoing(event, proxy, zombie));
        event.setDamage(1);
        assertFalse(ZombiePrototype.acceptedOutgoing(event, stub(Villager.class), zombie));
        assertFalse(ZombiePrototype.acceptedOutgoing(event, proxy, stub(Zombie.class)));
    }
    @Test void cleanupClearsOnlyTheOwnedTargetAndPreservesANewerExternalTarget() {
        LivingEntity owned = stub(Villager.class), external = stub(Player.class);
        AtomicReference<LivingEntity> target = new AtomicReference<>(owned);
        Mob zombie = (Mob) Proxy.newProxyInstance(Mob.class.getClassLoader(), new Class<?>[]{Mob.class}, (proxy, method, args) -> {
            if (method.getName().equals("getTarget")) return target.get();
            if (method.getName().equals("setTarget")) { target.set((LivingEntity) args[0]); return null; }
            return null;
        });
        ZombiePrototype.releaseOwnedTarget(zombie, owned);
        assertNull(target.get());
        target.set(external);
        ZombiePrototype.releaseOwnedTarget(zombie, owned);
        assertSame(external, target.get());
    }
}
