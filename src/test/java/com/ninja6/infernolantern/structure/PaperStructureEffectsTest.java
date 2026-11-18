package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.item.GroundPolicy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.structure.StructureLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

/** Strict API boundary doubles execute the actual adapter; not native collision/physics evidence. */
class PaperStructureEffectsTest {
    final UUID worldId=UUID.randomUUID(),towerId=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID();
    final Position base=new Position(worldId,4,70,4,"north");
    final Map<Cell,String> blocks=new HashMap<>();
    final AtomicReference<UUID> liveSession=new AtomicReference<>(session);
    final Map<NamespacedKey,Object> tags=new HashMap<>();
    boolean permission=true;Object previousServer;
    World world;Player player;
    interface Answer {Object get(Method method,Object[] args);}
    static <T>T proxy(Class<T> type,Answer answer){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
        if(m.getName().equals("equals"))return p==args[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("toString"))return type.getSimpleName()+"Stub";
        return answer.get(m,args);
    }));}
    static RuntimeException unsupported(Method m){return new UnsupportedOperationException(m.toString());}
    BlockData data(String value){return proxy(BlockData.class,(m,a)->switch(m.getName()){
        case "getAsString"->value;
        case "getMaterial"->value.startsWith("minecraft:air")?Material.AIR:value.startsWith("minecraft:stone")?Material.STONE:Material.BARRIER;
        default->throw unsupported(m);
    });}
    @BeforeEach void setup()throws Exception{
        var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);previousServer=field.get(null);
        world=proxy(World.class,(m,a)->switch(m.getName()){
            case "getUID"->worldId;case "getMinHeight"->-64;case "getMaxHeight"->320;case "isChunkLoaded"->true;
            case "getWorldBorder"->proxy(WorldBorder.class,(method,args)->{if(method.getName().equals("isInside"))return true;throw unsupported(method);});
            case "getNearbyEntities"->List.of();
            case "getBlockAt"->{Cell c=new Cell(worldId,(int)a[0],(int)a[1],(int)a[2]);yield proxy(Block.class,(method,args)->switch(method.getName()){
                case "getBlockData"->data(blocks.getOrDefault(c,"minecraft:air"));
                case "setBlockData"->{blocks.put(c,((BlockData)args[0]).getAsString());yield null;}
                default->throw unsupported(method);
            });}
            default->throw unsupported(m);
        });
        PlayerInventory inventory=proxy(PlayerInventory.class,(m,a)->switch(m.getName()){case "getSize"->41;default->throw unsupported(m);});
        player=proxy(Player.class,(m,a)->switch(m.getName()){case "isOnline"->true;case "getUniqueId"->actor;case "getWorld"->world;case "getInventory"->inventory;default->throw unsupported(m);});
        Server server=proxy(Server.class,(m,a)->switch(m.getName()){
            case "isPrimaryThread"->true;case "getWorld"->world;case "createBlockData"->data((String)a[0]);case "getLogger"->Logger.getLogger("fixture");default->throw unsupported(m);
        });field.set(null,server);blocks.put(new Cell(worldId,4,70,4),"minecraft:barrier");
    }
    @AfterEach void reset()throws Exception{var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);field.set(null,previousServer);}
    TowerSnapshot before(){return new TowerSnapshot(new State(new Tower(towerId,"fixture",75,100,Form.COMPACT,2,1,null,base,true,false,0,44),null,List.of(new Component("base",ComponentKind.BLOCK,worldId,4,70,4,"minecraft:air","minecraft:barrier",null,null))),2,null);}
    Request request(){return new Request(UUID.randomUUID(),towerId,2,2,1,actor,session,Action.EXPAND,base,new Policy(1,50,200,1000,false));}
    PaperStructureEffects effects(PaperStructureEffects.Layout layout){return new PaperStructureEffects(player,liveSession::get,(r,s)->permission,layout,0,new Template(),GroundPolicy.DEFAULT,item->{fail("No ground drop for expansion");},false);}
    PaperStructureEffects.Layout layout(){return new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,"minecraft:barrier"),new PaperStructureEffects.Voxel(0,1,0,"minecraft:barrier"),new PaperStructureEffects.Voxel(0,2,0,null)),List.of());}
    Claim claim(Request request,Preparation prepared){
        State after=new State(new Tower(towerId,"fixture",75,100,Form.EXPANDED,2,2,actor,base,true,false,0,44),null,prepared.manifest());
        Intent intent=new Intent(request.operation(),towerId,2,2,1,"structure-expand",after,prepared.evidence());return new Claim(request,new Operation(intent,before().state(),Phase.INTENT,0,"intent"),prepared);
    }
    @Test void actualAdapterReservesUnchangedVoxelAndRestoresOwnedEffects(){
        Request request=request();var effects=effects(layout());var prepared=effects.prepare(request,before());assertNotNull(prepared);assertEquals(3,prepared.manifest().size());
        Claim claim=claim(request,prepared);assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,71,4)));
        assertTrue(effects.rollback(claim));assertEquals("minecraft:air",blocks.get(new Cell(worldId,4,71,4)));assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,70,4)));
    }
    @Test void actualAdapterLeavesExternalEditUntouchedDuringRollback(){
        Request r=request();var effects=effects(layout());var prepared=effects.prepare(r,before());Claim claim=claim(r,prepared);assertTrue(effects.apply(claim));
        Cell changed=new Cell(worldId,4,71,4);blocks.put(changed,"minecraft:stone");assertFalse(effects.verify(claim));assertFalse(effects.rollback(claim));assertEquals("minecraft:stone",blocks.get(changed));
    }
    @Test void actualAdapterRejectsObstructionAndRechecksSessionAndPolicy(){
        blocks.put(new Cell(worldId,4,71,4),"minecraft:stone");assertNull(effects(layout()).prepare(request(),before()));
        blocks.remove(new Cell(worldId,4,71,4));Request r=request();var effects=effects(layout());var prepared=effects.prepare(r,before());Claim claim=claim(r,prepared);
        permission=false;assertFalse(effects.apply(claim));permission=true;liveSession.set(UUID.randomUUID());assertFalse(effects.apply(claim));assertEquals("minecraft:air",blocks.getOrDefault(new Cell(worldId,4,71,4),"minecraft:air"));
    }
    @Test void actualAdapterRejectsCrossChunkBeforeBlockAccess(){
        var outside=new PaperStructureEffects.Layout(java.util.stream.IntStream.rangeClosed(0,12).mapToObj(x->new PaperStructureEffects.Voxel(x,0,0,null)).toList(),List.of());
        assertThrows(IllegalStateException.class,()->effects(outside).prepare(request(),before()));
    }
    @Test void componentOwnershipRejectsOldRevisionEvenForSameActor(){
        PersistentDataContainer pdc=proxy(PersistentDataContainer.class,(m,a)->{if(m.getName().equals("get"))return tags.get(a[0]);throw unsupported(m);});
        Entity entity=proxy(Entity.class,(m,a)->switch(m.getName()){case "isValid"->true;case "getWorld"->world;case "getPersistentDataContainer"->pdc;default->throw unsupported(m);});
        tags.put(new NamespacedKey("infernolantern","structure_tower"),towerId.toString());tags.put(new NamespacedKey("infernolantern","structure_revision"),2L);
        assertTrue(PaperStructureEffects.componentOwned(entity,worldId,towerId,2));assertFalse(PaperStructureEffects.componentOwned(entity,worldId,towerId,3));assertFalse(PaperStructureEffects.componentOwned(entity,worldId,UUID.randomUUID(),2));
    }
    static final class Template extends ItemStack {Template(){super();}@Override public Material getType(){return Material.PAPER;}@Override public Template clone(){return new Template();}}
}
