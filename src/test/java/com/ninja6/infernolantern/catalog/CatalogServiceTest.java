package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import com.ninja6.infernolantern.structure.BlastImpact;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

class CatalogServiceTest {
    @TempDir Path directory;SqliteRepository repository;CatalogService service;Owner owner=new Owner();Path file;AtomicInteger reconciles=new AtomicInteger();
    @BeforeEach void start()throws Exception{file=directory.resolve("catalog.db");repository=get(SqliteRepository.open(file,512));service=service();}
    CatalogService service(){return new CatalogService(repository,owner,d->{if(d.geometry().compactModel().equals("fixture:missing"))throw new IllegalArgumentException("Model unavailable");},snapshot->{reconciles.incrementAndGet();return CompletableFuture.completedFuture(null);});}
    @AfterEach void stop()throws Exception{service.close();get(repository.closeAsync());}
    CatalogService.Snapshot publish()throws Exception{return get(service.reload(config())).snapshot();}
    @Test void managedBlastAdmissionBindsTheExactConfiguredRepairDuration()throws Exception{
        var variant=variant();
        variant.put("repair",Map.of("cooling-ms",500L,"resources",Map.of("minecraft:iron_ingot",10)));
        var catalog=get(service.reload(config(Map.of("test",variant)))).snapshot();
        TowerSnapshot before=placed(repository,Form.COMPACT);
        long wrong=501;
        assertThrows(ExecutionException.class,()->get(repository.beginStructureIntent(blastIntent(before,wrong),catalog.revision())));
        assertEquals(before,get(repository.tower(before.state().tower().id())).orElseThrow());
        Operation admitted=get(repository.beginStructureIntent(blastIntent(before,500),catalog.revision()));
        assertEquals(65,admitted.intent().after().tower().hp());
        assertEquals(1500,admitted.intent().after().tower().repairAfter());
        assertEquals(before.state().tower().expandAfter(),admitted.intent().after().tower().expandAfter());
    }
    private static Intent blastIntent(TowerSnapshot before,long cooling){
        Tower tower=before.state().tower();
        var blast=BlastImpact.respectingProtection(UUID.randomUUID(),WORLD,Set.of("base"),10,1,false,1000,cooling);
        UUID operation=blast.operation(tower.id());
        var after=new Tower(tower.id(),tower.variantId(),blast.remainingHp(tower.hp()),tower.maximumHp(),Form.GROUND,
                tower.generation()+1,tower.deploymentRevision()+1,null,null,tower.light(),tower.defence(),
                tower.expandAfter(),blast.repairAfter(tower));
        var staged=new Ground(UUID.randomUUID(),WORLD,0,0,operation,"active");
        var evidence=RecoveryEnvelope.structure(STRUCTURE_BLAST,new WorldScheduler.Anchor(WORLD,0,0),
                new Document(1,"fixture request\n"+blast.encode()+"\nfixture physical proof"),null);
        return new Intent(operation,tower.id(),before.rowVersion(),tower.generation(),tower.deploymentRevision(),
                STRUCTURE_BLAST,new State(after,staged,List.of()),evidence);
    }
    @Test void invalidReloadAndWholeSqlFailureRetainPriorPublication()throws Exception{
        var prior=publish();var bad=variant();bad.put("maximum-hp",Double.NaN);assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("test",bad)))));assertEquals(prior,service.current().orElseThrow());
        var unavailable=variant();var geometry=new HashMap<>((Map<String,Object>)unavailable.get("geometry"));geometry.put("compact-model","fixture:missing");unavailable.put("geometry",geometry);assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("test",unavailable)))));
        var durable=get(repository.catalog());try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){s.execute("CREATE TRIGGER reject_bad BEFORE INSERT ON variants WHEN NEW.id='bad' BEGIN SELECT RAISE(ABORT,'injected publication failure'); END");}
        var changed=variant();changed.put("light-level",7);assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("test",changed,"bad",variant())))));
        assertEquals(durable,get(repository.catalog()));assertEquals(prior,service.current().orElseThrow());assertEquals(1,reconciles.get());
    }
    @Test void fluidPolicyPublicationReopensWithoutChangingThePlacedManifest()throws Exception{
        var prior=publish();
        TowerSnapshot placed=placed(repository,Form.EXPANDED);
        var changed=variant();
        changed.put("fluids",Map.of("water",Map.of("expand",false),"lava",Map.of("place",true)));
        var active=get(service.reload(config(Map.of("test",changed)))).snapshot();
        assertTrue(active.revision()>prior.revision());
        assertEquals(3,get(repository.variant("test")).orElseThrow().lastValidDefinition().version());
        assertEquals(placed,get(repository.tower(placed.state().tower().id())).orElseThrow());
        service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=service();
        assertEquals(active,get(service.load()));
        assertEquals(placed,get(repository.tower(placed.state().tower().id())).orElseThrow());
    }
    @Test void missingRenameRetiresManagedOnlyAndRetainsGeometryControllerAndTimers()throws Exception{
        publish();TowerSnapshot before=placed(repository,Form.EXPANDED);
        get(repository.createVariant(new Variant("prototype.item-fixture",1,VariantStatus.ACTIVE,new Document(1,"fixture data"),0)));
        get(repository.createVariant(new Variant("legacy",1,VariantStatus.ACTIVE,new Document(1,"unknown legacy data"),0)));
        var snapshot=get(service.reload(config(Map.of("renamed",variant())))).snapshot();assertEquals(VariantStatus.RETIRED,snapshot.entries().get("test").status());assertEquals(VariantStatus.ACTIVE,snapshot.entries().get("renamed").status());
        assertEquals(before,get(repository.tower(before.state().tower().id())).orElseThrow());assertEquals(VariantStatus.ACTIVE,get(repository.variant("legacy")).orElseThrow().status());assertEquals(VariantStatus.ACTIVE,get(repository.variant("prototype.item-fixture")).orElseThrow().status());
        assertFalse(CatalogService.effective(snapshot,before,world(),allowed()).light());assertFalse(CatalogService.effective(snapshot,before,world(),allowed()).defence());
        assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("legacy",variant())))));assertEquals(snapshot,service.current().orElseThrow());
    }
    @Test void catalogAwardHoldsDoNotDeliverAndOnlyOwnedHoldsResume()throws Exception{
        publish();UUID recipient=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        get(repository.saveAward(new Award(a,"pending",recipient,"test",3,AwardStatus.PENDING,0),-1));get(repository.saveAward(new Award(b,"manual",recipient,"test",2,AwardStatus.PAUSED,0),-1));
        get(service.reload(config(Map.of())));assertEquals(AwardStatus.PAUSED,get(repository.award(a)).orElseThrow().status());assertEquals(AwardStatus.PAUSED,get(repository.award(b)).orElseThrow().status());assertTrue(get(repository.awardTowerIds(a,null,10)).isEmpty());
        UUID later=UUID.randomUUID();assertEquals(AwardStatus.PAUSED,get(repository.saveAward(new Award(later,"later",recipient,"test",1,AwardStatus.PENDING,0),-1)).status());
        assertThrows(ExecutionException.class,()->get(repository.createTower(portable(UUID.randomUUID()))));
        get(service.reload(config()));assertEquals(AwardStatus.PENDING,get(repository.award(a)).orElseThrow().status());assertEquals(AwardStatus.PENDING,get(repository.award(later)).orElseThrow().status());assertEquals(AwardStatus.PAUSED,get(repository.award(b)).orElseThrow().status());
        assertEquals(3,get(repository.award(a)).orElseThrow().quantity());assertTrue(get(repository.awardTowerIds(a,null,10)).isEmpty());
    }
    @Test void manualPauseDoesNotBecomeCatalogOwnedOnReactivation()throws Exception{
        publish();UUID id=UUID.randomUUID();get(repository.saveAward(new Award(id,"manual-takeover",ACTOR,"test",1,AwardStatus.PENDING,0),-1));get(service.reload(config(Map.of())));Award held=get(repository.award(id)).orElseThrow();
        get(repository.saveAward(new Award(id,held.sourceKey(),held.recipient(),held.variantId(),held.quantity(),AwardStatus.PAUSED,held.rowVersion()+1),held.rowVersion()));get(service.reload(config()));assertEquals(AwardStatus.PAUSED,get(repository.award(id)).orElseThrow().status());
    }
    @Test void startupMissingConfigurationRetiresBeforePublishingActivePolicies()throws Exception{
        var prior=publish();TowerSnapshot before=placed(repository,Form.EXPANDED);service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=service();
        assertEquals(prior,get(service.load()));assertTrue(service.current().isEmpty());assertFalse(get(service.isCurrent(prior)));
        var retired=get(service.reload(config(Map.of()))).snapshot();assertEquals(VariantStatus.RETIRED,retired.entries().get("test").status());assertFalse(CatalogService.effective(retired,before,world(),allowed()).light());
        assertEquals(before,get(repository.tower(before.state().tower().id())).orElseThrow());var active=get(service.reload(config())).snapshot();assertTrue(CatalogService.effective(active,before,world(),allowed()).light());
    }
    @Test void geometryDeferralAndMaximumHealthMigrationPreserveLostHistory()throws Exception{
        publish();TowerSnapshot before=placed(repository,Form.EXPANDED);var changed=variant();var g=new HashMap<>((Map<String,Object>)changed.get("geometry"));g.put("height",4);changed.put("geometry",g);
        assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("test",changed)))));
        assertEquals(before,get(repository.tower(before.state().tower().id())).orElseThrow());
        UUID op=UUID.randomUUID();Tower t=before.state().tower();Tower lost=new Tower(t.id(),t.variantId(),t.hp(),t.maximumHp(),Form.LOST,t.generation()+1,t.deploymentRevision()+1,null,null,t.light(),t.defence(),t.expandAfter(),t.repairAfter());
        Operation pending=get(repository.beginCatalogIntent(new Intent(op,t.id(),before.rowVersion(),t.generation(),t.deploymentRevision(),"synthetic-loss",new State(lost,null,List.of()),new Document(1,"synthetic historical terminal fixture")),get(repository.catalog()).revision()));pending=get(repository.advanceOperation(op,pending.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"synthetic verified"));get(repository.completeOperation(op,pending.rowVersion(),"synthetic verified"));
        changed.put("maximum-hp",90);get(service.reload(config(Map.of("test",changed))));assertEquals(lost,get(repository.tower(t.id())).orElseThrow().state().tower());
    }
    @Test void maximumHpChangesPreserveAbsoluteHealthAndOnlyClampOnReduction()throws Exception{
        var original=publish();
        TowerSnapshot deployed=placed(repository,Form.EXPANDED);
        TowerSnapshot portable=get(repository.createTower(portable(UUID.randomUUID())));
        var bigger=variant();bigger.put("maximum-hp",150);
        var increased=get(service.reload(config(Map.of("test",bigger)))).snapshot();
        assertTrue(increased.revision()>original.revision());
        assertThrows(ExecutionException.class,()->get(repository.createTower(portable(UUID.randomUUID()))));
        assertThrows(ExecutionException.class,()->get(repository.compareAndSetTower(deployed.rowVersion()+1,deployed.state().tower())));
        for(TowerSnapshot row:List.of(deployed,portable)){
            var updated=get(repository.tower(row.state().tower().id())).orElseThrow();
            assertMaximumChange(row,updated,75,150);
        }
        TowerSnapshot expanded=get(repository.tower(deployed.state().tower().id())).orElseThrow();
        var smaller=variant();smaller.put("maximum-hp",50);
        get(service.reload(config(Map.of("test",smaller))));
        TowerSnapshot clamped=get(repository.tower(deployed.state().tower().id())).orElseThrow();
        assertMaximumChange(expanded,clamped,50,50);
        service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=service();
        assertEquals(clamped,get(repository.tower(deployed.state().tower().id())).orElseThrow());
    }
    private static void assertMaximumChange(TowerSnapshot before,TowerSnapshot after,double hp,double maximum){
        Tower t=before.state().tower();
        var updated=new Tower(t.id(),t.variantId(),hp,maximum,t.form(),t.generation(),t.deploymentRevision(),t.controller(),t.position(),
                t.light(),t.defence(),t.expandAfter(),t.repairAfter());
        assertEquals(new TowerSnapshot(new State(updated,before.state().ground(),before.state().components()),before.rowVersion()+1,null),after);
    }
    @Test void pendingMaximumHpChangeAndSqlFaultKeepCatalogAndTowerRowsAtomic()throws Exception{
        var settings=publish();TowerSnapshot placed=placed(repository,Form.EXPANDED);
        TowerSnapshot portable=get(repository.createTower(portable(UUID.randomUUID())));
        Tower t=placed.state().tower();
        UUID op=UUID.randomUUID();
        Operation pending=get(repository.beginCatalogIntent(new Intent(op,t.id(),placed.rowVersion(),t.generation(),t.deploymentRevision(),
                "synthetic-pending",placed.state(),new Document(1,"Fixture")),settings.revision()));
        var changed=variant();changed.put("maximum-hp",50);
        var config=config(Map.of("test",changed));
        var durable=get(repository.catalog());
        assertThrows(ExecutionException.class,()->get(service.reload(config)));
        assertEquals(durable,get(repository.catalog()));
        assertEquals(portable,get(repository.tower(portable.state().tower().id())).orElseThrow());
        pending=get(repository.advanceOperation(op,pending.rowVersion(),Phase.INTENT,Phase.RECONCILING,"Fixture"));
        get(repository.resolveRollback(op,pending.rowVersion(),"Fixture"));
        TowerSnapshot restored=get(repository.tower(t.id())).orElseThrow();
        try(var client=SqliteTestConnections.open(file);var sql=client.createStatement()){
            sql.execute("CREATE TRIGGER fixture_fail_hp_publication BEFORE UPDATE ON catalog_state BEGIN SELECT RAISE(ABORT,'fixture publication failure'); END");
        }
        assertThrows(ExecutionException.class,()->get(service.reload(config)));
        assertEquals(durable,get(repository.catalog()));
        assertEquals(settings,service.current().orElseThrow());
        assertEquals(restored,get(repository.tower(t.id())).orElseThrow());
        assertEquals(portable,get(repository.tower(portable.state().tower().id())).orElseThrow());
    }
    @Test void allFourChoicesSurviveReloadRestartAndCannotEnableUnavailableCapabilities()throws Exception{
        var catalog=publish();TowerSnapshot row=placed(repository,Form.EXPANDED);
        CatalogService.SwitchAccess access=(r,a,v)->{owner.assertOwner();return new CatalogService.SwitchObservation(world(),true);};
        for(boolean light:List.of(false,true))for(boolean defence:List.of(false,true)){
            row=owner.drain(service.switchPreference(row.state().tower().id(),row.rowVersion(),row.state().tower().deploymentRevision(),catalog,Action.LIGHT,light,access));row=owner.drain(service.switchPreference(row.state().tower().id(),row.rowVersion(),row.state().tower().deploymentRevision(),catalog,Action.DEFENCE,defence,access));
            var actual=CatalogService.effective(catalog,row,world(),allowed());assertEquals(light,actual.light());assertEquals(defence,actual.defence());assertEquals(170,row.state().tower().expandAfter());assertEquals(230,row.state().tower().repairAfter());
            service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=service();catalog=get(service.reload(config())).snapshot();row=get(repository.tower(row.state().tower().id())).orElseThrow();assertEquals(light,row.state().tower().light());assertEquals(defence,row.state().tower().defence());
        }
        TowerSnapshot saved=row;var v=variant();v.put("capabilities",Map.of("light",false));var disabled=get(service.reload(config(Map.of("test",v)))).snapshot();assertTrue(saved.state().tower().light());assertFalse(CatalogService.effective(disabled,saved,world(),allowed()).light());assertTrue(CatalogService.effective(disabled,saved,world(),allowed()).defence());
        assertThrows(ExecutionException.class,()->owner.drain(service.switchPreference(saved.state().tower().id(),saved.rowVersion(),saved.state().tower().deploymentRevision(),disabled,Action.LIGHT,true,access)));
        service.close();get(repository.closeAsync());repository=get(SqliteRepository.open(file,512));service=service();var restored=get(service.reload(config(Map.of("test",v)))).snapshot();TowerSnapshot current=get(repository.tower(saved.state().tower().id())).orElseThrow();assertEquals(saved,current);assertFalse(CatalogService.effective(restored,current,world(),allowed()).light());
    }
    @Test void compactPendingAndWrongWorldRemainInertWithoutResettingPreferences()throws Exception{
        var catalog=publish();TowerSnapshot row=placed(repository,Form.COMPACT);assertTrue(row.state().tower().light());assertTrue(row.state().tower().defence());assertEquals(new CatalogService.Effective(false,false,0),CatalogService.effective(catalog,row,world(),allowed()));
        Tower t=row.state().tower();var expanded=new Tower(t.id(),t.variantId(),t.hp(),t.maximumHp(),Form.EXPANDED,t.generation(),t.deploymentRevision()+1,ACTOR,t.position(),true,true,t.expandAfter(),t.repairAfter());
        get(repository.beginCatalogIntent(new Intent(UUID.randomUUID(),t.id(),row.rowVersion(),t.generation(),t.deploymentRevision(),"synthetic-pending",new State(expanded,null,row.state().components()),new Document(1,"synthetic")),catalog.revision()));TowerSnapshot pending=get(repository.tower(t.id())).orElseThrow();assertFalse(CatalogService.effective(catalog,pending,world(),allowed()).light());
        var replacement=new WorldRef(UUID.randomUUID(),world().name(),Dimension.OVERWORLD,false);assertFalse(CatalogService.effective(catalog,row,replacement,allowed()).light());
    }
    @Test void freshOwnerAuthorizationAndCatalogCasPreventStaleSwitchWrites()throws Exception{
        var catalog=publish();TowerSnapshot row=placed(repository,Form.EXPANDED);var future=service.switchPreference(row.state().tower().id(),row.rowVersion(),row.state().tower().deploymentRevision(),catalog,Action.LIGHT,false,(r,a,v)->new CatalogService.SwitchObservation(world(),false));assertThrows(ExecutionException.class,()->owner.drain(future));assertEquals(row,get(repository.tower(row.state().tower().id())).orElseThrow());
        var stale=service.switchPreference(row.state().tower().id(),row.rowVersion(),row.state().tower().deploymentRevision(),catalog,Action.LIGHT,false,(r,a,v)->new CatalogService.SwitchObservation(world(),true));owner.await();get(service.reload(config()));assertThrows(ExecutionException.class,()->owner.drain(stale));assertEquals(row,get(repository.tower(row.state().tower().id())).orElseThrow());
    }
    @Test void expandedPendingAndSameNameReplacementNeverUseCachedAuthority()throws Exception{
        var snapshot=publish();TowerSnapshot before=placed(repository,Form.EXPANDED);assertTrue(CatalogService.effective(snapshot,before,world(),allowed()).light());
        var replacement=new WorldRef(UUID.randomUUID(),world().name(),Dimension.OVERWORLD,false);assertFalse(CatalogService.effective(snapshot,before,replacement,allowed()).light());
        get(repository.beginNewIntent(new Intent(UUID.randomUUID(),before.state().tower().id(),before.rowVersion(),2,1,"synthetic-pending",before.state(),new Document(1,"synthetic"))));TowerSnapshot pending=get(repository.tower(before.state().tower().id())).orElseThrow();assertFalse(CatalogService.effective(snapshot,pending,world(),allowed()).light());assertFalse(CatalogService.effective(snapshot,pending,world(),allowed()).defence());
    }
    @Test void postCommitEffectFailureIsReportedWithoutRevertingDurablePolicy()throws Exception{
        publish();service.close();service=new CatalogService(repository,owner,d->{},snapshot->CompletableFuture.failedFuture(new IllegalStateException("owner unavailable")));
        var result=get(service.reload(config(Map.of())));assertTrue(result.published());assertFalse(result.effectsReconciled());assertFalse(service.ready());assertFalse(get(service.isCurrent(result.snapshot())));assertEquals(result.snapshot(),service.current().orElseThrow());assertEquals(VariantStatus.RETIRED,get(repository.variant("test")).orElseThrow().status());
    }
    @Test void pendingOldGeometryFencesEveryJournalPhaseBeforeAndAfterPublication()throws Exception{
        var snapshot=publish();var changed=variant();var geometry=new HashMap<>((Map<String,Object>)changed.get("geometry"));geometry.put("compact-model","fixture:compact-new");changed.put("geometry",geometry);
        for(Phase phase:List.of(Phase.INTENT,Phase.EFFECTS_APPLIED,Phase.RECONCILING)){
            UUID id=UUID.randomUUID(),operation=UUID.randomUUID();get(repository.createTower(portable(id)));
            Tower compact=new Tower(id,"test",75,100,Form.COMPACT,2,1,null,new Position(WORLD,4,70,4,"north"),true,true,170,230);
            State after=new State(compact,null,List.of(new Component("base",ComponentKind.BLOCK,WORLD,4,70,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null)));
            Operation op=get(repository.beginCatalogIntent(new Intent(operation,id,0,1,0,"structure-place",after,new Document(1,"synthetic staged old geometry")),snapshot.revision()));
            if(phase!=Phase.INTENT)op=get(repository.advanceOperation(operation,op.rowVersion(),Phase.INTENT,phase,"synthetic pending phase"));
            var durable=get(repository.catalog());assertThrows(ExecutionException.class,()->get(service.reload(config(Map.of("test",changed)))));assertEquals(durable,get(repository.catalog()));assertEquals(phase,get(repository.operation(operation)).orElseThrow().phase());
            if(op.phase()!=Phase.RECONCILING)op=get(repository.advanceOperation(operation,op.rowVersion(),op.phase(),Phase.RECONCILING,"synthetic preserved source"));
            get(repository.resolveRollback(operation,op.rowVersion(),"synthetic owner proved unchanged portable source"));
        }
        assertTrue(get(repository.pendingOperations(null,10)).isEmpty());get(service.reload(config(Map.of("test",changed))));
    }
    @Test void actualStructureAdmissionRejectsOldPolicyWhenGeometryPublishedFirst()throws Exception{
        var snapshot=publish();UUID id=UUID.randomUUID(),operation=UUID.randomUUID();get(repository.createTower(portable(id)));
        var structure=new com.ninja6.infernolantern.structure.StructureLifecycle(repository,owner);
        var request=new com.ninja6.infernolantern.structure.StructureLifecycle.Request(operation,id,0,1,0,ACTOR,UUID.randomUUID(),com.ninja6.infernolantern.structure.StructureLifecycle.Action.PLACE,new Position(WORLD,4,70,4,"north"),new com.ninja6.infernolantern.structure.StructureLifecycle.Policy(snapshot.revision(),50,60000,1000,false));
        var applied=new AtomicInteger();var discarded=new AtomicInteger();
        var effects=new com.ninja6.infernolantern.structure.StructureLifecycle.WorldEffects(){
            public com.ninja6.infernolantern.structure.StructureLifecycle.Preparation prepare(com.ninja6.infernolantern.structure.StructureLifecycle.Request r,TowerSnapshot row){owner.assertOwner();return new com.ninja6.infernolantern.structure.StructureLifecycle.Preparation(List.of(new Component("base",ComponentKind.BLOCK,WORLD,4,70,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null)),null,new Document(1,"synthetic old-model staging"));}
            public boolean validate(com.ninja6.infernolantern.structure.StructureLifecycle.Claim c){return true;}
            public boolean apply(com.ninja6.infernolantern.structure.StructureLifecycle.Claim c){applied.incrementAndGet();return true;}
            public boolean verify(com.ninja6.infernolantern.structure.StructureLifecycle.Claim c){return true;}
            public boolean rollback(com.ninja6.infernolantern.structure.StructureLifecycle.Claim c){return true;}
            public void discard(com.ninja6.infernolantern.structure.StructureLifecycle.Request r,com.ninja6.infernolantern.structure.StructureLifecycle.Preparation p){owner.assertOwner();discarded.incrementAndGet();}
            public boolean activate(com.ninja6.infernolantern.structure.StructureLifecycle.Claim c,TowerSnapshot row){return true;}
        };
        try{var result=structure.execute(request,effects);owner.await();var changed=variant();var geometry=new HashMap<>((Map<String,Object>)changed.get("geometry"));geometry.put("compact-model","fixture:compact-new");changed.put("geometry",geometry);get(service.reload(config(Map.of("test",changed))));
            assertEquals(com.ninja6.infernolantern.structure.StructureLifecycle.Outcome.REJECTED,owner.drain(result).outcome());assertEquals(0,applied.get());assertEquals(1,discarded.get());assertEquals(portable(id),get(repository.tower(id)).orElseThrow().state().tower());assertTrue(get(repository.operation(operation)).isEmpty());
        }finally{structure.close();}
    }
    @Test void managedRowsRejectLegacyCasWithoutPoisoningFutureValidReload()throws Exception{
        var snapshot=publish();Variant before=get(repository.variant("test")).orElseThrow();
        var bad=new Variant("test",before.definitionRevision()+1,VariantStatus.ACTIVE,new Document(1,"legacy-direct-write"),before.rowVersion()+1);
        assertThrows(ExecutionException.class,()->get(repository.compareAndSetVariant(before.rowVersion(),bad)));
        var retired=new Variant("test",before.definitionRevision(),VariantStatus.RETIRED,before.lastValidDefinition(),before.rowVersion()+1);assertThrows(ExecutionException.class,()->get(repository.compareAndSetVariant(before.rowVersion(),retired)));
        assertEquals(before,get(repository.variant("test")).orElseThrow());assertEquals(snapshot,service.current().orElseThrow());var changed=variant();changed.put("light-level",7);get(service.reload(config(Map.of("test",changed))));assertEquals(7,service.current().orElseThrow().entries().get("test").definition().lightLevel());
        Variant legacy=get(repository.createVariant(new Variant("legacy-cas",0,VariantStatus.ACTIVE,new Document(1,"legacy"),0)));get(repository.compareAndSetVariant(0,new Variant(legacy.id(),1,VariantStatus.ACTIVE,new Document(1,"changed unmanaged"),1)));assertEquals("changed unmanaged",get(repository.variant(legacy.id())).orElseThrow().lastValidDefinition().content());
    }
    @Test void concurrentCatalogWritersCannotSilentlyPublishAStaleRevision()throws Exception{
        var expected=get(repository.catalog());var candidate=CatalogConfig.parse(config());var docs=Map.of("test",CatalogCodec.definition(candidate.variants().get("test")));
        var first=repository.publishCatalog(expected,CatalogCodec.candidate(candidate),docs,Set.of());var second=repository.publishCatalog(expected,CatalogCodec.candidate(candidate),docs,Set.of());
        int winners=0;for(var future:List.of(first,second)){try{get(future);winners++;}catch(ExecutionException expectedConflict){assertInstanceOf(RepositoryException.Conflict.class,expectedConflict.getCause());}}assertEquals(1,winners);assertEquals(1,get(repository.catalog()).revision());
    }
}
