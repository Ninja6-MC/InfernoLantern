package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    @TempDir Path directory;
    final List<SqliteRepository> repositories=new ArrayList<>();
    final UUID towerId=new UUID(0,1),world=new UUID(0,2),actor=new UUID(0,3);
    Path file(){return directory.resolve("repository.db");}
    SqliteRepository open()throws Exception{return open(file(),64);}
    SqliteRepository open(Path file,int capacity)throws Exception{SqliteRepository r=SqliteRepository.open(file,capacity).get(10,TimeUnit.SECONDS);repositories.add(r);return r;}
    @AfterEach void close()throws Exception{for(var r:repositories)r.closeAsync().get(10,TimeUnit.SECONDS);}
    static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(10,TimeUnit.SECONDS);}
    static Throwable failed(CompletableFuture<?> future){return assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS)).getCause();}
    Variant variant(){return new Variant("test",1,VariantStatus.ACTIVE,new Document(1,"{\"maximumHp\":100}"),0);}
    Tower portable(UUID id,double hp){return new Tower(id,"test",hp,100,Form.PORTABLE,1,0,null,null,false,false,17,23);}
    void seed(SqliteRepository r)throws Exception{get(r.createVariant(variant()));get(r.createTower(portable(towerId,50)));}
    Tower withHp(Tower old,double hp){return new Tower(old.id(),old.variantId(),hp,old.maximumHp(),old.form(),old.generation(),old.deploymentRevision(),old.controller(),old.position(),old.light(),old.defence(),old.expandAfter(),old.repairAfter());}
    State compact(){
        Tower t=new Tower(towerId,"test",50,100,Form.COMPACT,2,1,null,new Position(world,4,70,4,"north"),false,false,17,23);
        return new State(t,null,List.of(new Component("base",ComponentKind.BLOCK,world,4,70,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null)));
    }
    Intent intent(UUID id,State after){return new Intent(id,towerId,0,1,0,"place",after,new Document(1,"effect snapshots captured on owner thread"));}
    @Test void schemasAndImmutablePagedReadsRoundTrip()throws Exception{
        var r=open();seed(r);assertEquals("3.54.1",get(r.sqliteVersion()));
        var rows=get(r.towers(null,10));assertEquals(1,rows.size());assertEquals(portable(towerId,50),rows.getFirst().state().tower());
        assertThrows(UnsupportedOperationException.class,()->rows.clear());assertTrue(get(r.towers(towerId,10)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->r.towers(null,257));
        assertThrows(IllegalArgumentException.class,()->r.pendingOperations(null,0));
    }
    @Test void controllerAndHealthInvariantsRejectMalformedInputs(){
        assertThrows(IllegalArgumentException.class,()->portable(towerId,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new Document(1,"broken-\uD800"));
        assertThrows(IllegalArgumentException.class,()->portable(towerId,101));
        assertThrows(IllegalArgumentException.class,()->new Tower(towerId,"test",1,100,Form.EXPANDED,0,1,null,new Position(world,0,0,0,"north"),true,true,0,0));
        assertThrows(IllegalArgumentException.class,()->new State(portable(towerId,1),null,compact().components()));
    }
    @Test void retirementRetainsLastDefinitionAndUsesCas()throws Exception{
        var r=open();get(r.createVariant(variant()));
        Variant retired=new Variant("test",1,VariantStatus.RETIRED,variant().lastValidDefinition(),1);
        get(r.compareAndSetVariant(0,retired));assertEquals(retired,get(r.variant("test")).orElseThrow());
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.compareAndSetVariant(0,retired)));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.compareAndSetVariant(1,new Variant("test",1,VariantStatus.ACTIVE,new Document(1,"changed"),2))));
        get(r.closeAsync());var reopened=open();assertEquals(retired,get(reopened.variant("test")).orElseThrow());
    }
    @Test void concurrentCasHasOneWinnerAndNoLostUpdate()throws Exception{
        var r=open();seed(r);var candidates=new ArrayList<CompletableFuture<TowerSnapshot>>();
        for(int i=0;i<32;i++)candidates.add(r.compareAndSetTower(0,portable(towerId,49)));
        int winners=0;for(var future:candidates){try{get(future);winners++;}catch(ExecutionException expected){assertInstanceOf(RepositoryException.Conflict.class,expected.getCause());}}
        assertEquals(1,winners);var latest=get(r.tower(towerId)).orElseThrow();assertEquals(1,latest.rowVersion());assertEquals(49,latest.state().tower().hp());
    }
    @Test void representationChangesCannotBypassJournal()throws Exception{
        var r=open();seed(r);
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.compareAndSetTower(0,compact().tower())));
        var op=get(r.beginIntent(intent(new UUID(0,9),compact())));
        assertFalse(get(r.tower(towerId)).orElseThrow().mutationReady());
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.compareAndSetTower(1,portable(towerId,49))));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.beginIntent(new Intent(new UUID(0,10),towerId,1,1,0,"place",compact(),new Document(1,"effect")))));
        assertEquals(op,get(r.beginIntent(op.intent())));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.beginIntent(new Intent(op.intent().id(),towerId,0,1,0,"changed",compact(),new Document(1,"effect")))));
    }
    @Test void pendingIntentSurvivesReopenAndCompletionIsIdempotent()throws Exception{
        var r=open();seed(r);Intent intent=intent(new UUID(0,9),compact());get(r.beginIntent(intent));get(r.closeAsync());
        var reopened=open();assertEquals(Phase.INTENT,get(reopened.pendingOperations(null,10)).getFirst().phase());
        assertInstanceOf(RepositoryException.Conflict.class,failed(reopened.completeOperation(intent.id(),0,"not yet verified")));
        var applied=get(reopened.advanceOperation(intent.id(),0,Phase.INTENT,Phase.EFFECTS_APPLIED,"caller verified named effects"));
        var done=get(reopened.completeOperation(intent.id(),applied.rowVersion(),"caller verified exact world state"));
        assertEquals(done,get(reopened.completeOperation(intent.id(),applied.rowVersion(),"retry does not replay effects")));
        var tower=get(reopened.tower(towerId)).orElseThrow();assertEquals(2,tower.rowVersion());assertTrue(tower.mutationReady());assertEquals(compact(),tower.state());assertTrue(get(reopened.pendingOperations(null,10)).isEmpty());
        get(reopened.closeAsync());var again=open();assertEquals(compact(),get(again.tower(towerId)).orElseThrow().state());
    }
    @Test void groundAndEntityManifestsCommitWithTowerAndPersist()throws Exception{
        var r=open();seed(r);UUID opid=new UUID(0,9);
        var groundTower=new Tower(towerId,"test",50,100,Form.GROUND,2,0,null,null,false,false,17,23);
        var state=new State(groundTower,new Ground(new UUID(0,20),world,1,2,opid,"pending"),List.of());
        var op=get(r.beginIntent(intent(opid,state)));op=get(r.advanceOperation(opid,op.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"ground entity verified"));get(r.completeOperation(opid,op.rowVersion(),"proof recorded"));
        assertEquals(state,get(r.tower(towerId)).orElseThrow().state());
        UUID second=new UUID(0,10);
        Tower expanded=new Tower(towerId,"test",40,100,Form.EXPANDED,3,2,actor,new Position(world,4,70,4,"east"),true,true,18,24);
        var components=List.of(new Component("base",ComponentKind.BLOCK,world,4,70,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null),new Component("visual",ComponentKind.ENTITY,world,4,70,4,null,null,new UUID(0,21),"minecraft:armor_stand"));
        State deployed=new State(expanded,null,components);
        op=get(r.beginIntent(new Intent(second,towerId,2,2,0,"deploy",deployed,new Document(1,"captured snapshots"))));op=get(r.advanceOperation(second,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"caller verification"));get(r.completeOperation(second,1,"caller verification"));
        get(r.closeAsync());var reopened=open();assertEquals(deployed,get(reopened.tower(towerId)).orElseThrow().state());
    }
    @Test void reconciliationDoesNotGuessAndRollbackNeedsExplicitPhase()throws Exception{
        var r=open();seed(r);UUID id=new UUID(0,9);get(r.beginIntent(intent(id,compact())));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.resolveRollback(id,0,"unverified")));
        get(r.advanceOperation(id,0,Phase.INTENT,Phase.RECONCILING,"ambiguous inventory/chunk save evidence"));get(r.closeAsync());
        var reopened=open();assertFalse(get(reopened.tower(towerId)).orElseThrow().mutationReady());assertEquals(Phase.RECONCILING,get(reopened.operation(id)).orElseThrow().phase());
        get(reopened.resolveRollback(id,1,"caller separately verified original representation restored"));
        assertEquals(portable(towerId,50),get(reopened.tower(towerId)).orElseThrow().state().tower());
    }
    @Test void failedCompletionRollsBackEntireRepresentationAndJournal()throws Exception{
        var r=open();seed(r);UUID id=new UUID(0,9);
        State invalidVariant=new State(new Tower(towerId,"missing",50,100,Form.COMPACT,2,1,null,new Position(world,4,70,4,"north"),false,false,0,0),null,compact().components());
        // A conflict before commit must leave ownership/version and all data intact.
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.beginIntent(intent(id,invalidVariant))));
        assertEquals(0,get(r.tower(towerId)).orElseThrow().rowVersion());assertTrue(get(r.pendingOperations(null,10)).isEmpty());
        // Trigger a real SQLite constraint error midway through completion, after durable intent.
        get(r.beginIntent(intent(id,compact())));get(r.advanceOperation(id,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"effects verified"));
        try(Connection c=PrivateSqlite.open(file());var s=c.createStatement()){s.execute("CREATE TRIGGER fail_manifest BEFORE INSERT ON manifest_components BEGIN SELECT RAISE(ABORT,'injected write failure'); END");}
        assertInstanceOf(SQLException.class,failed(r.completeOperation(id,1,"caller proof")));
        assertEquals(Form.PORTABLE,get(r.tower(towerId)).orElseThrow().state().tower().form());assertEquals(Phase.EFFECTS_APPLIED,get(r.operation(id)).orElseThrow().phase());
        try(Connection c=PrivateSqlite.open(file());var s=c.createStatement()){s.execute("DROP TRIGGER fail_manifest");}
        get(r.completeOperation(id,1,"retry after storage repaired"));assertEquals(compact(),get(r.tower(towerId)).orElseThrow().state());
    }
    @Test void escrowBytesAreDefensiveAndCannotBypassOwnershipOrSettleEarly()throws Exception{
        var r=open();seed(r);UUID opId=new UUID(0,9);get(r.beginIntent(intent(opId,compact())));
        byte[] bytes={1,2,3};var snapshot=new ResourceBytes(bytes);bytes[0]=9;assertArrayEquals(new byte[]{1,2,3},snapshot.copy());
        var escrow=new Escrow(opId,towerId,0,actor,1,snapshot,PaymentPhase.RESERVED,HpPhase.NOT_APPLIED,0);get(r.saveEscrow(escrow,-1,0));
        byte[] read=get(r.escrow(opId)).orElseThrow().resources().copy();read[0]=9;assertEquals(1,get(r.escrow(opId)).orElseThrow().resources().copy()[0]);
        get(r.advanceOperation(opId,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"caller proof"));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.completeOperation(opId,1,"escrow unresolved")));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.saveEscrow(new Escrow(opId,towerId,0,actor,1,snapshot,PaymentPhase.COMMITTED,HpPhase.APPLIED,1),0,0)));
        get(r.saveEscrow(new Escrow(opId,towerId,0,actor,1,snapshot,PaymentPhase.COMMITTED,HpPhase.APPLIED,1),0,1));get(r.completeOperation(opId,1,"resources and HP verified by caller"));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.saveEscrow(new Escrow(opId,towerId,0,actor,1,snapshot,PaymentPhase.COMMITTED,HpPhase.APPLIED,2),1,2)));
    }
    @Test void awardKeysAndIdentityClaimsAreDurableAndCasGuarded()throws Exception{
        var r=open();seed(r);UUID id=new UUID(0,20);
        Award pending=new Award(id,"generic-campaign",actor,"test",1,AwardStatus.PENDING,0);get(r.saveAward(pending,-1));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.saveAward(new Award(id,"generic-campaign",actor,"test",1,AwardStatus.DELIVERED,1),0)));
        assertInstanceOf(SQLException.class,failed(r.saveAward(new Award(new UUID(0,21),"generic-campaign",actor,"test",1,AwardStatus.PENDING,0),-1)));
        get(r.beginIntent(intent(new UUID(0,9),compact())));
        assertInstanceOf(RepositoryException.Conflict.class,failed(r.associateAwardTowers(id,0,Map.of(towerId,1L))));
        get(r.advanceOperation(new UUID(0,9),0,Phase.INTENT,Phase.RECONCILING,"source verified"));get(r.resolveRollback(new UUID(0,9),1,"original source restored"));
        get(r.associateAwardTowers(id,0,Map.of(towerId,2L)));assertEquals(List.of(towerId),get(r.awardTowerIds(id,null,10)));
        get(r.saveAward(new Award(id,"generic-campaign",actor,"test",1,AwardStatus.DELIVERED,2),1));get(r.closeAsync());
        var reopened=open();assertEquals(AwardStatus.DELIVERED,get(reopened.awardBySource("generic-campaign",actor,"test")).orElseThrow().status());
        // Large configured quantities can remain pending and are not constrained by a tower-count cap.
        get(reopened.saveAward(new Award(new UUID(0,30),"large-campaign",actor,"test",1_000_000,AwardStatus.PENDING,0),-1));
    }
    @Test void futureUnrelatedAndMalformedDatabasesFailWithoutContentChanges()throws Exception{
        Class.forName("org.sqlite.JDBC");
        Path unrelated=directory.resolve("unrelated.db");try(Connection c=PrivateSqlite.open(unrelated);var s=c.createStatement()){s.execute("CREATE TABLE other(value TEXT)");s.execute("INSERT INTO other VALUES('preserve')");}
        byte[] before=Files.readAllBytes(unrelated);assertInstanceOf(SQLException.class,failed(SqliteRepository.open(unrelated,16)));assertArrayEquals(before,Files.readAllBytes(unrelated));
        var r=open();seed(r);get(r.closeAsync());try(Connection c=PrivateSqlite.open(file());var s=c.createStatement()){s.execute("PRAGMA user_version=999");}
        before=Files.readAllBytes(file());assertInstanceOf(SQLException.class,failed(SqliteRepository.open(file(),16)));assertArrayEquals(before,Files.readAllBytes(file()));
        Path bad=directory.resolve("bad.db");Files.writeString(bad,"not a database");before=Files.readAllBytes(bad);assertInstanceOf(SQLException.class,failed(SqliteRepository.open(bad,16)));assertArrayEquals(before,Files.readAllBytes(bad));
    }
    @Test void alteredSchemaOrMalformedJournalCannotOpen()throws Exception{
        var r=open();seed(r);get(r.beginIntent(intent(new UUID(0,9),compact())));get(r.closeAsync());
        try(Connection c=PrivateSqlite.open(file());var s=c.createStatement()){s.execute("UPDATE operations SET after_state=x'0001'");}
        assertInstanceOf(java.io.IOException.class,failed(SqliteRepository.open(file(),16)));
        Path other=directory.resolve("altered.db");var second=open(other,16);get(second.closeAsync());
        try(Connection c=PrivateSqlite.open(other);var s=c.createStatement()){s.execute("ALTER TABLE towers ADD COLUMN unexpected INTEGER");}
        assertInstanceOf(SQLException.class,failed(SqliteRepository.open(other,16)));
    }
    @Test void v1MigrationPreservesExistingTowerAndVariant()throws Exception{
        Class.forName("org.sqlite.JDBC");
        try(Connection c=PrivateSqlite.open(file());var s=c.createStatement()){
            s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for(String sql:Schema.MIGRATIONS.getFirst())s.execute(sql);
            s.execute("INSERT INTO schema_migrations VALUES(1,'"+Schema.checksum(1)+"')");s.execute("PRAGMA application_id="+Schema.APPLICATION_ID);s.execute("PRAGMA user_version=1");
            s.execute("INSERT INTO variants VALUES('test',1,'RETIRED',1,'preserved definition',7)");
            s.execute("INSERT INTO towers VALUES('"+towerId+"','test',20,100,'PORTABLE',5,2,NULL,NULL,NULL,NULL,NULL,NULL,0,0,41,43,8,NULL)");
        }
        var r=open();assertEquals(7,get(r.variant("test")).orElseThrow().rowVersion());var saved=get(r.tower(towerId)).orElseThrow();assertEquals(20,saved.state().tower().hp());assertEquals(8,saved.rowVersion());assertEquals(5,saved.state().tower().generation());
        try(Connection c=PrivateSqlite.open(file())){assertEquals(Schema.VERSION,Schema.pragma(c,"user_version"));}
    }
    @Test void backpressureAndCloseAreBoundedAndCallbacksStayOffCaller()throws Exception{
        var r=open(file(),1);seed(r);long caller=Thread.currentThread().threadId();
        // Hold a genuine SQLite writer lock so the repository worker is occupied.
        try(Connection blocker=PrivateSqlite.open(file());var lock=blocker.createStatement()){
            lock.execute("BEGIN IMMEDIATE");
            var first=r.compareAndSetTower(0,portable(towerId,49));
            Thread.sleep(50);
            var queued=r.tower(towerId);assertInstanceOf(RejectedExecutionException.class,failed(r.tower(towerId)));
            var closed=r.closeAsync();assertFalse(closed.isDone());assertInstanceOf(RepositoryException.class,failed(r.tower(towerId)));
            long[] execution={caller};var callback=first.thenRun(()->execution[0]=Thread.currentThread().threadId());
            lock.execute("ROLLBACK");get(first);get(queued);get(closed);get(callback);assertNotEquals(caller,execution[0]);
        }
    }
    @Test void snapshotsDefensivelyCopyComponentsAndRejectMalformedWireFormat()throws Exception{
        var list=new ArrayList<>(compact().components());var state=new State(compact().tower(),null,list);list.clear();assertEquals(1,state.components().size());
        assertThrows(UnsupportedOperationException.class,()->state.components().clear());
        assertEquals(state,SnapshotCodec.decode(SnapshotCodec.encode(state)));
        byte[] encoded=SnapshotCodec.encode(state);byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);assertThrows(java.io.IOException.class,()->SnapshotCodec.decode(trailing));
    }
}
