package com.ninja6.infernolantern.item;

import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** PDC boundary tests use strict API doubles; native physics still requires Paper. */
class PaperItemIdentityTest {
    final PaperItemIdentity identity = new PaperItemIdentity();
    final ItemToken token = new ItemToken(1, "generic.fixture", new UUID(0, 1), 7);
    @Test void createsOneNonStackableItemWithOnlyIdentityFieldsAndPreservesTemplate() {
        Stack template = new Stack(); template.amount = 64;
        Stack created = (Stack) identity.create(template, token, GroundPolicy.DEFAULT);
        assertEquals(64, template.amount); assertTrue(template.values.isEmpty());
        assertEquals(1, created.amount); assertEquals(1, created.maximumStack); assertTrue(created.fireResistant);
        assertEquals(token, identity.read(created).orElseThrow());
        assertEquals(Set.of("item_schema", "item_variant", "item_tower", "item_generation"),
                created.values.keySet().stream().map(NamespacedKey::getKey).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void malformedPartialUnknownAndStackedTokensStayMarkedButUnusable() {
        Stack stack = (Stack) identity.create(new Stack(), token, GroundPolicy.DEFAULT);
        stack.amount = 2; assertTrue(identity.marked(stack)); assertTrue(identity.read(stack).isEmpty());
        stack.amount = 1;
        stack.values.put(new NamespacedKey("infernolantern", "item_schema"), new Value(Integer.class, 99));
        assertTrue(identity.read(stack).isEmpty());
        stack.values.remove(new NamespacedKey("infernolantern", "item_schema"));
        assertTrue(identity.marked(stack)); assertTrue(identity.read(stack).isEmpty());
        stack = (Stack) identity.create(new Stack(), token, GroundPolicy.DEFAULT);
        stack.values.put(new NamespacedKey("infernolantern", "item_generation"), new Value(String.class, "7"));
        assertTrue(identity.marked(stack)); assertTrue(identity.read(stack).isEmpty());
    }
    @Test void malformedUuidAndBurnPolicyDoNotBecomeValidTokens() {
        Stack stack = (Stack) identity.create(new Stack(), token, new GroundPolicy(false, false));
        assertFalse(stack.fireResistant);
        stack.values.put(new NamespacedKey("infernolantern", "item_tower"), new Value(String.class, "invalid"));
        assertTrue(identity.marked(stack)); assertTrue(identity.read(stack).isEmpty());
        assertFalse(identity.marked(new Stack())); assertFalse(identity.marked(null));
    }
    @Test void temporaryDeliveryMarkerMakesOtherwiseValidItemUnavailableUntilActivation() {
        Stack stack=(Stack)identity.create(new Stack(),token,GroundPolicy.DEFAULT);
        stack.values.put(PaperItemIdentity.DELIVERY,new Value(String.class,new UUID(0,2).toString()));
        assertTrue(identity.marked(stack));assertTrue(identity.read(stack).isEmpty());
        stack.values.remove(PaperItemIdentity.DELIVERY);assertEquals(token,identity.read(stack).orElseThrow());
    }
    record Value(Class<?> primitive, Object value) {}
    static final class Stack extends ItemStack {
        int amount = 1, maximumStack;
        boolean fireResistant;
        final Map<NamespacedKey, Value> values = new HashMap<>();
        final PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "has": { Value value = values.get(args[0]); return value != null && (args.length == 1 || value.primitive() == ((PersistentDataType<?, ?>) args[1]).getPrimitiveType()); }
                        case "get": { Value value = values.get(args[0]); return value != null && value.primitive() == ((PersistentDataType<?, ?>) args[1]).getPrimitiveType() ? value.value() : null; }
                        case "set": { values.put((NamespacedKey) args[0], new Value(((PersistentDataType<?, ?>) args[1]).getPrimitiveType(), args[2])); return null; }
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        final ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getPersistentDataContainer" -> data;
                case "setMaxStackSize" -> { maximumStack = (Integer) args[0]; yield null; }
                case "setFireResistant" -> { fireResistant = (Boolean) args[0]; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        Stack() { super(); }
        @Override public Material getType() { return Material.PAPER; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public PersistentDataContainerView getPersistentDataContainer() { return data; }
        @Override public ItemMeta getItemMeta() { return meta; }
        @Override public boolean setItemMeta(ItemMeta meta) { assertSame(this.meta, meta); return true; }
        @Override public Stack clone() { Stack copy = new Stack(); copy.values.putAll(values); copy.amount = amount; copy.maximumStack = maximumStack; copy.fireResistant = fireResistant; return copy; }
    }
}
