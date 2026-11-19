package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.structure.BlastImpact;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static com.ninja6.infernolantern.recovery.ReconciliationService.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationServiceTest {
    @TempDir Path directory;
    final UUID world=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID();
    final WorldScheduler.Anchor anchor=new WorldScheduler.Anchor(world,0,0);
    final Owner owner=new Owner();
    final AtomicLong exitClock=new AtomicLong(121_000);
    int nextStructureX=1;
    SqliteRepository repository;ReconciliationService service;Path file;
    @BeforeEach void setup()throws Exception{file=directory.resolve("recovery.db");repository=get(SqliteRepository.open(file,512));get(repository.createVariant(new Variant("fixture",1,VariantStatus.ACTIVE,new Document(1,"generic fixture"),0)));service=new ReconciliationService(repository,owner,exitClock::get);}
    @AfterEach void stop()throws Exception{service.close();get(repository.closeAsync());}
    static <T>T get(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
    <T>T drain(CompletableFuture<T> f)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!f.isDone()){owner.run();if(System.nanoTime()>until)fail("No coordinator progress");Thread.yield();}return get(f);}
    void pendingOwner()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(owner.work.isEmpty()){if(System.nanoTime()>until)fail("No owner observation");Thread.sleep(1);}}
    Document inventory(String action){return new Document(1,"action="+action+";actor="+actor+";session="+session+";slot=0;source="+Base64.getEncoder().encodeToString(new byte[]{1,2,3}));}
    Tower tower(UUID id,Form form,long generation,long revision,Position position){return new Tower(id,"fixture",75,100,form,generation,revision,form==Form.EXPANDED?actor:null,position,true,false,300,400);}
    Operation pending(Phase phase)throws Exception{
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));
        State after=new State(tower(id,Form.GROUND,2,0,null),new Ground(UUID.randomUUID(),world,0,0,operation,"active"),List.of());
        Operation op=get(repository.beginNewIntent(new Intent(operation,id,0,1,0,"item-drop",after,RecoveryEnvelope.item("item-drop",anchor,inventory("drop")))));
        if(phase!=Phase.INTENT)op=get(repository.advanceOperation(operation,op.rowVersion(),Phase.INTENT,phase,"synthetic interruption boundary"));return op;
    }
    TowerSnapshot row(Operation op)throws Exception{return get(repository.tower(op.intent().towerId())).orElseThrow();}
    class Port implements WorldPort {
        Physical physical=Physical.AFTER,observed;Coverage coverage=Coverage.MATCHED_COMPLETE;
        boolean valid=true,published=true;int inspections,checks,publications,age=2718;
        public Observation inspect(Operation op,TowerSnapshot row,RecoveryEnvelope.Context context){owner.assertOwner();inspections++;observed=physical;return Observation.of(op,row,coverage,physical,"synthetic matched save-set observation");}
        public boolean revalidate(Inspection i){owner.assertOwner();checks++;return valid&&physical==observed;}
        public boolean publish(Inspection i,TowerSnapshot row){owner.assertOwner();publications++;return published&&valid&&physical==observed;}
    }
    @Test void completeEveryPendingPhaseWithoutReplayingEffectsOrResettingAge()throws Exception{
        for(Phase phase:List.of(Phase.INTENT,Phase.EFFECTS_APPLIED,Phase.RECONCILING)){
            Operation op=pending(phase);Port port=new Port();Result result=drain(service.reconcile(op.intent().id(),port));
            assertEquals(Outcome.COMPLETED,result.outcome());assertEquals(Form.GROUND,row(op).state().tower().form());assertTrue(row(op).mutationReady());
            assertEquals(2718,port.age);assertEquals(1,port.publications);assertEquals(1,port.checks);
            assertEquals(Outcome.TERMINAL,drain(service.reconcile(op.intent().id(),port)).outcome());assertEquals(1,port.inspections);assertEquals(1,port.publications);
        }
    }
    @Test void exactOriginalRepresentationRollsBackWithNoRefundOrGenerationResetGuess()throws Exception{
        Operation op=pending(Phase.EFFECTS_APPLIED);Port port=new Port();port.physical=Physical.BEFORE;
        assertEquals(Outcome.ROLLED_BACK,drain(service.reconcile(op.intent().id(),port)).outcome());
        assertEquals(op.before(),row(op).state());assertEquals(1,port.publications);assertEquals(Phase.ROLLED_BACK,get(repository.operation(op.intent().id())).orElseThrow().phase());
    }
    @Test void independentSaveOrderModelsRequireMatchedCompleteCoverage()throws Exception{
        for(Physical state:List.of(Physical.BEFORE,Physical.AFTER,Physical.MIXED,Physical.ABSENT,Physical.UNKNOWN)){
            Operation op=pending(Phase.INTENT);Port p=new Port();p.physical=state;
            Outcome expected=switch(state){case BEFORE->Outcome.ROLLED_BACK;case AFTER->Outcome.COMPLETED;case UNKNOWN->Outcome.WAIT;default->Outcome.ADMIN_REQUIRED;};
            assertEquals(expected,drain(service.reconcile(op.intent().id(),p)).outcome());
            if(state!=Physical.BEFORE&&state!=Physical.AFTER){assertFalse(row(op).mutationReady());assertEquals(0,p.publications);}
        }
        for(Coverage coverage:List.of(Coverage.INCOMPLETE,Coverage.UNMATCHED)){
            Operation op=pending(Phase.INTENT);Port p=new Port();p.coverage=coverage;
            assertEquals(coverage==Coverage.INCOMPLETE?Outcome.WAIT:Outcome.ADMIN_REQUIRED,drain(service.reconcile(op.intent().id(),p)).outcome());assertFalse(row(op).mutationReady());assertEquals(0,p.publications);
        }
    }
    @Test void unloadedOrChangedOwnerEvidenceNeverMeansLoss()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port();owner.available=false;
        assertEquals(Outcome.WAIT,drain(service.reconcile(op.intent().id(),p)).outcome());assertEquals(0,p.inspections);owner.available=true;
        var future=service.reconcile(op.intent().id(),p);pendingOwner();owner.run();pendingOwner();p.physical=Physical.MIXED;
        assertEquals(Outcome.WAIT,drain(future).outcome());assertEquals(Phase.INTENT,get(repository.operation(op.intent().id())).orElseThrow().phase());assertEquals(0,p.publications);
    }
    @Test void staleObservationCannotResolveAnotherOperation()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port(){public Observation inspect(Operation ignored,TowerSnapshot row,RecoveryEnvelope.Context c){owner.assertOwner();return new Observation(UUID.randomUUID(),0,row.rowVersion(),Coverage.MATCHED_COMPLETE,Physical.AFTER,"wrong operation");}};
        assertEquals(Outcome.STALE,drain(service.reconcile(op.intent().id(),p)).outcome());assertFalse(row(op).mutationReady());
    }
    @Test void closeBeforeRevalidationLeavesPendingSourceUntouched()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port();var future=service.reconcile(op.intent().id(),p);pendingOwner();owner.run();service.close();
        assertTrue(Set.of(Outcome.STALE,Outcome.WAIT).contains(drain(future).outcome()));assertFalse(row(op).mutationReady());assertEquals(0,p.publications);
    }
    @Test void failedPublicationCannotOverwriteANewerTowerRow()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port();p.published=false;var future=service.reconcile(op.intent().id(),p);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(get(repository.operation(op.intent().id())).orElseThrow().pending()){owner.run();if(System.nanoTime()>until)fail("No finalization");Thread.yield();}
        pendingOwner();TowerSnapshot current=row(op);Tower t=current.state().tower();
        get(repository.compareAndSetTower(current.rowVersion(),new Tower(t.id(),t.variantId(),26,t.maximumHp(),t.form(),t.generation(),t.deploymentRevision(),t.controller(),t.position(),t.light(),t.defence(),t.expandAfter(),t.repairAfter())));
        assertEquals(Outcome.STALE,drain(future).outcome());assertEquals(26,row(op).state().tower().hp());assertTrue(row(op).mutationReady());
    }
    @Test void concurrentCoordinatorsHaveExactlyOneFreshPublicationOwner()throws Exception{
        Operation op=pending(Phase.EFFECTS_APPLIED);var other=new ReconciliationService(repository,owner);Port one=new Port(),two=new Port();
        try{var a=service.reconcile(op.intent().id(),one);var b=other.reconcile(op.intent().id(),two);drain(CompletableFuture.allOf(a,b));
            assertEquals(1,one.publications+two.publications);assertEquals(1,List.of(get(a),get(b)).stream().filter(r->r.outcome()==Outcome.COMPLETED).count());assertTrue(row(op).mutationReady());
        }finally{other.close();}
    }
    @Test void freshFinalizationNeverGrantsOwnershipFromAnOldReceipt()throws Exception{
        Operation op=pending(Phase.EFFECTS_APPLIED);Operation done=get(repository.finalizeReconciliation(op.intent().id(),op.rowVersion(),false,"proved after"));
        assertThrows(ExecutionException.class,()->get(repository.finalizeReconciliation(op.intent().id(),done.rowVersion(),false,"repeat must reject")));
        assertEquals(done,get(repository.completeOperation(op.intent().id(),0,"existing low-level idempotent receipt")));
    }
    @Test void publicationFailureCreatesSeparateFrozenReviewAndTerminalNeverReactivates()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port();p.published=false;
        assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(op.intent().id(),p)).outcome());TowerSnapshot frozen=row(op);
        assertEquals(Form.GROUND,frozen.state().tower().form());assertFalse(frozen.mutationReady());
        Operation review=get(repository.operation(frozen.activeOperation())).orElseThrow();assertEquals("reconciliation-review",review.intent().kind());assertTrue(review.intent().effects().content().contains(op.intent().id().toString()));assertEquals(Phase.RECONCILING,review.phase());
        p.published=true;assertEquals(Outcome.TERMINAL,drain(service.reconcile(op.intent().id(),p)).outcome());assertEquals(1,p.publications);
        assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(review.intent().id(),p)).outcome());assertEquals(1,p.publications);
    }
    @Test void databaseFailurePreservesIntentAndRetryReobservesInsteadOfReplaying()throws Exception{
        Operation op=pending(Phase.INTENT);Port p=new Port();
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){s.execute("CREATE TRIGGER fail_finish BEFORE UPDATE OF form ON towers BEGIN SELECT RAISE(ABORT,'recovery fault'); END");}
        assertEquals(Outcome.UNAVAILABLE,drain(service.reconcile(op.intent().id(),p)).outcome());assertEquals(0,p.publications);assertFalse(row(op).mutationReady());
        service.close();get(repository.closeAsync());
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){s.execute("DROP TRIGGER fail_finish");}
        repository=get(SqliteRepository.open(file,512));service=new ReconciliationService(repository,owner,exitClock::get);
        assertEquals(Outcome.COMPLETED,drain(service.reconcile(op.intent().id(),p)).outcome());assertEquals(2,p.inspections);assertEquals(1,p.publications);
    }
    @Test void legacyAndAuditedExternalRemovalEvidenceRemainAdministrative()throws Exception{
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));
        State ground=new State(tower(id,Form.GROUND,2,0,null),new Ground(UUID.randomUUID(),world,0,0,operation,"active"),List.of());
        Operation raw=get(repository.beginNewIntent(new Intent(operation,id,0,1,0,"item-drop",ground,new Document(1,"old opaque receipt"))));Port p=new Port();
        assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(operation,p)).outcome());assertEquals(0,p.inspections);
        get(repository.advanceOperation(operation,raw.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"fixture setup"));get(repository.completeOperation(operation,1,"fixture setup"));
        var lifecycle=new ItemLifecycle(repository,owner);var request=new ItemLifecycle.Request(UUID.randomUUID(),ItemLifecycle.Action.LOSS,ItemLifecycle.token(ground.tower()),1,ground.ground().entity(),anchor,null,new Document(1,"external-removal-OUT_OF_WORLD"));
        try{assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(lifecycle.unexpectedRemoval(request)).outcome());assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(request.operation(),p)).outcome());assertEquals(0,p.inspections);}finally{lifecycle.close();}
    }
    @Test void currentItemProducerEmitsUsableTypedSourceRecipe()throws Exception{
        UUID id=UUID.randomUUID();get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));UUID op=UUID.randomUUID();var lifecycle=new ItemLifecycle(repository,owner);
        var request=new ItemLifecycle.Request(op,ItemLifecycle.Action.DROP,new ItemToken(1,"fixture",id,1),1,UUID.randomUUID(),anchor,null,inventory("drop"));
        try{var f=lifecycle.execute(request,new ItemLifecycle.Effects(){public boolean apply(ItemLifecycle.Claim c){owner.assertOwner();return false;}public boolean verify(ItemLifecycle.Claim c){fail("not applied");return false;}});
            drain(f);Operation pending=get(repository.operation(op)).orElseThrow();var context=RecoveryEnvelope.decode(pending.intent().effects()).orElseThrow();assertEquals(RecoveryEnvelope.Profile.INVENTORY,context.profile());assertEquals(actor,context.inventory().actor());assertTrue(RecoveryEnvelope.supports(pending,context));
            assertEquals(Outcome.COMPLETED,drain(service.reconcile(op,new Port())).outcome());
        }finally{lifecycle.close();}
    }
    @Test void exactLegacyTerminalItemReceiptRemainsIdempotentWithoutRecoveryAuthority()throws Exception{
        UUID id=UUID.randomUUID(),op=UUID.randomUUID(),entity=UUID.randomUUID();get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));
        Document raw=new Document(1,"legacy custom exact source");State after=new State(tower(id,Form.GROUND,2,0,null),new Ground(entity,world,0,0,op,"active"),List.of());
        get(repository.beginIntent(new Intent(op,id,0,1,0,"item-drop",after,raw)));get(repository.advanceOperation(op,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"old callback"));get(repository.completeOperation(op,1,"old receipt"));
        var lifecycle=new ItemLifecycle(repository,owner);try{
            var request=new ItemLifecycle.Request(op,ItemLifecycle.Action.DROP,new ItemToken(1,"fixture",id,1),1,entity,anchor,null,raw);
            assertEquals(ItemLifecycle.Outcome.COMMITTED,drain(lifecycle.execute(request,new ItemLifecycle.Effects(){public boolean apply(ItemLifecycle.Claim c){fail("Legacy receipt replayed physical effects");return false;}public boolean verify(ItemLifecycle.Claim c){fail("Legacy receipt replayed verification");return false;}})).outcome());
        }finally{lifecycle.close();}
    }
    @Test void structureReservationsSurviveUnknownAndReleaseOnlyAfterProvedOriginal()throws Exception{
        UUID id=UUID.randomUUID(),place=UUID.randomUUID();get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));Position base=new Position(world,4,70,4,"north");
        Component bottom=new Component("base",ComponentKind.BLOCK,world,4,70,4,"minecraft:air","minecraft:barrier",null,null);
        State compact=new State(tower(id,Form.COMPACT,2,1,base),null,List.of(bottom));Operation initial=get(repository.beginNewIntent(new Intent(place,id,0,1,0,"setup-place",compact,new Document(1,"test setup"))));get(repository.advanceOperation(place,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"test setup"));get(repository.completeOperation(place,1,"test setup"));
        UUID expand=UUID.randomUUID();State expanded=new State(tower(id,Form.EXPANDED,2,2,base),null,List.of(bottom,new Component("upper",ComponentKind.BLOCK,world,4,71,4,"minecraft:air","minecraft:barrier",null,null),new Component("clearance",ComponentKind.BLOCK,world,4,72,4,"minecraft:air","minecraft:air",null,null)));
        Operation pending=get(repository.beginNewIntent(new Intent(expand,id,2,2,1,"structure-expand",expanded,RecoveryEnvelope.structure("structure-expand",anchor,new Document(1,"complete synthetic structure evidence"),null))));
        Port p=new Port();p.coverage=Coverage.INCOMPLETE;assertEquals(Outcome.WAIT,drain(service.reconcile(expand,p)).outcome());assertEquals(3,get(repository.structureCells(id)).size());
        service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=new ReconciliationService(repository,owner,exitClock::get);assertEquals(3,get(repository.structureCells(id)).size());
        p.coverage=Coverage.MATCHED_COMPLETE;p.physical=Physical.BEFORE;assertEquals(Outcome.ROLLED_BACK,drain(service.reconcile(expand,p)).outcome());assertEquals(1,get(repository.structureCells(id)).size());assertEquals(compact,get(repository.tower(id)).orElseThrow().state());
    }
    private Operation timedExit(String kind,Phase phase,boolean typed,long duration)throws Exception{
        UUID id=UUID.randomUUID(),setup=UUID.randomUUID(),exit=UUID.randomUUID();
        get(repository.createTower(tower(id,Form.PORTABLE,1,0,null)));
        int x=nextStructureX++;
        var p=new Position(world,x,70,4,"north");
        var base=new Component("base",ComponentKind.BLOCK,world,x,70,4,"minecraft:air","minecraft:barrier",null,null);
        var upper=new Component("upper",ComponentKind.BLOCK,world,x,71,4,"minecraft:air","minecraft:barrier",null,null);
        State expanded=new State(tower(id,Form.EXPANDED,2,1,p),null,List.of(base,upper));
        get(repository.beginNewIntent(new Intent(setup,id,0,1,0,"setup-structure",expanded,new Document(1,"Fixture setup"))));
        get(repository.advanceOperation(setup,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"Fixture setup"));
        get(repository.completeOperation(setup,1,"Fixture setup"));

        boolean blast=kind.equals(STRUCTURE_EXIT_BLAST);
        BlastImpact impact=blast?BlastImpact.respectingProtection(UUID.randomUUID(),world,Set.of("base"),50,.5,true,1_000,500):null;
        if(blast)exit=impact.operation(id);
        State after;
        if(blast){
            var damaged=new Tower(id,"fixture",50,100,Form.GROUND,3,2,null,null,true,false,300,1500);
            after=new State(damaged,new Ground(UUID.randomUUID(),world,0,0,exit,"active"),List.of());
        }else if(kind.equals(STRUCTURE_EXIT_RECOVER)){
            after=new State(tower(id,Form.GROUND,3,2,null),new Ground(UUID.randomUUID(),world,0,0,exit,"active"),List.of());
        }else after=new State(tower(id,Form.COMPACT,2,2,p),null,List.of(base));
        var evidence=new Document(1,blast?"fixture request\n"+impact.encode()+"\nSynthetic exact blast effects":"Synthetic exact structure effects");
        var recipe=typed?RecoveryEnvelope.timedStructure(kind,anchor,evidence,duration)
                :RecoveryEnvelope.structure(kind,anchor,evidence,null);
        Operation op=get(repository.beginNewIntent(new Intent(exit,id,2,2,1,kind,after,recipe)));
        return phase==Phase.INTENT?op:get(repository.advanceOperation(exit,0,Phase.INTENT,phase,"Synthetic interruption"));
    }

    @Test void trustedTimedCompactionAndRecoveryRestoreExactBefore() throws Exception {
        for(String kind:List.of(STRUCTURE_EXIT_COMPACT,STRUCTURE_EXIT_RECOVER,STRUCTURE_EXIT_BLAST)){
            Operation op=timedExit(kind,Phase.EFFECTS_APPLIED,true,60_000);
            var recipe=RecoveryEnvelope.decode(op.intent().effects()).orElseThrow();
            assertEquals(RecoveryEnvelope.Profile.STRUCTURE,recipe.profile());
            assertEquals(60_000L,recipe.exitCooldownMillis().longValue());
            assertTrue(RecoveryEnvelope.supports(op,recipe));
            Port port=new Port();
            port.physical=Physical.BEFORE;
            assertEquals(Outcome.ROLLED_BACK,drain(service.reconcile(op.intent().id(),port)).outcome());
            assertEquals(op.before(),row(op).state());
            assertEquals(300,row(op).state().tower().expandAfter());
            assertEquals(2,get(repository.structureCells(op.intent().towerId())).size());
            assertEquals(1,port.publications);
        }
    }

    @Test void trustedTimedAfterUsesOriginalDurationAndOnlyOneFreshPublication() throws Exception {
        for(String kind:List.of(STRUCTURE_EXIT_COMPACT,STRUCTURE_EXIT_RECOVER,STRUCTURE_EXIT_BLAST)){
            exitClock.set(121_000);
            Operation op=timedExit(kind,Phase.RECONCILING,true,60_000);
            Port port=new Port(){
                @Override public boolean publish(Inspection i,TowerSnapshot resolved){
                    assertEquals(300,i.operation().intent().after().tower().expandAfter());
                    assertEquals(181_000,resolved.state().tower().expandAfter());
                    assertEquals(STRUCTURE_EXIT_BLAST.equals(kind)?50:75,resolved.state().tower().hp());
                    assertEquals(STRUCTURE_EXIT_BLAST.equals(kind)?1500:400,resolved.state().tower().repairAfter());
                    assertEquals(op.intent().after().ground(),resolved.state().ground());
                    assertEquals(op.intent().after().components(),resolved.state().components());
                    return super.publish(i,resolved);
                }
            };
            assertEquals(Outcome.COMPLETED,drain(service.reconcile(op.intent().id(),port)).outcome());
            Operation receipt=get(repository.operation(op.intent().id())).orElseThrow();
            assertEquals(row(op).state(),receipt.intent().after());
            assertEquals(181_000,receipt.intent().after().tower().expandAfter());
            assertEquals(STRUCTURE_EXIT_COMPACT.equals(kind)?1:0,get(repository.structureCells(op.intent().towerId())).size());
            exitClock.set(400_000);
            assertEquals(Outcome.TERMINAL,drain(service.reconcile(op.intent().id(),port)).outcome());
            assertEquals(receipt,get(repository.operation(op.intent().id())).orElseThrow());
            assertEquals(1,port.publications);
        }
    }

    @Test void timedAfterFailureReobservesAndKeepsFullCooldownAfterReopen() throws Exception {
        var op=timedExit(STRUCTURE_EXIT_RECOVER,Phase.INTENT,true,60_000);
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){
            s.execute("CREATE TRIGGER fail_timed_exit BEFORE UPDATE OF form ON towers BEGIN SELECT RAISE(ABORT,'fixture fault'); END");
        }
        var port=new Port();
        assertEquals(Outcome.UNAVAILABLE,drain(service.reconcile(op.intent().id(),port)).outcome());
        assertEquals(300,get(repository.operation(op.intent().id())).orElseThrow().intent().after().tower().expandAfter());
        assertEquals(0,port.publications);
        assertFalse(row(op).mutationReady());
        service.close();get(repository.closeAsync());
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){s.execute("DROP TRIGGER fail_timed_exit");}
        repository=get(SqliteRepository.open(file,512));service=new ReconciliationService(repository,owner,exitClock::get);
        exitClock.set(240_000);
        assertEquals(Outcome.COMPLETED,drain(service.reconcile(op.intent().id(),port)).outcome());
        assertEquals(300_000,row(op).state().tower().expandAfter());
        assertEquals(2,port.inspections);
        assertEquals(1,port.publications);
    }

    @Test void timedUnknownUnmatchedAndInterimEvidenceRemainPending() throws Exception {
        var timed=timedExit(STRUCTURE_EXIT_COMPACT,Phase.INTENT,true,60_000);
        var port=new Port();
        port.physical=Physical.UNKNOWN;
        assertEquals(Outcome.WAIT,drain(service.reconcile(timed.intent().id(),port)).outcome());
        port.physical=Physical.AFTER;
        port.coverage=Coverage.UNMATCHED;
        assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(timed.intent().id(),port)).outcome());
        assertEquals(0,port.publications);
        assertEquals(timed,get(repository.operation(timed.intent().id())).orElseThrow());

        var interim=timedExit(STRUCTURE_EXIT_RECOVER,Phase.EFFECTS_APPLIED,false,60_000);
        port.coverage=Coverage.MATCHED_COMPLETE;
        assertEquals(Outcome.ADMIN_REQUIRED,drain(service.reconcile(interim.intent().id(),port)).outcome());
        assertEquals(2,port.inspections);
        assertEquals(0,port.publications);
        assertFalse(row(interim).mutationReady());
        assertThrows(ExecutionException.class,()->get(repository.finalizeTimedExitReconciliation(interim.intent().id(),
                interim.rowVersion(),"Invalid interim evidence",exitClock::get)));
    }
    static final class Owner implements WorldScheduler {
        final Queue<Runnable> work=new ConcurrentLinkedQueue<>();boolean available=true,running;
        public <T>CompletableFuture<T> supply(Anchor anchor,Supplier<T> action){var f=new CompletableFuture<T>();work.add(()->{if(!available){f.completeExceptionally(new IllegalStateException("chunk unloaded"));return;}running=true;try{f.complete(action.get());}catch(Throwable e){f.completeExceptionally(e);}finally{running=false;}});return f;}
        void run(){Runnable r=work.poll();if(r!=null)r.run();}void assertOwner(){assertTrue(running,"Owner callback required");}
    }
}
