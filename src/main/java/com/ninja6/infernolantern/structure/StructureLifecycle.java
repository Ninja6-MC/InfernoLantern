package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Journaled structure transitions. World/player saves are independent of database commits. */
public final class StructureLifecycle implements AutoCloseable {
    public enum Action { PLACE, EXPAND, COMPACT, BREAK, ADMIN_RECOVER }
    public enum Outcome { COMMITTED, REJECTED, ROLLED_BACK, QUARANTINED }
    public record Result(Outcome outcome,UUID operation) {}
    /** Managed variants require the current catalog revision; unmanaged legacy policies keep their own revision. */
    public record Policy(long revision,double expansionPercent,long cooldownMillis,long now,boolean explicitOverride) {
        public Policy {
            if(revision<0||!Double.isFinite(expansionPercent)||expansionPercent<=0||expansionPercent>100||cooldownMillis<0||now<0)throw new IllegalArgumentException("Policy bounds");
        }
    }
    /** Session and revision are capabilities to revalidate, not permanent ownership. */
    public record Request(UUID operation,UUID tower,long rowVersion,long generation,long revision,UUID actor,UUID session,
                          Action action,Position base,Policy policy) {
        public Request {
            Objects.requireNonNull(operation);Objects.requireNonNull(tower);Objects.requireNonNull(actor);Objects.requireNonNull(session);
            Objects.requireNonNull(action);Objects.requireNonNull(base);Objects.requireNonNull(policy);
            if(rowVersion<0||generation<0||revision<0)throw new IllegalArgumentException("Expected version");
        }
        public WorldScheduler.Anchor anchor(){return new WorldScheduler.Anchor(base.world(),base.x()>>4,base.z()>>4);}
    }
    /** Every clearance voxel must be represented, including unchanged AIR/fluid cells. */
    public record Preparation(List<Component> manifest,Ground stagedGround,Document evidence) {
        public Preparation {manifest=List.copyOf(manifest);Objects.requireNonNull(evidence);}
    }
    public record Claim(Request request,Operation operation,Preparation preparation) {}
    /** All calls run on the owner scheduler. No automatic replay or replacement is permitted. */
    public interface WorldEffects {
        Preparation prepare(Request request,TowerSnapshot before);
        boolean validate(Claim claim);
        default CommitPermit commitPermit() { return null; }
        boolean apply(Claim claim);
        boolean verify(Claim claim);
        /** Restore only provably owned effects and verify the original source/structure. */
        boolean rollback(Claim claim);
        /** Remove only this unadmitted unavailable stage, never the source or an unrelated entity. */
        void discard(Request request,Preparation preparation);
        /** Ground delivery remains unavailable until the completed row is re-read and verified here. */
        boolean activate(Claim claim,TowerSnapshot completed);
    }
    private final SqliteRepository repository;
    private final WorldScheduler scheduler;
    private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public StructureLifecycle(SqliteRepository repository,WorldScheduler scheduler){this.repository=Objects.requireNonNull(repository);this.scheduler=Objects.requireNonNull(scheduler);}
    public CompletableFuture<Result> execute(Request request,WorldEffects effects){
        Objects.requireNonNull(request);Objects.requireNonNull(effects);
        if(closed||!inFlight.add(request.operation()))return done(Outcome.REJECTED,request.operation());
        return repository.operation(request.operation()).thenCompose(receipt->{
            if(receipt.isPresent()){
                if(!sameReceipt(request,receipt.get())||receipt.get().phase()!=Phase.COMPLETE)return done(Outcome.QUARANTINED,request.operation());
                return repository.tower(request.tower()).thenCompose(row->done(row.isPresent()&&row.get().mutationReady()?Outcome.COMMITTED:Outcome.QUARANTINED,request.operation()));
            }
            return repository.tower(request.tower()).thenCompose(found->{
                if(closed||found.isEmpty()||!matches(request,found.get()))return done(Outcome.REJECTED,request.operation());
                TowerSnapshot before=found.get();
                return scheduler.supply(request.anchor(),()->closed?null:effects.prepare(request,before)).thenCompose(prepared->{
                    if(prepared==null)return done(Outcome.REJECTED,request.operation());
                    Intent intent;
                    try { State after=after(request,before.state(),prepared);
                        var context=RecoveryEnvelope.decode(prepared.evidence()).filter(c->c.kind().equals(kind(request))&&c.anchor().equals(request.anchor()));
                        Document original=RecoveryEnvelope.original(prepared.evidence());
                        Document payload=new Document(1,fingerprint(request)+"\n"+original.content());
                        Document recovery=context.isPresent()?RecoveryEnvelope.structure(kind(request),request.anchor(),payload,context.get().inventory()):RecoveryEnvelope.administrative(kind(request),request.anchor(),payload);
                        intent=new Intent(request.operation(),request.tower(),before.rowVersion(),request.generation(),request.revision(),kind(request),after,recovery);
                    } catch(RuntimeException invalid){return discard(request,effects,prepared);}
                    return repository.beginStructureIntent(intent,request.policy().revision()).handle((operation,failure)->{
                        if(failure==null)return run(new Claim(request,operation,prepared),effects);
                        // A known CAS conflict rolled back admission. An unknown I/O outcome may have persisted.
                        Throwable cause=unwrap(failure);
                        if(cause instanceof RepositoryException.Conflict)return discard(request,effects,prepared);
                        return done(Outcome.QUARANTINED,request.operation());
                    }).thenCompose(f->f);
                });
            });
        }).exceptionally(failure->new Result(Outcome.QUARANTINED,request.operation()))
                .whenComplete((result,failure)->inFlight.remove(request.operation()));
    }
    private CompletableFuture<Boolean> variantReady(Request r,String variant){
        return repository.variant(variant).thenApply(found->found.isPresent()&&((r.action()!=Action.PLACE&&r.action()!=Action.EXPAND)||found.get().status()==VariantStatus.ACTIVE));
    }
    private CompletableFuture<Result> discard(Request r,WorldEffects effects,Preparation prepared){
        return scheduler.supply(r.anchor(),()->{effects.discard(r,prepared);return new Result(Outcome.REJECTED,r.operation());})
                .exceptionally(failure->new Result(Outcome.QUARANTINED,r.operation()));
    }
    private CompletableFuture<Result> run(Claim claim,WorldEffects effects){
        Request r=claim.request();
        return variantReady(r,claim.operation().before().tower().variantId()).thenCompose(variantReady->scheduler.supply(r.anchor(),()->!closed&&variantReady&&effects.validate(claim)&&effects.apply(claim)))
                .handle((applied,failure)->failure==null&&Boolean.TRUE.equals(applied)).thenCompose(applied->{
                    if(!applied)return rollback(claim,effects);
                    return variantReady(r,claim.operation().before().tower().variantId()).thenCompose(variantReady->scheduler.supply(r.anchor(),()->!closed&&variantReady&&effects.validate(claim)&&effects.verify(claim)))
                            .handle((verified,failure)->failure==null&&Boolean.TRUE.equals(verified))
                            .thenCompose(verified->verified?finish(claim,effects):rollback(claim,effects));
                });
    }
    private record CommitCheck(CommitPermit permit) {}
    private CompletableFuture<Result> finish(Claim claim,WorldEffects effects){
        Operation op=claim.operation();UUID id=op.intent().id();
        return repository.advanceOperation(id,op.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"Owner verified named structure effects; world/player saves remain independent")
                .thenCompose(applied->variantReady(claim.request(),op.before().tower().variantId())
                        .thenCompose(ready->scheduler.supply(claim.request().anchor(),()->!closed&&ready&&effects.validate(claim)
                                ?new CommitCheck(effects.commitPermit()):null))
                        .handle((check,failure)->failure==null?check:null)
                        .thenCompose(check->check==null?rollback(claim,effects,applied):complete(claim,effects,applied,check.permit())))
                .exceptionallyCompose(failure->quarantineLatest(claim));
    }
    private CompletableFuture<Result> complete(Claim claim,WorldEffects effects,Operation applied,CommitPermit permit){
        UUID id=applied.intent().id();
        return repository.completeStructureOperation(id,applied.rowVersion(),claim.request().policy().revision(),"Verified structure effects, not an atomic Minecraft save",permit)
                .<CompletableFuture<Result>>handle((committed,failure)->{
                    if(failure!=null){
                        Throwable cause=unwrap(failure);
                        return (cause instanceof RepositoryException.CatalogSuperseded||cause instanceof RepositoryException.CommitRevoked)
                                &&cause.getSuppressed().length==0
                                ?rollback(claim,effects,applied):quarantineLatest(claim);
                    }
                    return repository.tower(applied.intent().towerId())
                            .thenCompose(found->scheduler.supply(claim.request().anchor(),()->!closed&&found.isPresent()&&effects.activate(claim,found.get())))
                            .thenCompose(active->active?done(Outcome.COMMITTED,id):freezeCompleted(claim));
                }).thenCompose(result->result);
    }
    private CompletableFuture<Result> rollback(Claim claim,WorldEffects effects){
        return rollback(claim,effects,claim.operation());
    }
    private CompletableFuture<Result> rollback(Claim claim,WorldEffects effects,Operation pending){
        return markQuarantined(pending).thenCompose(quarantined->
                scheduler.supply(claim.request().anchor(),()->!closed&&effects.rollback(claim))
                        .handle((restored,failure)->failure==null&&Boolean.TRUE.equals(restored))
                        .thenCompose(restored->{
                            if(!restored)return done(Outcome.QUARANTINED,claim.request().operation());
                            return repository.resolveRollback(quarantined.intent().id(),quarantined.rowVersion(),"Owner proved source and original structure restored; only owned effects compensated")
                                    .thenCompose(done->done(Outcome.ROLLED_BACK,claim.request().operation()));
                        })).exceptionallyCompose(failure->quarantineLatest(claim));
    }
    private CompletableFuture<Operation> markQuarantined(Operation op){
        if(op.phase()==Phase.RECONCILING)return CompletableFuture.completedFuture(op);
        return repository.advanceOperation(op.intent().id(),op.rowVersion(),op.phase(),Phase.RECONCILING,"Structure source/policy/component outcome uncertain; no replacement or automatic replay");
    }
    private CompletableFuture<Result> quarantineLatest(Claim claim){
        return repository.operation(claim.request().operation()).thenCompose(found->{
            if(found.isEmpty())return done(Outcome.QUARANTINED,claim.request().operation());
            if(found.get().phase()==Phase.COMPLETE)return freezeCompleted(claim);
            if(!found.get().pending())return done(Outcome.QUARANTINED,claim.request().operation());
            return markQuarantined(found.get()).thenCompose(op->done(Outcome.QUARANTINED,op.intent().id()));
        }).exceptionally(failure->new Result(Outcome.QUARANTINED,claim.request().operation()));
    }
    private CompletableFuture<Result> freezeCompleted(Claim claim){
        // Completion is not evidence that a staged drop became usable; freeze the latest exact receipt.
        return repository.tower(claim.request().tower()).thenCompose(found->{
            if(found.isEmpty())return done(Outcome.QUARANTINED,claim.request().operation());
            TowerSnapshot row=found.get();
            if(row.activeOperation()!=null||row.rowVersion()!=Math.addExact(claim.request().rowVersion(),2)||!row.state().equals(claim.operation().intent().after()))return done(Outcome.QUARANTINED,claim.request().operation());
            var intent=new Intent(UUID.randomUUID(),row.state().tower().id(),row.rowVersion(),row.state().tower().generation(),row.state().tower().deploymentRevision(),"structure-activation-review",row.state(),new Document(1,"Completed operation activation uncertain; preserve exact staged delivery and save evidence"));
            return repository.beginNewIntent(intent).thenCompose(this::markQuarantined).thenCompose(op->done(Outcome.QUARANTINED,claim.request().operation()));
        }).exceptionally(failure->new Result(Outcome.QUARANTINED,claim.request().operation()));
    }
    private static boolean matches(Request r,TowerSnapshot row){
        Tower t=row.state().tower();
        if(!row.mutationReady()||row.rowVersion()!=r.rowVersion()||t.generation()!=r.generation()||t.deploymentRevision()!=r.revision())return false;
        if(r.action()!=Action.PLACE&&!r.base().equals(t.position()))return false;
        return switch(r.action()){
            case PLACE->t.form()==Form.PORTABLE;
            case EXPAND->t.form()==Form.COMPACT&&t.hp()>=t.maximumHp()*r.policy().expansionPercent()/100
                    &&(r.policy().explicitOverride()||r.policy().now()>=t.expandAfter());
            case COMPACT->t.form()==Form.EXPANDED&&(r.policy().explicitOverride()||r.actor().equals(t.controller()));
            case BREAK->t.form()==Form.COMPACT;
            case ADMIN_RECOVER->t.form()==Form.EXPANDED&&r.policy().explicitOverride();
        };
    }
    private static State after(Request r,State before,Preparation prepared){
        Tower t=before.tower();Form form=switch(r.action()){case PLACE,COMPACT->Form.COMPACT;case EXPAND->Form.EXPANDED;case BREAK,ADMIN_RECOVER->Form.GROUND;};
        long generation=r.action()==Action.PLACE||form==Form.GROUND?Math.incrementExact(t.generation()):t.generation();
        long revision=Math.incrementExact(t.deploymentRevision());
        long cooldown=r.action()==Action.COMPACT||r.action()==Action.ADMIN_RECOVER?Math.addExact(r.policy().now(),r.policy().cooldownMillis()):t.expandAfter();
        Tower next=new Tower(t.id(),t.variantId(),t.hp(),t.maximumHp(),form,generation,revision,form==Form.EXPANDED?r.actor():null,
                form==Form.GROUND?null:r.base(),t.light(),t.defence(),cooldown,t.repairAfter());
        if(form==Form.GROUND){Ground g=Objects.requireNonNull(prepared.stagedGround());
            if(!g.deliveryOperation().equals(r.operation())||!g.world().equals(r.base().world())||g.chunkX()!=r.anchor().chunkX()||g.chunkZ()!=r.anchor().chunkZ()||!prepared.manifest().isEmpty())throw new IllegalArgumentException("Ground staging ownership");
        }else if(prepared.stagedGround()!=null)throw new IllegalArgumentException("Unexpected drop");
        Set<Cell> unique=new HashSet<>();boolean base=false;
        for(Component c:prepared.manifest()){
            if(!c.world().equals(r.base().world())||(c.x()>>4)!=r.anchor().chunkX()||(c.z()>>4)!=r.anchor().chunkZ())throw new IllegalArgumentException("Unsupported cross-chunk geometry");
            if(c.kind()==ComponentKind.BLOCK&&!unique.add(new Cell(c.world(),c.x(),c.y(),c.z())))throw new IllegalArgumentException("Duplicate clearance voxel");
            if(c.kind()==ComponentKind.BLOCK&&c.x()==r.base().x()&&c.y()==r.base().y()&&c.z()==r.base().z())base=true;
        }
        if(form!=Form.GROUND&&!base)throw new IllegalArgumentException("Base clearance missing");
        for(Component c:prepared.manifest())if(c.kind()==ComponentKind.ENTITY&&!unique.contains(new Cell(c.world(),c.x(),c.y(),c.z())))throw new IllegalArgumentException("Entity outside declared clearance");
        if(!unique.isEmpty()){
            long minX=unique.stream().mapToInt(Cell::x).min().orElseThrow(),maxX=unique.stream().mapToInt(Cell::x).max().orElseThrow();
            long minY=unique.stream().mapToInt(Cell::y).min().orElseThrow(),maxY=unique.stream().mapToInt(Cell::y).max().orElseThrow();
            long minZ=unique.stream().mapToInt(Cell::z).min().orElseThrow(),maxZ=unique.stream().mapToInt(Cell::z).max().orElseThrow();
            long dx=maxX-minX+1,dy=maxY-minY+1,dz=maxZ-minZ+1;
            if(dx>MAX_COMPONENTS||dy>MAX_COMPONENTS||dz>MAX_COMPONENTS||dx*dy*dz!=unique.size())throw new IllegalArgumentException("Incomplete declared clearance box");
        }
        if(form==Form.COMPACT&&unique.size()!=1)throw new IllegalArgumentException("Compact footprint must be one voxel");
        return new State(next,prepared.stagedGround(),prepared.manifest());
    }
    private static String fingerprint(Request r){return r.toString();}
    private static String kind(Request r){return "structure-"+r.action().name().toLowerCase(Locale.ROOT);}
    private static boolean sameReceipt(Request r,Operation op){
        return RecoveryEnvelope.original(op.intent().effects()).content().startsWith(fingerprint(r)+"\n")&&op.intent().towerId().equals(r.tower())&&op.intent().kind().equals(kind(r))&&op.intent().expectedRowVersion()==r.rowVersion()
                &&op.intent().expectedGeneration()==r.generation()&&op.intent().expectedDeploymentRevision()==r.revision();
    }
    private static Throwable unwrap(Throwable e){while(e instanceof CompletionException&&e.getCause()!=null)e=e.getCause();return e;}
    private static CompletableFuture<Result> done(Outcome outcome,UUID id){return CompletableFuture.completedFuture(new Result(outcome,id));}
    @Override public void close(){closed=true;}
}
