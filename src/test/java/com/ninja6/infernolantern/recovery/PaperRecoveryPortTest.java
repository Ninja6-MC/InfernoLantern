package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.repository.RepositoryRecords.*;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import static com.ninja6.infernolantern.recovery.MatchedItemSaves.*;
import static com.ninja6.infernolantern.recovery.PaperRecoveryPort.*;
import static com.ninja6.infernolantern.recovery.ReconciliationService.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.STRUCTURE_EXIT_COMPACT;
import static org.junit.jupiter.api.Assertions.*;

/** Actual observation/activation adapter with strict API doubles, not native save/physics evidence. */
class PaperRecoveryPortTest {
    @TempDir Path directory;
    final UUID worldId=UUID.randomUUID(),actor=UUID.randomUUID(),towerId=UUID.randomUUID(),operation=UUID.randomUUID(),entityId=UUID.randomUUID(),session=UUID.randomUUID();
    final WorldScheduler.Anchor anchor=new WorldScheduler.Anchor(worldId,0,0);
    final PaperItemIdentity identity=new PaperItemIdentity();
    final Map<Integer,ItemStack> inventory=new HashMap<>();final Map<UUID,Entity> entities=new HashMap<>();
    final Map<NamespacedKey,Object> tags=new HashMap<>();
    final NamespacedKey staged=new NamespacedKey("infernolantern","item_staged");
    World world;Player player;Item item;Object oldServer;boolean loaded=true,online=true,authorized=true,pickup=false,mob=false,invulnerable=true,unlimited=true,gravity=true;
    int age=2718,mutations,admitted;double x=4.5,y=70.5,z=4.5;ItemStack groundStack;
    Coverage coverage=Coverage.MATCHED_COMPLETE;
    interface Answer {Object answer(Method method,Object[] args);}
    static <T>T proxy(Class<T> type,Answer answer){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("toString"))return type.getSimpleName()+"Fixture";return answer.answer(m,a);
    }));}
    static RuntimeException unsupported(Method m){return new UnsupportedOperationException(m.toString());}
    @BeforeEach void setup()throws Exception{
        var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);oldServer=field.get(null);
        world=proxy(World.class,(m,a)->switch(m.getName()){case "getUID"->worldId;case "isChunkLoaded"->loaded;case "getEntity"->entities.get(a[0]);default->throw unsupported(m);});
        PlayerInventory slots=proxy(PlayerInventory.class,(m,a)->switch(m.getName()){case "getSize"->41;case "getItem"->inventory.get((int)a[0]);case "setItem"->{mutations++;inventory.put((int)a[0],(ItemStack)a[1]);yield null;}default->throw unsupported(m);});
        player=proxy(Player.class,(m,a)->switch(m.getName()){case "isOnline"->online;case "getInventory"->slots;case "getUniqueId"->actor;default->throw unsupported(m);});
        PersistentDataContainer data=proxy(PersistentDataContainer.class,(m,a)->switch(m.getName()){case "get"->tags.get(a[0]);case "has"->tags.containsKey(a[0]);case "remove"->{mutations++;tags.remove(a[0]);yield null;}default->throw unsupported(m);});
        item=proxy(Item.class,(m,a)->switch(m.getName()){
            case "getUniqueId"->entityId;case "isValid"->true;case "getWorld"->world;case "getLocation"->new Location(world,x,y,z);case "getPersistentDataContainer"->data;case "getItemStack"->groundStack;
            case "canPlayerPickup"->pickup;case "canMobPickup"->mob;case "isInvulnerable"->invulnerable;case "isUnlimitedLifetime"->unlimited;case "hasGravity"->gravity;case "getTicksLived"->age;
            case "setCanPlayerPickup"->{mutations++;pickup=(boolean)a[0];yield null;}case "setInvulnerable"->{mutations++;invulnerable=(boolean)a[0];yield null;}case "setUnlimitedLifetime"->{mutations++;unlimited=(boolean)a[0];yield null;}case "setGravity"->{mutations++;gravity=(boolean)a[0];yield null;}case "setPickupDelay"->{mutations++;yield null;}
            default->throw unsupported(m);
        });
        Server server=proxy(Server.class,(m,a)->switch(m.getName()){case "isPrimaryThread"->true;case "getWorld"->world;case "getPlayer"->actor.equals(a[0])?player:null;case "getLogger"->Logger.getLogger("fixture");default->throw unsupported(m);});field.set(null,server);
    }
    @AfterEach void reset()throws Exception{var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);field.set(null,oldServer);}
    Tower tower(Form form,long generation){return new Tower(towerId,"fixture",25,100,form,generation,0,null,null,false,true,300,400);}
    Stack stack(long generation){return (Stack)identity.create(new Stack(),new ItemToken(1,"fixture",towerId,generation),GroundPolicy.DEFAULT);}
    Operation drop(){
        Stack source=stack(1);var raw=new Document(1,"action=drop;actor="+actor+";session="+session+";slot=0;source="+Base64.getEncoder().encodeToString(source.serializeAsBytes()));
        Ground ground=new Ground(entityId,worldId,0,0,operation,"active");
        return new Operation(new Intent(operation,towerId,0,1,0,"item-drop",new State(tower(Form.GROUND,2),ground,List.of()),RecoveryEnvelope.item("item-drop",anchor,raw)),new State(tower(Form.PORTABLE,1),null,List.of()),Phase.INTENT,0,"intent");
    }
    void stage(){groundStack=stack(2);tags.put(staged,(byte)1);tags.put(PaperItemIdentity.OPERATION,operation.toString());entities.put(entityId,item);}
    PaperRecoveryPort port(){return port((o,t,c,i)->new CoverageProof(coverage,"synthetic independent matched-save scope"));}
    PaperRecoveryPort port(CoverageCheck witness){return new PaperRecoveryPort(witness,o->authorized,v->GroundPolicy.DEFAULT,i->{assertSame(item,i);admitted++;});}
    Inspection inspect(PaperRecoveryPort port,Operation op){var context=RecoveryEnvelope.decode(op.intent().effects()).orElseThrow();TowerSnapshot pending=new TowerSnapshot(op.before(),1,operation);return new Inspection(op,pending,context,port.inspect(op,pending,context));}
    Capture saved(Operation op,String slot,GroundView mark){
        UUID id=UUID.randomUUID();
        return new Capture(new Database(new Source(id,"1".repeat(64)),op,new TowerSnapshot(op.before(),1,operation)),
                new WorldScope(new Source(id,"2".repeat(64)),anchor,Custody.EXACT),
                new PlayerSlot(new Source(id,"3".repeat(64)),actor,0,slot),
                new GroundEntity(new Source(id,"4".repeat(64)),op.intent().after().ground(),mark));
    }
    String itemBytes(ItemStack stack){return Base64.getEncoder().encodeToString(stack.serializeAsBytes());}
    GroundView savedStage(int savedAge){
        return new GroundView(entityId,itemBytes(stack(2)),worldId,0,0,4.5,70.5,4.5,true,false,false,true,true,true,savedAge,operation.toString());
    }
    @Test void independentBeforeAndAfterCapturesMatchOnlyTheirExactRepresentation(){
        Operation op=drop();String source=itemBytes(stack(1));
        Capture before=saved(op,source,null),after=saved(op,"empty",savedStage(age));
        inventory.put(0,stack(1));
        assertEquals(Coverage.MATCHED_COMPLETE,inspect(port(new MatchedItemSaves(()->Optional.of(before))),op).observation().coverage());
        assertEquals(Coverage.UNMATCHED,inspect(port(new MatchedItemSaves(()->Optional.of(after))),op).observation().coverage());

        inventory.clear();stage();age+=5;
        var loaded=port(new MatchedItemSaves(()->Optional.of(after)));
        var observation=inspect(loaded,op);
        assertEquals(Coverage.MATCHED_COMPLETE,observation.observation().coverage());
        assertEquals(Physical.AFTER,observation.observation().physical());
        assertTrue(loaded.publish(observation,new TowerSnapshot(op.intent().after(),2,null)));
        assertEquals(2723,age);
        assertEquals(1,admitted);
    }
    @Test void missingAndMismatchedCapturePartsHoldWithoutPublishing(){
        Operation op=drop();stage();Capture original=saved(op,"empty",savedStage(age));
        var current=new AtomicReference<>(Optional.of(original));
        var witness=new MatchedItemSaves(current::get);
        var cases=Map.of(
                new Capture(original.database(),original.world(),null,original.ground()),Coverage.INCOMPLETE,
                new Capture(original.database(),original.world(),original.player(),null),Coverage.INCOMPLETE,
                new Capture(original.database(),new WorldScope(original.world().source(),anchor,Custody.INCOMPLETE),original.player(),original.ground()),Coverage.INCOMPLETE,
                new Capture(original.database(),new WorldScope(original.world().source(),anchor,Custody.CONFLICTING),original.player(),original.ground()),Coverage.UNMATCHED,
                new Capture(original.database(),original.world(),saved(op,"empty",null).player(),original.ground()),Coverage.UNMATCHED,
                new Capture(original.database(),original.world(),original.player(),new GroundEntity(original.ground().source(),original.ground().reference(),null)),Coverage.UNMATCHED,
                new Capture(original.database(),original.world(),original.player(),new GroundEntity(original.ground().source(),original.ground().reference(),savedStage(age+1))),Coverage.UNMATCHED,
                new Capture(original.database(),original.world(),original.player(),new GroundEntity(original.ground().source(),original.ground().reference(),savedStage(-1))),Coverage.INCOMPLETE,
                new Capture(original.database(),original.world(),new PlayerSlot(original.player().source(),actor,1,"empty"),original.ground()),Coverage.UNMATCHED,
                new Capture(new Database(original.database().source(),new Operation(op.intent(),op.before(),Phase.EFFECTS_APPLIED,1,"advanced"),original.database().tower()),original.world(),original.player(),original.ground()),Coverage.UNMATCHED);
        for(var test:cases.entrySet()){
            current.set(Optional.of(test.getKey()));
            var port=port(witness);var observation=inspect(port,op);
            assertEquals(test.getValue(),observation.observation().coverage());
            assertFalse(port.publish(observation,new TowerSnapshot(op.intent().after(),2,null)));
        }
        assertEquals(0,mutations);
        assertTrue(tags.containsKey(staged));
    }
    @Test void withdrawalOrNewCaptureInvalidatesTheAttempt(){
        Operation op=drop();stage();Capture original=saved(op,"empty",savedStage(age));
        var current=new AtomicReference<>(Optional.of(original));
        var port=port(new MatchedItemSaves(current::get));
        var observation=inspect(port,op);
        current.set(Optional.empty());
        assertFalse(port.revalidate(observation));
        current.set(Optional.of(saved(op,"empty",savedStage(age))));
        assertFalse(port.revalidate(observation));
        assertFalse(port.publish(observation,new TowerSnapshot(op.intent().after(),2,null)));
        assertEquals(0,mutations);
    }
    @Test void reopenedJournalUsesInspectedDatabaseCopyAndNeverReusesTerminalAuthority() throws Exception {
        Path store=directory.resolve("active.db"),copy=directory.resolve("captured.db");
        Operation op=drop();
        var seed=get(SqliteRepository.open(store,32));
        try {
            get(seed.createVariant(new Variant("fixture",1,VariantStatus.ACTIVE,new Document(1,"Fixture"),0)));
            get(seed.createTower(op.before().tower()));
            get(seed.beginNewIntent(op.intent()));
        } finally {get(seed.closeAsync());}
        Files.copy(store,copy);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy)));
        var savedDatabase=get(SqliteRepository.open(copy,32));
        Capture capture;
        try {
            Operation savedOp=get(savedDatabase.operation(operation)).orElseThrow();
            TowerSnapshot savedRow=get(savedDatabase.tower(towerId)).orElseThrow();
            Capture physical=saved(savedOp,"empty",savedStage(age));
            capture=new Capture(new Database(new Source(physical.database().source().captureId(),hash),savedOp,savedRow),
                    physical.world(),physical.player(),physical.ground());
        } finally {get(savedDatabase.closeAsync());}
        var repository=get(SqliteRepository.open(store,32));
        var current=new AtomicReference<>(Optional.of(new Capture(capture.database(),capture.world(),capture.player(),null)));
        try(var owner=Executors.newSingleThreadExecutor()){
            var scheduler=new WorldScheduler(){
                @Override public <T> CompletableFuture<T> supply(Anchor at,Supplier<T> action){
                    assertEquals(anchor,at);return CompletableFuture.supplyAsync(action,owner);
                }
            };
            try(var service=new ReconciliationService(repository,scheduler)){
                stage();var witness=new MatchedItemSaves(current::get);
                assertEquals(Outcome.WAIT,get(service.reconcile(operation,port(witness))).outcome());
                assertEquals(capture.database().operation(),get(repository.operation(operation)).orElseThrow());
                assertEquals(0,mutations);
                current.set(Optional.of(capture));
                var matched=port(witness);
                assertEquals(Outcome.COMPLETED,get(service.reconcile(operation,matched)).outcome());
                assertEquals(Phase.COMPLETE,get(repository.operation(operation)).orElseThrow().phase());
                assertEquals(age,item.getTicksLived());
                assertEquals(1,admitted);
                assertEquals(Outcome.TERMINAL,get(service.reconcile(operation,matched)).outcome());
                assertEquals(1,admitted);
            }
        } finally {get(repository.closeAsync());}
    }
    static <T>T get(CompletableFuture<T> work) throws Exception {return work.get(10,TimeUnit.SECONDS);}
    @Test void existingTaggedDropActivatesOnlyAfterExactCompletedRowWithoutAgeReset(){
        Operation op=drop();stage();var port=port();Inspection observed=inspect(port,op);assertEquals(Physical.AFTER,observed.observation().physical());assertEquals(0,mutations);
        assertFalse(port.publish(observed,new TowerSnapshot(op.intent().after(),1,operation)));assertEquals(0,mutations);
        age+=7;assertTrue(port.revalidate(observed));assertTrue(port.publish(observed,new TowerSnapshot(op.intent().after(),2,null)));
        assertEquals(2725,age);assertFalse(tags.containsKey(staged));assertTrue(pickup);assertFalse(invulnerable);assertFalse(unlimited);assertEquals(1,admitted);
        assertFalse(port.publish(observed,new TowerSnapshot(op.intent().after(),2,null)));assertEquals(1,admitted);
    }
    @Test void changedStageLocationOrTagsArePreservedRatherThanActivated(){
        Operation op=drop();stage();var port=port();Inspection observed=inspect(port,op);x+=1;assertFalse(port.revalidate(observed));assertFalse(port.publish(observed,new TowerSnapshot(op.intent().after(),2,null)));assertEquals(0,mutations);assertTrue(tags.containsKey(staged));
        x-=1;tags.put(PaperItemIdentity.OPERATION,UUID.randomUUID().toString());assertFalse(port.revalidate(observed));assertEquals(0,mutations);
    }
    @Test void incompleteCoverageAndOfflinePlayersCannotInventMissingSource(){
        Operation op=drop();stage();coverage=Coverage.UNMATCHED;Inspection unmatched=inspect(port(),op);assertEquals(Coverage.UNMATCHED,unmatched.observation().coverage());assertEquals(Physical.AFTER,unmatched.observation().physical());
        online=false;Inspection unknown=inspect(port(),op);assertEquals(Physical.UNKNOWN,unknown.observation().physical());assertEquals(Coverage.INCOMPLETE,unknown.observation().coverage());loaded=false;assertEquals(Physical.UNKNOWN,inspect(port(),op).observation().physical());assertEquals(0,mutations);
    }
    @Test void exactPortableSourceCanProveBeforeButUnknownOrForeignTargetCannot(){
        Operation op=drop();inventory.put(0,stack(1));Inspection before=inspect(port(),op);assertEquals(Physical.BEFORE,before.observation().physical());
        entities.put(entityId,proxy(ArmorStand.class,(m,a)->{throw unsupported(m);}));assertEquals(Physical.MIXED,inspect(port(),op).observation().physical());
        entities.clear();inventory.put(0,new Stack());assertEquals(Physical.MIXED,inspect(port(),op).observation().physical());assertEquals(0,mutations);
    }
    @Test void absentGroundIsNotLossWithoutAppliedIntentAndMatchedCoverage(){
        Ground source=new Ground(entityId,worldId,0,0,UUID.randomUUID(),"active");State before=new State(tower(Form.GROUND,1),source,List.of());State after=new State(tower(Form.LOST,2),null,List.of());
        var effects=RecoveryEnvelope.item("item-loss",anchor,new Document(1,"despawn"));Operation intent=new Operation(new Intent(operation,towerId,0,1,0,"item-loss",after,effects),before,Phase.INTENT,0,"intent");
        assertEquals(Physical.ABSENT,inspect(port(),intent).observation().physical());Operation applied=new Operation(intent.intent(),before,Phase.EFFECTS_APPLIED,1,"owner applied");assertEquals(Physical.AFTER,inspect(port(),applied).observation().physical());coverage=Coverage.UNMATCHED;assertEquals(Coverage.UNMATCHED,inspect(port(),applied).observation().coverage());assertEquals(0,mutations);
    }
    @Test void fixtureMarkerIsRemovedFromExistingExactSourceOnly(){
        Stack delivered=(Stack)identity.create(new Stack(),new ItemToken(1,FixtureDelivery.VARIANT,towerId,1),GroundPolicy.DEFAULT);delivered.values.put(PaperItemIdentity.DELIVERY,operation.toString());inventory.put(0,delivered);
        var details=new Document(1,"fixture recipient="+actor+";session="+session+";slot=0;staged="+Base64.getEncoder().encodeToString(delivered.serializeAsBytes()));
        State before=new State(new Tower(towerId,FixtureDelivery.VARIANT,0,100,Form.PORTABLE,0,0,null,null,false,false,0,0),null,List.of()),after=new State(new Tower(towerId,FixtureDelivery.VARIANT,0,100,Form.PORTABLE,1,0,null,null,false,false,0,0),null,List.of());
        Operation op=new Operation(new Intent(operation,towerId,0,0,0,"fixture-item-delivery",after,RecoveryEnvelope.fixture("fixture-item-delivery",anchor,details,operation)),before,Phase.EFFECTS_APPLIED,1,"staged");
        var port=port();Inspection observed=inspect(port,op);assertEquals(Physical.AFTER,observed.observation().physical());assertTrue(port.publish(observed,new TowerSnapshot(after,2,null)));assertFalse(delivered.values.containsKey(PaperItemIdentity.DELIVERY));assertEquals(1,mutations);assertEquals(0,admitted);
    }
    @Test void typedTimedExitStillHasNoBuiltInPaperStructureObservation(){
        var base=new Position(worldId,4,70,4,"north");
        var tower=new Tower(towerId,"fixture",25,100,Form.EXPANDED,2,1,actor,base,false,true,300,400);
        var compact=new Tower(towerId,"fixture",25,100,Form.COMPACT,2,2,null,base,false,true,300,400);
        var component=new Component("base",ComponentKind.BLOCK,worldId,4,70,4,"minecraft:air","minecraft:barrier",null,null);
        var before=new State(tower,null,List.of(component));
        var after=new State(compact,null,List.of(component));
        var evidence=RecoveryEnvelope.timedStructure(STRUCTURE_EXIT_COMPACT,anchor,new Document(1,"Fixture"),60_000);
        var op=new Operation(new Intent(operation,towerId,0,2,1,STRUCTURE_EXIT_COMPACT,after,evidence),before,Phase.INTENT,0,"Fixture");
        var inspection=inspect(port(),op);
        assertTrue(RecoveryEnvelope.supports(op,inspection.context()));
        assertEquals(Physical.UNSUPPORTED,inspection.observation().physical());
        assertEquals(0,mutations);
        assertEquals(0,admitted);
    }
    static final class Stack extends ItemStack {
        final Map<NamespacedKey,Object> values=new HashMap<>();int amount=1,maximum;boolean fire;
        final PersistentDataContainer data=proxy(PersistentDataContainer.class,(m,a)->switch(m.getName()){case "has"->values.containsKey(a[0]);case "get"->values.get(a[0]);case "set"->{values.put((NamespacedKey)a[0],a[2]);yield null;}case "remove"->{values.remove(a[0]);yield null;}default->throw unsupported(m);});
        final ItemMeta meta=proxy(ItemMeta.class,(m,a)->switch(m.getName()){case "getPersistentDataContainer"->data;case "setMaxStackSize"->{maximum=(int)a[0];yield null;}case "setFireResistant"->{fire=(boolean)a[0];yield null;}default->throw unsupported(m);});
        Stack(){super();}@Override public Material getType(){return Material.PAPER;}@Override public int getAmount(){return amount;}@Override public void setAmount(int n){amount=n;}@Override public PersistentDataContainerView getPersistentDataContainer(){return data;}@Override public ItemMeta getItemMeta(){return meta;}@Override public boolean setItemMeta(ItemMeta m){assertSame(meta,m);return true;}
        @Override public Stack clone(){Stack copy=new Stack();copy.values.putAll(values);copy.amount=amount;copy.maximum=maximum;copy.fire=fire;return copy;}
        @Override public byte[] serializeAsBytes(){return (amount+":"+maximum+":"+fire+":"+values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).sorted().toList()).getBytes(StandardCharsets.UTF_8);}
    }
}
