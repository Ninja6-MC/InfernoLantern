package com.ninja6.infernolantern.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class CampaignMigrationTest {
    @TempDir Path directory;

    @Test void schemaFourMigratesWithoutAdoptingLegacyAward() throws Exception {
        var file=directory.resolve("schema-four.db");
        var id=UUID.randomUUID();
        var recipient=UUID.randomUUID();
        try(var connection=PrivateSqlite.open(file);var statement=connection.createStatement()){
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for(int version=1;version<=4;version++){
                for(String sql:Schema.MIGRATIONS.get(version-1))statement.execute(sql);
                statement.execute("INSERT INTO schema_migrations VALUES("+version+",'"+Schema.checksum(version)+"')");
            }
            statement.execute("PRAGMA application_id="+Schema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=4");
            statement.execute("INSERT INTO variants VALUES('legacy',0,'ACTIVE',1,'legacy definition',0)");
            try(var insert=connection.prepareStatement("INSERT INTO awards VALUES(?,'legacy-source',?,'legacy',4,'PENDING',0,1)")){
                insert.setString(1,id.toString());
                insert.setString(2,recipient.toString());
                assertEquals(1,insert.executeUpdate());
            }
        }
        var repository=SqliteRepository.open(file,32).get(10,TimeUnit.SECONDS);
        try{
            assertEquals(new Award(id,"legacy-source",recipient,"legacy",4,AwardStatus.PENDING,0),
                    repository.award(id).get(10,TimeUnit.SECONDS).orElseThrow());
            assertTrue(repository.campaignAward(id).get(10,TimeUnit.SECONDS).isEmpty());
            assertEquals(List.of(),repository.pendingCampaignAwards(recipient,null,4).get(10,TimeUnit.SECONDS));
        }finally{
            repository.closeAsync().get(10,TimeUnit.SECONDS);
        }
        try(var connection=PrivateSqlite.open(file)){
            assertEquals(5,Schema.pragma(connection,"user_version"));
        }
    }
}
