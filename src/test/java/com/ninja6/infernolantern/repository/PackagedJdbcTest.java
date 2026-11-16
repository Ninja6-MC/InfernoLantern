package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PackagedJdbcTest {
    @TempDir Path directory;
    @Test void actualDeployableJarLoadsItsOwnJdbcDriverAndNativeLibrary()throws Exception{
        Path jar=Path.of(System.getProperty("pluginJar"));
        try(var archive=new ZipFile(jar.toFile())){
            assertNotNull(archive.getEntry("org/sqlite/JDBC.class"));
            assertNotNull(archive.getEntry("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"));
            assertNotNull(archive.getEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"));
            assertNotNull(archive.getEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"));
            assertTrue(new String(archive.getInputStream(archive.getEntry("META-INF/services/java.sql.Driver")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("org.sqlite.JDBC"));
            assertFalse(archive.stream().anyMatch(e->e.getName().startsWith("org/bukkit/")||e.getName().startsWith("org/slf4j/")));
        }
        Path source=directory.resolve("JdbcSmoke.java");
        Files.writeString(source,"""
            import java.nio.file.*;
            import java.sql.*;
            import java.util.concurrent.*;
            import com.ninja6.infernolantern.repository.SqliteRepository;
            class JdbcSmoke {
              public static void main(String[] args)throws Exception {
                Class<?> driver=Class.forName("org.sqlite.JDBC");
                if(!Path.of(driver.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(Path.of(args[1])))throw new AssertionError("Driver not loaded from plugin JAR");
                var service=java.util.ServiceLoader.load(java.sql.Driver.class).stream().anyMatch(p->p.type().getName().equals("org.sqlite.JDBC"));
                if(!service)throw new AssertionError("Missing driver service");
                var r=SqliteRepository.open(Path.of(args[0]),8).get(10,TimeUnit.SECONDS);
                System.out.println("Packaged SQLite "+r.sqliteVersion().get(10,TimeUnit.SECONDS));
                r.closeAsync().get(10,TimeUnit.SECONDS);
              }
            }
            """);
        // A separate JVM has no test/runtime JDBC classpath. Only Paper's logging API is supplied.
        Path slf4j=Path.of(Class.forName("org.slf4j.LoggerFactory").getProtectionDomain().getCodeSource().getLocation().toURI());
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--class-path",jar+java.io.File.pathSeparator+slf4j,source.toString(),directory.resolve("packaged.db").toString(),jar.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("probe.log").toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        String output=Files.readString(directory.resolve("probe.log"));
        assertEquals(0,process.exitValue(),output);assertTrue(output.contains("Packaged SQLite 3.54.1"),output);
    }
}
