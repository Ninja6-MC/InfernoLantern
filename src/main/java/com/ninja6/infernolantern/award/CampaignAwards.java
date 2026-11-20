package com.ninja6.infernolantern.award;

import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.catalog.CatalogService.Snapshot;
import com.ninja6.infernolantern.repository.CampaignDelivery;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.SqliteRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

public final class CampaignAwards implements AutoCloseable {
    // One inventory-sized unit of work per request. Campaign quantity is independent of this bound.
    private static final int MAX_DELIVERIES = 36;

    public record Campaign(String variant,long quantity) {
        public Campaign {
            if (!identifier(variant) || quantity < 1) throw new IllegalArgumentException("Campaign variant or quantity");
        }
    }
    public enum Stop { DELIVERED, PENDING, PAUSED, REVIEW, UNAVAILABLE }
    public record Result(Stop stop,CampaignProgress progress) {}
    public enum Stage { NO_WRITE, VERIFIED, UNCERTAIN }

    /** Inventory implementations run only on the existing owner executor. */
    public interface Inventory {
        Prepared prepare(CampaignProgress progress,UUID operation);
    }
    public interface Prepared {
        CampaignDelivery proof();
        CommitPermit permit();
        Stage stage(BooleanSupplier admission);
        boolean verify();
        boolean activate(BooleanSupplier admission);
    }

    private final SqliteRepository repository;
    private final CatalogService catalog;
    private final Map<String,Campaign> campaigns;
    private final Executor owner;
    private final Inventory inventory;
    private final CommitPermit lifetime = new CommitPermit();
    private volatile boolean closed;

    public CampaignAwards(SqliteRepository repository,CatalogService catalog,Map<String,Campaign> campaigns,
                          Executor owner,Inventory inventory) {
        this.repository=Objects.requireNonNull(repository);
        this.catalog=Objects.requireNonNull(catalog);
        this.campaigns=Map.copyOf(campaigns);
        this.campaigns.keySet().forEach(CampaignDelivery::source);
        this.owner=Objects.requireNonNull(owner);
        this.inventory=Objects.requireNonNull(inventory);
    }

    /** Parse the optional awards campaign mapping without selecting a variant or quantity by default. */
    public static Map<String,Campaign> parse(Map<String,?> values) {
        Objects.requireNonNull(values);
        var campaigns = new java.util.HashMap<String,Campaign>();
        values.forEach((id,raw)->{
            CampaignDelivery.source(id);
            if(!(raw instanceof Map<?,?> fields)||!fields.keySet().equals(java.util.Set.of("variant","quantity"))
                    ||!(fields.get("variant") instanceof String variant))
                throw new IllegalArgumentException("Campaign requires variant and quantity");
            Object number=fields.get("quantity");
            if(!(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long))
                throw new IllegalArgumentException("Campaign quantity must be a positive integer");
            campaigns.put(id,new Campaign(variant,((Number)number).longValue()));
        });
        return Map.copyOf(campaigns);
    }

    private static boolean identifier(String id) { return id!=null&&id.matches("[a-z0-9][a-z0-9_.:-]{0,127}"); }

    public CompletableFuture<Result> award(String campaign,UUID recipient,CommitPermit authority) {
        Objects.requireNonNull(authority);
        if(closed||!identifier(campaign))return unavailable();
        return repository.campaignAward(campaign,recipient).thenCompose(existing->{
            if(existing.isPresent())return deliver(existing.get(),MAX_DELIVERIES);
            Campaign settings=campaigns.get(campaign);
            Snapshot current=catalog.current().orElse(null);
            if(settings==null||!published(current))return unavailable();
            return repository.recordCampaignAward(campaign,recipient,settings.variant(),settings.quantity(),current.revision(),
                    CommitPermit.allOf(authority,lifetime)).thenCompose(progress->deliver(progress,MAX_DELIVERIES));
        });
    }

    /** Resume a previously persisted receipt on a new owner opportunity. */
    public CompletableFuture<Result> resume(UUID award) {
        if(closed)return unavailable();
        return repository.campaignAward(award).thenCompose(found->found.isPresent()
                ?deliver(found.get(),MAX_DELIVERIES):unavailable());
    }

    private CompletableFuture<Result> deliver(CampaignProgress progress,int remaining) {
        if(progress.operation()!=null)return done(Stop.REVIEW,progress);
        if(progress.award().status()==AwardStatus.DELIVERED)return done(Stop.DELIVERED,progress);
        if(progress.award().status()==AwardStatus.PAUSED)return done(Stop.PAUSED,progress);
        Snapshot snapshot=catalog.current().orElse(null);
        if(remaining==0||!active(snapshot,progress))return done(Stop.PENDING,progress);
        return onOwner(()->active(snapshot,progress)?inventory.prepare(progress,UUID.randomUUID()):null)
                .thenCompose(prepared->{
                    if(prepared==null)return done(Stop.PENDING,progress);
                    CampaignDelivery proof=prepared.proof();
                    CommitPermit permit=CommitPermit.allOf(lifetime,prepared.permit());
                    return repository.beginCampaignDelivery(progress,proof,snapshot.revision(),permit)
                            .<CompletableFuture<Result>>handle((operation,error)->{
                                if(error==null)return stage(progress,snapshot,operation,prepared,permit,remaining);
                                if(knownRejection(error))return latest(progress);
                                return failed(error);
                            }).thenCompose(action->action);
                });
    }

