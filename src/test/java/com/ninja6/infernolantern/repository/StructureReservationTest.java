package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureReservationTest {
    @TempDir Path directory;
    final List<SqliteRepository> opened=new ArrayList<>();
    final UUID id=UUID.randomUUID(),other=UUID.randomUUID(),world=UUID.randomUUID(),actor=UUID.randomUUID();
    Path file(){return directory.resolve("cells.db");}
    static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
    SqliteRepository open()throws Exception{var r=await(SqliteRepository.open(file(),64));opened.add(r);return r;}
    @AfterEach void close()throws Exception{for(var r:opened)await(r.closeAsync());}
    Tower portable(UUID tower){return new Tower(tower,"fixture",75,100,Form.PORTABLE,1,0,null,null,true,false,33,44);}
    Component component(int y,String expected){return new Component("cell."+y,ComponentKind.BLOCK,world,4,y,4,"minecraft:air",expected,null,null);}
    State placed(UUID tower,boolean expanded,int y,long generation,long revision){
        var t=new Tower(tower,"fixture",75,100,expanded?Form.EXPANDED:Form.COMPACT,generation,revision,expanded?actor:null,new Position(world,4,y,4,"north"),true,false,33,44);
        return new State(t,null,expanded?List.of(component(y,"minecraft:barrier"),component(y+1,"minecraft:barrier"),component(y+2,"minecraft:air")):List.of(component(y,"minecraft:barrier")));
    }
    void seed(SqliteRepository r)throws Exception{
        await(r.createVariant(new Variant("fixture",1,VariantStatus.ACTIVE,new Document(1,"generic fixture"),0)));await(r.createTower(portable(id)));await(r.createTower(portable(other)));
    }
    Intent intent(SqliteRepository r,UUID tower,State after)throws Exception{
        var row=await(r.tower(tower)).orElseThrow();return new Intent(UUID.randomUUID(),tower,row.rowVersion(),row.state().tower().generation(),row.state().tower().deploymentRevision(),"test-structure",after,new Document(1,"explicit full-volume clearance proof"));
    }
    void complete(SqliteRepository r,Operation op)throws Exception{var applied=await(r.advanceOperation(op.intent().id(),op.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"owner proof"));await(r.completeOperation(applied.intent().id(),applied.rowVersion(),"owner proof"));}
    @Test void unionReservesUnchangedClearanceAcrossQuarantineAndReopen()throws Exception{
        var r=open();seed(r);complete(r,await(r.beginIntent(intent(r,id,placed(id,false,70,2,1)))));
        var expansion=await(r.beginIntent(intent(r,id,placed(id,true,70,2,2))));await(r.advanceOperation(expansion.intent().id(),0,Phase.INTENT,Phase.RECONCILING,"interrupted world effects"));
        assertEquals(3,await(r.structureCells(id)).size());assertTrue(await(r.structureCells(id)).stream().allMatch(c->expansion.intent().id().equals(c.operation())));await(r.closeAsync());
        var again=open();assertEquals(3,await(again.structureCells(id)).size());
        var collision=intent(again,other,placed(other,false,72,2,1));
        var failure=assertThrows(ExecutionException.class,()->await(again.beginIntent(collision)));
        assertInstanceOf(RepositoryException.Conflict.class,failure.getCause());assertTrue(await(again.operation(collision.id())).isEmpty());
        assertTrue(await(again.tower(other)).orElseThrow().mutationReady());
    }
    @Test void completedCompactionReleasesUpperCellsOnlyAfterCompletion()throws Exception{
        var r=open();seed(r);complete(r,await(r.beginIntent(intent(r,id,placed(id,true,70,2,1)))));
        var compaction=await(r.beginIntent(intent(r,id,placed(id,false,70,2,2))));assertEquals(3,await(r.structureCells(id)).size());
        Intent collision=intent(r,other,placed(other,false,72,2,1));assertThrows(ExecutionException.class,()->await(r.beginIntent(collision)));
        complete(r,compaction);assertEquals(1,await(r.structureCells(id)).size());assertNull(await(r.structureCells(id)).getFirst().operation());
        complete(r,await(r.beginIntent(collision)));assertEquals(1,await(r.structureCells(other)).size());
    }
    @Test void twoRepositoryWritersCannotClaimSameCell()throws Exception{
        var a=open();seed(a);var b=open();
        Intent first=intent(a,id,placed(id,false,70,2,1)),second=intent(b,other,placed(other,false,70,2,1));
        var x=a.beginIntent(first);var y=b.beginIntent(second);int successes=0;
        for(var f:List.of(x,y)){try{await(f);successes++;}catch(ExecutionException e){assertInstanceOf(RepositoryException.Conflict.class,e.getCause());}}
        assertEquals(1,successes);assertEquals(1,await(a.pendingOperations(null,10)).size());
    }
    @Test void missingReservationFailsOpenWithoutRebuildingAuthority()throws Exception{
        var r=open();seed(r);complete(r,await(r.beginIntent(intent(r,id,placed(id,false,70,2,1)))));await(r.closeAsync());
        try(var c=PrivateSqlite.open(file());var s=c.createStatement()){s.execute("DELETE FROM structure_cells");}
        assertThrows(ExecutionException.class,()->await(SqliteRepository.open(file(),64)));
        try(var c=PrivateSqlite.open(file());var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM structure_cells")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    void legacy(boolean placed,boolean pending)throws Exception{
        try(var c=PrivateSqlite.open(file());var s=c.createStatement()){
            s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for(int version=1;version<=2;version++){
                for(String sql:Schema.MIGRATIONS.get(version-1))s.execute(sql);
                s.execute("INSERT INTO schema_migrations VALUES("+version+",'"+Schema.checksum(version)+"')");
            }
            s.execute("PRAGMA application_id="+Schema.APPLICATION_ID);s.execute("PRAGMA user_version=2");
            s.execute("INSERT INTO variants VALUES('fixture',1,'ACTIVE',1,'generic fixture',0)");
            s.execute("INSERT INTO towers VALUES('"+id+"','fixture',75,100,'"+(placed?"COMPACT":"PORTABLE")+"',1,0,NULL,"+(placed?"'"+world+"',4,70,4,'north'":"NULL,NULL,NULL,NULL,NULL")+",1,0,33,44,0,NULL)");
            if(pending){
                UUID operation=UUID.randomUUID();State before=new State(portable(id),null,List.of()),after=placed(id,false,70,2,1);
                try(var p=c.prepareStatement("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                    Object[] values={operation.toString(),id.toString(),0,1,0,"item-place",SnapshotCodec.encode(before),SnapshotCodec.encode(after),1,"legacy explicit source",Phase.INTENT.name(),0,"pending"};
                    for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
                }
                s.execute("UPDATE towers SET row_version=1,active_operation='"+operation+"'");
            }
        }
    }
    @Test void portableOnlyV2MigratesWithoutInventingReservations()throws Exception{
        legacy(false,false);var r=open();assertEquals(Form.PORTABLE,await(r.tower(id)).orElseThrow().state().tower().form());assertTrue(await(r.structureCells(id)).isEmpty());
        try(var c=PrivateSqlite.open(file())){assertEquals(Schema.VERSION,Schema.pragma(c,"user_version"));}
    }
    @Test void v2PlacedGeometryFailsMigrationAndPreservesOriginalSchema()throws Exception{
        legacy(true,false);byte[] original=Files.readAllBytes(file());assertThrows(ExecutionException.class,()->await(SqliteRepository.open(file(),64)));assertArrayEquals(original,Files.readAllBytes(file()));
        try(var c=PrivateSqlite.open(file());var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='structure_cells'")){assertEquals(2,Schema.pragma(c,"user_version"));assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    @Test void v2PendingPlacementAlsoFailsMigrationRatherThanGuessingVolume()throws Exception{
        legacy(false,true);byte[] original=Files.readAllBytes(file());assertThrows(ExecutionException.class,()->await(SqliteRepository.open(file(),64)));assertArrayEquals(original,Files.readAllBytes(file()));
    }
}
