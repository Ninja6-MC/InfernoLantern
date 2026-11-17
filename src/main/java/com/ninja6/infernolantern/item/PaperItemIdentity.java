package com.ninja6.infernolantern.item;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Compact versioned PDC contract, independent of names/lore/material. Owner-thread API. */
public final class PaperItemIdentity {
    private final NamespacedKey schema = key("item_schema"), variant = key("item_variant"),
            tower = key("item_tower"), generation = key("item_generation");
    public static final NamespacedKey DELIVERY = key("item_delivery");
    public static final NamespacedKey OPERATION = key("item_operation");
    private static NamespacedKey key(String name) { return new NamespacedKey("infernolantern", name); }
    private static boolean air(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getType() == Material.CAVE_AIR || stack.getType() == Material.VOID_AIR;
    }
    public boolean marked(ItemStack stack) {
        if (air(stack)) return false;
        var data = stack.getPersistentDataContainer();
        return data.has(schema) || data.has(variant) || data.has(tower) || data.has(generation) || data.has(DELIVERY);
    }
    /** Malformed/unknown schemas and illegal stack amounts remain recognizable, but unusable. */
    public Optional<ItemToken> read(ItemStack stack) {
        if (!marked(stack) || stack.getAmount() != 1 || stack.getPersistentDataContainer().has(DELIVERY)) return Optional.empty();
        try {
            var data = stack.getPersistentDataContainer();
            return Optional.of(new ItemToken(Objects.requireNonNull(data.get(schema, PersistentDataType.INTEGER)),
                    data.get(variant, PersistentDataType.STRING), UUID.fromString(data.get(tower, PersistentDataType.STRING)),
                    Objects.requireNonNull(data.get(generation, PersistentDataType.LONG))));
        } catch (RuntimeException malformed) { return Optional.empty(); }
    }
    /** Appearance is supplied by the caller's validated catalog; no fixed production material. */
    @SuppressWarnings("deprecation")
    public ItemStack create(ItemStack appearance, ItemToken token, GroundPolicy policy) {
        if (air(appearance)) throw new IllegalArgumentException("Item appearance required");
        ItemStack item = appearance.clone(); item.setAmount(1);
        var meta = item.getItemMeta();
        meta.setMaxStackSize(1);
        meta.setFireResistant(policy.survivesFireAndLava());
        var data = meta.getPersistentDataContainer();
        data.set(schema, PersistentDataType.INTEGER, token.schema());
        data.set(variant, PersistentDataType.STRING, token.variant());
        data.set(tower, PersistentDataType.STRING, token.tower().toString());
        data.set(generation, PersistentDataType.LONG, token.generation());
        item.setItemMeta(meta);
        return item;
    }
}
