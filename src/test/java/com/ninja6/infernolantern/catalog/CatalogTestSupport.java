package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

final class CatalogTestSupport {
    static final UUID WORLD=new UUID(0,101),ACTOR=new UUID(0,102);
    static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(10,TimeUnit.SECONDS);}
    static Map<String,Object> variant(){var v=new LinkedHashMap<String,Object>();v.put("player-switches",Map.of("light",true,"defence",true));v.put("light-level",15);v.put("maximum-hp",100);v.put("expansion-cooldown-ms",60000L);v.put("geometry",Map.of("compact-model","fixture:compact","expanded-model","fixture:expanded","width",1,"height",3,"depth",1,"light-x",0,"light-y",2,"light-z",0));return v;}
    static Map<String,Object> config(){return config(Map.of("test",variant()));}
    static Map<String,Object> config(Map<String,?> variants){return new LinkedHashMap<>(Map.of("schema",1,"variants",variants));}
    static CatalogConfig.WorldRef world(){return new CatalogConfig.WorldRef(WORLD,"generic-test-world",CatalogConfig.Dimension.OVERWORLD,false);}
    static CatalogService.Conditions allowed(){return new CatalogService.Conditions(true,true,true,true,true);}
    static class Owner implements WorldScheduler {
        final Queue<Runnable> work=new ConcurrentLinkedQueue<>();boolean inside,available=true;
        public <T>CompletableFuture<T> supply(Anchor anchor,Supplier<T> action){var future=new CompletableFuture<T>();work.add(()->{if(!available){future.completeExceptionally(new IllegalStateException("Owner unavailable"));return;}inside=true;try{future.complete(action.get());}catch(Throwable e){future.completeExceptionally(e);}finally{inside=false;}});return future;}
        void run(){Runnable job=work.poll();if(job!=null)job.run();}
        void assertOwner(){if(!inside)throw new AssertionError("Owner required");}
        <T>T drain(CompletableFuture<T> future)throws Exception{long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!future.isDone()){run();if(System.nanoTime()>limit)throw new AssertionError("Owner progress timeout");Thread.yield();}return get(future);}
        void await()throws Exception{long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(work.isEmpty()){if(System.nanoTime()>limit)throw new AssertionError("No owner work");Thread.sleep(1);}}
    }
    static Tower portable(UUID id){return new Tower(id,"test",75,100,Form.PORTABLE,1,0,null,null,true,true,170,230);}
    static TowerSnapshot placed(SqliteRepository repository,Form form)throws Exception{return placed(repository,form,4);}
    static TowerSnapshot placed(SqliteRepository repository,Form form,int x)throws Exception{return placed(repository,form,x,75,100);}
    static TowerSnapshot placed(SqliteRepository repository,Form form,int x,double hp,double maximumHp)throws Exception{
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();
        get(repository.createTower(new Tower(id,"test",hp,maximumHp,Form.PORTABLE,1,0,null,null,true,true,170,230)));
        var components=new ArrayList<Component>();components.add(new Component("base",ComponentKind.BLOCK,WORLD,x,70,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null));
        if(form==Form.EXPANDED){components.add(new Component("shaft",ComponentKind.BLOCK,WORLD,x,71,4,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null));components.add(new Component("light",ComponentKind.BLOCK,WORLD,x,72,4,"minecraft:air","minecraft:air",null,null));}
        Tower tower=new Tower(id,"test",hp,maximumHp,form,2,1,form==Form.EXPANDED?ACTOR:null,new Position(WORLD,x,70,4,"north"),true,true,170,230);
        Operation op=get(repository.beginCatalogIntent(new Intent(operation,id,0,1,0,"synthetic-placement",new State(tower,null,components),new Document(1,"synthetic source/manifest proof")),get(repository.catalog()).revision()));
        op=get(repository.advanceOperation(operation,op.rowVersion(),Phase.INTENT,Phase.EFFECTS_APPLIED,"synthetic owned effects"));get(repository.completeOperation(operation,op.rowVersion(),"synthetic verified result"));return get(repository.tower(id)).orElseThrow();
    }
}
