package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogMigrationTest {
    @TempDir Path directory;
    @Test void schemaThreeMigrationCreatesNoAuthorityOrVariantAdoption()throws Exception{
        Path file=directory.resolve("v3.db");
        try(var c=SqliteTestConnections.open(file);var s=c.createStatement()){
            s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for(int version=1;version<=3;version++){for(String sql:Schema.MIGRATIONS.get(version-1))s.execute(sql);s.execute("INSERT INTO schema_migrations VALUES("+version+",'"+Schema.checksum(version)+"')");}
            s.execute("INSERT INTO variants VALUES('prototype.item-fixture',1,'ACTIVE',1,'opaque fixture definition',0)");s.execute("INSERT INTO variants VALUES('legacy',1,'ACTIVE',1,'opaque legacy definition',0)");s.execute("PRAGMA application_id="+Schema.APPLICATION_ID);s.execute("PRAGMA user_version=3");
        }
        var repository=SqliteRepository.open(file,128).get(10,TimeUnit.SECONDS);
        try{var catalog=repository.catalog().get(10,TimeUnit.SECONDS);assertEquals(0,catalog.revision());assertTrue(catalog.variants().isEmpty());assertEquals("opaque legacy definition",repository.variant("legacy").get(10,TimeUnit.SECONDS).orElseThrow().lastValidDefinition().content());assertEquals("opaque fixture definition",repository.variant("prototype.item-fixture").get(10,TimeUnit.SECONDS).orElseThrow().lastValidDefinition().content());}
        finally{repository.closeAsync().get(10,TimeUnit.SECONDS);}
        try(var c=SqliteTestConnections.open(file)){assertEquals(4,Schema.pragma(c,"user_version"));}
    }
}
