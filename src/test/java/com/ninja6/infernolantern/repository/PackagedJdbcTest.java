package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedJdbcTest {
    @TempDir Path directory;
    @Test void actualDeployableJarContainsOnlyPrivateDriverAndNativeResources()throws Exception{
        Path jar=Path.of(System.getProperty("pluginJar"));
        try(var archive=new ZipFile(jar.toFile())){
            assertNull(archive.getEntry("org/sqlite/JDBC.class"));
            assertNull(archive.getEntry("META-INF/services/java.sql.Driver"));
            var entry=archive.getEntry(PrivateSqlite.RESOURCE.substring(1));assertNotNull(entry);
            byte[] bytes=archive.getInputStream(entry).readAllBytes();
            assertEquals(PrivateSqlite.SHA256,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            var names=new java.util.HashSet<String>();
            try(var nested=new ZipInputStream(new java.io.ByteArrayInputStream(bytes))){ZipEntry e;while((e=nested.getNextEntry())!=null)names.add(e.getName());}
            assertTrue(names.contains("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"));
            assertTrue(names.contains("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"));
            assertTrue(names.contains("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"));
            assertFalse(archive.stream().anyMatch(e->e.getName().startsWith("org/bukkit/")||e.getName().startsWith("org/slf4j/")));
        }
        probe(false);
    }
    @Test void initializedOlderParentDriverCannotReplacePrivateEngineOrBreakJournal()throws Exception{probe(true);}
    @Test void missingOrTamperedPrivateBundleFailsBeforeDatabaseMutation()throws Exception{
        Path original=Path.of(System.getProperty("pluginJar"));
        for(boolean missing:new boolean[]{true,false}){
            Path altered=directory.resolve("altered-"+missing+".jar");
            try(var input=new ZipFile(original.toFile());var output=new ZipOutputStream(Files.newOutputStream(altered))){
                var entries=input.entries();
                while(entries.hasMoreElements()){
                    var e=entries.nextElement();
                    if(e.getName().equals(PrivateSqlite.RESOURCE.substring(1))){
                        if(missing)continue;
                        output.putNextEntry(new ZipEntry(e.getName()));output.write(new byte[]{0,1,2});output.closeEntry();
                    }else{output.putNextEntry(new ZipEntry(e.getName()));input.getInputStream(e).transferTo(output);output.closeEntry();}
                }
            }
            Path file=directory.resolve("preserved-"+missing+".db");byte[] evidence={11,12,13};Files.write(file,evidence);
            Path source=directory.resolve("FailedBundle.java");
            Files.writeString(source,"""
                import java.nio.file.*;
                import java.util.concurrent.*;
                import com.ninja6.infernolantern.repository.SqliteRepository;
                class FailedBundle {public static void main(String[] args)throws Exception{
                    try{SqliteRepository.open(Path.of(args[0]),8).get(10,TimeUnit.SECONDS);throw new AssertionError("Invalid bundle accepted");}
                    catch(ExecutionException expected){if(!expected.getCause().getMessage().contains("Private SQLite initialization failed"))throw expected;}
                }}
                """);
            Path log=directory.resolve("failed-"+missing+".log");
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--class-path",altered.toString(),source.toString(),file.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished=process.waitFor(30,TimeUnit.SECONDS);if(!finished)process.destroyForcibly();
            assertTrue(finished);assertEquals(0,process.exitValue(),Files.readString(log));assertArrayEquals(evidence,Files.readAllBytes(file));
        }
    }
    private void probe(boolean host)throws Exception{
        Path jar=Path.of(System.getProperty("pluginJar"));
        Path source=directory.resolve("JdbcSmoke.java");
        Files.writeString(source,"""
            import java.nio.file.*;
            import java.sql.*;
            import java.util.*;
            import java.util.concurrent.*;
            import com.ninja6.infernolantern.repository.*;
            import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
            class JdbcSmoke {
              static <T>T get(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
              static String version(Connection c)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT sqlite_version()")){r.next();return r.getString(1);}}
              public static void main(String[] args)throws Exception {
                boolean host=Boolean.parseBoolean(args[1]);
                Connection parent=null;
                if(host){Class.forName("org.sqlite.JDBC");parent=DriverManager.getConnection("jdbc:sqlite::memory:");if(!"3.49.1".equals(version(parent)))throw new AssertionError("Host fixture mismatch");}
                int drivers=Collections.list(DriverManager.getDrivers()).size();
                Path file=Path.of(args[0]);
                var r=get(SqliteRepository.open(file,16));
                if(!"3.54.1".equals(get(r.sqliteVersion())))throw new AssertionError("Wrong private engine");
                UUID tower=UUID.randomUUID(),operation=UUID.randomUUID();
                get(r.createVariant(new Variant("probe",1,VariantStatus.ACTIVE,new Document(1,"generic probe"),0)));
                get(r.createTower(new Tower(tower,"probe",20,40,Form.PORTABLE,0,0,null,null,false,false,0,0)));
                var contenders=new ArrayList<CompletableFuture<TowerSnapshot>>();
                for(int i=0;i<8;i++)contenders.add(r.compareAndSetTower(0,new Tower(tower,"probe",19,40,Form.PORTABLE,0,0,null,null,false,false,0,0)));
                int winners=0;
                for(var contender:contenders){try{get(contender);winners++;}catch(ExecutionException denied){if(!(denied.getCause() instanceof RepositoryException.Conflict))throw denied;}}
                if(winners!=1)throw new AssertionError("CAS did not have exactly one winner");
                var field=SqliteRepository.class.getDeclaredField("connection");field.setAccessible(true);var privateConnection=(Connection)field.get(r);
                try(var statement=privateConnection.createStatement()){
                    try(var result=statement.executeQuery("PRAGMA journal_mode")){if(!result.next()||!"wal".equals(result.getString(1)))throw new AssertionError("Not WAL");}
                    try(var result=statement.executeQuery("PRAGMA synchronous")){if(!result.next()||result.getInt(1)!=2)throw new AssertionError("Not FULL");}
                    try(var result=statement.executeQuery("PRAGMA foreign_keys")){if(!result.next()||result.getInt(1)!=1)throw new AssertionError("Foreign keys disabled");}
                }
                if(!"3.54.1".equals(r.validatedEngineVersion()))throw new AssertionError("Diagnostic mismatch");
                var lost=new State(new Tower(tower,"probe",19,40,Form.LOST,1,0,null,null,false,false,0,0),null,List.of());
                get(r.beginIntent(new Intent(operation,tower,1,0,0,"probe",lost,new Document(1,"probe evidence"))));
                get(r.closeAsync());
                r=get(SqliteRepository.open(file,16));
                if(get(r.pendingOperations(null,1)).size()!=1)throw new AssertionError("Pending receipt lost");
                get(r.advanceOperation(operation,0,Phase.INTENT,Phase.EFFECTS_APPLIED,"verified probe"));
                get(r.completeOperation(operation,1,"verified probe"));
                if(get(r.tower(tower)).orElseThrow().state().tower().form()!=Form.LOST)throw new AssertionError("Journal transition lost");
                get(r.closeAsync());
                if(host){if(!"3.49.1".equals(version(parent)))throw new AssertionError("Host engine altered");parent.close();}
                if(drivers!=Collections.list(DriverManager.getDrivers()).size())throw new AssertionError("Private driver registered globally");
                System.out.println("Private SQLite 3.54.1; host "+(host?"3.49.1 unchanged":"absent")+"; journal/reopen PASS");
              }
            }
            """);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--class-path",jar.toString(),"-d",directory.toString(),source.toString()));
        String cp=jar+java.io.File.pathSeparator+directory;
        java.util.List<String> command=new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
        if(host){
            Path slf4j=Path.of(Class.forName("org.slf4j.LoggerFactory").getProtectionDomain().getCodeSource().getLocation().toURI());
            cp=System.getProperty("hostSqliteJar")+java.io.File.pathSeparator+slf4j;
            Path launcher=directory.resolve("HostFirstLauncher.java");
            Files.writeString(launcher,"""
                import java.net.*;
                import java.nio.file.*;
                class HostFirstLauncher {public static void main(String[] args)throws Exception{
                    Class.forName("org.sqlite.JDBC");
                    try(var parent=java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")){
                        try(var statement=parent.createStatement();var rows=statement.executeQuery("SELECT sqlite_version()")){
                            if(!rows.next()||!"3.49.1".equals(rows.getString(1)))throw new AssertionError("Parent native engine not initialized first");
                        }
                        try(var plugin=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()},ClassLoader.getSystemClassLoader())){
                            var main=Class.forName("JdbcSmoke",true,plugin).getDeclaredMethod("main",String[].class);main.setAccessible(true);
                            main.invoke(null,(Object)new String[]{args[2],"true"});
                        }
                    }
                }}
                """);
            command.addAll(java.util.List.of("--class-path",cp,launcher.toString(),jar.toString(),directory.toString(),directory.resolve("packaged-true.db").toString()));
        }else command.addAll(java.util.List.of("--class-path",cp,"JdbcSmoke",directory.resolve("packaged-false.db").toString(),"false"));
        Path log=directory.resolve("probe-"+host+".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished=process.waitFor(40,TimeUnit.SECONDS);if(!finished)process.destroyForcibly();
        assertTrue(finished,Files.readString(log));String output=Files.readString(log);
        assertEquals(0,process.exitValue(),output);assertTrue(output.contains("journal/reopen PASS"),output);
    }
}
