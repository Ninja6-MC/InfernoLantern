package com.ninja6.infernolantern.repair;

import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepairPayment;
import com.ninja6.infernolantern.repository.RepositoryRecords.ResourceBytes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Exact player-storage-slot adapter for a caller that owns and protects the slot throughout payment. */
public final class PaperRepairInventory implements RepairService.Inventory {
    /**
     * The router proves uninterrupted exclusive ownership of this slot and revokes the permit on
     * external mutation, disconnect, or invalidation. Slot equality alone does not prove continuity.
     * Keep the lease until the service releases it, including during proved rollback.
     */
    public interface SlotLease {
        CommitPermit current(Player player, int slot);
        /** Release exact lease metadata from any thread without touching Paper. */
        void release();
    }

    private final Player player;
    private final UUID session;
    private final int slot;
    private final ItemStack source;
    private final int offered;
    private final CommitPermit permit;
    private final SlotLease lease;
    private final Supplier<UUID> currentSession;
    private RepairPayment selected;
    private ItemStack remainder;
    private boolean attempted;
    private final AtomicBoolean released = new AtomicBoolean();

    public PaperRepairInventory(Player player, UUID session, int slot, int offered, SlotLease lease,
                                Supplier<UUID> currentSession, PaperItemIdentity identity) {
        requireOwner();
        if (slot < 0 || slot >= 36) throw new IllegalArgumentException("Player storage slot required");
        this.player = Objects.requireNonNull(player);
        this.session = Objects.requireNonNull(session);
        this.slot = slot;
        this.lease = Objects.requireNonNull(lease);
        this.currentSession = Objects.requireNonNull(currentSession);
        Objects.requireNonNull(identity);
        if (!player.isOnline() || !session.equals(currentSession.get())) throw new IllegalArgumentException("Repair viewer changed");
        this.permit = Objects.requireNonNull(lease.current(player, slot), "Repair slot has no current lease");
        permit.requireValid();
        ItemStack sourceSlot = player.getInventory().getItem(slot);
        if (empty(sourceSlot) || identity.marked(sourceSlot)
                || sourceSlot.getPersistentDataContainer().has(PaperItemIdentity.OPERATION)
                || offered < 1 || offered > sourceSlot.getAmount()) {
            throw new IllegalArgumentException("Authorized resource slot required");
        }
        this.source = sourceSlot.clone();
        this.offered = offered;
    }

    @Override public String resource() { return source.getType().getKey().toString(); }

    @Override public int offered() { return offered; }

    @Override public RepairPayment quote(int items) {
        requireOwner();
        if (selected != null || items < 1 || items > offered || slotPermit() == null) return null;
        ItemStack debit = source.clone();
        debit.setAmount(items);
        remainder = source.getAmount() == items ? null : source.clone();
        if (remainder != null) remainder.setAmount(source.getAmount() - items);
        try {
            selected = new RepairPayment(player.getUniqueId(), session, slot, resource(), items,
                    bytes(source), bytes(remainder), bytes(debit));
            return selected;
        } catch (IllegalArgumentException oversized) {
            return null;
        }
    }

    @Override public boolean sourceMatches(RepairPayment payment) {
        requireOwner();
        return selected != null && selected.equals(payment) && slotPermit() != null
                && payment.before().equals(bytes(player.getInventory().getItem(slot)));
    }

    @Override public CommitPermit slotPermit() {
        requireOwner();
        return !released.get() && player.isOnline() && session.equals(currentSession.get())
                && lease.current(player, slot) == permit ? permit : null;
    }

    @Override public boolean debit(RepairPayment payment) {
        requireOwner();
        if (attempted || !sourceMatches(payment)) return false;
        attempted = true;
        player.getInventory().setItem(slot, remainder == null ? null : remainder.clone());
        return true;
    }

    @Override public boolean debitMatches(RepairPayment payment) {
        requireOwner();
        return attempted && selected != null && selected.equals(payment) && slotPermit() != null
                && payment.remainder().equals(bytes(player.getInventory().getItem(slot)));
    }

    @Override public boolean restore(RepairPayment payment) {
        requireOwner();
        if (!attempted) return true;
        if (selected == null || !selected.equals(payment)) return false;
        CommitPermit permit = slotPermit();
        if (permit == null) return false;
        permit.requireValid();
        if (sourceMatches(payment)) return true; // A failed setter left the original exact source.
        if (!debitMatches(payment)) return false;
        player.getInventory().setItem(slot, source.clone());
        return sourceMatches(payment);
    }

    @Override public void release() {
        if (released.compareAndSet(false, true)) {
            permit.revoke();
            lease.release();
        }
    }

    private static ResourceBytes bytes(ItemStack stack) {
        return new ResourceBytes(empty(stack) ? new byte[0] : stack.serializeAsBytes());
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR
                || stack.getType() == Material.CAVE_AIR || stack.getType() == Material.VOID_AIR;
    }

    private static void requireOwner() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Repair inventory requires the Paper owner");
    }
}
