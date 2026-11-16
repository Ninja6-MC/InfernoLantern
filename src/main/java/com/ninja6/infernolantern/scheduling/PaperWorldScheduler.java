package com.ninja6.infernolantern.scheduling;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Paper main-thread adapter. No chunk loads and no Folia compatibility claim. */
public final class PaperWorldScheduler implements WorldScheduler, AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public PaperWorldScheduler(JavaPlugin plugin){this.plugin=Objects.requireNonNull(plugin);}
    @Override public <T> CompletableFuture<T> supply(Anchor anchor,Supplier<T> action){
        Objects.requireNonNull(anchor);Objects.requireNonNull(action);
        var result=new CompletableFuture<T>();pending.add(result);
        result.whenComplete((value,failure)->pending.remove(result));
        if(closed){result.completeExceptionally(new IllegalStateException("World scheduler closed"));return result;}
        try{
            Bukkit.getScheduler().runTask(plugin,()->{
                if(result.isDone())return;
                if(closed||!plugin.isEnabled()){result.completeExceptionally(new IllegalStateException("World scheduler unavailable"));return;}
                World world=Bukkit.getWorld(anchor.world());
                if(world==null||!world.isChunkLoaded(anchor.chunkX(),anchor.chunkZ())){result.completeExceptionally(new IllegalStateException("Owning world/chunk is unavailable"));return;}
                try{result.complete(action.get());}catch(Throwable failure){result.completeExceptionally(failure);}
            });
        }catch(RuntimeException failure){result.completeExceptionally(failure);}
        return result;
    }
    /** Call during owner-thread plugin shutdown before its tasks are cancelled. */
    @Override public void close(){closed=true;for(var future:pending)future.completeExceptionally(new IllegalStateException("World scheduler closed"));}
}
