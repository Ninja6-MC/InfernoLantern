package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Explicit reconciliation calls only. No startup replay, force loading or replacement delivery. */
public final class ReconciliationService implements AutoCloseable {
    public enum Outcome { COMPLETED, ROLLED_BACK, WAIT, ADMIN_REQUIRED, STALE, TERMINAL, UNAVAILABLE }
    public enum Physical { BEFORE, AFTER, MIXED, ABSENT, UNKNOWN, UNSUPPORTED }
    public enum Coverage { MATCHED_COMPLETE, INCOMPLETE, UNMATCHED }
    public record Result(UUID operation,Outcome outcome,String diagnostic) {}
    /** Coverage is independently established by the trusted observation adapter, never by an envelope. */
    public record Observation(UUID operation,long operationVersion,long towerVersion,Coverage coverage,Physical physical,String evidence) {
        public Observation {Objects.requireNonNull(operation);Objects.requireNonNull(coverage);Objects.requireNonNull(physical);Objects.requireNonNull(evidence);
            if(operationVersion<0||towerVersion<0||evidence.isBlank()||evidence.length()>4096)throw new IllegalArgumentException("Observation bounds");}
        public static Observation of(Operation operation,TowerSnapshot tower,Coverage coverage,Physical physical,String evidence){return new Observation(operation.intent().id(),operation.rowVersion(),tower.rowVersion(),coverage,physical,evidence);}
    }
    public record Inspection(Operation operation,TowerSnapshot tower,RecoveryEnvelope.Context context,Observation observation) {}
    public interface WorldPort {
        Observation inspect(Operation operation,TowerSnapshot tower,RecoveryEnvelope.Context context);
        /** Independently reread the exact source/target and coverage before a database decision. */
        boolean revalidate(Inspection inspection);
        /** Revalidate after the winning commit. The resolved row includes any deadline stamped at completion. */
        boolean publish(Inspection inspection,TowerSnapshot resolved);
    }
    private final SqliteRepository repository;
    private final WorldScheduler scheduler;
    private final LongSupplier exitClock;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public ReconciliationService(SqliteRepository repository,WorldScheduler scheduler){
        this(repository,scheduler,System::currentTimeMillis);
    }
    /** The exit clock runs on the SQL worker and must have no server dependencies. */
    public ReconciliationService(SqliteRepository repository,WorldScheduler scheduler,LongSupplier exitClock){
        this.repository=Objects.requireNonNull(repository);this.scheduler=Objects.requireNonNull(scheduler);
        this.exitClock=Objects.requireNonNull(exitClock);
    }
    public CompletableFuture<Result> reconcile(UUID id,WorldPort port){
        Objects.requireNonNull(id);Objects.requireNonNull(port);
        if(closed||!active.add(id))return done(id,Outcome.UNAVAILABLE,"Coordinator closed or operation already being observed");
        return repository.operation(id).thenCompose(found->{
            if(found.isEmpty())return done(id,Outcome.STALE,"Operation no longer available");Operation op=found.get();
            if(!op.pending())return done(id,Outcome.TERMINAL,"Terminal receipt is read-only; unavailable stages require a separate reviewed disposition");
            return repository.tower(op.intent().towerId()).thenCompose(tower->{
                if(tower.isEmpty()||!owned(op,tower.get()))return done(id,Outcome.STALE,"Operation no longer owns the exact frozen representation");
                var parsed=RecoveryEnvelope.decode(op.intent().effects());
                if(parsed.isEmpty()||!RecoveryEnvelope.supports(op,parsed.get()))
                    return done(id,Outcome.ADMIN_REQUIRED,"Legacy, unsupported or administrative evidence; preserve quarantine");
                RecoveryEnvelope.Context context=parsed.get();
                return scheduler.supply(context.anchor(),()->closed?null:port.inspect(op,tower.get(),context)).handle((observation,failure)->{
                    if(failure!=null||observation==null)return done(id,Outcome.WAIT,"Owner world, chunk, player or observation is unavailable");
                    if(!observation.operation().equals(id)||observation.operationVersion()!=op.rowVersion()||observation.towerVersion()!=tower.get().rowVersion())return done(id,Outcome.STALE,"Observation belongs to another journal snapshot");
                    if(observation.coverage()==Coverage.INCOMPLETE||observation.physical()==Physical.UNKNOWN)return done(id,Outcome.WAIT,"Observation incomplete; absence is not loss");
                    if(observation.coverage()!=Coverage.MATCHED_COMPLETE||!(observation.physical()==Physical.BEFORE||observation.physical()==Physical.AFTER))return done(id,Outcome.ADMIN_REQUIRED,"Unmatched, missing or mixed saves require reviewed evidence");
                    return decide(new Inspection(op,tower.get(),context,observation),port);
                }).thenCompose(f->f);
            });
        }).exceptionally(failure->new Result(id,Outcome.UNAVAILABLE,"Repository or evidence failed; no world effect replayed"))
                .whenComplete((result,failure)->active.remove(id));
    }
    private CompletableFuture<Result> decide(Inspection inspection,WorldPort port){
        UUID id=inspection.operation().intent().id();
        return repository.operation(id).thenCompose(latest->{
            if(closed||latest.isEmpty()||!latest.get().equals(inspection.operation()))return done(id,Outcome.STALE,"Journal changed during observation");
            return scheduler.supply(inspection.context().anchor(),()->!closed&&port.revalidate(inspection))
                    .handle((valid,failure)->failure==null&&Boolean.TRUE.equals(valid)).thenCompose(valid->{
                        if(!valid)return done(id,Outcome.WAIT,"Owner evidence changed before finalization; preserve pending state");
                        Operation op=inspection.operation();boolean rollback=inspection.observation().physical()==Physical.BEFORE;
                        CompletableFuture<Operation> phase;
                        if(rollback)phase=op.phase()==Phase.RECONCILING?CompletableFuture.completedFuture(op):repository.advanceOperation(id,op.rowVersion(),op.phase(),Phase.RECONCILING,"Recovery observed exact original representation; no physical replay");
                        else phase=op.phase()==Phase.EFFECTS_APPLIED?CompletableFuture.completedFuture(op):repository.advanceOperation(id,op.rowVersion(),op.phase(),Phase.EFFECTS_APPLIED,"Recovery observed exact named after representation; independent save coverage supplied");
                        return phase.thenCompose(current->finalizeObserved(current,inspection.observation(),rollback))
                                .thenCompose(receipt->repository.tower(op.intent().towerId()).thenCompose(row->{
                                    State expected=rollback?receipt.before():receipt.intent().after();
                                    if(row.isEmpty()||!resolved(op,row.get(),expected))return done(id,Outcome.STALE,"Newer representation prevents publication");
                                    TowerSnapshot committed=row.get();
                                    return scheduler.supply(inspection.context().anchor(),()->!closed&&port.publish(inspection,committed))
                                            .handle((published,failure)->failure==null&&Boolean.TRUE.equals(published))
                                            .thenCompose(published->published?done(id,rollback?Outcome.ROLLED_BACK:Outcome.COMPLETED,"Exact representation reconciled; no replay or replacement"):
                                                freeze(inspection,committed));
                                })).exceptionally(failure->new Result(id,unwrap(failure) instanceof RepositoryException.Conflict?Outcome.STALE:Outcome.UNAVAILABLE,"Finalization changed or failed; no publication replayed"));
                    });
        });
    }
    private CompletableFuture<Operation> finalizeObserved(Operation op,Observation observation,boolean rollback){
        UUID id=op.intent().id();
        String evidence="Recovered "+observation.physical()+"; "+observation.evidence();
        if(!rollback&&timedStructureExit(op.intent().kind())){
            return repository.finalizeTimedExitReconciliation(id,op.rowVersion(),evidence,exitClock);
        }
        return repository.finalizeReconciliation(id,op.rowVersion(),rollback,evidence);
    }
    private CompletableFuture<Result> freeze(Inspection inspection,TowerSnapshot committed){
        UUID id=inspection.operation().intent().id();
        UUID review=UUID.nameUUIDFromBytes((id+":reconciliation-review").getBytes(StandardCharsets.UTF_8));
        Tower t=committed.state().tower();
        var intent=new Intent(review,t.id(),committed.rowVersion(),t.generation(),t.deploymentRevision(),"reconciliation-review",committed.state(),new Document(1,"origin="+id+";publication uncertain after recovery commit; preserve existing stage and independent-save evidence"));
        return repository.beginNewIntent(intent).thenCompose(op->repository.advanceOperation(review,op.rowVersion(),Phase.INTENT,Phase.RECONCILING,"No blind activation replay; administrative observation required"))
                .handle((ignored,failure)->new Result(id,failure!=null&&unwrap(failure) instanceof RepositoryException.Conflict?Outcome.STALE:Outcome.ADMIN_REQUIRED,"Post-commit observation or activation failed; preserve evidence without replay"));
    }
    private static boolean owned(Operation op,TowerSnapshot row){return row.activeOperation()!=null&&row.activeOperation().equals(op.intent().id())&&row.rowVersion()==op.intent().expectedRowVersion()+1&&row.state().equals(op.before());}
    private static boolean resolved(Operation op,TowerSnapshot row,State expected){return row.mutationReady()&&row.rowVersion()==op.intent().expectedRowVersion()+2&&row.state().equals(expected);}
    private static Throwable unwrap(Throwable e){while(e instanceof CompletionException&&e.getCause()!=null)e=e.getCause();return e;}
    private static CompletableFuture<Result> done(UUID id,Outcome outcome,String diagnostic){return CompletableFuture.completedFuture(new Result(id,outcome,diagnostic));}
    @Override public void close(){closed=true;}
}
