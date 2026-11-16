package com.ninja6.infernolantern.repository;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Synthetic local filesystem microbenchmark. No server/world/player data or APIs. */
public final class RepositoryBenchmark {
    private RepositoryBenchmark(){}
    private static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(30,TimeUnit.SECONDS);}
    private static UUID id(int n){return new UUID(0,n);}
    private static Tower tower(int n,double hp){return new Tower(id(n),"synthetic",hp,100,Form.PORTABLE,1,0,null,null,false,false,0,0);}
    private static String stats(List<Long> ns){var sorted=ns.stream().sorted().toList();return String.format(Locale.ROOT,"{\"count\":%d,\"mean_ms\":%.4f,\"p50_ms\":%.4f,\"p95_ms\":%.4f,\"p99_ms\":%.4f}",ns.size(),ns.stream().mapToLong(Long::longValue).average().orElse(0)/1e6,sorted.get(sorted.size()/2)/1e6,sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1e6,sorted.get((int)Math.ceil(sorted.size()*.99)-1)/1e6);}
    public static void main(String[] args)throws Exception{
        Path directory=Files.createTempDirectory("lantern-repository-benchmark-");
        var r=await(SqliteRepository.open(directory.resolve("synthetic.db"),256));
        String sqlite;
        var inserts=new ArrayList<Long>();var writes=new ArrayList<Long>();var pages=new ArrayList<Long>();var journals=new ArrayList<Long>();
        try{
            sqlite=await(r.sqliteVersion());
            await(r.createVariant(new Variant("synthetic",1,VariantStatus.ACTIVE,new Document(1,"synthetic definition"),0)));
            for(int i=1;i<=1000;i++){long start=System.nanoTime();await(r.createTower(tower(i,50)));inserts.add(System.nanoTime()-start);}
            for(int i=1;i<=1000;i++){long start=System.nanoTime();await(r.compareAndSetTower(0,tower(i,49)));writes.add(System.nanoTime()-start);}
            for(int i=0;i<100;i++){long start=System.nanoTime();var result=await(r.towers(null,256));if(result.size()!=256)throw new AssertionError();pages.add(System.nanoTime()-start);}
            for(int i=1;i<=100;i++){
                Tower before=tower(i,49);
                Tower placed=new Tower(id(i),"synthetic",49,100,Form.COMPACT,2,1,null,new Position(id(2000),i,70,0,"north"),false,false,0,0);
                State state=new State(placed,null,List.of(new Component("base",ComponentKind.BLOCK,id(2000),i,70,0,"minecraft:air","minecraft:barrier[waterlogged=false]",null,null)));
                UUID opid=id(10000+i);long start=System.nanoTime();
                await(r.beginIntent(new Intent(opid,before.id(),1,1,0,"synthetic-placement",state,new Document(1,"synthetic effect snapshot; no world writes"))));
                await(r.advanceOperation(opid,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"synthetic caller evidence"));
                await(r.completeOperation(opid,1,"synthetic completion"));journals.add(System.nanoTime()-start);
            }
        }finally{await(r.closeAsync());}
        String json="{\n  \"fixture\":\"1000 synthetic towers; WAL/FULL; single writer; no Minecraft process\",\n  \"sqlite\":\""+sqlite+"\",\n  \"jdbc\":\"3.54.1.0\",\n  \"java\":\""+System.getProperty("java.version")+"\",\n  \"os\":\""+System.getProperty("os.name")+" "+System.getProperty("os.arch")+"\",\n  \"available_processors\":"+Runtime.getRuntime().availableProcessors()+",\n  \"max_heap_bytes\":"+Runtime.getRuntime().maxMemory()+",\n  \"insert\":"+stats(inserts)+",\n  \"cas_write\":"+stats(writes)+",\n  \"page_256\":"+stats(pages)+",\n  \"journal_three_commits\":"+stats(journals)+"\n}\n";
        Path output=Path.of("build/benchmarks/repository.json");Files.createDirectories(output.getParent());Files.writeString(output,json);System.out.print(json);
        // Only the private synthetic fixture is removed; report contains no temporary path or IDs.
        try(var files=Files.list(directory)){for(Path p:files.toList())Files.delete(p);}Files.delete(directory);
    }
}
