package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class FixtureDeliveryTest {
    @TempDir Path directory;
    SqliteRepository repository;
    ItemPrototypeStore store;
    FixtureDelivery delivery;
    final ItemLifecycleTest.OwnerScheduler owner = new ItemLifecycleTest.OwnerScheduler();
    final UUID tower=new UUID(0,100),operation=new UUID(0,101);
    FixtureDelivery.Request request(){return new FixtureDelivery.Request(tower,operation,new WorldScheduler.Anchor(new UUID(0,1),0,0),new Document(1,"generic staged bytes and exact session/empty slot"));}
    static <T>T get(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
    @BeforeEach void start()throws Exception{store=new ItemPrototypeStore();repository=get(store.open(directory.resolve("items.db"),true,GroundPolicy.DEFAULT)).repository();delivery=new FixtureDelivery(repository,owner);}
    @AfterEach void stop()throws Exception{delivery.close();get(store.closeAsync());}
    void pending()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(owner.actions.isEmpty()){if(System.nanoTime()>end)fail("Owner callback not queued");Thread.sleep(1);}}
    ItemLifecycle.Result drain(CompletableFuture<ItemLifecycle.Result> f)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!f.isDone()){owner.runOne();if(System.nanoTime()>end)fail("Result not completed");}return get(f);}
    static final class Source implements FixtureDelivery.Effects{
        boolean session=true,slotEmpty=true,staged,activated,activate=true;
        int stages,activations;
        public boolean stage(){stages++;if(!session||!slotEmpty)return false;staged=true;slotEmpty=false;return true;}
        public boolean verify(){return session&&staged;}
        public boolean activate(){activations++;if(!verify()||!activate)return false;activated=true;return true;}
    }
    @Test void rowAndIntentPrecedeStagingAndCompletionPrecedesActivation()throws Exception{
        Source source=new Source();var future=delivery.issue(request(),source);pending();
        var initial=get(repository.tower(tower)).orElseThrow();assertFalse(initial.mutationReady());assertEquals(0,initial.state().tower().hp());assertEquals(0,initial.state().tower().generation());assertFalse(source.staged);
        owner.runOne();pending();assertTrue(source.staged);assertFalse(source.activated);owner.runOne();pending();
        var committed=get(repository.tower(tower)).orElseThrow();assertTrue(committed.mutationReady());assertEquals(1,committed.state().tower().generation());assertEquals(Phase.COMPLETE,get(repository.operation(operation)).orElseThrow().phase());assertFalse(source.activated);
        assertEquals(ItemLifecycle.Outcome.COMMITTED,drain(future).outcome());assertTrue(source.activated);assertEquals(1,source.stages);
        assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(delivery.issue(request(),source)).outcome());assertEquals(1,source.stages);
    }
    @Test void changedSessionOrOccupiedSlotDoesNotWriteInventoryAndFreezesIntent()throws Exception{
        Source source=new Source();source.slotEmpty=false;
        assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(delivery.issue(request(),source)).outcome());assertFalse(source.staged);assertFalse(source.activated);
        assertEquals(Phase.RECONCILING,get(repository.operation(operation)).orElseThrow().phase());
    }
    @Test void reconnectBetweenStageAndVerifyKeepsUnavailableEvidence()throws Exception{
        Source source=new Source();var future=delivery.issue(request(),source);pending();owner.runOne();pending();source.session=false;
        assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(future).outcome());assertTrue(source.staged);assertFalse(source.activated);assertFalse(get(repository.tower(tower)).orElseThrow().mutationReady());
    }
    @Test void failedActivationAfterCompletionCreatesSeparateFrozenReview()throws Exception{
        Source source=new Source();source.activate=false;
        assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(delivery.issue(request(),source)).outcome());
        assertEquals(Phase.COMPLETE,get(repository.operation(operation)).orElseThrow().phase());
        var row=get(repository.tower(tower)).orElseThrow();assertFalse(row.mutationReady());assertEquals(1,row.state().tower().generation());
        var pending=get(repository.pendingOperations(null,10));assertEquals(1,pending.size());assertEquals("fixture-activation-review",pending.getFirst().intent().kind());assertEquals(Phase.RECONCILING,pending.getFirst().phase());
    }
    @Test void disabledWorldSchedulerAndShutdownNeverActivateOrRefund()throws Exception{
        Source source=new Source();var future=delivery.issue(request(),source);pending();owner.available=false;delivery.close();
        assertEquals(ItemLifecycle.Outcome.QUARANTINED,drain(future).outcome());assertFalse(source.staged);assertEquals(0,source.activations);
    }
    @Test void pendingStartupIsUnavailableWithCreationDisabled()throws Exception{
        Source source=new Source();source.slotEmpty=false;drain(delivery.issue(request(),source));get(store.closeAsync());
        store=new ItemPrototypeStore();var reopened=get(store.open(directory.resolve("items.db"),false,GroundPolicy.DEFAULT));repository=reopened.repository();assertNotNull(repository);assertFalse(reopened.mutationsReady());
    }
    @Test void disabledFreshStoreDoesNotCreateDatabase()throws Exception{
        var disabled=new ItemPrototypeStore();Path absent=directory.resolve("absent.db");var opened=get(disabled.open(absent,false,GroundPolicy.DEFAULT));assertNull(opened.repository());assertFalse(opened.mutationsReady());assertFalse(Files.exists(absent));get(disabled.closeAsync());
    }
    @Test void closeDuringOpeningDrainsAndRejectsAdmission()throws Exception{
        var closing=new ItemPrototypeStore();var opened=closing.open(directory.resolve("closing.db"),true,GroundPolicy.DEFAULT);var stopped=closing.closeAsync();
        try{get(opened);}catch(ExecutionException expected){assertInstanceOf(IllegalStateException.class,expected.getCause());}
        get(stopped);
        var probe=SqliteRepository.open(directory.resolve("closing.db"),16);var database=get(probe);get(database.closeAsync());
    }
    @Test void closedStoreCannotOpenOrCreateAFile()throws Exception{
        var closed=new ItemPrototypeStore();get(closed.closeAsync());Path absent=directory.resolve("never-open.db");
        assertThrows(ExecutionException.class,()->get(closed.open(absent,true,GroundPolicy.DEFAULT)));assertFalse(Files.exists(absent));
    }
    @Test void invalidDatabaseFailsClosedWithoutOverwrite()throws Exception{
        Path invalid=directory.resolve("invalid.db");byte[] original="invalid database evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(invalid,original);
        var broken=new ItemPrototypeStore();assertThrows(ExecutionException.class,()->get(broken.open(invalid,true,GroundPolicy.DEFAULT)));get(broken.closeAsync());assertArrayEquals(original,Files.readAllBytes(invalid));
    }
}
