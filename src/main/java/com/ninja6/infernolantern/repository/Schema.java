package com.ninja6.infernolantern.repository;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Migrations are validated before any persistent pragma or schema write. */
final class Schema {
    static final int APPLICATION_ID=0x494C4442; // ILDB
    static final int NATIVE_APPLICATION_ID=0x494C4E44; // ILND
    static final int VERSION=5;
    static final List<List<String>> MIGRATIONS=List.of(List.of(
        "CREATE TABLE variants(id TEXT PRIMARY KEY, definition_revision INTEGER NOT NULL CHECK(definition_revision>=0), status TEXT NOT NULL CHECK(status IN ('ACTIVE','RETIRED')), payload_version INTEGER NOT NULL CHECK(payload_version>0), definition TEXT NOT NULL, row_version INTEGER NOT NULL CHECK(row_version>=0))",
        "CREATE TABLE towers(id TEXT PRIMARY KEY, variant_id TEXT NOT NULL REFERENCES variants(id), hp REAL NOT NULL CHECK(hp>=0), maximum_hp REAL NOT NULL CHECK(maximum_hp>0 AND hp<=maximum_hp), form TEXT NOT NULL CHECK(form IN ('PORTABLE','GROUND','COMPACT','EXPANDED','LOST')), generation INTEGER NOT NULL CHECK(generation>=0), deployment_revision INTEGER NOT NULL CHECK(deployment_revision>=0), controller TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, orientation TEXT, light INTEGER NOT NULL CHECK(light IN (0,1)), defence INTEGER NOT NULL CHECK(defence IN (0,1)), expand_after INTEGER NOT NULL CHECK(expand_after>=0), repair_after INTEGER NOT NULL CHECK(repair_after>=0), row_version INTEGER NOT NULL CHECK(row_version>=0), active_operation TEXT UNIQUE, CHECK((form='EXPANDED')=(controller IS NOT NULL)), CHECK((form IN ('COMPACT','EXPANDED'))=(world IS NOT NULL)), CHECK((world IS NULL AND x IS NULL AND y IS NULL AND z IS NULL AND orientation IS NULL) OR (world IS NOT NULL AND x IS NOT NULL AND y IS NOT NULL AND z IS NOT NULL AND orientation IS NOT NULL)))",
        "CREATE TABLE operations(id TEXT PRIMARY KEY, tower_id TEXT NOT NULL REFERENCES towers(id), expected_version INTEGER NOT NULL CHECK(expected_version>=0), expected_generation INTEGER NOT NULL CHECK(expected_generation>=0), expected_revision INTEGER NOT NULL CHECK(expected_revision>=0), kind TEXT NOT NULL, before_state BLOB NOT NULL, after_state BLOB NOT NULL, effects_version INTEGER NOT NULL CHECK(effects_version>0), effects TEXT NOT NULL, phase TEXT NOT NULL CHECK(phase IN ('INTENT','EFFECTS_APPLIED','RECONCILING','COMPLETE','ROLLED_BACK')), row_version INTEGER NOT NULL CHECK(row_version>=0), evidence TEXT NOT NULL)",
        "CREATE UNIQUE INDEX one_pending_operation ON operations(tower_id) WHERE phase IN ('INTENT','EFFECTS_APPLIED','RECONCILING')",
        "CREATE INDEX tower_page ON towers(id)",
        "CREATE INDEX operation_page ON operations(phase,id)"
    ),List.of(
        "CREATE TABLE ground_representations(tower_id TEXT PRIMARY KEY REFERENCES towers(id), generation INTEGER NOT NULL CHECK(generation>=0), entity_id TEXT NOT NULL UNIQUE, world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL, delivery_operation TEXT NOT NULL, status TEXT NOT NULL, payload_version INTEGER NOT NULL CHECK(payload_version=1))",
        "CREATE TABLE manifest_components(tower_id TEXT NOT NULL REFERENCES towers(id), deployment_revision INTEGER NOT NULL CHECK(deployment_revision>=0), component_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('BLOCK','ENTITY')), world TEXT NOT NULL, x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,original_state TEXT,expected_state TEXT,entity_id TEXT,entity_type TEXT,payload_version INTEGER NOT NULL CHECK(payload_version=1),PRIMARY KEY(tower_id,component_id),CHECK((kind='BLOCK' AND original_state IS NOT NULL AND expected_state IS NOT NULL AND entity_id IS NULL AND entity_type IS NULL) OR (kind='ENTITY' AND original_state IS NULL AND expected_state IS NULL AND entity_id IS NOT NULL AND entity_type IS NOT NULL)))",
        "CREATE TABLE awards(id TEXT PRIMARY KEY, source_key TEXT NOT NULL, recipient TEXT NOT NULL, variant_id TEXT NOT NULL REFERENCES variants(id), quantity INTEGER NOT NULL CHECK(quantity>0), status TEXT NOT NULL CHECK(status IN ('PENDING','DELIVERED','PAUSED')), row_version INTEGER NOT NULL CHECK(row_version>=0), payload_version INTEGER NOT NULL CHECK(payload_version=1), UNIQUE(source_key,recipient,variant_id))",
        "CREATE TABLE award_towers(award_id TEXT NOT NULL REFERENCES awards(id), tower_id TEXT NOT NULL UNIQUE REFERENCES towers(id),PRIMARY KEY(award_id,tower_id))",
        "CREATE TABLE repair_escrows(operation_id TEXT PRIMARY KEY REFERENCES operations(id),tower_id TEXT NOT NULL REFERENCES towers(id),deployment_revision INTEGER NOT NULL CHECK(deployment_revision>=0),contributor TEXT NOT NULL,resource_format INTEGER NOT NULL CHECK(resource_format>0),resources BLOB NOT NULL,payment TEXT NOT NULL CHECK(payment IN ('RESERVED','COMMITTED','REFUND_PENDING','REFUNDED','RECONCILING')),hp_phase TEXT NOT NULL CHECK(hp_phase IN ('NOT_APPLIED','APPLIED','RECONCILING')),row_version INTEGER NOT NULL CHECK(row_version>=0),payload_version INTEGER NOT NULL CHECK(payload_version=1))"
    ),List.of(
        "CREATE TABLE structure_cells(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,tower_id TEXT NOT NULL REFERENCES towers(id),operation_id TEXT REFERENCES operations(id),PRIMARY KEY(world,x,y,z))",
        "CREATE INDEX structure_cells_tower ON structure_cells(tower_id)"
    ),List.of(
        "CREATE TABLE catalog_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL CHECK(revision>0),payload_version INTEGER NOT NULL CHECK(payload_version>0),configuration TEXT NOT NULL)",
        "CREATE TABLE catalog_managed_variants(variant_id TEXT PRIMARY KEY REFERENCES variants(id))",
        "CREATE TABLE catalog_award_holds(award_id TEXT PRIMARY KEY REFERENCES awards(id))"
    ),List.of(
        "CREATE TABLE campaign_progress(award_id TEXT PRIMARY KEY REFERENCES awards(id), delivered INTEGER NOT NULL CHECK(delivered>=0), next_tower TEXT UNIQUE REFERENCES towers(id), operation_id TEXT UNIQUE REFERENCES operations(id), CHECK(operation_id IS NULL OR next_tower IS NOT NULL))"
    ));
    private Schema(){}
    private static RepositoryRecords.State decode(byte[] bytes)throws SQLException{try{return SnapshotCodec.decode(bytes);}catch(java.io.IOException e){throw new SQLException("Invalid legacy operation snapshot",e);}}
    static int pragma(Connection c,String key)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA "+key)){if(!r.next())throw new SQLException("Missing pragma "+key);return r.getInt(1);}}
    static String checksum(int version){try{byte[] bytes=String.join("\n",MIGRATIONS.get(version-1)).getBytes(StandardCharsets.UTF_8);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static void initialize(Connection c)throws SQLException{
        initialize(c,APPLICATION_ID);
    }
    static void initialize(Connection c,int applicationId)throws SQLException{
        int app=pragma(c,"application_id"),version=pragma(c,"user_version");
        boolean empty;
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")){empty=r.next()&&r.getInt(1)==0;}
        if((app!=applicationId && !(app==0&&version==0&&empty)) || version<0 || version>VERSION)throw new SQLException("Unrecognized or future database schema; preserve database");
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA quick_check")){if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw new SQLException("Database integrity check failed");}
        if(version>0){
            try(var s=c.createStatement();var r=s.executeQuery("SELECT version,checksum FROM schema_migrations ORDER BY version")){
                int expected=1;
                while(r.next()){if(r.getInt(1)!=expected||expected>version||!checksum(expected).equals(r.getString(2)))throw new SQLException("Migration history mismatch");expected++;}
                if(expected!=version+1)throw new SQLException("Missing migration history");
            }
            verifyTables(c,version);
            if(applicationId==NATIVE_APPLICATION_ID)requireCatalogOnly(c);
        } else if(app==applicationId&&!empty)throw new SQLException("Missing migration metadata");
        c.setAutoCommit(false);
        try(var s=c.createStatement()){
            if(version==0)s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
            for(int next=version+1;next<=VERSION;next++){
                if(next==3){
                    // Old manifests never declared their full clearance volume. Do not invent it.
                    try(var rows=s.executeQuery("SELECT 1 FROM towers WHERE form IN ('COMPACT','EXPANDED') LIMIT 1")){
                        if(rows.next())throw new SQLException("Legacy structure clearance is unknown; preserve database for migration review");
                    }
                    try(var rows=s.executeQuery("SELECT after_state FROM operations WHERE phase IN ('INTENT','EFFECTS_APPLIED','RECONCILING')")){
                        while(rows.next())if(!decode(rows.getBytes(1)).components().isEmpty())throw new SQLException("Legacy pending structure clearance is unknown; preserve database");
                    }
                }
                for(String sql:MIGRATIONS.get(next-1))s.execute(sql);
                try(var insert=c.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){insert.setInt(1,next);insert.setString(2,checksum(next));insert.executeUpdate();}
                s.execute("PRAGMA user_version="+next);
            }
            s.execute("PRAGMA application_id="+applicationId);
            c.commit();
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA journal_mode=WAL")){if(!r.next()||!"wal".equalsIgnoreCase(r.getString(1)))throw new SQLException("WAL unavailable");}
        try(var s=c.createStatement()) {s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA wal_autocheckpoint=1000");}
        if(pragma(c,"synchronous")!=2||pragma(c,"foreign_keys")!=1||pragma(c,"busy_timeout")!=5000||pragma(c,"wal_autocheckpoint")!=1000)throw new SQLException("Required durability pragmas unavailable");
        verifyTables(c,VERSION);
    }
    static void requireCatalogOnly(Connection c)throws SQLException{
        String sql="SELECT EXISTS(SELECT 1 FROM towers) OR EXISTS(SELECT 1 FROM operations) "
                +"OR EXISTS(SELECT 1 FROM awards) OR EXISTS(SELECT 1 FROM variants "
                +"WHERE NOT EXISTS(SELECT 1 FROM catalog_managed_variants WHERE variant_id=variants.id))";
        try(var s=c.createStatement();var rows=s.executeQuery(sql)){
            if(!rows.next()||rows.getBoolean(1))throw new SQLException("Native gameplay evidence needs integration review");
        }
    }
    private static void verifyTables(Connection c,int version)throws SQLException{
        var expected=new HashSet<String>();
        expected.add("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL)");
        for(int i=0;i<version;i++)expected.addAll(MIGRATIONS.get(i));
        var actual=new HashSet<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")){
            while(r.next())actual.add(r.getString(1));
        }
        if(!actual.equals(expected))throw new SQLException("Schema structure differs from migration history; preserve database");
    }
}
