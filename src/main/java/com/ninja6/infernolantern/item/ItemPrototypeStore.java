package com.ninja6.infernolantern.item;

import com.ninja6.infernolantern.repository.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Async bootstrap/close without Minecraft access. Pending evidence never becomes ready automatically. */
public final class ItemPrototypeStore {
    public record Ready(SqliteRepository repository, boolean mutationsReady) {}
    private boolean closed, openingFinished, started;
    private SqliteRepository repository;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    public CompletableFuture<Ready> open(Path file, boolean create, GroundPolicy policy) {
        synchronized(this){if(started||closed)return CompletableFuture.failedFuture(new IllegalStateException("Item store lifecycle already started or closed"));started=true;}
        return CompletableFuture.supplyAsync(() -> create || Files.exists(file))
                .thenCompose(needed -> needed ? SqliteRepository.open(file, 256) : CompletableFuture.completedFuture(null))
                .thenCompose(opened -> {
                    synchronized (this) {
                        repository = opened;
                        if (closed) { closeOpened(); return CompletableFuture.failedFuture(new IllegalStateException("Item prototype stopped")); }
                    }
                    if (opened == null) return CompletableFuture.completedFuture(new Ready(null, false));
                    Document definition = new Document(1, "disposable fixture;maximum-hp=100;fire-survival=" + policy.survivesFireAndLava() + ";despawn=" + policy.despawns());
                    return opened.variant(FixtureDelivery.VARIANT).thenCompose(found -> {
                        if(found.isEmpty())return opened.createVariant(new Variant(FixtureDelivery.VARIANT,1,VariantStatus.ACTIVE,definition,0));
                        Variant old=found.get();
                        if(old.lastValidDefinition().equals(definition))return CompletableFuture.completedFuture(old);
                        return opened.compareAndSetVariant(old.rowVersion(),new Variant(old.id(),Math.incrementExact(old.definitionRevision()),old.status(),definition,Math.incrementExact(old.rowVersion())));
                    })
                            .thenCompose(variant -> opened.pendingOperations(null, 1))
                            .thenApply(pending -> new Ready(opened, pending.isEmpty()));
                }).whenComplete((ready,failure)->{synchronized(this){openingFinished=true;if(closed)closeOpened();}});
    }
    public synchronized CompletableFuture<Void> closeAsync() { closed = true; closeOpened(); return stopped; }
    private void closeOpened() {
        if (repository == null) { if(!started||openingFinished)stopped.complete(null); return; }
        repository.closeAsync().whenComplete((ignored, failure) -> { if (failure == null) stopped.complete(null); else stopped.completeExceptionally(failure); });
    }
}
