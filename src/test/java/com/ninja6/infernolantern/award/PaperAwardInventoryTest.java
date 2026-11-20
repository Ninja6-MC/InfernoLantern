package com.ninja6.infernolantern.award;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.item.GroundPolicy;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperAwardInventoryTest {
    private final UUID recipient=UUID.randomUUID();
    private final PaperItemIdentity identity=new PaperItemIdentity();
    private final OverrideSessions sessions=new OverrideSessions();
    private final CampaignProgress progress=new CampaignProgress(new Award(UUID.randomUUID(),"admin-campaign:welcome",
            recipient,"test",1,AwardStatus.PENDING,0),0,null,null);
    private Server server;
    private Field bukkitServer;
    private Object prior;
    private Player player;
    private Stack slot;
    private boolean ready=true,online=true,fenced=true;
    private int writes;

    @BeforeEach void setup() throws Exception {
        bukkitServer=Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        prior=bukkitServer.get(null);
        Thread owner=Thread.currentThread();
        server=proxy(Server.class,(name,args)->switch(name){
            case "isPrimaryThread"->Thread.currentThread()==owner;
            case "getPlayer"->{assertEquals(recipient,args[0]);yield online?player:null;}
            default->throw new AssertionError(name);
        });
        bukkitServer.set(null,server);
        var inventory=proxy(PlayerInventory.class,(name,args)->switch(name){
            case "firstEmpty"->slot==null?1:-1;
            case "getItem"->{assertEquals(1,args[0]);yield slot;}
            case "setItem"->{assertEquals(1,args[0]);slot=(Stack)args[1];writes++;yield null;}
            default->throw new AssertionError(name);
        });
        player=proxy(Player.class,(name,args)->switch(name){
            case "getUniqueId"->recipient;
            case "isOnline"->online;
            case "getInventory"->inventory;
            case "hasPermission"->false;
            default->throw new AssertionError(name);
        });
        sessions.start(List.of(player));
    }
    @AfterEach void restore() throws Exception {
        sessions.close();
        bukkitServer.set(null,prior);
    }

    @Test void exactUnavailableStageBecomesOneValidItem() {
        var prepared=prepare();
        assertEquals(0,writes);
        assertEquals(CampaignAwards.Stage.VERIFIED,prepared.stage(()->true));
        assertTrue(identity.marked(slot));
        assertTrue(identity.read(slot).isEmpty());
        assertArrayEquals(prepared.proof().staged().copy(),slot.serializeAsBytes());
        assertTrue(prepared.verify());
        assertFalse(prepared.activate(()->false));
        assertEquals(1,writes);
        assertTrue(prepared.activate(()->true));
        assertArrayEquals(prepared.proof().active().copy(),slot.serializeAsBytes());
        var token=identity.read(slot).orElseThrow();
        assertEquals(progress.plannedTower(),token.tower());
        assertEquals(1,token.generation());
        assertEquals(1,slot.getAmount());
        assertFalse(prepared.activate(()->true));
        assertEquals(2,writes);
    }

    @Test void slotReplacementAndConnectionLossNeverWriteOrActivate() {
        var prepared=prepare();
        slot=new Stack();
        assertEquals(CampaignAwards.Stage.NO_WRITE,prepared.stage(()->true));
        assertEquals(0,writes);
        slot=null;
        assertEquals(CampaignAwards.Stage.VERIFIED,prepared.stage(()->true));
        Stack staged=slot;
        online=false;
        assertFalse(prepared.activate(()->true));
        assertSame(staged,slot);
        assertEquals(1,writes);
    }

    @Test void unavailableReadinessLeavesNoStage() {
        ready=false;
        assertNull(prepare());
        online=false;
        ready=true;
        assertNull(prepare());
        assertEquals(0,writes);
    }

    @Test void saveFenceIsRequiredAtBothPhysicalWrites() {
        var prepared=prepare();
        fenced=false;
        assertEquals(CampaignAwards.Stage.NO_WRITE,prepared.stage(()->true));
        assertEquals(0,writes);
        fenced=true;
        assertEquals(CampaignAwards.Stage.VERIFIED,prepared.stage(()->true));
        fenced=false;
        assertFalse(prepared.activate(()->true));
        assertTrue(identity.read(slot).isEmpty());
        assertEquals(1,writes);
    }

    private CampaignAwards.Prepared prepare(){
        return new PaperAwardInventory(server,sessions,identity,id->new PaperAwardInventory.Appearance(new Stack(),
                new GroundPolicy(true,true)),(p,id)->ready,(p,proof)->fenced).prepare(progress,UUID.randomUUID());
    }

    private static final class Stack extends ItemStack {
        private int amount=8;
        private final Map<String,Object> values=new TreeMap<>();

        @Override public Material getType(){return Material.PAPER;}
        @Override public int getAmount(){return amount;}
        @Override public void setAmount(int value){amount=value;}
        @Override public ItemMeta getItemMeta(){
            var container=proxy(PersistentDataContainer.class,this::data);
            return proxy(ItemMeta.class,(name,args)->switch(name){
                case "setMaxStackSize","setFireResistant"->null;
                case "getPersistentDataContainer"->container;
                default->throw new AssertionError(name);
            });
        }
        @Override public boolean setItemMeta(ItemMeta meta){return true;}
        @Override public PersistentDataContainerView getPersistentDataContainer(){
            return proxy(PersistentDataContainerView.class,this::data);
        }
        private Object data(String name,Object[] args){
            return switch(name){
                case "has"->values.containsKey(((NamespacedKey)args[0]).toString());
                case "get"->values.get(((NamespacedKey)args[0]).toString());
                case "set"->{values.put(((NamespacedKey)args[0]).toString(),args[2]);yield null;}
                default->throw new AssertionError(name);
            };
        }
        @Override public byte[] serializeAsBytes(){return (amount+":"+values).getBytes(StandardCharsets.UTF_8);}
        @Override public Stack clone(){var copy=new Stack();copy.amount=amount;copy.values.putAll(values);return copy;}
    }

    private static <T>T proxy(Class<T> type,BiFunction<String,Object[],Object> callback){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
                (ignored,method,args)->callback.apply(method.getName(),args)));
    }
}