    private CompletableFuture<Result> stage(CampaignProgress progress,Snapshot snapshot,Operation operation,
            Prepared prepared,CommitPermit permit,int remaining) {
        CampaignDelivery proof=prepared.proof();
        return onOwner(()->{
            BooleanSupplier admission=()->active(snapshot,progress)&&authorized(permit);
            if(!admission.getAsBoolean())return Stage.NO_WRITE;
            Stage staged=prepared.stage(admission);
            return staged==Stage.VERIFIED&&!prepared.verify()?Stage.UNCERTAIN:staged;
        }).thenCompose(staged->switch(staged){
            case NO_WRITE -> repository.cancelCampaignDelivery(proof).thenCompose(this::latest);
            case UNCERTAIN -> quarantine(progress,operation);
            case VERIFIED -> repository.completeCampaignDelivery(proof,snapshot.revision(),permit)
                    .thenCompose(committed->activate(progress,snapshot,committed,prepared))
                    .thenCompose(activated->activated
                            ?repository.acknowledgeCampaignDelivery(proof).thenCompose(current->deliver(current,remaining-1))
                            :latest(progress));
        }).exceptionallyCompose(failure->quarantine(progress,operation));
    }

    private CompletableFuture<Boolean> activate(CampaignProgress progress,Snapshot snapshot,Operation operation,Prepared prepared) {
        var expected=new TowerSnapshot(operation.intent().after(),operation.intent().expectedRowVersion()+2,null);
        return repository.observeTower(expected).thenCompose(observed->{
            if(observed.isEmpty())return CompletableFuture.completedFuture(false);
            var observation=observed.get();
            return onOwner(()->{
                BooleanSupplier admission=()->active(snapshot,progress)&&authorized(prepared.permit())&&observation.current();
                return admission.getAsBoolean()&&prepared.activate(admission);
            }).whenComplete((value,failure)->observation.close());
        });
    }

    private CompletableFuture<Result> quarantine(CampaignProgress progress,Operation operation) {
        return repository.operation(operation.intent().id()).thenCompose(found->{
            Operation current=found.orElse(null);
            if(current!=null&&current.pending()&&current.phase()!=Phase.RECONCILING)
                return repository.advanceOperation(current.intent().id(),current.rowVersion(),current.phase(),Phase.RECONCILING,
                        "Inventory evidence uncertain; no replay or replacement").thenCompose(ignored->latest(progress));
            return latest(progress);
        }).exceptionally(failure->new Result(Stop.REVIEW,progress));
    }

    private CompletableFuture<Result> latest(CampaignProgress prior) {
        return repository.campaignAward(prior.award().id()).thenApply(found->{
            CampaignProgress current=found.orElse(prior);
            Stop stop=current.operation()!=null?Stop.REVIEW:switch(current.award().status()){
                case DELIVERED->Stop.DELIVERED;
                case PAUSED->Stop.PAUSED;
                case PENDING->Stop.PENDING;
            };
            return new Result(stop,current);
        });
    }

    private boolean published(Snapshot snapshot) { return !closed&&snapshot!=null&&catalog.isPublished(snapshot); }
    private boolean active(Snapshot snapshot,CampaignProgress progress) {
        if(!published(snapshot))return false;
        var entry=snapshot.entries().get(progress.award().variantId());
        return entry!=null&&entry.status()==VariantStatus.ACTIVE;
    }
    private <T> CompletableFuture<T> onOwner(Supplier<T> action) {
        try{return CompletableFuture.supplyAsync(action,owner);}
        catch(RuntimeException unavailable){return CompletableFuture.failedFuture(unavailable);}
    }
    private static boolean authorized(CommitPermit permit) {
        try{permit.requireValid();return true;}
        catch(RepositoryException.CommitRevoked denied){return false;}
    }
    private static Throwable cause(Throwable error) {
        return error instanceof CompletionException&&error.getCause()!=null?error.getCause():error;
    }
    private static boolean knownRejection(Throwable error) {
        Throwable original=cause(error);
        return (original instanceof RepositoryException.Conflict||original instanceof RepositoryException.CommitRevoked)
                &&original.getSuppressed().length==0;
    }
    private static <T> CompletableFuture<T> failed(Throwable error) { return CompletableFuture.failedFuture(cause(error)); }
    private static CompletableFuture<Result> unavailable() { return done(Stop.UNAVAILABLE,null); }
    private static CompletableFuture<Result> done(Stop stop,CampaignProgress progress) {
        return CompletableFuture.completedFuture(new Result(stop,progress));
    }
    @Override public void close(){closed=true;lifetime.revoke();}
}
