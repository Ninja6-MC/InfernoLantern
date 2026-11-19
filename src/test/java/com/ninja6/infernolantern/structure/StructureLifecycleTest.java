package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.structure.StructureLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureLifecycleTest {
    @TempDir Path directory;
    SqliteRepository repository;
    StructureLifecycle service;
    final UUID id=UUID.randomUUID(),worldId=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID();
    final Position base=new Position(worldId,4,70,4,"north");
    final Owner scheduler=new Owner();
    final Map<Cell,String> blocks=new HashMap<>();
    final Set<UUID> sources=new HashSet<>(),usableDrops=new HashSet<>();
    int physical,activated;
    @BeforeEach void setup()throws Exception{
        repository=await(SqliteRepository.open(directory.resolve("structures.db"),256));
        await(repository.createVariant(new Variant("fixture",1,VariantStatus.ACTIVE,new Document(1,"immutable generic definition"),0)));
        seed(id);service=new StructureLifecycle(repository,scheduler);
    }
    void seed(UUID tower)throws Exception{await(repository.createTower(new Tower(tower,"fixture",75,100,Form.PORTABLE,1,0,null,null,true,false,33,44)));sources.add(tower);}
    @AfterEach void close()throws Exception{service.close();await(repository.closeAsync());}
    static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
    TowerSnapshot row()throws Exception{return await(repository.tower(id)).orElseThrow();}
    Request request(Action action)throws Exception{return request(action,row(),actor,false,1000);}
    Request request(Action action,TowerSnapshot t,UUID who,boolean override,long now){return new Request(UUID.randomUUID(),t.state().tower().id(),t.rowVersion(),t.state().tower().generation(),t.state().tower().deploymentRevision(),who,session,action,base,new Policy(1,50,200,now,override));}
    Result drain(CompletableFuture<Result> f)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!f.isDone()){scheduler.runOne();if(System.nanoTime()>until)fail("Timed out");Thread.yield();}return await(f);
    }
    void pendingOwner()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(scheduler.actions.isEmpty()){if(System.nanoTime()>until)fail("No owner work");Thread.sleep(1);}}
    Result execute(Action action)throws Exception{return drain(service.execute(request(action),new Effects()));}
    Cell cell(int y){return new Cell(worldId,4,y,4);}
    String block(Cell c){return blocks.getOrDefault(c,"minecraft:air");}
    class Effects implements WorldEffects {
        boolean allowed=true,sourceValid=true,allowWater,partialFailure,changeAfterApply,proveRollback=true,activation=true;
        Preparation prepared;TowerSnapshot before;Request r;boolean consumed;
        final Map<Cell,String> captured=new HashMap<>(),writes=new HashMap<>();
        public Preparation prepare(Request request,TowerSnapshot snapshot){
            scheduler.assertOwner();r=request;before=snapshot;if(!allowed||!sourceValid)return null;
            boolean drop=r.action()==Action.BREAK||r.action()==Action.ADMIN_RECOVER;
            List<Component> manifest=new ArrayList<>();int height=r.action()==Action.EXPAND?3:1;
            Map<Cell,Component> old=new HashMap<>();before.state().components().forEach(c->old.put(new Cell(c.world(),c.x(),c.y(),c.z()),c));
            if(!drop)for(int i=0;i<height;i++){
                Cell c=new Cell(worldId,base.x(),base.y()+i,base.z());String state=block(c);Component previous=old.get(c);
                if(previous==null&&!state.equals("minecraft:air")&&!(allowWater&&state.equals("minecraft:water[level=0]")))return null;
                captured.put(c,state);String original=previous==null?state:previous.originalState();
                // The top AIR voxel is clearance, not a replacement, but must still reserve its cell.
                String expected=i==2?original:"minecraft:barrier[waterlogged=false]";
                manifest.add(new Component("cell."+i,ComponentKind.BLOCK,worldId,c.x(),c.y(),c.z(),original,expected,null,null));
            }
            for(Component c:before.state().components())captured.put(new Cell(c.world(),c.x(),c.y(),c.z()),block(new Cell(c.world(),c.x(),c.y(),c.z())));
            Ground g=drop?new Ground(UUID.randomUUID(),worldId,0,0,r.operation(),"active"):null;
            prepared=new Preparation(manifest,g,new Document(1,"exact synthetic source/session/world/config evidence"));return prepared;
        }
        public boolean validate(Claim claim){scheduler.assertOwner();return allowed&&sourceValid;}
        public boolean apply(Claim claim){
            scheduler.assertOwner();if(!allowed||!sourceValid)return false;
            for(var entry:captured.entrySet())if(!block(entry.getKey()).equals(entry.getValue()))return false;
            for(Component c:before.state().components())writes.put(new Cell(c.world(),c.x(),c.y(),c.z()),c.originalState());
            for(Component c:prepared.manifest())writes.put(new Cell(c.world(),c.x(),c.y(),c.z()),c.expectedState());
            for(var entry:writes.entrySet()){blocks.put(entry.getKey(),entry.getValue());if(partialFailure)return false;}
            if(r.action()==Action.PLACE){if(!sources.remove(r.tower()))return false;consumed=true;}
            physical++;return true;
        }
        public boolean verify(Claim claim){
            scheduler.assertOwner();if(changeAfterApply){blocks.put(cell(71),"minecraft:stone");return false;}
            return writes.entrySet().stream().allMatch(e->block(e.getKey()).equals(e.getValue()));
        }
        public boolean rollback(Claim claim){
            scheduler.assertOwner();if(!proveRollback||!sourceValid)return false;
            for(var e:writes.entrySet())if(!block(e.getKey()).equals(e.getValue())&&!block(e.getKey()).equals(captured.get(e.getKey())))return false;
            blocks.putAll(captured);if(consumed)sources.add(r.tower());return true;
        }
        public void discard(Request request,Preparation preparation){scheduler.assertOwner();}
        public boolean activate(Claim claim,TowerSnapshot completed){
            scheduler.assertOwner();if(!activation)return false;activated++;
            if(completed.state().ground()!=null)usableDrops.add(completed.state().ground().entity());return true;
        }
    }
    @Test void completeLifecyclePreservesHpTimersAndInvalidatesOldRevision()throws Exception{
        assertEquals(Outcome.COMMITTED,execute(Action.PLACE).outcome());Tower placed=row().state().tower();
        assertEquals(Form.COMPACT,placed.form());assertNull(placed.controller());assertEquals(2,placed.generation());assertEquals(1,placed.deploymentRevision());assertEquals(75,placed.hp());assertEquals(33,placed.expandAfter());assertEquals(44,placed.repairAfter());
        Request stale=request(Action.EXPAND);assertEquals(Outcome.COMMITTED,drain(service.execute(stale,new Effects())).outcome());
        assertEquals(actor,row().state().tower().controller());assertEquals(2,row().state().tower().deploymentRevision());
        assertEquals(3,await(repository.structureCells(id)).size());assertEquals("minecraft:air",block(cell(72)));
        assertEquals(Outcome.COMMITTED,execute(Action.COMPACT).outcome());assertNull(row().state().tower().controller());assertEquals(1200,row().state().tower().expandAfter());
        assertEquals(Outcome.REJECTED,drain(service.execute(request(Action.EXPAND),new Effects())).outcome());
        assertEquals(Outcome.COMMITTED,drain(service.execute(request(Action.EXPAND,row(),actor,false,1300),new Effects())).outcome());
        assertEquals(Outcome.REJECTED,drain(service.execute(new Request(UUID.randomUUID(),id,stale.rowVersion(),stale.generation(),stale.revision(),actor,session,Action.EXPAND,base,stale.policy()),new Effects())).outcome());
        assertEquals(Outcome.REJECTED,execute(Action.BREAK).outcome());
        assertEquals(Outcome.COMMITTED,drain(service.execute(request(Action.ADMIN_RECOVER,row(),actor,true,1400),new Effects())).outcome());
        Tower recovered=row().state().tower();assertEquals(Form.GROUND,recovered.form());assertEquals(75,recovered.hp());assertEquals(1600,recovered.expandAfter());assertEquals(44,recovered.repairAfter());assertEquals(1,usableDrops.size());assertTrue(await(repository.structureCells(id)).isEmpty());
        assertEquals("minecraft:air",block(cell(70)));assertEquals("minecraft:air",block(cell(71)));
    }
    @Test void compactBreakPreservesTimersAndProducesOnlyGroundForm()throws Exception{
        execute(Action.PLACE);Request breaking=request(Action.BREAK);Effects effects=new Effects();assertEquals(Outcome.COMMITTED,drain(service.execute(breaking,effects)).outcome());
        assertEquals(33,row().state().tower().expandAfter());assertEquals(44,row().state().tower().repairAfter());assertEquals(3,row().state().tower().generation());assertFalse(sources.contains(id));
        int calls=physical;assertEquals(Outcome.COMMITTED,drain(service.execute(breaking,new Effects())).outcome());assertEquals(calls,physical);assertEquals(1,usableDrops.size());
    }
    @Test void zeroDurationExitClearsDeadlineAndAClockChangeCannotReenableIt()throws Exception{
        execute(Action.PLACE);execute(Action.EXPAND);
        TowerSnapshot expanded=row();Tower t=expanded.state().tower();
        Request compact=new Request(UUID.randomUUID(),id,expanded.rowVersion(),t.generation(),t.deploymentRevision(),
                actor,session,Action.COMPACT,base,new Policy(1,50,0,1000,false));
        assertEquals(Outcome.COMMITTED,drain(service.execute(compact,new Effects())).outcome());
        assertEquals(Form.COMPACT,row().state().tower().form());
        assertEquals(0,row().state().tower().expandAfter());
        assertEquals(44,row().state().tower().repairAfter());
        assertEquals(Outcome.COMMITTED,drain(service.execute(request(Action.EXPAND,row(),actor,false,500),new Effects())).outcome());
    }
    @Test void controllerAndExplicitOverrideAreNotPermanentOwnership()throws Exception{
        execute(Action.PLACE);execute(Action.EXPAND);UUID other=UUID.randomUUID();
        assertEquals(Outcome.REJECTED,drain(service.execute(request(Action.COMPACT,row(),other,false,1000),new Effects())).outcome());
        assertEquals(Outcome.REJECTED,drain(service.execute(request(Action.ADMIN_RECOVER,row(),other,false,1000),new Effects())).outcome());
        assertEquals(Outcome.COMMITTED,drain(service.execute(request(Action.COMPACT,row(),other,true,1000),new Effects())).outcome());assertNull(row().state().tower().controller());
    }
    @Test void hpThresholdStillAppliesWithExplicitOverride()throws Exception{
        Tower t=row().state().tower();await(repository.compareAndSetTower(0,new Tower(t.id(),t.variantId(),10,100,t.form(),t.generation(),t.deploymentRevision(),null,null,t.light(),t.defence(),t.expandAfter(),t.repairAfter())));
        execute(Action.PLACE);assertEquals(Outcome.REJECTED,drain(service.execute(request(Action.EXPAND,row(),actor,true,1000),new Effects())).outcome());assertEquals(10,row().state().tower().hp());
    }
    @Test void solidAndUnsupportedFluidObstructionsPreserveItemAndUnrelatedBlocks()throws Exception{
        blocks.put(cell(70),"minecraft:stone");assertEquals(Outcome.REJECTED,execute(Action.PLACE).outcome());assertTrue(sources.contains(id));assertEquals("minecraft:stone",block(cell(70)));
        blocks.put(cell(70),"minecraft:lava[level=0]");assertEquals(Outcome.REJECTED,execute(Action.PLACE).outcome());assertTrue(await(repository.pendingOperations(null,10)).isEmpty());
    }
    @Test void supportedFluidOriginalIsRestoredAndExpansionRejectsUpperObstruction()throws Exception{
        blocks.put(cell(70),"minecraft:water[level=0]");Effects placement=new Effects();placement.allowWater=true;assertEquals(Outcome.COMMITTED,drain(service.execute(request(Action.PLACE),placement)).outcome());
        blocks.put(cell(72),"minecraft:stone");assertEquals(Outcome.REJECTED,execute(Action.EXPAND).outcome());assertEquals(Form.COMPACT,row().state().tower().form());
        assertEquals(Outcome.COMMITTED,execute(Action.BREAK).outcome());assertEquals("minecraft:water[level=0]",block(cell(70)));assertEquals("minecraft:stone",block(cell(72)));
    }
    @Test void overlappingTowerPlacementHasOneWinnerAndRejectedSourceRemains()throws Exception{
        UUID other=UUID.randomUUID();seed(other);var one=service.execute(request(Action.PLACE),new Effects());
        var anotherRow=await(repository.tower(other)).orElseThrow();var two=service.execute(request(Action.PLACE,anotherRow,actor,false,1000),new Effects());
        Result a=drain(one),b=drain(two);assertEquals(1,List.of(a,b).stream().filter(r->r.outcome()==Outcome.COMMITTED).count());
        assertEquals(1,sources.size());assertEquals(1,physical);
    }
    @Test void changedPolicyAfterIntentRollsBackOnlyWithProof()throws Exception{
        Request r=request(Action.PLACE);Effects effects=new Effects();var future=service.execute(r,effects);pendingOwner();scheduler.runOne();pendingOwner();effects.allowed=false;
        assertEquals(Outcome.ROLLED_BACK,drain(future).outcome());assertTrue(sources.contains(id));assertTrue(row().mutationReady());assertTrue(await(repository.structureCells(id)).isEmpty());
    }
    @Test void partialEffectsCompensateButExternalEditsFreezeWithoutOverwrite()throws Exception{
        execute(Action.PLACE);Effects partial=new Effects();partial.partialFailure=true;
        assertEquals(Outcome.ROLLED_BACK,drain(service.execute(request(Action.EXPAND),partial)).outcome());assertEquals(Form.COMPACT,row().state().tower().form());assertEquals("minecraft:air",block(cell(71)));
        Effects edited=new Effects();edited.changeAfterApply=true;
        assertEquals(Outcome.QUARANTINED,drain(service.execute(request(Action.EXPAND),edited)).outcome());assertEquals("minecraft:stone",block(cell(71)));assertFalse(row().mutationReady());assertEquals(3,await(repository.structureCells(id)).size());
    }
    @Test void unloadOrReconnectRetainsQuarantineAndReservations()throws Exception{
        Effects effects=new Effects();Request r=request(Action.PLACE);var future=service.execute(r,effects);pendingOwner();scheduler.runOne();pendingOwner();scheduler.available=false;
        assertEquals(Outcome.QUARANTINED,drain(future).outcome());assertTrue(sources.contains(id));assertFalse(row().mutationReady());assertEquals(1,await(repository.structureCells(id)).size());
    }
    @Test void failedActivationFreezesNewReviewWithoutRepeatingGroundEffects()throws Exception{
        execute(Action.PLACE);Effects effects=new Effects();effects.activation=false;Request r=request(Action.BREAK);
        assertEquals(Outcome.QUARANTINED,drain(service.execute(r,effects)).outcome());assertEquals(Form.GROUND,row().state().tower().form());assertFalse(row().mutationReady());assertTrue(usableDrops.isEmpty());
        assertEquals(Outcome.QUARANTINED,drain(service.execute(r,new Effects())).outcome());
        assertEquals("structure-activation-review",await(repository.pendingOperations(null,10)).getFirst().intent().kind());
    }
    @Test void sqlCompletionFailureKeepsReservationAndNeverReplaysEffects()throws Exception{
        Request r=request(Action.PLACE);
        try(var c=SqliteTestConnections.open(directory.resolve("structures.db"));var s=c.createStatement()){s.execute("CREATE TRIGGER fail_completion BEFORE UPDATE OF form ON towers BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");}
        assertEquals(Outcome.QUARANTINED,drain(service.execute(r,new Effects())).outcome());assertEquals(1,physical);assertEquals(1,await(repository.structureCells(id)).size());
        assertEquals(Outcome.QUARANTINED,drain(service.execute(r,new Effects())).outcome());assertEquals(1,physical);
    }
    @Test void operationReceiptCannotBeReusedByAnotherActorOrPolicy()throws Exception{
        Request r=request(Action.PLACE);assertEquals(Outcome.COMMITTED,drain(service.execute(r,new Effects())).outcome());
        Request forged=new Request(r.operation(),r.tower(),r.rowVersion(),r.generation(),r.revision(),UUID.randomUUID(),r.session(),r.action(),r.base(),r.policy());
        assertEquals(Outcome.QUARANTINED,drain(service.execute(forged,new Effects())).outcome());assertEquals(1,physical);
    }
    @Test void retiredVariantBlocksExpansionButStillPermitsCompactionAndRecovery()throws Exception{
        execute(Action.PLACE);execute(Action.EXPAND);
        await(repository.compareAndSetVariant(0,new Variant("fixture",1,VariantStatus.RETIRED,new Document(1,"immutable generic definition"),1)));
        assertEquals(Outcome.COMMITTED,execute(Action.COMPACT).outcome());
        assertEquals(Outcome.ROLLED_BACK,drain(service.execute(request(Action.EXPAND,row(),actor,true,1500),new Effects())).outcome());
        assertEquals(Form.COMPACT,row().state().tower().form());assertEquals(Outcome.COMMITTED,execute(Action.BREAK).outcome());
    }
    @Test void repeatedOperationAcrossServiceInstancesHasOnlyOnePhysicalOwner()throws Exception{
        var otherService=new StructureLifecycle(repository,scheduler);Request r=request(Action.PLACE);
        try {
            var one=service.execute(r,new Effects());var two=otherService.execute(r,new Effects());
            drain(one);drain(two);assertEquals(1,physical);assertEquals(1,activated);assertTrue(row().mutationReady());
        } finally {otherService.close();}
    }
    @Test void stagedGroundIdentityIsPersistedBeforeStructureRemovalAndActivatedOnlyAfterCommit()throws Exception{
        execute(Action.PLACE);Request r=request(Action.BREAK);Effects effects=new Effects();var future=service.execute(r,effects);
        pendingOwner();scheduler.runOne();pendingOwner();
        assertNotNull(effects.prepared.stagedGround());assertTrue(usableDrops.isEmpty());assertEquals("minecraft:barrier[waterlogged=false]",block(cell(70)));
        Operation intent=await(repository.operation(r.operation())).orElseThrow();assertEquals(Phase.INTENT,intent.phase());
        assertEquals(effects.prepared.stagedGround().entity(),intent.intent().after().ground().entity());
        assertEquals(Outcome.COMMITTED,drain(future).outcome());assertEquals(Set.of(effects.prepared.stagedGround().entity()),usableDrops);
    }
    @Test void layoutsRequireAllClearanceVoxelsAndBoundVisuals(){
        var baseVoxel=new PaperStructureEffects.Voxel(0,0,0,"minecraft:barrier");
        assertThrows(IllegalArgumentException.class,()->new PaperStructureEffects.Layout(List.of(baseVoxel,new PaperStructureEffects.Voxel(0,2,0,null)),List.of()));
        assertThrows(IllegalArgumentException.class,()->new PaperStructureEffects.Layout(List.of(baseVoxel),List.of(new PaperStructureEffects.Visual(0,1,0,new ResourceBytes(new byte[0])))));
        assertEquals(3,new PaperStructureEffects.Layout(List.of(baseVoxel,new PaperStructureEffects.Voxel(0,1,0,null),new PaperStructureEffects.Voxel(0,2,0,null)),List.of()).voxels().size());
    }
    static final class Owner implements WorldScheduler {
        final Queue<Runnable> actions=new ConcurrentLinkedQueue<>();boolean owner,available=true;
        public <T>CompletableFuture<T> supply(Anchor anchor,Supplier<T> action){var f=new CompletableFuture<T>();actions.add(()->{if(!available){f.completeExceptionally(new IllegalStateException("Unloaded"));return;}try{owner=true;f.complete(action.get());}catch(Throwable e){f.completeExceptionally(e);}finally{owner=false;}});return f;}
        void runOne(){Runnable task=actions.poll();if(task!=null)task.run();}
        void assertOwner(){assertTrue(owner,"World effect escaped owner scheduler");}
    }
}
