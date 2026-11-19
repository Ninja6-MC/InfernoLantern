package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Light;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

class LightReconcilerTest {
    @TempDir Path directory;SqliteRepository repository;CatalogService catalog;LightReconciler lights;Owner owner=new Owner();Path file;FakeWorld world=new FakeWorld();TowerSnapshot row;CatalogService.Snapshot snapshot;
    @BeforeEach void start()throws Exception{file=directory.resolve("light.db");repository=get(SqliteRepository.open(file,512));catalog=new CatalogService(repository,owner,d->{},s->CompletableFuture.completedFuture(null));snapshot=get(catalog.reload(config())).snapshot();row=placed(repository,Form.EXPANDED);lights=new LightReconciler(repository,owner);}
    @AfterEach void stop()throws Exception{lights.close();catalog.close();get(repository.closeAsync());}
    LightReconciler.Request request(){return new LightReconciler.Request(UUID.randomUUID(),row.state().tower().id(),row.rowVersion(),row.state().tower().deploymentRevision(),snapshot);}
    LightReconciler.View view(){return new LightReconciler.View(catalog.current().orElseThrow().revision(),CatalogTestSupport.world(),allowed(),true);}
    PaperLightEffects effects(){return new PaperLightEffects(world.proxy,FakeWorld::data,()->owner.inside,r->view());}
    LightReconciler.Outcome run()throws Exception{var result=owner.drain(lights.reconcile(request(),effects()));row=get(repository.tower(row.state().tower().id())).orElseThrow();return result;}
    void level(int level)throws Exception{var v=variant();v.put("light-level",level);snapshot=get(catalog.reload(config(Map.of("test",v)))).snapshot();}
    @Test void actualPaperAdapterWritesEveryLevelAndCleansUpWithoutResettingPreferences()throws Exception{
        for(int level=0;level<=15;level++){level(level);assertEquals(level==0?LightReconciler.Outcome.NO_CHANGE:LightReconciler.Outcome.COMMITTED,run());assertEquals(level,FakeWorld.level(world.actual));assertTrue(row.state().tower().light());assertTrue(row.state().tower().defence());assertEquals(75,row.state().tower().hp());assertEquals(170,row.state().tower().expandAfter());assertEquals(230,row.state().tower().repairAfter());assertEquals(2,row.state().tower().generation());assertEquals(1,row.state().tower().deploymentRevision());}
        level(0);assertEquals(LightReconciler.Outcome.COMMITTED,run());assertEquals("minecraft:air",world.actual.getAsString());assertTrue(get(repository.pendingOperations(null,10)).isEmpty());assertEquals(3,get(repository.structureCells(row.state().tower().id())).size());
    }
    @Test void sourceWaterAndCanonicalShorthandRestoreExactly()throws Exception{
        Component old=row.state().components().stream().filter(c->c.id().equals("light")).findFirst().orElseThrow();var components=new ArrayList<>(row.state().components());components.replaceAll(c->c.id().equals("light")?new Component(c.id(),c.kind(),c.world(),c.x(),c.y(),c.z(),"minecraft:water","minecraft:water",null,null):c);
        UUID operation=UUID.randomUUID();Operation pending=get(repository.beginNewIntent(new Intent(operation,row.state().tower().id(),row.rowVersion(),2,1,"synthetic-water",new State(row.state().tower(),null,components),new Document(1,"synthetic source-water ownership"))));pending=get(repository.advanceOperation(operation,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"synthetic verified"));get(repository.completeOperation(operation,pending.rowVersion(),"synthetic verified"));row=get(repository.tower(row.state().tower().id())).orElseThrow();world.actual=FakeWorld.data("minecraft:water[level=0]");
        level(7);assertEquals(LightReconciler.Outcome.COMMITTED,run());assertEquals("minecraft:light[level=7,waterlogged=true]",world.actual.getAsString());snapshot=get(catalog.reload(config(Map.of()))).snapshot();assertEquals(LightReconciler.Outcome.COMMITTED,run());assertEquals("minecraft:water[level=0]",world.actual.getAsString());assertTrue(row.state().tower().light());
    }
    @Test void capabilityDisableTurnsOffOwnedLightWithoutChangingPlayerChoice()throws Exception{
        assertEquals(LightReconciler.Outcome.COMMITTED,run());var v=variant();v.put("capabilities",Map.of("light",false));snapshot=get(catalog.reload(config(Map.of("test",v)))).snapshot();assertEquals(LightReconciler.Outcome.COMMITTED,run());assertEquals(0,FakeWorld.level(world.actual));assertTrue(row.state().tower().light());assertTrue(row.state().tower().defence());
    }
    @Test void changedExternalBlockIsNeverOverwrittenAndPendingCellsStayReserved()throws Exception{
        var future=lights.reconcile(request(),effects());owner.await();owner.run();owner.await();owner.run(); // prepare, then apply
        world.actual=FakeWorld.data("minecraft:stone");assertEquals(LightReconciler.Outcome.QUARANTINED,owner.drain(future));assertEquals("minecraft:stone",world.actual.getAsString());row=get(repository.tower(row.state().tower().id())).orElseThrow();assertFalse(row.mutationReady());assertEquals(Phase.RECONCILING,get(repository.operation(row.activeOperation())).orElseThrow().phase());assertEquals(3,get(repository.structureCells(row.state().tower().id())).size());
    }
    @Test void failedPartialWriteCanRestoreOnlyItsExactOwnedState()throws Exception{
        world.failNext=true;assertEquals(LightReconciler.Outcome.ROLLED_BACK,run());assertEquals("minecraft:air",world.actual.getAsString());assertTrue(row.mutationReady());assertTrue(get(repository.pendingOperations(null,10)).isEmpty());assertEquals(2,world.writes);
    }
    @Test void catalogChangeAfterIntentCannotCommitStaleLightingPolicy()throws Exception{
        var future=lights.reconcile(request(),effects());owner.await();owner.run();owner.await();var v=variant();v.put("light-level",4);get(catalog.reload(config(Map.of("test",v))));assertEquals(LightReconciler.Outcome.ROLLED_BACK,owner.drain(future));assertEquals("minecraft:air",world.actual.getAsString());assertTrue(get(repository.pendingOperations(null,10)).isEmpty());
    }
    @Test void sqlCompletionFailureLeavesQuarantineRatherThanClaimingCleanup()throws Exception{
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){s.execute("CREATE TRIGGER fail_light_completion BEFORE UPDATE ON operations WHEN NEW.phase='COMPLETE' AND NEW.kind='catalog-light' BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");}
        assertEquals(LightReconciler.Outcome.QUARANTINED,run());assertEquals(15,FakeWorld.level(world.actual));assertFalse(row.mutationReady());assertEquals(Phase.RECONCILING,get(repository.operation(row.activeOperation())).orElseThrow().phase());
    }
    @Test void unloadedOwnerAndStaleSnapshotsNeverForceLoadOrApply()throws Exception{
        world.loaded=false;assertEquals(LightReconciler.Outcome.REJECTED,run());assertEquals(0,world.writes);world.loaded=true;
        var stale=new LightReconciler.Request(UUID.randomUUID(),row.state().tower().id(),row.rowVersion()+1,1,snapshot);assertEquals(LightReconciler.Outcome.REJECTED,owner.drain(lights.reconcile(stale,effects())));assertEquals(0,world.writes);
        var forged=new CatalogService.Snapshot(snapshot.revision(),Map.of());var wrong=new LightReconciler.Request(UUID.randomUUID(),row.state().tower().id(),row.rowVersion(),1,forged);assertEquals(LightReconciler.Outcome.REJECTED,owner.drain(lights.reconcile(wrong,effects())));assertEquals(0,world.writes);
    }
    @Test void existingReceiptsNeverReplayLightWrites()throws Exception{
        var request=request();assertEquals(LightReconciler.Outcome.COMMITTED,owner.drain(lights.reconcile(request,effects())));int writes=world.writes;assertEquals(LightReconciler.Outcome.REJECTED,owner.drain(lights.reconcile(request,effects())));assertEquals(writes,world.writes);
    }
    @Test void postCompletionExternalEditCreatesReviewWithoutOverwritingIt()throws Exception{
        var future=lights.reconcile(request(),effects());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(get(repository.tower(row.state().tower().id())).orElseThrow().rowVersion()<row.rowVersion()+2){owner.run();if(System.nanoTime()>until)fail("No durable completion");Thread.yield();}
        owner.await();world.actual=FakeWorld.data("minecraft:stone");assertEquals(LightReconciler.Outcome.QUARANTINED,owner.drain(future));assertEquals("minecraft:stone",world.actual.getAsString());TowerSnapshot frozen=get(repository.tower(row.state().tower().id())).orElseThrow();assertFalse(frozen.mutationReady());assertEquals("catalog-light-review",get(repository.operation(frozen.activeOperation())).orElseThrow().intent().kind());
    }
    @Test void postCompletionStaleCheckCannotOverwriteNewerTowerState()throws Exception{
        var future=lights.reconcile(request(),effects());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        TowerSnapshot completed;
        while((completed=get(repository.tower(row.state().tower().id())).orElseThrow()).rowVersion()<row.rowVersion()+2){owner.run();if(System.nanoTime()>until)fail("No durable completion");Thread.yield();}
        // The queued final callback already captured the completed row; it performs read-only verification.
        owner.await();int committedWrites=world.writes;Tower t=completed.state().tower();get(repository.compareAndSetTower(completed.rowVersion(),new Tower(t.id(),t.variantId(),50,t.maximumHp(),t.form(),t.generation(),t.deploymentRevision(),t.controller(),t.position(),t.light(),t.defence(),t.expandAfter(),t.repairAfter())));
        assertEquals(LightReconciler.Outcome.COMMITTED,owner.drain(future));TowerSnapshot latest=get(repository.tower(t.id())).orElseThrow();assertEquals(50,latest.state().tower().hp());assertTrue(latest.mutationReady());assertEquals(committedWrites,world.writes);assertTrue(get(repository.pendingOperations(null,10)).isEmpty());
    }
    @Test void writeAuthorizationIsDistinctFromOperationalDisableAndRecheckedForCompensation()throws Exception{
        var authorized=new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Supplier<PaperLightEffects> port=()->new PaperLightEffects(world.proxy,FakeWorld::data,()->owner.inside,r->new LightReconciler.View(catalog.current().orElseThrow().revision(),CatalogTestSupport.world(),allowed(),authorized.get()));
        assertEquals(LightReconciler.Outcome.REJECTED,owner.drain(lights.reconcile(request(),port.get())));assertEquals(0,world.writes);assertTrue(get(repository.pendingOperations(null,10)).isEmpty());
        authorized.set(true);var future=lights.reconcile(request(),port.get());owner.await();owner.run();owner.await();owner.run();authorized.set(false);
        assertEquals(LightReconciler.Outcome.QUARANTINED,owner.drain(future));assertEquals(1,world.writes);assertEquals(15,FakeWorld.level(world.actual));assertFalse(get(repository.tower(row.state().tower().id())).orElseThrow().mutationReady());
    }
    static final class FakeWorld {
        BlockData actual=data("minecraft:air");boolean loaded=true,failNext;int writes;
        final World proxy;final Block block;
        FakeWorld(){
            WorldBorder border=proxy(WorldBorder.class,(p,m,a)->{if(m.getName().equals("isInside"))return true;return basic(p,m,a);});
            block=proxy(Block.class,(p,m,a)->switch(m.getName()){case "getBlockData"->actual.clone();case "setBlockData"->{actual=((BlockData)a[0]).clone();writes++;if(failNext){failNext=false;throw new IllegalStateException("synthetic write failed after mutation");}yield null;}default->basic(p,m,a);});
            proxy=proxy(World.class,(p,m,a)->switch(m.getName()){case "getUID"->WORLD;case "getMinHeight"->-64;case "getMaxHeight"->320;case "isChunkLoaded"->loaded;case "getWorldBorder"->border;case "getBlockAt"->block;default->basic(p,m,a);});
        }
        static BlockData data(String value){String name=value.contains("[")?value.substring(0,value.indexOf('[')):value;int level=0;boolean waterlogged=value.contains("waterlogged=true");if(value.contains("level=")){String raw=value.substring(value.indexOf("level=")+6).split("[,\\]]")[0];level=Integer.parseInt(raw);}Class<?> type=name.equals("minecraft:light")?Light.class:name.equals("minecraft:water")?Levelled.class:BlockData.class;return (BlockData)Proxy.newProxyInstance(LightReconcilerTest.class.getClassLoader(),new Class<?>[]{type},new Data(name,level,waterlogged));}
        static int level(BlockData value){return value instanceof Light light?light.getLevel():0;}
        static final class Data implements InvocationHandler {
            String name;int level;boolean waterlogged;Data(String n,int l,boolean w){name=n;level=l;waterlogged=w;}
            String canonical(){return name.equals("minecraft:light")?name+"[level="+level+",waterlogged="+waterlogged+"]":name.equals("minecraft:water")?name+"[level="+level+"]":name;}
            public Object invoke(Object p,Method m,Object[] a){return switch(m.getName()){case "getAsString","toString"->canonical();case "clone"->data(canonical());case "getLevel"->level;case "setLevel"->{level=(int)a[0];yield null;}case "isWaterlogged"->waterlogged;case "setWaterlogged"->{waterlogged=(boolean)a[0];yield null;}case "getMaterial"->switch(name){case "minecraft:light"->Material.LIGHT;case "minecraft:water"->Material.WATER;case "minecraft:stone"->Material.STONE;default->Material.AIR;};default->basic(p,m,a);};}
        }
        static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
        static Object basic(Object proxy,Method method,Object[] args){return switch(method.getName()){case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);case "toString"->"strict API double";default->throw new AssertionError("Unexpected API call: "+method.getName());};}
    }
}
