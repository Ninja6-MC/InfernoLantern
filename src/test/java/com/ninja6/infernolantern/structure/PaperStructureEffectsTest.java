package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.catalog.CatalogConfig.FluidPolicy;
import com.ninja6.infernolantern.catalog.CatalogConfig.FluidRule;
import com.ninja6.infernolantern.item.GroundPolicy;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Piston;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
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
    final Map<UUID,Entity> entities=new HashMap<>();
    final AtomicReference<UUID> liveSession=new AtomicReference<>(session);
    final Map<NamespacedKey,Object> tags=new HashMap<>();
    boolean permission=true;Object previousServer;
    World world;Player player;Dropped dropped;ItemStack selected;
    final Set<Cell> writes=new HashSet<>();
    boolean adjacentChunksLoaded=true;
    interface Answer {Object get(Method method,Object[] args);}
    static <T>T proxy(Class<T> type,Answer answer){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
        if(m.getName().equals("equals"))return p==args[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("toString"))return type.getSimpleName()+"Stub";
        return answer.get(m,args);
    }));}
    static RuntimeException unsupported(Method m){return new UnsupportedOperationException(m.toString());}
    BlockData data(String value){
        if(value.startsWith("minecraft:water[")||value.startsWith("minecraft:lava[")){
            Material material=value.startsWith("minecraft:water[")?Material.WATER:Material.LAVA;
            int level=Integer.parseInt(value.substring(value.indexOf('=')+1,value.length()-1));
            return proxy(Levelled.class,(m,a)->switch(m.getName()){
                case "getAsString"->value;case "getMaterial"->material;case "getLevel"->level;
                default->throw unsupported(m);
            });
        }
        if(value.startsWith("minecraft:sticky_piston[")){
            return proxy(Piston.class,(m,a)->switch(m.getName()){
                case "getAsString"->value;case "getMaterial"->Material.STICKY_PISTON;
                case "isExtended"->value.contains("extended=true");
                case "getFacing"->value.contains("facing=east")?BlockFace.EAST:BlockFace.WEST;
                default->throw unsupported(m);
            });
        }
        if(value.equals("minecraft:piston_head")||value.equals("minecraft:moving_piston")){
            return proxy(BlockData.class,(m,a)->switch(m.getName()){
                case "getAsString"->value;
                case "getMaterial"->value.endsWith("head")?Material.PISTON_HEAD:Material.MOVING_PISTON;
                default->throw unsupported(m);
            });
        }
        if(value.contains("waterlogged=")){
            boolean[] wet={value.contains("waterlogged=true")};
            Material material=value.startsWith("minecraft:light")?Material.LIGHT
                    :value.startsWith("minecraft:oak_slab")?Material.OAK_SLAB:Material.BARRIER;
            return proxy(Waterlogged.class,(m,a)->switch(m.getName()){
                case "getAsString"->value.replaceAll("waterlogged=(false|true)","waterlogged="+wet[0]);
                case "getMaterial"->material;case "isWaterlogged"->wet[0];
                case "setWaterlogged"->{wet[0]=(boolean)a[0];yield null;}
                default->throw unsupported(m);
            });
        }
        return proxy(BlockData.class,(m,a)->switch(m.getName()){
            case "getAsString"->value;
            case "getMaterial"->value.startsWith("minecraft:air")?Material.AIR:value.startsWith("minecraft:stone")?Material.STONE:Material.BARRIER;
            default->throw unsupported(m);
        });
    }
    @BeforeEach void setup()throws Exception{
        var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);previousServer=field.get(null);
        world=proxy(World.class,(m,a)->switch(m.getName()){
            case "getUID"->worldId;case "getMinHeight"->-64;case "getMaxHeight"->320;case "isChunkLoaded"->adjacentChunksLoaded||((int)a[0]==0&&(int)a[1]==0);
            case "getWorldBorder"->proxy(WorldBorder.class,(method,args)->{if(method.getName().equals("isInside"))return true;throw unsupported(method);});
            case "getNearbyEntities"->List.of();case "getEntity"->entities.get(a[0]);
            case "dropItem"->{
                dropped=new Dropped((Location)a[0],(ItemStack)a[1]);
                @SuppressWarnings("unchecked") var initialize=(java.util.function.Consumer<Item>)a[2];
                initialize.accept(dropped.item);
                yield dropped.item;
            }
            case "getBlockAt"->{Cell c=new Cell(worldId,(int)a[0],(int)a[1],(int)a[2]);
                assertTrue(adjacentChunksLoaded||(c.x()>>4)==0&&(c.z()>>4)==0,"Do not read an unloaded neighbor");
                yield proxy(Block.class,(method,args)->switch(method.getName()){
                case "getBlockData"->data(blocks.getOrDefault(c,"minecraft:air"));
                case "setBlockData"->{assertEquals(false,args[1]);writes.add(c);blocks.put(c,((BlockData)args[0]).getAsString());yield null;}
                default->throw unsupported(method);
            });}
            default->throw unsupported(m);
        });
        PlayerInventory inventory=proxy(PlayerInventory.class,(m,a)->switch(m.getName()){case "getSize"->41;case "getItem"->selected;default->throw unsupported(m);});
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
    Claim claim(Request request,Preparation prepared){return claim(request,prepared,before());}
    Claim claim(Request request,Preparation prepared,TowerSnapshot source){
        State after=new State(new Tower(towerId,"fixture",75,100,Form.EXPANDED,2,2,actor,base,true,false,0,44),null,prepared.manifest());
        Intent intent=new Intent(request.operation(),towerId,2,2,1,"structure-expand",after,prepared.evidence());return new Claim(request,new Operation(intent,source.state(),Phase.INTENT,0,"intent"),prepared);
    }
    private static PersistentDataContainer container(Map<NamespacedKey,Object> values){
        return proxy(PersistentDataContainer.class,(m,a)->switch(m.getName()){
            case "get"->values.get(a[0]);
            case "has"->values.containsKey(a[0]);
            case "set"->{values.put((NamespacedKey)a[0],a[2]);yield null;}
            case "remove"->{values.remove(a[0]);yield null;}
            default->throw unsupported(m);
        });
    }
    final class Dropped {
        final UUID id=UUID.randomUUID();
        final Location location;
        final ItemStack stack;
        final PersistentDataContainer pdc=container(new HashMap<>());
        boolean alive=true,playerPickup,mobPickup,invulnerable,gravity,unlimited;
        final Item item=proxy(Item.class,(m,a)->switch(m.getName()){
            case "getUniqueId"->id;case "getLocation"->location();case "getWorld"->world;
            case "getItemStack"->stack();case "getPersistentDataContainer"->pdc;case "isValid"->alive;
            case "canPlayerPickup"->playerPickup;case "canMobPickup"->mobPickup;case "isInvulnerable"->invulnerable;
            case "setCanPlayerPickup"->{playerPickup=(boolean)a[0];yield null;}
            case "setCanMobPickup"->{mobPickup=(boolean)a[0];yield null;}
            case "setInvulnerable"->{invulnerable=(boolean)a[0];yield null;}
            case "setGravity"->{gravity=(boolean)a[0];yield null;}
            case "setUnlimitedLifetime"->{unlimited=(boolean)a[0];yield null;}
            case "setVelocity"->null;
            case "remove"->{alive=false;yield null;}
            default->throw unsupported(m);
        });
        Dropped(Location location,ItemStack stack){this.location=location;this.stack=stack;}
        private Location location(){return location.clone();}
        private ItemStack stack(){return stack;}
    }
    static final class BlastTemplate extends ItemStack {
        final Map<NamespacedKey,Object> values=new HashMap<>();
        final PersistentDataContainer pdc=container(values);
        final ItemMeta meta=proxy(ItemMeta.class,(m,a)->switch(m.getName()){
            case "getPersistentDataContainer"->pdc;
            case "setMaxStackSize","setFireResistant"->null;
            default->throw unsupported(m);
        });
        int amount=1;
        @Override public Material getType(){return Material.PAPER;}
        @Override public int getAmount(){return amount;}
        @Override public byte[] serializeAsBytes(){return new byte[]{1};}
        @Override public void setAmount(int amount){this.amount=amount;}
        @Override public PersistentDataContainerView getPersistentDataContainer(){return pdc;}
        @Override public ItemMeta getItemMeta(){return meta;}
        @Override public boolean setItemMeta(ItemMeta value){assertSame(meta,value);return true;}
        @Override public BlastTemplate clone(){var copy=new BlastTemplate();copy.values.putAll(values);copy.amount=amount;return copy;}
    }
    private Claim blastClaim(PaperStructureEffects effects,OldStand stand){
        TowerSnapshot source=beforeWith(stand);
        var impact=BlastImpact.respectingProtection(UUID.randomUUID(),worldId,Set.of("base","old.0"),25,1,false,1000,0);
        var request=new Request(impact.operation(towerId),towerId,2,2,1,null,null,Action.BLAST,base,
                new Policy(1,50,200,1000,false),impact);
        var prepare=effects.prepare(request,source);assertNotNull(prepare);
        var after=new State(new Tower(towerId,"fixture",50,100,Form.GROUND,3,2,null,null,true,false,0,44),
                prepare.stagedGround(),List.of());
        var intent=new Intent(request.operation(),towerId,2,2,1,STRUCTURE_BLAST,after,prepare.evidence());
        return new Claim(request,new Operation(intent,source.state(),Phase.INTENT,0,"fixture"),prepare);
    }
    private final PaperStructureEffects.BlastAccess blastPhysical=new PaperStructureEffects.BlastAccess(){
        @Override public boolean allowed(Request request,State before){return permission;}
        @Override public boolean mayRestore(Request request,State before){return permission;}
    };
    @Test void actualBlastAdapterDoesNotStageAHitOnUnchangedClearance(){
        var effects=new PaperStructureEffects(blastPhysical,new BlastTemplate(),GroundPolicy.DEFAULT,
                item->fail("No item may activate"));
        var components=new ArrayList<>(before().state().components());
        components.add(new Component("clearance",ComponentKind.BLOCK,worldId,4,71,4,"minecraft:air","minecraft:air",null,null));
        var tower=new Tower(towerId,"fixture",75,100,Form.EXPANDED,2,1,actor,base,true,false,0,44);
        var source=new TowerSnapshot(new State(tower,null,components),2,null);
        var impact=BlastImpact.respectingProtection(UUID.randomUUID(),worldId,Set.of("clearance"),25,1,true,1000,0);
        var request=new Request(impact.operation(towerId),towerId,2,2,1,null,null,Action.BLAST,base,
                new Policy(1,50,200,1000,false),impact);
        assertNull(effects.prepare(request,source));assertNull(dropped);
        assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,70,4)));
    }
    @Test void actualBlastAdapterStagesOneTokenAndRemovesOnlyItsOwnStructure(){
        var admitted=new ArrayList<Item>();
        var effects=new PaperStructureEffects(blastPhysical,new BlastTemplate(),GroundPolicy.DEFAULT,admitted::add);
        OldStand stand=new OldStand();
        Claim claim=blastClaim(effects,stand);
        assertTrue(dropped.invulnerable);assertFalse(dropped.playerPickup);assertFalse(dropped.mobPickup);assertTrue(admitted.isEmpty());
        assertEquals(3,new PaperItemIdentity().read(dropped.stack).orElseThrow().generation());
        assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));assertTrue(stand.valid);
        assertEquals("minecraft:air",blocks.get(new Cell(worldId,4,70,4)));
        assertTrue(effects.activate(claim,completed(claim)));
        assertEquals(1,stand.removed);assertFalse(dropped.invulnerable);
        assertEquals(List.of(dropped.item),admitted);assertTrue(dropped.gravity);
    }
    @Test void actualBlastAdapterCancelsStageButNeverOverwritesExternalEditing(){
        var effects=new PaperStructureEffects(blastPhysical,new BlastTemplate(),GroundPolicy.DEFAULT,
                item->fail("No item may activate"));
        OldStand stand=new OldStand();Claim claim=blastClaim(effects,stand);
        assertTrue(effects.apply(claim));
        Cell baseCell=new Cell(worldId,4,70,4);blocks.put(baseCell,"minecraft:stone");
        assertFalse(effects.verify(claim));assertFalse(effects.rollback(claim));
        assertEquals("minecraft:stone",blocks.get(baseCell));assertFalse(dropped.alive);assertEquals(0,stand.removed);
    }
    @Test void systemDefeatUsesWorldProtectionWithoutAPlayerAndRestoresOwnedLight(){
        Cell light=new Cell(worldId,4,71,4);
        String lit="minecraft:light[level=15,waterlogged=false]";
        blocks.put(light,lit);
        var components=new ArrayList<>(before().state().components());
        components.add(new Component("light",ComponentKind.BLOCK,worldId,4,71,4,"minecraft:air",lit,null,null));
        var zero=new State(new Tower(towerId,"fixture",0,100,Form.EXPANDED,2,1,actor,base,true,false,0,44),null,components);
        var source=new TowerSnapshot(zero,2,null);
        var request=new Request(UUID.randomUUID(),towerId,2,2,1,null,null,Action.DEFEAT,base,new Policy(1,50,200,1000,false));
        var compact=new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,"minecraft:barrier")),List.of());
        var restoration=new java.util.concurrent.atomic.AtomicBoolean();
        var effects=new PaperStructureEffects((r,state,restoring)->!restoring||restoration.get(),compact,false);
        var prepared=effects.prepare(request,source);assertNotNull(prepared);
        var recipe=com.ninja6.infernolantern.recovery.RecoveryEnvelope.decode(prepared.evidence()).orElseThrow();
        assertEquals(com.ninja6.infernolantern.recovery.RecoveryEnvelope.Profile.STRUCTURE,recipe.profile());
        assertEquals(200L,recipe.exitCooldownMillis().longValue());
        var after=new State(new Tower(towerId,"fixture",0,100,Form.COMPACT,2,2,null,base,true,false,0,44),null,prepared.manifest());
        var intent=new Intent(request.operation(),towerId,2,2,1,"structure-defeat",after,prepared.evidence());
        var claim=new Claim(request,new Operation(intent,zero,Phase.INTENT,0,"Fixture"),prepared);
        assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));assertEquals("minecraft:air",blocks.get(light));
        assertFalse(effects.rollback(claim));assertEquals("minecraft:air",blocks.get(light));
        restoration.set(true);assertTrue(effects.rollback(claim));assertEquals(lit,blocks.get(light));
        var denied=new PaperStructureEffects((r,state,restoring)->false,compact,false);
        assertNull(denied.prepare(request,source));assertEquals(lit,blocks.get(light));
    }
    @Test void actualAdapterReservesUnchangedVoxelAndRestoresOwnedEffects(){
        Request request=request();var effects=effects(layout());var prepared=effects.prepare(request,before());assertNotNull(prepared);assertEquals(3,prepared.manifest().size());
        assertEquals(com.ninja6.infernolantern.recovery.RecoveryEnvelope.Profile.STRUCTURE,com.ninja6.infernolantern.recovery.RecoveryEnvelope.decode(prepared.evidence()).orElseThrow().profile());
        Claim claim=claim(request,prepared);assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,71,4)));
        assertTrue(effects.rollback(claim));assertEquals("minecraft:air",blocks.get(new Cell(worldId,4,71,4)));assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,70,4)));
    }
    @Test void actualAdapterLeavesExternalEditUntouchedDuringRollback(){
        Request r=request();var effects=effects(layout());var prepared=effects.prepare(r,before());Claim claim=claim(r,prepared);assertTrue(effects.apply(claim));
        Cell changed=new Cell(worldId,4,71,4);blocks.put(changed,"minecraft:stone");assertFalse(effects.verify(claim));assertFalse(effects.rollback(claim));assertEquals("minecraft:stone",blocks.get(changed));
    }
    @Test void actualAdapterLeavesOwnedBlocksWhenProtectionDeniesRestoration(){
        var authorize=new PaperStructureEffects.Access(){
            public boolean allowed(Request request,State before){return true;}
            public boolean mayRestore(Request request,State before){return false;}
        };
        var effects=new PaperStructureEffects(player,liveSession::get,authorize,layout(),0,new Template(),
                GroundPolicy.DEFAULT,item->fail("No ground drop for expansion"),false);
        Request request=request();Claim claim=claim(request,effects.prepare(request,before()));
        assertTrue(effects.apply(claim));
        assertFalse(effects.rollback(claim));
        assertEquals("minecraft:barrier",blocks.get(new Cell(worldId,4,71,4)));
    }
    @Test void actualAdapterRejectsObstructionAndRechecksSessionAndPolicy(){
        blocks.put(new Cell(worldId,4,71,4),"minecraft:stone");assertNull(effects(layout()).prepare(request(),before()));
        blocks.remove(new Cell(worldId,4,71,4));Request r=request();var effects=effects(layout());var prepared=effects.prepare(r,before());Claim claim=claim(r,prepared);
        permission=false;assertFalse(effects.apply(claim));permission=true;liveSession.set(UUID.randomUUID());assertFalse(effects.apply(claim));assertEquals("minecraft:air",blocks.getOrDefault(new Cell(worldId,4,71,4),"minecraft:air"));
    }
    private static final String SOURCE_WATER="minecraft:water[level=0]", FLOW_WATER="minecraft:water[level=6]",
            FLOW_LAVA="minecraft:lava[level=3]", DRY_BARRIER="minecraft:barrier[waterlogged=false]",
            WET_BARRIER="minecraft:barrier[waterlogged=true]";
    private PaperStructureEffects effects(PaperStructureEffects.Layout layout,FluidPolicy fluids){
        var access=new PaperStructureEffects.Access(){
            @Override public boolean allowed(Request request,State before){return permission;}
            @Override public FluidPolicy fluidPolicy(){return fluids;}
        };
        return new PaperStructureEffects(player,liveSession::get,access,layout,0,new Template(),GroundPolicy.DEFAULT,
                item->fail("No ground drop for placement or expansion"),false);
    }
    private PaperStructureEffects.Layout waterLayout(){
        return new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,"minecraft:barrier"),
                new PaperStructureEffects.Voxel(0,1,0,DRY_BARRIER),new PaperStructureEffects.Voxel(0,2,0,null)),List.of());
    }
    @Test void sourceWaterIsWaterloggedAndFlowingClearanceStaysUntouchedThroughRollback(){
        Cell source=new Cell(worldId,4,71,4), flow=new Cell(worldId,4,72,4);
        blocks.put(source,SOURCE_WATER);blocks.put(flow,FLOW_WATER);
        var effects=effects(waterLayout(),FluidPolicy.DEFAULT);Request request=request();
        var prepared=effects.prepare(request,before());assertNotNull(prepared);
        assertEquals(SOURCE_WATER,prepared.manifest().get(1).originalState());
        assertEquals(WET_BARRIER,prepared.manifest().get(1).expectedState());
        assertEquals(FLOW_WATER,prepared.manifest().get(2).expectedState());
        Claim claim=claim(request,prepared);
        assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));
        assertEquals(WET_BARRIER,blocks.get(source));assertEquals(FLOW_WATER,blocks.get(flow));
        assertEquals(Set.of(source),writes);
        assertTrue(effects.rollback(claim));assertEquals(SOURCE_WATER,blocks.get(source));assertEquals(FLOW_WATER,blocks.get(flow));
        assertEquals(Set.of(source),writes);
    }
    @Test void permittedClearanceRechecksFlowBeforeWritingAnyBlock(){
        Cell flow=new Cell(worldId,4,72,4);blocks.put(flow,FLOW_WATER);
        var effects=effects(waterLayout(),FluidPolicy.DEFAULT);Request request=request();
        var prepared=effects.prepare(request,before());assertNotNull(prepared);
        blocks.put(flow,SOURCE_WATER);
        assertFalse(effects.apply(claim(request,prepared)));assertTrue(writes.isEmpty());
        assertEquals(SOURCE_WATER,blocks.get(flow));
    }
    @Test void mixedFlowRequiresBothPermissionsAndUnsupportedReplacementDoesNotWrite(){
        Cell replacement=new Cell(worldId,4,71,4), clearance=new Cell(worldId,4,72,4);
        blocks.put(replacement,SOURCE_WATER);blocks.put(clearance,FLOW_LAVA);
        var both=new FluidPolicy(FluidPolicy.DEFAULT.water(),new FluidRule(false,true,false));
        assertNull(effects(waterLayout(),FluidPolicy.DEFAULT).prepare(request(),before()));
        var allowed=effects(waterLayout(),both);Request request=request();
        var prepared=allowed.prepare(request,before());assertNotNull(prepared);
        assertEquals(FLOW_LAVA,prepared.manifest().get(2).expectedState());
        Claim claim=claim(request,prepared);
        assertTrue(allowed.apply(claim));assertTrue(allowed.verify(claim));
        assertEquals(Set.of(replacement),writes);assertEquals(FLOW_LAVA,blocks.get(clearance));
        assertTrue(allowed.rollback(claim));writes.clear();
        for(String unsupported:List.of(FLOW_WATER,FLOW_LAVA,"minecraft:oak_slab[type=bottom,waterlogged=true]")){
            blocks.put(replacement,unsupported);
            assertNull(effects(waterLayout(),both).prepare(request(),before()));
            assertEquals(unsupported,blocks.get(replacement));assertTrue(writes.isEmpty());
        }
    }
    @Test void placementAndExpansionUseDifferentFlags(){
        var tower=new Tower(towerId,"fixture",75,100,Form.PORTABLE,2,1,null,null,true,false,0,44);
        var portable=new TowerSnapshot(new State(tower,null,List.of()),2,null);
        selected=new PaperItemIdentity().create(new BlastTemplate(),com.ninja6.infernolantern.item.ItemLifecycle.token(tower),GroundPolicy.DEFAULT);
        var compact=new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,DRY_BARRIER)),List.of());
        Cell baseCell=new Cell(worldId,4,70,4);blocks.put(baseCell,SOURCE_WATER);
        var placeOnly=new FluidPolicy(new FluidRule(true,false,false),FluidPolicy.DEFAULT.lava());
        var expandOnly=new FluidPolicy(new FluidRule(false,true,false),FluidPolicy.DEFAULT.lava());
        var request=new Request(UUID.randomUUID(),towerId,2,2,1,actor,session,Action.PLACE,base,new Policy(1,50,200,1000,false));
        assertNotNull(effects(compact,placeOnly).prepare(request,portable));
        assertNull(effects(compact,expandOnly).prepare(request,portable));
        blocks.put(baseCell,"minecraft:barrier");
        blocks.put(new Cell(worldId,4,71,4),SOURCE_WATER);
        assertNull(effects(waterLayout(),placeOnly).prepare(request(),before()));
        assertNotNull(effects(waterLayout(),expandOnly).prepare(request(),before()));
        assertTrue(writes.isEmpty());
    }
    @Test void blastRestoresOwnedSourceWaterWithoutOverwritingChangedClearance(){
        var effects=new PaperStructureEffects(blastPhysical,new BlastTemplate(),new GroundPolicy(false,false),item->{});
        Cell baseCell=new Cell(worldId,4,70,4), clearance=new Cell(worldId,4,71,4);
        blocks.put(baseCell,WET_BARRIER);blocks.put(clearance,FLOW_LAVA);
        var tower=new Tower(towerId,"fixture",75,100,Form.COMPACT,2,1,null,base,true,false,0,44);
        var source=new TowerSnapshot(new State(tower,null,List.of(
                new Component("base",ComponentKind.BLOCK,worldId,4,70,4,SOURCE_WATER,WET_BARRIER,null,null),
                new Component("clearance",ComponentKind.BLOCK,worldId,4,71,4,"minecraft:air","minecraft:air",null,null))),2,null);
        var impact=BlastImpact.respectingProtection(UUID.randomUUID(),worldId,Set.of("base"),25,1,false,1000,0);
        var request=new Request(impact.operation(towerId),towerId,2,2,1,null,null,Action.BLAST,base,new Policy(1,50,200,1000,false),impact);
        var prepared=effects.prepare(request,source);assertNotNull(prepared);
        var after=new State(new Tower(towerId,"fixture",50,100,Form.GROUND,3,2,null,null,true,false,0,44),prepared.stagedGround(),List.of());
        var intent=new Intent(request.operation(),towerId,2,2,1,STRUCTURE_BLAST,after,prepared.evidence());
        var claim=new Claim(request,new Operation(intent,source.state(),Phase.INTENT,0,"fixture"),prepared);
        assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));
        assertEquals(SOURCE_WATER,blocks.get(baseCell));assertEquals(FLOW_LAVA,blocks.get(clearance));
        assertEquals(Set.of(baseCell),writes);
        assertTrue(effects.activate(claim,completed(claim)));assertTrue(dropped.unlimited);
    }
    @Test void manualAndSystemCompactionCanChangeAnOwnedWaterloggedLightToABarrier(){
        Cell baseCell=new Cell(worldId,4,70,4),released=new Cell(worldId,4,71,4);
        String light="minecraft:light[level=15,waterlogged=true]";
        String piston="minecraft:sticky_piston[extended=false,facing=west]";
        blocks.put(baseCell,light);blocks.put(released,piston);
        var components=List.of(new Component("base",ComponentKind.BLOCK,worldId,4,70,4,SOURCE_WATER,light,null,null),
                new Component("clearance",ComponentKind.BLOCK,worldId,4,71,4,"minecraft:air","minecraft:air",null,null));
        var compact=new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,DRY_BARRIER)),List.of());
        for(Action action:List.of(Action.COMPACT,Action.DEFEAT)){
            double hp=action==Action.DEFEAT?0:75;
            var before=new TowerSnapshot(new State(new Tower(towerId,"fixture",hp,100,Form.EXPANDED,2,1,actor,base,
                    true,false,0,44),null,components),2,null);
            var request=new Request(UUID.randomUUID(),towerId,2,2,1,action==Action.DEFEAT?null:actor,
                    action==Action.DEFEAT?null:session,action,base,new Policy(1,50,200,1000,false));
            var effects=action==Action.DEFEAT?new PaperStructureEffects((r,state,restoring)->true,compact,false)
                    :effects(compact,new FluidPolicy(new FluidRule(false,false,false),FluidPolicy.DEFAULT.lava()));
            var prepared=effects.prepare(request,before);assertNotNull(prepared);
            assertEquals(SOURCE_WATER,prepared.manifest().getFirst().originalState());
            assertEquals(WET_BARRIER,prepared.manifest().getFirst().expectedState());
            var after=new State(new Tower(towerId,"fixture",hp,100,Form.COMPACT,2,2,null,base,true,false,0,44),
                    null,prepared.manifest());
            var intent=new Intent(request.operation(),towerId,2,2,1,kind(request),after,prepared.evidence());
            var claim=new Claim(request,new Operation(intent,before.state(),Phase.INTENT,0,"fixture"),prepared);
            assertTrue(effects.apply(claim));assertTrue(effects.verify(claim));
            assertEquals(WET_BARRIER,blocks.get(baseCell));
            assertTrue(effects.rollback(claim));
            assertEquals(light,blocks.get(baseCell));assertEquals(piston,blocks.get(released));
            assertEquals(Set.of(baseCell),writes);
        }
    }
    @Test void alreadyActivePistonReachAndAnUnloadedNeighborRejectBeforeFootprintAdmission(){
        Cell neighbor=new Cell(worldId,3,71,4), target=new Cell(worldId,4,71,4);
        blocks.put(neighbor,"minecraft:sticky_piston[extended=true,facing=east]");
        assertNull(effects(layout()).prepare(request(),before()));
        blocks.put(neighbor,"minecraft:sticky_piston[extended=true,facing=west]");
        assertNotNull(effects(layout()).prepare(request(),before()));
        blocks.put(target,"minecraft:piston_head");
        assertNull(effects(layout()).prepare(request(),before()));
        blocks.put(target,"minecraft:moving_piston");
        assertNull(effects(layout()).prepare(request(),before()));
        blocks.remove(neighbor);blocks.remove(target);

        adjacentChunksLoaded=false;
        var edge=new PaperStructureEffects.Layout(java.util.stream.IntStream.rangeClosed(0,11)
                .mapToObj(x->new PaperStructureEffects.Voxel(x,0,0,null)).toList(),List.of());
        assertNull(effects(edge).prepare(request(),before()));
        assertTrue(writes.isEmpty());
    }
    @Test void writerRechecksPistonReachAndRejectsPistonReplacement(){
        Cell neighbor=new Cell(worldId,3,71,4);
        var effects=effects(layout());Request request=request();
        var prepared=effects.prepare(request,before());assertNotNull(prepared);
        blocks.put(neighbor,"minecraft:sticky_piston[extended=true,facing=east]");
        assertFalse(effects.apply(claim(request,prepared)));assertTrue(writes.isEmpty());
        assertTrue(effects.rollback(claim(request,prepared)));assertTrue(writes.isEmpty());
        assertEquals("minecraft:sticky_piston[extended=true,facing=east]",blocks.remove(neighbor));

        var pistonLayout=new PaperStructureEffects.Layout(List.of(new PaperStructureEffects.Voxel(0,0,0,"minecraft:barrier"),
                new PaperStructureEffects.Voxel(0,1,0,"minecraft:sticky_piston[extended=false,facing=east]")),List.of());
        assertNull(effects(pistonLayout).prepare(request(),before()));
        assertTrue(writes.isEmpty());
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
    final class OldStand {
        final UUID id=UUID.randomUUID();final Map<NamespacedKey,Object> data=new HashMap<>();
        final AtomicReference<Location> location=new AtomicReference<>(new Location(world,4.5,70,4.5));
        final AtomicReference<ItemStack> helmet=new AtomicReference<>(new Template());
        boolean marker=true,invisible=true,invulnerable=true,gravity,valid=true;int removed,helmetWrites;Runnable onRemove=()->{};
        final PersistentDataContainer pdc=proxy(PersistentDataContainer.class,(m,a)->{if(m.getName().equals("get"))return data.get(a[0]);throw unsupported(m);});
        final EntityEquipment equipment=proxy(EntityEquipment.class,(m,a)->switch(m.getName()){
            case "getHelmet"->helmet.get();case "setHelmet"->{helmet.set((ItemStack)a[0]);helmetWrites++;yield null;}default->throw unsupported(m);
        });
        final ArmorStand entity=proxy(ArmorStand.class,(m,a)->switch(m.getName()){
            case "getUniqueId"->id;case "getWorld"->world;case "isValid"->valid;case "getLocation"->location.get().clone();
            case "getEquipment"->equipment;case "getPersistentDataContainer"->pdc;case "isMarker"->marker;case "isInvisible"->invisible;
            case "isInvulnerable"->invulnerable;case "hasGravity"->gravity;case "remove"->{removed++;valid=false;onRemove.run();yield null;}
            default->throw unsupported(m);
        });
        OldStand(){data.put(new NamespacedKey("infernolantern","structure_tower"),towerId.toString());data.put(new NamespacedKey("infernolantern","structure_revision"),1L);data.put(new NamespacedKey("infernolantern","structure_operation"),UUID.randomUUID().toString());entities.put(id,entity);}
        void move(){location.set(new Location(world,5.5,70,4.5));}
    }
    TowerSnapshot beforeWith(OldStand... stands){
        var components=new ArrayList<>(before().state().components());int i=0;
        for(OldStand stand:stands)components.add(new Component("old."+i++,ComponentKind.ENTITY,worldId,4,70,4,null,null,stand.id,"minecraft:armor_stand"));
        return new TowerSnapshot(new State(before().state().tower(),null,components),2,null);
    }
    record VisualCase(PaperStructureEffects effects,Claim claim) {}
    VisualCase applied(OldStand... stands){
        Request r=request();TowerSnapshot source=beforeWith(stands);var effects=effects(layout());var prepared=effects.prepare(r,source);assertNotNull(prepared);
        Claim claim=claim(r,prepared,source);assertTrue(effects.apply(claim));for(OldStand stand:stands){assertNull(stand.helmet.get());assertEquals(1,stand.helmetWrites);}return new VisualCase(effects,claim);
    }
    TowerSnapshot completed(Claim claim){return new TowerSnapshot(claim.operation().intent().after(),4,null);}
    @Test void teleportedOldVisualFailsVerificationAndIsNotRemovedOrRestored(){
        OldStand stand=new OldStand();VisualCase c=applied(stand);stand.move();
        assertFalse(c.effects().verify(c.claim()));assertFalse(c.effects().activate(c.claim(),completed(c.claim())));assertFalse(c.effects().rollback(c.claim()));
        assertEquals(0,stand.removed);assertEquals(1,stand.helmetWrites);assertNull(stand.helmet.get());assertEquals(5.5,stand.location.get().getX());
    }
    @Test void teleportAfterSuccessfulVerificationStillPreventsActivation(){
        OldStand stand=new OldStand();VisualCase c=applied(stand);assertTrue(c.effects().verify(c.claim()));stand.move();
        assertFalse(c.effects().activate(c.claim(),completed(c.claim())));assertEquals(0,stand.removed);assertNull(stand.helmet.get());
    }
    @Test void oldVisualMustRemainExactAtEachRemovalNotOnlyTheInitialVerification(){
        OldStand first=new OldStand(),second=new OldStand();VisualCase c=applied(first,second);assertTrue(c.effects().verify(c.claim()));first.onRemove=second::move;
        assertFalse(c.effects().activate(c.claim(),completed(c.claim())));assertEquals(1,first.removed);assertEquals(0,second.removed);assertNull(second.helmet.get());
    }
    @Test void changedCapturedVisualPropertiesPreventRemovalAndHelmetRestoration(){
        for(int property=0;property<6;property++){
            OldStand stand=new OldStand();VisualCase c=applied(stand);
            switch(property){case 0->stand.marker=false;case 1->stand.invisible=false;case 2->stand.invulnerable=false;case 3->stand.gravity=true;
                case 4->stand.data.put(new NamespacedKey("infernolantern","structure_operation"),UUID.randomUUID().toString());
                case 5->stand.data.put(new NamespacedKey("infernolantern","structure_revision"),2L);default->fail();}
            assertFalse(c.effects().verify(c.claim()));assertFalse(c.effects().activate(c.claim(),completed(c.claim())));assertFalse(c.effects().rollback(c.claim()));
            assertEquals(0,stand.removed);assertEquals(1,stand.helmetWrites);assertNull(stand.helmet.get());blocks.put(new Cell(worldId,4,70,4),"minecraft:barrier");blocks.remove(new Cell(worldId,4,71,4));
        }
    }
    @Test void externalHelmetReplacementIsPreservedDuringFailedRollback(){
        OldStand stand=new OldStand();VisualCase c=applied(stand);ItemStack external=new Template();stand.helmet.set(external);
        assertFalse(c.effects().verify(c.claim()));assertFalse(c.effects().rollback(c.claim()));assertSame(external,stand.helmet.get());assertEquals(1,stand.helmetWrites);assertEquals(0,stand.removed);
    }
    @Test void unchangedOldVisualCanBeRestoredOrRemovedWithProof(){
        OldStand restored=new OldStand();VisualCase rollback=applied(restored);assertTrue(rollback.effects().verify(rollback.claim()));assertTrue(rollback.effects().rollback(rollback.claim()));assertNotNull(restored.helmet.get());assertEquals(2,restored.helmetWrites);assertEquals(0,restored.removed);
        OldStand removed=new OldStand();VisualCase activation=applied(removed);assertTrue(activation.effects().activate(activation.claim(),completed(activation.claim())));assertEquals(1,removed.removed);
    }
    static final class Template extends ItemStack {Template(){super();}@Override public Material getType(){return Material.PAPER;}@Override public Template clone(){return new Template();}@Override public byte[] serializeAsBytes(){return new byte[]{1};}}
}
