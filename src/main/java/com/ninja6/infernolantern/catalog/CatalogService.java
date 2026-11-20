package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** One runtime owner publishes immutable catalog snapshots after an atomic durable transaction. */
public final class CatalogService implements AutoCloseable {
    public record Entry(Definition definition,VariantStatus status,long definitionRevision) {}
    public record Snapshot(long revision,Map<String,Entry> entries) {
        public Snapshot {entries=Map.copyOf(entries);}
    }
    /** Caller must validate actual model/resource availability; syntax alone cannot prove a pack exists. */
    @FunctionalInterface public interface DefinitionValidator {void validate(Definition definition);}
    /** Invoked after durable publication. It must marshal loaded effect/service reconciliation to owners. */
    @FunctionalInterface public interface Reconciler {CompletableFuture<Void> reconcile(Snapshot snapshot);}
    public record Reload(boolean published,boolean effectsReconciled,Snapshot snapshot) {}
    /** These are mandatory current inputs, never authority granted by a saved switch. */
    public record Conditions(boolean nativeAccess,boolean fluidAllowed,boolean lifecycleLight,boolean lifecycleDefence,boolean componentIntegrity) {}
    public record Effective(boolean light,boolean defence,int lightLevel) {}
    public record SwitchObservation(WorldRef world,boolean authorized) {public SwitchObservation{Objects.requireNonNull(world);}}
    @FunctionalInterface public interface SwitchAccess {
        SwitchObservation inspect(TowerSnapshot row,Action action,boolean value);
        /** Immutable receipt notification after CAS; may run off the world owner. */
        default void committed(TowerSnapshot row) {}
    }
    private final SqliteRepository repository;private final WorldScheduler scheduler;private final DefinitionValidator validator;private final Reconciler reconciler;
    private final AtomicReference<Snapshot> published=new AtomicReference<>();private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean closed,effectsReady;
    public CatalogService(SqliteRepository repository,WorldScheduler scheduler,DefinitionValidator validator,Reconciler reconciler){this.repository=Objects.requireNonNull(repository);this.scheduler=Objects.requireNonNull(scheduler);this.validator=Objects.requireNonNull(validator);this.reconciler=Objects.requireNonNull(reconciler);}
    public Optional<Snapshot> current(){return Optional.ofNullable(published.get());}
    public boolean ready(){return !closed&&effectsReady;}
    public boolean isPublished(Snapshot snapshot){
        Snapshot observed=published.get();
        return snapshot.revision()>0&&snapshot.equals(observed)&&ready()&&observed==published.get();
    }
    /** Startup explicitly publishes current validated configuration, retiring omitted managed definitions. */
    public CompletableFuture<Reload> reload(Map<String,?> values){return reload(values,null);}
    private CompletableFuture<Reload> reload(Map<String,?> values,CommitPermit permit){Candidate candidate;
        try{candidate=CatalogConfig.parse(values);for(Definition d:candidate.variants().values())validator.validate(d);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        return publish(candidate,permit);
    }
    public CompletableFuture<Reload> reloadYaml(String text){return reloadYaml(text,null);}
    public CompletableFuture<Reload> reloadYaml(String text,CommitPermit permit){try{return reload(CatalogYaml.read(text),permit);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}}
    public CompletableFuture<Snapshot> load(){
        if(closed||!busy.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException("Catalog closed or publication pending"));
        return repository.catalog().thenApply(CatalogService::decodeStored).thenApply(snapshot->{if(closed)throw new CompletionException(new IllegalStateException("Catalog closed"));return snapshot;}).whenComplete((value,error)->busy.set(false));
    }
    private CompletableFuture<Reload> publish(Candidate candidate,CommitPermit permit){
        if(closed||!busy.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException("Catalog closed or publication pending"));
        return repository.catalog().thenCompose(before->{
            // Validate durable last-good definitions before any publication; never adopt opaque legacy IDs.
            Snapshot previous=decodeStored(before);var geometry=new HashSet<String>();
            var definitions=new TreeMap<String,Document>();for(Definition d:candidate.variants().values()){
                Entry old=previous.entries().get(d.id());if(old!=null&&!old.definition().geometry().equals(d.geometry()))geometry.add(d.id());
                definitions.put(d.id(),CatalogCodec.definition(d));
            }
            if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Catalog closed"));
            return repository.publishCatalog(before,CatalogCodec.candidate(candidate),definitions,geometry,permit);
        }).thenCompose(committed->{
            Snapshot snapshot=decodeStored(committed);
            if(closed)return CompletableFuture.completedFuture(new Reload(true,false,snapshot));
            effectsReady=false;published.set(snapshot);
            try{return Objects.requireNonNull(reconciler.reconcile(snapshot)).handle((ignored,error)->{effectsReady=error==null&&!closed;return new Reload(true,effectsReady,snapshot);});}
            catch(RuntimeException failed){return CompletableFuture.completedFuture(new Reload(true,false,snapshot));}
        }).whenComplete((result,failure)->busy.set(false));
    }
    static Snapshot decodeStored(CatalogSnapshot stored){
        if(stored.revision()==0){if(!stored.variants().isEmpty())throw new IllegalArgumentException("Unpublished catalog has definitions");return new Snapshot(0,Map.of());}
        Candidate candidate=CatalogCodec.candidate(stored.configuration());var entries=new TreeMap<String,Entry>();var active=new HashSet<String>();
        for(Variant variant:stored.variants()){
            Definition d=CatalogCodec.definition(variant.lastValidDefinition());if(!variant.id().equals(d.id()))throw new IllegalArgumentException("Durable variant identity mismatch");
            if(variant.status()==VariantStatus.ACTIVE){if(!d.equals(candidate.variants().get(d.id())))throw new IllegalArgumentException("Durable active catalog mismatch");active.add(d.id());}
            entries.put(d.id(),new Entry(d,variant.status(),variant.definitionRevision()));
        }
        if(!active.equals(candidate.variants().keySet()))throw new IllegalArgumentException("Durable catalog membership mismatch");
        return new Snapshot(stored.revision(),entries);
    }
    /** Check at native commit; a cached snapshot is not a cross-instance authority grant. */
    public CompletableFuture<Boolean> isCurrent(Snapshot snapshot){return repository.catalog().thenApply(current->isPublished(snapshot)&&decodeStored(current).equals(snapshot));}
    public static Effective effective(Snapshot snapshot,TowerSnapshot row,WorldRef world,Conditions conditions){
        Tower tower=row.state().tower();
        Entry entry=snapshot.entries().get(tower.variantId());if(!row.mutationReady()||tower.form()!=Form.EXPANDED||tower.hp()<=0||entry==null||entry.status()!=VariantStatus.ACTIVE||tower.position()==null||!tower.position().world().equals(world.id())||!conditions.nativeAccess()||!conditions.fluidAllowed()||!conditions.componentIntegrity())return new Effective(false,false,0);
        Definition d=entry.definition();boolean light=tower.light()&&d.lightAvailable()&&conditions.lifecycleLight()&&d.worlds().get(Action.LIGHT).allows(world);
        boolean defence=tower.defence()&&d.defenceAvailable()&&conditions.lifecycleDefence()&&d.worlds().get(Action.DEFENCE).allows(world);
        return new Effective(light,defence,light?d.lightLevel():0);
    }
    /** No ownership or session inference: caller's owner-thread callback must prove both at each boundary. */
    public CompletableFuture<TowerSnapshot> switchPreference(UUID tower,long rowVersion,long deploymentRevision,Snapshot catalog,Action action,boolean value,SwitchAccess access){
        return switchPreference(tower,rowVersion,deploymentRevision,catalog,action,value,access,null);
    }
    public CompletableFuture<TowerSnapshot> switchPreference(UUID tower,long rowVersion,long deploymentRevision,Snapshot catalog,Action action,
            boolean value,SwitchAccess access,CommitPermit permit){
        if(!isPublished(catalog))return CompletableFuture.failedFuture(new RepositoryException.Conflict("Catalog is not currently published"));
        if(action!=Action.LIGHT&&action!=Action.DEFENCE)return CompletableFuture.failedFuture(new IllegalArgumentException("Player switch action required"));
        return repository.tower(tower).thenCompose(found->{
            TowerSnapshot before=found.orElseThrow(()->new IllegalArgumentException("Unknown tower"));Tower t=before.state().tower();Entry entry=catalog.entries().get(t.variantId());
            if(closed||t.form()!=Form.EXPANDED||!before.mutationReady()||before.rowVersion()!=rowVersion||t.deploymentRevision()!=deploymentRevision||t.position()==null||entry==null||entry.status()!=VariantStatus.ACTIVE)throw new RepositoryException.Conflict("Switch snapshot unavailable");
            Definition definition=entry.definition();if(value&&!(action==Action.LIGHT?definition.lightAvailable():definition.defenceAvailable()))throw new RepositoryException.Conflict("Switch capability unavailable");
            var anchor=new WorldScheduler.Anchor(t.position().world(),t.position().x()>>4,t.position().z()>>4);
            return scheduler.supply(anchor,()->isPublished(catalog)&&switchAllowed(access,before,definition,action,value)).thenCompose(allowed->{
                if(!allowed)return CompletableFuture.failedFuture(new RepositoryException.Conflict("Switch access changed"));
                return repository.saveSwitches(catalog.revision(),tower,rowVersion,deploymentRevision,action==Action.LIGHT?value:t.light(),action==Action.DEFENCE?value:t.defence(),permit);
            }).thenCompose(saved->{
                access.committed(saved);
                return scheduler.supply(anchor,()->{
                    // Persisted preference stays authoritative even if a callback becomes unavailable; effects remain separate.
                    if(!isPublished(catalog)||!switchAllowed(access,saved,definition,action,value))throw new RepositoryException.Conflict("Switch effect reconciliation required");return saved;
                });
            });
        });
    }
    private static boolean switchAllowed(SwitchAccess access,TowerSnapshot row,Definition definition,Action action,boolean value){
        SwitchObservation observation=access.inspect(row,action,value);return observation!=null&&observation.authorized()&&row.state().tower().position().world().equals(observation.world().id())&&(!value||definition.worlds().get(action).allows(observation.world()));
    }
    @Override public void close(){closed=true;effectsReady=false;}
}
