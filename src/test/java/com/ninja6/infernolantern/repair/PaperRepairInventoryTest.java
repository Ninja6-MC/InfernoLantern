package com.ninja6.infernolantern.repair;

import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.repository.CommitPermit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class PaperRepairInventoryTest {
    private final UUID actor = UUID.randomUUID();
    private UUID session = UUID.randomUUID();
    private final Lease lease = new Lease();
    private Stack current = new Stack(8, 10);
    private Field bukkitServer;
    private Object previousServer;
    private Player player;
    private int writes;

    @BeforeEach void setup() throws Exception {
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        previousServer = bukkitServer.get(null);
        Thread owner = Thread.currentThread();
        bukkitServer.set(null, proxy(Server.class, (name,args) -> {
            assertEquals("isPrimaryThread", name);
            return Thread.currentThread() == owner;
        }));
        var inventory = proxy(PlayerInventory.class, (name,args) -> switch (name) {
            case "getItem" -> { assertEquals(1, args[0]); yield current; }
            case "setItem" -> { assertEquals(1, args[0]); current = (Stack)args[1]; writes++; yield null; }
            default -> throw new AssertionError(name);
        });
        player = proxy(Player.class, (name,args) -> switch (name) {
            case "getUniqueId" -> actor;
            case "isOnline" -> true;
            case "getInventory" -> inventory;
            default -> throw new AssertionError(name);
        });
    }

    @AfterEach void restoreServer() throws Exception { bukkitServer.set(null, previousServer); }

    @Test void restoresOnlyTheOriginalExactDebitUnderUninterruptedLease() {
        var adapter = capture();
        var quote = adapter.quote(3);
        assertEquals(actor, quote.contributor());
        assertEquals(session, quote.session());
        assertEquals("minecraft:iron_ingot", quote.resource());
        assertEquals(8, current.getAmount());
        assertTrue(adapter.debit(quote));
        assertTrue(adapter.debitMatches(quote));
        assertEquals(5, current.getAmount());
        assertEquals(10, current.marker);
        assertTrue(adapter.restore(quote));
        assertEquals(8, current.getAmount());
        assertEquals(10, current.marker);
        assertEquals(2, writes);
        adapter.release();
        adapter.release();
        assertEquals(1, lease.releases);
    }

    @Test void changedStackLeaseOrConnectionNeverReceivesRefund() {
        var adapter = capture();
        var quote = adapter.quote(3);
        assertTrue(adapter.debit(quote));
        current = new Stack(5, 11);
        assertFalse(adapter.restore(quote));
        assertEquals(11, current.marker);
        current = new Stack(5, 10); // Bytes match, but there is a new lease.
        Lease prior = lease;
        prior.permit = new CommitPermit();
        assertFalse(adapter.restore(quote));
        session = UUID.randomUUID();
        assertFalse(adapter.debitMatches(quote));
        assertEquals(1, writes);
    }

    @Test void unavailableTowerIdentityCannotBeUsedAsPayment() {
        current.markers.add(PaperItemIdentity.DELIVERY);
        assertThrows(IllegalArgumentException.class, this::capture);
        assertEquals(8, current.getAmount());
        assertEquals(0, writes);
    }

    private PaperRepairInventory capture() {
        return new PaperRepairInventory(player, session, 1, 8, lease, () -> session, new PaperItemIdentity());
    }

    private final class Lease implements PaperRepairInventory.SlotLease {
        CommitPermit permit = new CommitPermit();
        int releases;
        @Override public CommitPermit current(Player player, int slot) { return permit; }
        @Override public void release() { releases++; }
    }

    private static final class Stack extends ItemStack {
        private int amount;
        private final int marker;
        private final Set<NamespacedKey> markers = new HashSet<>();

        Stack(int amount, int marker) { this.amount = amount; this.marker = marker; }

        @Override public Material getType() { return Material.IRON_INGOT; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public byte[] serializeAsBytes() { return new byte[]{(byte)amount, (byte)marker}; }
        @Override public PersistentDataContainerView getPersistentDataContainer() {
            return proxy(PersistentDataContainerView.class, (name,args) -> {
                assertEquals("has", name);
                return markers.contains(args[0]);
            });
        }
        @Override public Stack clone() {
            var copy = new Stack(amount, marker);
            copy.markers.addAll(markers);
            return copy;
        }
    }

    private static <T> T proxy(Class<T> type, BiFunction<String,Object[],Object> callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> callback.apply(method.getName(), args)));
    }
}
