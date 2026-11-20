package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.award.CampaignAwards;
import com.ninja6.infernolantern.recovery.ReconciliationService.Coverage;
import com.ninja6.infernolantern.repository.CampaignDelivery;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.SqliteRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignAwardsTest {
    @TempDir Path directory;
    private SqliteRepository repository;
    private CatalogService catalog;
    private final Owner owner=new Owner();
    private final UUID recipient=UUID.randomUUID();
    private final Slots slots=new Slots();

    @BeforeEach void open() throws Exception {
        repository=get(SqliteRepository.open(directory.resolve("awards.db"),64));
        catalog=new CatalogService(repository,owner,ignored->{},ignored->CompletableFuture.completedFuture(null));
        get(catalog.reload(config(Map.of("test",variant(),"other",variant()))));
    }

    @AfterEach void stop() throws Exception {
        if(catalog!=null)catalog.close();
        if(repository!=null)get(repository.closeAsync());
    }

    private CampaignAwards service(Map<String,CampaignAwards.Campaign> campaigns) {
        return new CampaignAwards(repository,catalog,campaigns,owner.work::add,slots);
    }

    private CampaignAwards service(long quantity) {
        return service(Map.of("welcome",new CampaignAwards.Campaign("test",quantity)));
    }

    private CampaignAwards.Result issue(CampaignAwards service) throws Exception {
        return owner.drain(service.award("welcome",recipient,new CommitPermit()));
    }

    private CampaignProgress progress() throws Exception {
        return get(repository.campaignAward("welcome",recipient)).orElseThrow();
    }

    private CampaignDelivery.AwaitingSave awaitingSave() throws Exception {
        return get(repository.awaitingCampaignSave(progress().award().id())).orElseThrow();
    }

    private CampaignProgress confirmSave(CampaignAwards service) throws Exception {
        var pending=awaitingSave();
        return get(service.acknowledgeSaved(witness(pending,Coverage.MATCHED_COMPLETE,pending.delivery().slot(),slots.live),
                new CommitPermit()));
    }

    private CampaignDelivery.SaveEvidence witness(CampaignDelivery.AwaitingSave pending,Coverage coverage,
                                                   int slot,ResourceBytes saved){
        return new CampaignDelivery.SaveEvidence(pending,coverage,"a".repeat(64),recipient,slot,saved);
    }

    private final class Slots implements CampaignAwards.Inventory {
        int free,stages,activations;
        boolean offline,rejectStage,denyCommit,denyActivation;
        UUID lastTower;
        ResourceBytes live;

        @Override public CampaignAwards.Prepared prepare(CampaignProgress progress,UUID operation){
            if(offline||free==0)return null;
            lastTower=progress.plannedTower();
            var proof=new CampaignDelivery(progress.award().id(),lastTower,operation,recipient,UUID.randomUUID(),0,
                    new ResourceBytes(new byte[]{1}),new ResourceBytes(new byte[]{2}));
            var permit=new CommitPermit();
            return new CampaignAwards.Prepared(){
                @Override public CampaignDelivery proof(){return proof;}
                @Override public CommitPermit permit(){return permit;}
                @Override public CampaignAwards.Stage stage(BooleanSupplier admission){
                    if(rejectStage||!admission.getAsBoolean())return CampaignAwards.Stage.NO_WRITE;
                    stages++;
                    live=proof.staged();
                    return CampaignAwards.Stage.VERIFIED;
                }
                @Override public boolean verify(){
                    if(denyCommit)permit.revoke();
                    return true;
                }
                @Override public boolean activate(BooleanSupplier admission){
                    if(denyActivation||!admission.getAsBoolean())return false;
                    activations++;
                    live=proof.active();
                    free--;
                    return true;
                }
            };
        }
    }

    @Test void offlineFullAndEditedDefinitionsResumeTheOriginalAward() throws Exception {
        var initial=service(3);
        slots.offline=true;
        assertEquals(CampaignAwards.Stop.PENDING,issue(initial).stop());
        UUID id=progress().award().id();
        assertTrue(get(repository.awardTowerIds(id,null,8)).isEmpty());
        slots.offline=false;
        assertEquals(CampaignAwards.Stop.PENDING,issue(initial).stop());
        assertEquals(0,slots.stages);
        slots.free=2;
        var edited=service(Map.of("welcome",new CampaignAwards.Campaign("other",8)));
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(edited).stop());
        assertEquals(0,progress().delivered());
        assertEquals(CampaignAwards.Stop.REVIEW,issue(edited).stop());
        assertEquals(1,confirmSave(edited).delivered());
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(edited).stop());
        assertEquals(2,confirmSave(edited).delivered());
        assertEquals(CampaignAwards.Stop.PENDING,issue(edited).stop());
        assertEquals("test",progress().award().variantId());
        assertEquals(3,progress().award().quantity());
        assertEquals(id,progress().award().id());
        slots.free=1;
        var removed=service(Map.of());
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(removed).stop());
        assertEquals(AwardStatus.DELIVERED,confirmSave(removed).award().status());
        assertEquals(CampaignAwards.Stop.DELIVERED,issue(edited).stop());
        assertEquals(3,slots.activations);
        var towers=get(repository.awardTowerIds(id,null,8));
        assertEquals(3,Set.copyOf(towers).size());
        for(var tower:towers){
            var row=get(repository.tower(tower)).orElseThrow();
            assertTrue(row.mutationReady());
            assertEquals(Form.PORTABLE,row.state().tower().form());
            assertEquals(1,row.state().tower().generation());
            assertEquals(0,row.state().tower().hp());
        }
        assertTrue(get(repository.pendingCampaignAwards(recipient,null,8)).isEmpty());
    }

    @Test void retiredVariantHoldsOriginalAndLooksUpReceiptWithoutCurrentCampaign() throws Exception {
        var service=service(1);
        assertEquals(CampaignAwards.Stop.PENDING,issue(service).stop());
        UUID id=progress().award().id();
        get(catalog.reload(config(Map.of("other",variant()))));
        slots.free=1;
        assertEquals(CampaignAwards.Stop.PAUSED,issue(service(Map.of())).stop());
        assertEquals(0,slots.stages);
        assertTrue(get(repository.pendingCampaignAwards(recipient,null,8)).isEmpty());
        get(catalog.reload(config(Map.of("test",variant(),"other",variant()))));
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,owner.drain(service.resume(id)).stop());
        assertEquals(AwardStatus.DELIVERED,confirmSave(service).award().status());
        assertEquals(1,slots.activations);
    }

    @Test void provedNoWriteKeepsOneDormantIdentityForRetry() throws Exception {
        var service=service(1);
        slots.free=1;
        slots.rejectStage=true;
        assertEquals(CampaignAwards.Stop.PENDING,issue(service).stop());
        var paused=progress();
        UUID tower=paused.nextTower();
        assertNotNull(tower);
        assertNull(paused.operation());
        assertEquals(Form.LOST,get(repository.tower(tower)).orElseThrow().state().tower().form());
        assertEquals(0,slots.stages);
        slots.rejectStage=false;
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(service).stop());
        assertEquals(AwardStatus.DELIVERED,confirmSave(service).award().status());
        assertEquals(tower,slots.lastTower);
        assertEquals(java.util.List.of(tower),get(repository.awardTowerIds(paused.award().id(),null,8)));
    }

    @Test void singleFreshOwnerAcrossConcurrentRequests() throws Exception {
        var first=service(1);
        var second=service(1);
        slots.free=2;
        var one=first.award("welcome",recipient,new CommitPermit());
        var two=second.award("welcome",recipient,new CommitPermit());
        owner.drain(CompletableFuture.allOf(one,two));
        assertEquals(1,slots.stages);
        assertEquals(1,slots.activations);
        assertEquals(0,progress().delivered());
        var save=awaitingSave();
        var evidence=witness(save,Coverage.MATCHED_COMPLETE,save.delivery().slot(),slots.live);
        var oneAck=first.acknowledgeSaved(evidence,new CommitPermit());
        var twoAck=second.acknowledgeSaved(evidence,new CommitPermit());
        assertEquals(1,get(oneAck).delivered());
        assertThrows(ExecutionException.class,()->get(twoAck));
        assertTrue(get(repository.operation(save.delivery().operation())).orElseThrow().evidence().contains("a".repeat(64)));
    }

    @Test void crashAfterLiveActivationRetainsReviewUntilSavedItemIsProved() throws Exception {
        var service=service(2);
        slots.free=2;
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(service).stop());
        var live=progress();
        var save=awaitingSave();
        assertEquals(AwardStatus.PENDING,live.award().status());
        assertEquals(0,live.delivered());
        assertEquals(save.delivery().active(),slots.live);
        assertEquals(1,slots.activations);
        slots.live=save.delivery().staged(); // Reload the earlier durable player state.
        stop();
        open();
        var reopened=service(2);
        assertEquals(live,progress());
        assertEquals(save,awaitingSave());
        var missing=witness(save,Coverage.MATCHED_COMPLETE,save.delivery().slot(),slots.live);
        assertThrows(ExecutionException.class,()->get(reopened.acknowledgeSaved(missing,new CommitPermit())));
        var absent=witness(save,Coverage.MATCHED_COMPLETE,save.delivery().slot(),new ResourceBytes(new byte[0]));
        assertThrows(ExecutionException.class,()->get(reopened.acknowledgeSaved(absent,new CommitPermit())));
        assertEquals(CampaignAwards.Stop.REVIEW,issue(reopened).stop());
        assertEquals(1,slots.stages);
        assertEquals(1,slots.activations);
        assertEquals(0,progress().delivered());
        assertEquals(1,get(repository.awardTowerIds(live.award().id(),null,2)).size());
    }

    @Test void exactCurrentSaveProofCannotBypassRetirementOrResumeByItself() throws Exception {
        var service=service(2);
        slots.free=2;
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(service).stop());
        var save=awaitingSave();
        ResourceBytes saved=slots.live;
        stop();
        open();
        var resumed=service(2);
        assertEquals(save,awaitingSave());
        var changed=variant();
        changed.put("maximum-hp",120);
        assertThrows(ExecutionException.class,()->get(catalog.reload(config(Map.of("test",changed,"other",variant())))));
        assertThrows(ExecutionException.class,()->get(repository.saveSwitches(catalog.current().orElseThrow().revision(),
                save.delivery().tower(),save.towerVersion(),0,false,true)));
        assertEquals(save,awaitingSave());
        var wrongVersion=new CampaignDelivery.AwaitingSave(save.delivery(),save.operationVersion()+1,save.towerVersion());
        var stale=witness(wrongVersion,Coverage.MATCHED_COMPLETE,save.delivery().slot(),saved);
        assertThrows(ExecutionException.class,()->get(resumed.acknowledgeSaved(stale,new CommitPermit())));
        var incomplete=witness(save,Coverage.INCOMPLETE,save.delivery().slot(),saved);
        assertThrows(ExecutionException.class,()->get(resumed.acknowledgeSaved(incomplete,new CommitPermit())));
        var wrongSlot=witness(save,Coverage.MATCHED_COMPLETE,save.delivery().slot()+1,saved);
        assertThrows(ExecutionException.class,()->get(resumed.acknowledgeSaved(wrongSlot,new CommitPermit())));
        var evidence=witness(save,Coverage.MATCHED_COMPLETE,save.delivery().slot(),saved);
        var revoked=new CommitPermit();
        revoked.revoke();
        assertThrows(ExecutionException.class,()->get(resumed.acknowledgeSaved(evidence,revoked)));
        get(catalog.reload(config(Map.of("other",variant()))));
        assertThrows(ExecutionException.class,()->get(resumed.acknowledgeSaved(evidence,new CommitPermit())));
        assertEquals(AwardStatus.PAUSED,progress().award().status());
        get(catalog.reload(config(Map.of("test",variant(),"other",variant()))));
        var receipt=get(resumed.acknowledgeSaved(evidence,new CommitPermit()));
        assertEquals(AwardStatus.PENDING,receipt.award().status());
        assertEquals(1,receipt.delivered());
        assertNull(receipt.operation());
        assertEquals(1,slots.activations);
        assertEquals(CampaignAwards.Stop.AWAITING_SAVE,issue(resumed).stop());
        assertEquals(AwardStatus.DELIVERED,confirmSave(resumed).award().status());
        assertEquals(2,slots.activations);
    }

    @Test void uncertainActivationAfterCommitNeverReplaysOnRestart() throws Exception {
        slots.free=1;
        slots.denyActivation=true;
        assertEquals(CampaignAwards.Stop.REVIEW,issue(service(1)).stop());
        var prior=progress();
        assertEquals(Phase.COMPLETE,get(repository.operation(prior.operation())).orElseThrow().phase());
        var blocked=get(repository.tower(prior.nextTower())).orElseThrow();
        assertThrows(ExecutionException.class,()->get(repository.compareAndSetTower(blocked.rowVersion(),blocked.state().tower())));
        var intent=new Intent(UUID.randomUUID(),prior.nextTower(),blocked.rowVersion(),1,0,
                "alternate-mutation",blocked.state(),new Document(1,"synthetic"));
        assertThrows(ExecutionException.class,()->get(repository.beginNewIntent(intent)));
        stop();
        open();
        slots.denyActivation=false;
        assertEquals(CampaignAwards.Stop.REVIEW,issue(service(1)).stop());
        assertEquals(prior,progress());
        assertEquals(1,slots.stages);
        assertEquals(0,slots.activations);
    }

    @Test void revokedDeliveryAfterStageRetainsEvidenceAndNoValidTower() throws Exception {
        slots.free=1;
        slots.denyCommit=true;
        assertEquals(CampaignAwards.Stop.REVIEW,issue(service(1)).stop());
        var retained=progress();
        var op=get(repository.operation(retained.operation())).orElseThrow();
        assertEquals(Phase.RECONCILING,op.phase());
        assertThrows(ExecutionException.class,()->get(repository.resolveRollback(op.intent().id(),op.rowVersion(),"untrusted evidence")));
        var locked=get(repository.tower(retained.nextTower())).orElseThrow();
        assertFalse(locked.mutationReady());
        assertEquals(Form.LOST,locked.state().tower().form());
        assertEquals(CampaignAwards.Stop.REVIEW,issue(service(1)).stop());
        assertEquals(1,slots.stages);
        assertEquals(0,slots.activations);
    }

    @Test void rejectedRegistrationDoesNotAllocateAndCampaignConfigHasNoImplicitValues() throws Exception {
        var service=service(1);
        var permit=new CommitPermit();
        permit.revoke();
        assertThrows(ExecutionException.class,()->get(service.award("welcome",recipient,permit)));
        assertTrue(get(repository.campaignAward("welcome",recipient)).isEmpty());
        assertEquals(Map.of("welcome",new CampaignAwards.Campaign("test",3)),
                CampaignAwards.parse(Map.of("welcome",Map.of("variant","test","quantity",3))));
        assertThrows(IllegalArgumentException.class,()->CampaignAwards.parse(Map.of("welcome",Map.of("variant","test"))));
        assertThrows(IllegalArgumentException.class,()->CampaignAwards.parse(Map.of("welcome",Map.of("variant","test","quantity",1.5))));
    }

    @Test void campaignsAndRecipientsRemainIndependent() throws Exception {
        var service=service(Map.of("welcome",new CampaignAwards.Campaign("test",1),
                "second",new CampaignAwards.Campaign("test",1)));
        var other=UUID.randomUUID();
        assertEquals(CampaignAwards.Stop.PENDING,issue(service).stop());
        assertEquals(CampaignAwards.Stop.PENDING,owner.drain(service.award("second",recipient,new CommitPermit())).stop());
        assertEquals(CampaignAwards.Stop.PENDING,owner.drain(service.award("welcome",other,new CommitPermit())).stop());
        var pending=get(repository.pendingCampaignAwards(recipient,null,1));
        assertEquals(1,pending.size());
        var later=get(repository.pendingCampaignAwards(recipient,pending.getFirst().award().id(),2));
        assertEquals(1,later.size());
        assertEquals(Set.of("admin-campaign:welcome","admin-campaign:second"),
                get(repository.pendingCampaignAwards(recipient,null,2)).stream().map(p->p.award().sourceKey()).collect(Collectors.toSet()));
        assertNotEquals(progress().award().id(),get(repository.campaignAward("welcome",other)).orElseThrow().award().id());
    }
}
