package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Journaled real-light adjustment. Not registered as a native switch or startup handler. */
public final class LightReconciler implements AutoCloseable {
    public enum Outcome { COMMITTED, NO_CHANGE, REJECTED, ROLLED_BACK, QUARANTINED }
    public record Request(UUID operation,UUID tower,long rowVersion,long deploymentRevision,CatalogService.Snapshot catalog) {
        public Request {Objects.requireNonNull(operation);Objects.requireNonNull(tower);Objects.requireNonNull(catalog);if(rowVersion<0||deploymentRevision<0)throw new IllegalArgumentException("Expected version");}
    }
    public record View(long catalogRevision,WorldRef world,CatalogService.Conditions conditions,boolean writeAuthorized) {public View{if(catalogRevision<0)throw new IllegalArgumentException("Published catalog revision");Objects.requireNonNull(world);Objects.requireNonNull(conditions);}}
    public record Prepared(Component after,int level,Document evidence) {public Prepared{Objects.requireNonNull(after);Objects.requireNonNull(evidence);if(level<0||level>15)throw new IllegalArgumentException("Light level");}}
    public record Claim(Request request,TowerSnapshot before,Operation operation,Prepared prepared) {}
    public interface Effects {
        /** Current policies/world identity, not a cached authorization grant. Owner thread only. */
        View view(TowerSnapshot row);
        Prepared prepare(TowerSnapshot row,Component lightCell,int level);
        boolean apply(Claim claim);
        boolean verify(Claim claim);
        boolean rollback(Claim claim);
    }
    private final SqliteRepository repository;private final WorldScheduler scheduler;private final Set<UUID> busy=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    public LightReconciler(SqliteRepository repository,WorldScheduler scheduler){this.repository=Objects.requireNonNull(repository);this.scheduler=Objects.requireNonNull(scheduler);}
    public CompletableFuture<Outcome> reconcile(Request request,Effects effects){
        if(closed||!busy.add(request.operation()))return CompletableFuture.completedFuture(Outcome.REJECTED);
        return repository.operation(request.operation()).thenCompose(receipt->{
            if(receipt.isPresent())return CompletableFuture.completedFuture(Outcome.REJECTED);
            return repository.catalog().thenCompose(stored->{
                if(!CatalogService.decodeStored(stored).equals(request.catalog()))return CompletableFuture.completedFuture(Outcome.REJECTED);
                return repository.tower(request.tower()).thenCompose(found->{
                TowerSnapshot row=found.orElse(null);if(row==null||!row.mutationReady()||row.rowVersion()!=request.rowVersion()||row.state().tower().deploymentRevision()!=request.deploymentRevision())return CompletableFuture.completedFuture(Outcome.REJECTED);
                Tower t=row.state().tower();if(t.form()!=Form.EXPANDED)return CompletableFuture.completedFuture(Outcome.NO_CHANGE);
                var entry=request.catalog().entries().get(t.variantId());if(entry==null)return CompletableFuture.completedFuture(Outcome.REJECTED);
                Component cell=lightCell(row,entry.definition().geometry());var anchor=new WorldScheduler.Anchor(t.position().world(),t.position().x()>>4,t.position().z()>>4);
                return scheduler.supply(anchor,()->{
                    if(closed)return null;View view=effects.view(row);if(!view.writeAuthorized()||view.catalogRevision()!=request.catalog().revision())return null;int level=CatalogService.effective(request.catalog(),row,view.world(),view.conditions()).lightLevel();return effects.prepare(row,cell,level);
                }).thenCompose(prepared->{
                    if(prepared==null)return CompletableFuture.completedFuture(Outcome.REJECTED);
                    if(!sameCell(cell,prepared.after()))throw new IllegalArgumentException("Light preparation moved a component");
                    if(cell.equals(prepared.after()))return repository.catalog().thenApply(c->CatalogService.decodeStored(c).equals(request.catalog())?Outcome.NO_CHANGE:Outcome.REJECTED);
                    var components=new ArrayList<>(row.state().components());components.replaceAll(c->c.id().equals(cell.id())?prepared.after():c);
                    State after=new State(t,null,components);Intent intent=new Intent(request.operation(),t.id(),row.rowVersion(),t.generation(),t.deploymentRevision(),"catalog-light",after,prepared.evidence());
                    return repository.beginCatalogIntent(intent,request.catalog().revision()).thenCompose(op->run(new Claim(request,row,op,prepared),effects,anchor));
                });
            });});
        }).exceptionally(failure->Outcome.QUARANTINED).whenComplete((result,failure)->busy.remove(request.operation()));
    }
    private CompletableFuture<Outcome> run(Claim claim,Effects effects,WorldScheduler.Anchor anchor){
        return current(claim).thenCompose(current->scheduler.supply(anchor,()->current&&!closed&&matchesDesired(claim,effects)&&effects.apply(claim)))
                .handle((ok,failure)->failure==null&&Boolean.TRUE.equals(ok)).thenCompose(applied->{
                    if(!applied)return compensate(claim,effects,anchor);
                    return current(claim).thenCompose(current->scheduler.supply(anchor,()->current&&!closed&&matchesDesired(claim,effects)&&effects.verify(claim)))
                            .handle((ok,failure)->failure==null&&Boolean.TRUE.equals(ok)).thenCompose(verified->{
                                if(!verified)return compensate(claim,effects,anchor);
                                return repository.advanceOperation(claim.request().operation(),claim.operation().rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"Owner verified exact owned light adjustment")
                                        .thenCompose(op->repository.completeCatalogOperation(op.intent().id(),op.rowVersion(),claim.request().catalog().revision(),"Light manifest/effect verified against catalog revision")
                                                .handle((done,failure)->{
                                                    if(failure!=null)return unwrap(failure) instanceof RepositoryException.CatalogSuperseded?compensate(claim,effects,anchor):freeze(claim);
                                                    // A newer policy does not make this matching committed effect uncertain.
                                                    // Leave the row ready for a fresh request under that policy.
                                                    return repository.tower(claim.request().tower())
                                                            .thenCompose(found->scheduler.supply(anchor,()->!closed&&found.isPresent()&&found.get().mutationReady()&&found.get().rowVersion()==claim.request().rowVersion()+2&&found.get().state().equals(claim.operation().intent().after())&&effects.verify(claim)))
                                                            .thenCompose(ok->ok?CompletableFuture.completedFuture(Outcome.COMMITTED):freeze(claim));
                                                }).thenCompose(result->result));
                            });
                }).exceptionallyCompose(failure->freeze(claim));
    }
    private CompletableFuture<Boolean> current(Claim claim){return repository.catalog().thenApply(c->CatalogService.decodeStored(c).equals(claim.request().catalog()));}
    private boolean matchesDesired(Claim claim,Effects effects){View view=effects.view(claim.before());int level=CatalogService.effective(claim.request().catalog(),claim.before(),view.world(),view.conditions()).lightLevel();
        return view.writeAuthorized()&&view.catalogRevision()==claim.request().catalog().revision()&&level==claim.prepared().level();
    }
    /** An old callback may compensate only while its exact intent still owns the before row. */
    private CompletableFuture<Optional<Operation>> pendingOwned(Claim claim){
        return repository.operation(claim.request().operation()).thenCompose(found->{
            if(found.isEmpty()||!found.get().pending()||!found.get().intent().equals(claim.operation().intent())||!found.get().before().equals(claim.before().state()))return CompletableFuture.completedFuture(Optional.empty());
            Operation operation=found.get();return repository.tower(claim.request().tower()).thenApply(row->row.isPresent()&&operation.intent().id().equals(row.get().activeOperation())&&row.get().rowVersion()==claim.request().rowVersion()+1&&row.get().state().equals(claim.before().state())?Optional.of(operation):Optional.empty());
        });
    }
    private CompletableFuture<Outcome> compensate(Claim claim,Effects effects,WorldScheduler.Anchor anchor){
        return pendingOwned(claim).thenCompose(owned->{
            if(owned.isEmpty())return freeze(claim);Operation initial=owned.get();
            CompletableFuture<Operation> claimed=initial.phase()==Phase.RECONCILING?CompletableFuture.completedFuture(initial):repository.advanceOperation(initial.intent().id(),initial.rowVersion(),initial.phase(),Phase.RECONCILING,"Owner claiming exact light compensation; no generic replay");
            return claimed.thenCompose(operation->pendingOwned(claim).thenCompose(fresh->{
                if(fresh.isEmpty()||!fresh.get().equals(operation))return freeze(claim);
                return scheduler.supply(anchor,()->!closed&&effects.rollback(claim)).handle((ok,failure)->failure==null&&Boolean.TRUE.equals(ok))
                        .thenCompose(restored->restored?repository.resolveRollback(operation.intent().id(),operation.rowVersion(),"Owner proved original light state restored with exact pending ownership").thenApply(done->Outcome.ROLLED_BACK):CompletableFuture.completedFuture(Outcome.QUARANTINED));
            }));
        }).exceptionallyCompose(failure->freeze(claim));
    }
    private static Throwable unwrap(Throwable failure){while(failure instanceof CompletionException&&failure.getCause()!=null)failure=failure.getCause();return failure;}
    private CompletableFuture<Outcome> freeze(Claim claim){return repository.operation(claim.request().operation()).thenCompose(found->{
        if(found.isEmpty())return CompletableFuture.completedFuture(Outcome.QUARANTINED);Operation op=found.get();
        if(op.phase()==Phase.COMPLETE)return repository.tower(claim.request().tower()).thenCompose(row->{
            if(row.isEmpty()||!row.get().mutationReady()||row.get().rowVersion()!=claim.request().rowVersion()+2||!row.get().state().equals(op.intent().after()))return CompletableFuture.completedFuture(Outcome.QUARANTINED);
            var review=new Intent(UUID.randomUUID(),op.intent().towerId(),row.get().rowVersion(),row.get().state().tower().generation(),row.get().state().tower().deploymentRevision(),"catalog-light-review",row.get().state(),new Document(1,"Completed light outcome changed; preserve physical evidence"));
            return repository.beginNewIntent(review).thenCompose(pending->repository.advanceOperation(pending.intent().id(),pending.rowVersion(),Phase.INTENT,Phase.RECONCILING,"Light review required")).thenApply(ignored->Outcome.QUARANTINED);
        });
        if(!op.pending()||op.phase()==Phase.RECONCILING)return CompletableFuture.completedFuture(Outcome.QUARANTINED);
        return repository.advanceOperation(op.intent().id(),op.rowVersion(),op.phase(),Phase.RECONCILING,"Light effect/commit uncertain; no blind restoration").thenApply(ignored->Outcome.QUARANTINED);
    }).exceptionally(failure->Outcome.QUARANTINED);}
    static Component lightCell(TowerSnapshot row,Geometry geometry){Position p=row.state().tower().position();int x=geometry.lightX(),z=geometry.lightZ();int dx,dz;
        switch(p.orientation()){case "north"->{dx=x;dz=z;}case "east"->{dx=-z;dz=x;}case "south"->{dx=-x;dz=-z;}case "west"->{dx=z;dz=-x;}default->throw new IllegalArgumentException("Unknown orientation");}
        int targetX=Math.addExact(p.x(),dx),targetY=Math.addExact(p.y(),geometry.lightY()),targetZ=Math.addExact(p.z(),dz);
        return row.state().components().stream().filter(c->c.kind()==ComponentKind.BLOCK&&c.x()==targetX&&c.y()==targetY&&c.z()==targetZ&&c.world().equals(p.world())).findFirst().orElseThrow(()->new IllegalArgumentException("Declared light voxel missing"));
    }
    private static boolean sameCell(Component a,Component b){return a.id().equals(b.id())&&a.kind()==b.kind()&&a.world().equals(b.world())&&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z()&&a.originalState().equals(b.originalState())&&b.entity()==null&&b.entityType()==null;}
    @Override public void close(){closed=true;}
}
