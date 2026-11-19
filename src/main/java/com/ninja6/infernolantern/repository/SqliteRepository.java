package com.ninja6.infernolantern.repository;

import com.ninja6.infernolantern.catalog.CatalogCodec;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Async storage only. A committed database intent is not a committed Minecraft effect. */
public final class SqliteRepository {
    private final ArrayBlockingQueue<Job<?>> queue;
    private final Object admission = new Object();
    private final Semaphore observationSlots;
    private final ConcurrentMap<UUID,Set<TowerObservation>> observations = new ConcurrentHashMap<>();
    private volatile Object observationCatalog = new Object();
    private final CompletableFuture<SqliteRepository> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean closing;
    private volatile Throwable fatal;
    private Connection connection;
    private String validatedEngineVersion;
    private final Thread worker;
    private record Job<T>(Callable<T> action, CompletableFuture<T> future) {
        void run() { try { future.complete(action.call()); } catch(Throwable e) { future.completeExceptionally(e); if(e instanceof Error error)throw error; } }
    }
    private SqliteRepository(Path file,int capacity) {
        if(capacity<1||capacity>100_000)throw new IllegalArgumentException("Queue capacity");
        queue=new ArrayBlockingQueue<>(capacity);
        observationSlots=new Semaphore(capacity);
        worker=new Thread(()->run(file),"infernolantern-repository");
        worker.setDaemon(true);
        worker.start();
    }
    public static CompletableFuture<SqliteRepository> open(Path file,int queueCapacity) {
        Objects.requireNonNull(file);
        try { return new SqliteRepository(file.toAbsolutePath(),queueCapacity).ready; }
        catch(RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
    private void run(Path file) {
        Throwable failure=null;
        try {
            Path parent=file.getParent();if(parent!=null)Files.createDirectories(parent);
            connection=PrivateSqlite.open(file);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT sqlite_version()")){
                if(!rows.next())throw new SQLException("Missing validated SQLite version");
                validatedEngineVersion=rows.getString(1);
            }
            Schema.initialize(connection);
            validateStoredRows();
            ready.complete(this);
            while(fatal==null) {
                Job<?> job=queue.poll(100,TimeUnit.MILLISECONDS);
                if(job!=null)job.run();
                synchronized(admission){if(closing&&queue.isEmpty())break;}
            }
            if(fatal!=null)throw new RepositoryException("Repository became unavailable",fatal);
        }catch(Throwable e){failure=e;ready.completeExceptionally(e);}
        finally {
            synchronized(admission){closing=true;}
            observations.keySet().forEach(this::invalidateObservations);
            Job<?> job;while((job=queue.poll())!=null)job.future().completeExceptionally(failure==null?new RepositoryException("Repository closed"):failure);
            try {if(connection!=null)connection.close();}catch(SQLException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure==null)closed.complete(null);else closed.completeExceptionally(failure);
        }
    }
    private <T> CompletableFuture<T> submit(Callable<T> action) {
        var future=new CompletableFuture<T>();
        synchronized(admission){
            if(closing||fatal!=null)future.completeExceptionally(new RepositoryException("Repository is closing or failed"));
            else if(!queue.offer(new Job<>(action,future)))future.completeExceptionally(new RejectedExecutionException("Repository request queue is full; retry later"));
        }
        return future;
    }
    /** Stop admission, drain accepted requests, then close on the database thread. Never join on a world thread. */
    public CompletableFuture<Void> closeAsync() {
        synchronized(admission){closing=true;}
        observations.keySet().forEach(this::invalidateObservations);
        return closed;
    }
    /** Immutable startup diagnostic, published with the successfully opened repository. */
    public String validatedEngineVersion(){return validatedEngineVersion;}
    public int queuedRequests() {return queue.size();}
    public CompletableFuture<String> sqliteVersion(){return submit(()->{try(var s=connection.createStatement();var r=s.executeQuery("SELECT sqlite_version()")){r.next();return r.getString(1);}});}
    private <T> T transaction(Callable<T> action)throws Exception {
        return transaction(action,null);
    }
    private <T> T transaction(Callable<T> action,CommitPermit permit)throws Exception {
        if(permit!=null)permit.requireCurrent();
        connection.setAutoCommit(false);
        try {
            T result=action.call();
            // Revocation may win while the request waits for the SQLite writer or executes SQL.
            if(permit!=null)permit.requireCurrent();
            connection.commit();
            return result;
        }
        catch(Exception|Error e){try{connection.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);fatal=e;}throw e;}
        finally {try{connection.setAutoCommit(true);}catch(SQLException e){fatal=e;throw e;}}
    }
    private <T> T snapshot(Callable<T> action)throws Exception {
        // Explicit DEFERRED read transaction keeps multi-query rows/components on one WAL snapshot.
        try(var statement=connection.createStatement()){
            statement.execute("BEGIN DEFERRED");
            try{T value=action.call();statement.execute("COMMIT");return value;}
            catch(Exception|Error failure){try{statement.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);fatal=failure;}throw failure;}
        }
    }
    private PreparedStatement statement(String sql,Object...values)throws SQLException {
        var p=connection.prepareStatement(sql);
        try {for(int i=0;i<values.length;i++)p.setObject(i+1,values[i] instanceof UUID id?id.toString():values[i]);return p;}
        catch(SQLException e){p.close();throw e;}
    }
    private int update(String sql,Object...values)throws SQLException{try(var p=statement(sql,values)){return p.executeUpdate();}}
    private static void require(boolean condition,String message){if(!condition)throw new RepositoryException.Conflict(message);}
    private static UUID uuid(String value){return value==null?null:UUID.fromString(value);}
    private static long next(long version){return Math.incrementExact(version);}
    private static int page(int limit){if(limit<1||limit>MAX_PAGE)throw new IllegalArgumentException("Page size must be 1.."+MAX_PAGE);return limit;}

    public CompletableFuture<Optional<Variant>> variant(String id){identifier(id);return submit(()->snapshot(()->Optional.ofNullable(loadVariant(id))));}
    public CompletableFuture<Variant> createVariant(Variant variant){Objects.requireNonNull(variant);return submit(()->transaction(()->{
        require(variant.rowVersion()==0,"New variant version must be zero");
        update("INSERT INTO variants VALUES(?,?,?,?,?,?)",variant.id(),variant.definitionRevision(),variant.status().name(),variant.lastValidDefinition().version(),variant.lastValidDefinition().content(),0);
        return variant;
    }));}
    public CompletableFuture<Variant> compareAndSetVariant(long expected,Variant replacement){Objects.requireNonNull(replacement);return submit(()->transaction(()->{
        require(!catalogManaged(replacement.id()),"Managed variant requires catalog publication");
        Variant old=loadVariant(replacement.id());require(old!=null&&old.rowVersion()==expected,"Stale variant");
        require(replacement.rowVersion()==next(expected),"Next variant version required");
        require(replacement.definitionRevision()>=old.definitionRevision(),"Definition revision regressed");
        if(replacement.definitionRevision()==old.definitionRevision())require(replacement.lastValidDefinition().equals(old.lastValidDefinition()),"Definition changed without revision");
        require(update("UPDATE variants SET definition_revision=?,status=?,payload_version=?,definition=?,row_version=? WHERE id=? AND row_version=?",replacement.definitionRevision(),replacement.status().name(),replacement.lastValidDefinition().version(),replacement.lastValidDefinition().content(),replacement.rowVersion(),replacement.id(),expected)==1,"Variant CAS lost");return replacement;
    }));}
    private boolean catalogManaged(String id)throws SQLException{
        try(var p=statement("SELECT 1 FROM catalog_managed_variants WHERE variant_id=?",id);var rows=p.executeQuery()){return rows.next();}
    }
    private boolean pendingRepresentation(String variant,boolean geometry)throws Exception{
        try(var p=statement("SELECT o.before_state,o.after_state FROM operations o JOIN towers t ON t.id=o.tower_id WHERE t.variant_id=? AND o.phase IN ('INTENT','EFFECTS_APPLIED','RECONCILING')",variant);var rows=p.executeQuery()){
            while(rows.next())for(int column=1;column<=2;column++){
                State state=SnapshotCodec.decode(rows.getBytes(column));
                if(geometry?!state.components().isEmpty():state.tower().form()!=Form.LOST)return true;
            }
        }return false;
    }
    private Variant loadVariant(String id)throws SQLException{
        try(var p=statement("SELECT * FROM variants WHERE id=?",id);var r=p.executeQuery()){
            return r.next()?new Variant(r.getString("id"),r.getLong("definition_revision"),VariantStatus.valueOf(r.getString("status")),new Document(r.getInt("payload_version"),r.getString("definition")),r.getLong("row_version")):null;
        }
    }
    /** Atomic catalog metadata only; no world effects or item delivery occur here. */
    public CompletableFuture<CatalogSnapshot> catalog(){return submit(()->snapshot(this::loadCatalog));}
    private CatalogSnapshot loadCatalog()throws Exception{
        long revision=0;Document configuration=new Document(1,"catalog-unpublished");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT revision,payload_version,configuration FROM catalog_state WHERE singleton=1")){
            if(r.next()){revision=r.getLong(1);configuration=new Document(r.getInt(2),r.getString(3));}
        }
        var variants=new ArrayList<Variant>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT variant_id FROM catalog_managed_variants ORDER BY variant_id LIMIT 1025")){
            while(r.next())variants.add(Objects.requireNonNull(loadVariant(r.getString(1))));
        }
        require(variants.size()<=1024&&(revision>0||variants.isEmpty()),"Catalog metadata bounds");
        return new CatalogSnapshot(revision,configuration,variants);
    }
    /** Only explicitly managed IDs participate. A caller cannot adopt an existing unmanaged row. */
    public CompletableFuture<CatalogSnapshot> publishCatalog(CatalogSnapshot expected,Document configuration,
            Map<String,Document> definitions,Set<String> geometryChanges){
        Objects.requireNonNull(expected);Objects.requireNonNull(configuration);
        Map<String,Document> wanted=Map.copyOf(definitions);Set<String> geometry=Set.copyOf(geometryChanges);
        var candidate=CatalogCodec.candidate(configuration);
        if(!candidate.variants().keySet().equals(wanted.keySet()))throw new IllegalArgumentException("Catalog definitions differ from configuration");
        for(var pair:candidate.variants().entrySet()){
            if(!CatalogCodec.definition(pair.getValue()).equals(wanted.get(pair.getKey())))throw new IllegalArgumentException("Catalog definition differs from configuration");
        }
        return submit(()->transaction(()->{
            CatalogSnapshot current=loadCatalog();require(current.equals(expected),"Catalog publication changed");
            observationCatalog=new Object();
            var existing=new HashMap<String,Variant>();for(Variant v:current.variants())existing.put(v.id(),v);
            var all=new TreeSet<String>(existing.keySet());all.addAll(wanted.keySet());
            for(String id:geometry)require(!pendingRepresentation(id,true),"Pending geometry change needs resolution");
            for(String id:geometry)try(var p=statement("SELECT 1 FROM towers WHERE variant_id=? AND form IN ('COMPACT','EXPANDED') LIMIT 1",id);var rows=p.executeQuery()){require(!rows.next(),"Placed geometry change needs migration");}
            for(String id:all){
                Variant old=existing.get(id);Document definition=wanted.get(id);
                if(definition!=null){
                    double maximum=candidate.variants().get(id).maximumHp();
                    if(old!=null&&CatalogCodec.definition(old.lastValidDefinition()).maximumHp()!=maximum){
                        migrateMaximumHp(id,maximum);
                    }
                }
                if(old==null){require(loadVariant(id)==null,"Unmanaged variant ID collision");
                    update("INSERT INTO variants VALUES(?,?,?,?,?,0)",id,0,VariantStatus.ACTIVE.name(),definition.version(),definition.content());
                    update("INSERT INTO catalog_managed_variants VALUES(?)",id);
                }else{
                    VariantStatus status=definition==null?VariantStatus.RETIRED:VariantStatus.ACTIVE;
                    Document kept=definition==null?old.lastValidDefinition():definition;
                    long revision=old.lastValidDefinition().equals(kept)?old.definitionRevision():next(old.definitionRevision());
                    if(status!=old.status()||!kept.equals(old.lastValidDefinition()))
                        require(update("UPDATE variants SET definition_revision=?,status=?,payload_version=?,definition=?,row_version=? WHERE id=? AND row_version=?",revision,status.name(),kept.version(),kept.content(),next(old.rowVersion()),id,old.rowVersion())==1,"Variant publication changed");
                }
                if(definition==null){
                    try(var p=statement("SELECT 1 FROM awards WHERE variant_id=? AND status='PENDING' AND row_version=9223372036854775807 LIMIT 1",id);var rows=p.executeQuery()){require(!rows.next(),"Award row version exhausted");}
                    update("INSERT OR IGNORE INTO catalog_award_holds SELECT id FROM awards WHERE variant_id=? AND status='PENDING'",id);
                    update("UPDATE awards SET status='PAUSED',row_version=row_version+1 WHERE variant_id=? AND status='PENDING'",id);
                }else{
                    update("UPDATE awards SET status='PENDING',row_version=row_version+1 WHERE variant_id=? AND status='PAUSED' AND id IN (SELECT award_id FROM catalog_award_holds)",id);
                    update("DELETE FROM catalog_award_holds WHERE award_id IN (SELECT id FROM awards WHERE variant_id=?)",id);
                }
            }
            long revision=next(expected.revision());
            if(expected.revision()==0)update("INSERT INTO catalog_state VALUES(1,?,?,?)",revision,configuration.version(),configuration.content());
            else require(update("UPDATE catalog_state SET revision=?,payload_version=?,configuration=? WHERE singleton=1 AND revision=?",revision,configuration.version(),configuration.content(),expected.revision())==1,"Catalog CAS lost");
            return loadCatalog();
        }));
    }
    /** Clamp live rows only. The caller already owns the catalog publication transaction. */
    private void migrateMaximumHp(String id,double maximum)throws Exception{
        require(!pendingRepresentation(id,false),"Pending maximum HP change needs resolution");
        try(var p=statement("SELECT 1 FROM towers WHERE variant_id=? AND form<>'LOST' AND (active_operation IS NOT NULL OR (maximum_hp<>? AND row_version=9223372036854775807)) LIMIT 1",id,maximum);var rows=p.executeQuery()){
            require(!rows.next(),"Maximum HP migration blocked by tower ownership or exhausted revision");
        }
        update("UPDATE towers SET hp=MIN(hp,?),maximum_hp=?,row_version=row_version+1 WHERE variant_id=? AND form<>'LOST' AND maximum_hp<>?",maximum,maximum,id,maximum);
    }
    /** Preferences change only, guarded by both the current catalog and tower revision. */
    public CompletableFuture<TowerSnapshot> saveSwitches(long catalogRevision,UUID tower,long expectedVersion,long expectedDeploymentRevision,boolean light,boolean defence){
        return saveSwitches(catalogRevision,tower,expectedVersion,expectedDeploymentRevision,light,defence,null);
    }
    public CompletableFuture<TowerSnapshot> saveSwitches(long catalogRevision,UUID tower,long expectedVersion,long expectedDeploymentRevision,
            boolean light,boolean defence,CommitPermit permit){
        return submit(()->transaction(()->{
            require(loadCatalog().revision()==catalogRevision,"Catalog changed before switch commit");
            TowerSnapshot old=ownedTower(tower,expectedVersion,null);Tower t=old.state().tower();
            require(t.deploymentRevision()==expectedDeploymentRevision,"Stale switch deployment");
            Tower changed=new Tower(t.id(),t.variantId(),t.hp(),t.maximumHp(),t.form(),t.generation(),t.deploymentRevision(),t.controller(),t.position(),light,defence,t.expandAfter(),t.repairAfter());
            State state=new State(changed,old.state().ground(),old.state().components());
            writeTower(state,next(expectedVersion),null,false,expectedVersion,null);return new TowerSnapshot(state,next(expectedVersion),null);
        },permit));
    }
    /** Non-blast damage changes only the ready deployed row. A zero result remains authoritative even if compaction fails. */
    public CompletableFuture<TowerSnapshot> damageTower(long catalogRevision,UUID tower,long expectedVersion,
            long expectedGeneration,long expectedDeploymentRevision,double damage,long repairAfter,CommitPermit permit){
        if(!Double.isFinite(damage)||damage<=0||repairAfter<0)throw new IllegalArgumentException("Damage bounds");
        return submit(()->transaction(()->{
            try(var p=statement("SELECT 1 FROM catalog_state WHERE singleton=1 AND revision=?",catalogRevision);var rows=p.executeQuery()){
                require(rows.next(),"Catalog changed before damage commit");
            }
            TowerSnapshot old=ownedTower(tower,expectedVersion,null);Tower t=old.state().tower();
            require(t.form()==Form.EXPANDED&&t.hp()>0&&t.generation()==expectedGeneration&&t.deploymentRevision()==expectedDeploymentRevision,
                    "Stale deployed damage target");
            require(catalogManaged(t.variantId())&&loadVariant(t.variantId()).status()==VariantStatus.ACTIVE,"Damage requires an active catalog variant");
            Tower changed=new Tower(t.id(),t.variantId(),Math.max(0,t.hp()-damage),t.maximumHp(),t.form(),t.generation(),t.deploymentRevision(),
                    t.controller(),t.position(),t.light(),t.defence(),t.expandAfter(),Math.max(t.repairAfter(),repairAfter));
            State state=new State(changed,null,old.state().components());long version=next(expectedVersion);
            invalidateObservations(t.id());
            require(update("UPDATE towers SET hp=?,repair_after=?,row_version=? WHERE id=? AND row_version=? AND active_operation IS NULL",
                    changed.hp(),changed.repairAfter(),version,t.id(),expectedVersion)==1,"Damage CAS lost");
            return new TowerSnapshot(state,version,null);
        },permit));
    }
    public CompletableFuture<TowerSnapshot> createTower(Tower tower){Objects.requireNonNull(tower);return submit(()->transaction(()->{
        require(tower.form()==Form.PORTABLE,"Initial storage insertion must be portable");
        Variant definition=loadVariant(tower.variantId());require(definition!=null&&definition.status()==VariantStatus.ACTIVE,"Issuance requires an active variant");
        if(catalogManaged(tower.variantId()))require(tower.maximumHp()==CatalogCodec.definition(definition.lastValidDefinition()).maximumHp(),"Issuance requires the current maximum HP");
        var state=new State(tower,null,List.of());SnapshotCodec.encode(state);
        writeTower(state,0,null,true,0,null);return new TowerSnapshot(state,0,null);
    }));}
    public CompletableFuture<Optional<TowerSnapshot>> tower(UUID id){Objects.requireNonNull(id);return submit(()->snapshot(()->Optional.ofNullable(loadTower(id))));}
    /**
     * Authoritative display fence for the single runtime repository owner.
     * Invalidated before a target-row mutation, catalog publication or shutdown.
     */
    public final class TowerObservation implements AutoCloseable {
        private final UUID tower;
        private final Object catalog;
        private final AtomicBoolean open=new AtomicBoolean(true);

        private TowerObservation(UUID tower){this.tower=tower;this.catalog=observationCatalog;}
        public boolean current(){return !closing&&fatal==null&&open.get()&&catalog==observationCatalog;}
        @Override public void close(){
            if(!open.compareAndSet(true,false))return;
            observations.computeIfPresent(tower,(id,holders)->{
                holders.remove(this);return holders.isEmpty()?null:holders;
            });
            observationSlots.release();
        }
    }
    /** Snapshot and registration are serialized with writes. Close on every display or scheduling outcome. */
    public CompletableFuture<Optional<TowerObservation>> observeTower(TowerSnapshot expected){
        Objects.requireNonNull(expected);
        UUID id=expected.state().tower().id();
        return submit(()->{
            if(closing||!expected.equals(snapshot(()->loadTower(id)))||!observationSlots.tryAcquire())return Optional.empty();
            TowerObservation observation=new TowerObservation(id);
            observations.compute(id,(key,holders)->{
                if(holders==null)holders=new HashSet<>();
                holders.add(observation);return holders;
            });
            if(closing){observation.close();return Optional.empty();}
            return Optional.of(observation);
        });
    }
    private void invalidateObservations(UUID tower){
        Set<TowerObservation> holders=observations.remove(tower);
        if(holders!=null)holders.forEach(TowerObservation::close);
    }
    /** Paged snapshots have no shared live collection or database cursor. UUID lexical cursor is exclusive. */
    public CompletableFuture<List<TowerSnapshot>> towers(UUID after,int limit){page(limit);return submit(()->snapshot(()->{
        var result=new ArrayList<TowerSnapshot>();
        for(UUID id:ids("SELECT id FROM towers WHERE id>? ORDER BY id LIMIT ?",after==null?"":after.toString(),limit))result.add(loadTower(id));
        return List.copyOf(result);
    }));}
    /** Only data within an unchanged representation can use direct CAS. Form/identity/manifest changes require a journal. */
    public CompletableFuture<TowerSnapshot> compareAndSetTower(long expected,Tower replacement){Objects.requireNonNull(replacement);return submit(()->transaction(()->{
        TowerSnapshot old=ownedTower(replacement.id(),expected,null);
        Tower before=old.state().tower();
        require(sameRepresentation(before,replacement),"Representation changes require durable intent");
        if(catalogManaged(before.variantId()))require(before.maximumHp()==replacement.maximumHp(),"Managed maximum HP changes require catalog publication");
        State state=new State(replacement,old.state().ground(),old.state().components());
        writeTower(state,next(expected),null,false,expected,null);return new TowerSnapshot(state,next(expected),null);
    }));}
    private static boolean sameRepresentation(Tower a,Tower b){return a.id().equals(b.id())&&a.variantId().equals(b.variantId())&&a.form()==b.form()&&a.generation()==b.generation()&&a.deploymentRevision()==b.deploymentRevision()&&Objects.equals(a.controller(),b.controller())&&Objects.equals(a.position(),b.position());}
    private TowerSnapshot ownedTower(UUID id,long version,UUID operation)throws Exception{
        TowerSnapshot value=loadTower(id);require(value!=null&&value.rowVersion()==version&&Objects.equals(value.activeOperation(),operation),"Stale tower or operation ownership");return value;
    }
    private TowerSnapshot loadTower(UUID id)throws Exception{
        try(var p=statement("SELECT * FROM towers WHERE id=?",id);var r=p.executeQuery()){
            if(!r.next())return null;
            String world=r.getString("world");
            Position position=world==null?null:new Position(uuid(world),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("orientation"));
            Tower t=new Tower(uuid(r.getString("id")),r.getString("variant_id"),r.getDouble("hp"),r.getDouble("maximum_hp"),Form.valueOf(r.getString("form")),r.getLong("generation"),r.getLong("deployment_revision"),uuid(r.getString("controller")),position,r.getBoolean("light"),r.getBoolean("defence"),r.getLong("expand_after"),r.getLong("repair_after"));
            long version=r.getLong("row_version");UUID operation=uuid(r.getString("active_operation"));
            Ground ground=null;
            try(var gp=statement("SELECT * FROM ground_representations WHERE tower_id=?",id);var gr=gp.executeQuery()){
                if(gr.next()){
                    if(gr.getLong("generation")!=t.generation()||gr.getInt("payload_version")!=1)throw new RepositoryException("Ground snapshot mismatch");
                    ground=new Ground(uuid(gr.getString("entity_id")),uuid(gr.getString("world")),gr.getInt("chunk_x"),gr.getInt("chunk_z"),uuid(gr.getString("delivery_operation")),gr.getString("status"));
                }
            }
            var components=new ArrayList<Component>();
            try(var mp=statement("SELECT * FROM manifest_components WHERE tower_id=? ORDER BY component_id LIMIT ?",id,MAX_COMPONENTS+1);var mr=mp.executeQuery()){
                while(mr.next()){
                    if(mr.getLong("deployment_revision")!=t.deploymentRevision()||mr.getInt("payload_version")!=1)throw new RepositoryException("Manifest snapshot mismatch");
                    components.add(new Component(mr.getString("component_id"),ComponentKind.valueOf(mr.getString("kind")),uuid(mr.getString("world")),mr.getInt("x"),mr.getInt("y"),mr.getInt("z"),mr.getString("original_state"),mr.getString("expected_state"),uuid(mr.getString("entity_id")),mr.getString("entity_type")));
                }
            }
            return new TowerSnapshot(new State(t,ground,components),version,operation);
        }
    }
    private void writeTower(State state,long version,UUID operation,boolean insert,long expectedVersion,UUID expectedOperation)throws Exception{
        SnapshotCodec.encode(state);Tower t=state.tower();Position p=t.position();
        invalidateObservations(t.id());
        Object[] values={t.variantId(),t.hp(),t.maximumHp(),t.form().name(),t.generation(),t.deploymentRevision(),t.controller(),p==null?null:p.world(),p==null?null:p.x(),p==null?null:p.y(),p==null?null:p.z(),p==null?null:p.orientation(),t.light(),t.defence(),t.expandAfter(),t.repairAfter(),version,operation,t.id()};
        if(insert)update("INSERT INTO towers(variant_id,hp,maximum_hp,form,generation,deployment_revision,controller,world,x,y,z,orientation,light,defence,expand_after,repair_after,row_version,active_operation,id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
        else {
            Object[] guarded=Arrays.copyOf(values,values.length+2);guarded[values.length]=expectedVersion;guarded[values.length+1]=expectedOperation;
            require(update("UPDATE towers SET variant_id=?,hp=?,maximum_hp=?,form=?,generation=?,deployment_revision=?,controller=?,world=?,x=?,y=?,z=?,orientation=?,light=?,defence=?,expand_after=?,repair_after=?,row_version=?,active_operation=? WHERE id=? AND row_version=? AND active_operation IS ?",guarded)==1,"Tower CAS lost");
        }
        update("DELETE FROM ground_representations WHERE tower_id=?",t.id());
        update("DELETE FROM manifest_components WHERE tower_id=?",t.id());
        Ground g=state.ground();if(g!=null)update("INSERT INTO ground_representations VALUES(?,?,?,?,?,?,?,?,1)",t.id(),t.generation(),g.entity(),g.world(),g.chunkX(),g.chunkZ(),g.deliveryOperation(),g.status());
        syncCells(t.id(), List.of(state), null);
        for(Component c:state.components())update("INSERT INTO manifest_components VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1)",t.id(),t.deploymentRevision(),c.id(),c.kind().name(),c.world(),c.x(),c.y(),c.z(),c.originalState(),c.expectedState(),c.entity(),c.entityType());
    }

    public CompletableFuture<Operation> beginIntent(Intent intent){return beginIntent(intent,true,-1,false);}
    /** Fresh-only physical effect admission. Existing receipts must never grant a second effect owner. */
    public CompletableFuture<Operation> beginNewIntent(Intent intent){return beginIntent(intent,false,-1,false);}
    public CompletableFuture<Operation> beginCatalogIntent(Intent intent,long catalogRevision){if(catalogRevision<0)throw new IllegalArgumentException("Catalog revision");return beginIntent(intent,false,catalogRevision,false);}
    /** Managed structure policies bind to the catalog revision; legacy unmanaged definitions stay independent. */
    public CompletableFuture<Operation> beginStructureIntent(Intent intent,long policyRevision){if(policyRevision<0)throw new IllegalArgumentException("Policy revision");return beginIntent(intent,false,policyRevision,true);}
    private CompletableFuture<Operation> beginIntent(Intent intent,boolean allowReceipt,long catalogRevision,boolean managedOnly){Objects.requireNonNull(intent);return submit(()->transaction(()->{
        // For generic intents the guard applies to every variant; structure callers retain legacy policy revisions.
        if(!managedOnly&&catalogRevision>=0)require(loadCatalog().revision()==catalogRevision,"Catalog changed before intent");
        Operation existing=loadOperation(intent.id());
        if(existing!=null){require(allowReceipt,"Operation is already admitted");require(existing.intent().equals(intent),"Operation identity reused with different intent");return existing;}
        TowerSnapshot tower=ownedTower(intent.towerId(),intent.expectedRowVersion(),null);Tower before=tower.state().tower(),after=intent.after().tower();
        boolean managed=catalogManaged(before.variantId());
        if(managed){
            require(before.maximumHp()==after.maximumHp(),"Managed maximum HP changes require catalog publication");
            if(timedStructureExit(intent.kind()))requireCatalogExitDuration(before.variantId(),exitRecipe(intent).exitCooldownMillis());
        }
        if(managedOnly&&managed)require(loadCatalog().revision()==catalogRevision,"Managed structure policy changed before intent");
        boolean structureTransition=(!tower.state().components().isEmpty()||!intent.after().components().isEmpty())
                &&(before.form()!=after.form()||before.deploymentRevision()!=after.deploymentRevision()||!Objects.equals(before.position(),after.position()));
        if(managed&&(structureTransition||!tower.state().components().equals(intent.after().components())))require(catalogRevision>=0,"Managed structure changes require catalog-bound admission");
        require(before.generation()==intent.expectedGeneration()&&before.deploymentRevision()==intent.expectedDeploymentRevision(),"Stale representation generation/revision");
        require(before.variantId().equals(after.variantId())&&after.generation()>=before.generation()&&after.deploymentRevision()>=before.deploymentRevision(),"Intent identity/revision regression");
        byte[] beforeBytes=SnapshotCodec.encode(tower.state()),afterBytes=SnapshotCodec.encode(intent.after());
        update("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?,?,0,?)",intent.id(),intent.towerId(),intent.expectedRowVersion(),intent.expectedGeneration(),intent.expectedDeploymentRevision(),intent.kind(),beforeBytes,afterBytes,intent.effects().version(),intent.effects().content(),Phase.INTENT.name(),"Intent recorded; world effects unverified");
        syncCells(intent.towerId(), List.of(tower.state(), intent.after()), intent.id());
        invalidateObservations(intent.towerId());
        require(update("UPDATE towers SET active_operation=?,row_version=? WHERE id=? AND row_version=? AND active_operation IS NULL",intent.id(),next(intent.expectedRowVersion()),intent.towerId(),intent.expectedRowVersion())==1,"Intent CAS lost");
        return loadOperation(intent.id());
    }));}
    private static Set<Cell> cells(List<State> states){
        var result=new HashSet<Cell>();
        for(State state:states)for(Component c:state.components())result.add(new Cell(c.world(),c.x(),c.y(),c.z()));
        return result;
    }
    private void syncCells(UUID tower,List<State> states,UUID operation)throws SQLException{
        Set<Cell> wanted=cells(states);
        for(Cell cell:wanted)try(var p=statement("SELECT tower_id FROM structure_cells WHERE world=? AND x=? AND y=? AND z=?",cell.world(),cell.x(),cell.y(),cell.z());var r=p.executeQuery()){
            require(!r.next()||tower.equals(uuid(r.getString(1))),"Structure clearance already reserved");
        }
        update("DELETE FROM structure_cells WHERE tower_id=?",tower);
        for(Cell cell:wanted)update("INSERT INTO structure_cells VALUES(?,?,?,?,?,?)",cell.world(),cell.x(),cell.y(),cell.z(),tower,operation);
    }
    public CompletableFuture<List<Reservation>> structureCells(UUID tower){Objects.requireNonNull(tower);return submit(()->snapshot(()->loadCells(tower)));}
    private List<Reservation> loadCells(UUID tower)throws SQLException{
        var result=new ArrayList<Reservation>();
        try(var p=statement("SELECT * FROM structure_cells WHERE tower_id=? ORDER BY world,x,y,z LIMIT ?",tower,MAX_COMPONENTS*2+1);var r=p.executeQuery()){
            while(r.next())result.add(new Reservation(new Cell(uuid(r.getString("world")),r.getInt("x"),r.getInt("y"),r.getInt("z")),tower,uuid(r.getString("operation_id"))));
        }require(result.size()<=MAX_COMPONENTS*2,"Cell reservation bounds");return List.copyOf(result);
    }
    public CompletableFuture<Optional<Operation>> operation(UUID id){Objects.requireNonNull(id);return submit(()->snapshot(()->Optional.ofNullable(loadOperation(id))));}
    public CompletableFuture<List<Operation>> pendingOperations(UUID after,int limit){page(limit);return submit(()->snapshot(()->{
        var result=new ArrayList<Operation>();
        for(UUID id:ids("SELECT id FROM operations WHERE id>? AND phase IN ('INTENT','EFFECTS_APPLIED','RECONCILING') ORDER BY id LIMIT ?",after==null?"":after.toString(),limit))result.add(loadOperation(id));
        return List.copyOf(result);
    }));}
    public CompletableFuture<Operation> advanceOperation(UUID id,long expected,Phase from,Phase to,String evidence){Objects.requireNonNull(from);Objects.requireNonNull(to);text(evidence,MAX_PAYLOAD);return submit(()->transaction(()->{
        Operation op=requireOperation(id,expected);require(op.phase()==from,"Stale operation phase");
        boolean allowed=(from==Phase.INTENT&&(to==Phase.EFFECTS_APPLIED||to==Phase.RECONCILING))||(from==Phase.EFFECTS_APPLIED&&to==Phase.RECONCILING)||(from==Phase.RECONCILING&&to==Phase.EFFECTS_APPLIED);
        require(allowed,"Invalid phase progression");ownedTower(op.intent().towerId(),next(op.intent().expectedRowVersion()),id);
        require(update("UPDATE operations SET phase=?,row_version=?,evidence=? WHERE id=? AND row_version=? AND phase=?",to.name(),next(expected),evidence,id,expected,from.name())==1,"Operation CAS lost");return loadOperation(id);
    }));}
    /** Caller must have verified physical effects on the owning scheduler. This method only commits database state. */
    public CompletableFuture<Operation> completeOperation(UUID id,long expected,String evidence){text(evidence,MAX_PAYLOAD);return finish(id,expected,evidence,false,true,-1);}
    /** Caller-supplied reconciliation evidence is not database proof of restored world/player saves. */
    public CompletableFuture<Operation> resolveRollback(UUID id,long expected,String evidence){text(evidence,MAX_PAYLOAD);return finish(id,expected,evidence,true,true,-1);}
    /** Only the winning fresh finalizer may publish/activate a recovery outcome. Receipts grant no effect ownership. */
    public CompletableFuture<Operation> finalizeReconciliation(UUID id,long expected,boolean rollback,String evidence){text(evidence,MAX_PAYLOAD);return finish(id,expected,evidence,rollback,false,-1);}
    /** Fresh-only AFTER finalization after a trusted recovery port revalidated the exact saved effects. */
    public CompletableFuture<Operation> finalizeTimedExitReconciliation(UUID id,long expected,String evidence,LongSupplier clock){
        text(evidence,MAX_PAYLOAD);
        return finish(id,expected,evidence,false,false,-1,false,null,Objects.requireNonNull(clock));
    }
    public CompletableFuture<Operation> completeCatalogOperation(UUID id,long expected,long catalogRevision,String evidence){if(catalogRevision<0)throw new IllegalArgumentException("Catalog revision");text(evidence,MAX_PAYLOAD);return finish(id,expected,evidence,false,false,catalogRevision);}
    /** Native structure completion repeats its admission guard for managed definitions. */
    public CompletableFuture<Operation> completeStructureOperation(UUID id,long expected,long catalogRevision,String evidence,CommitPermit permit){
        if(catalogRevision<0)throw new IllegalArgumentException("Catalog revision");
        text(evidence,MAX_PAYLOAD);return finish(id,expected,evidence,false,false,catalogRevision,true,permit,null);
    }
    /** Stamp one verified deployed exit after SQLite writer admission. The clock is thread-safe and server independent. */
    public CompletableFuture<Operation> completeStructureExit(UUID id,long expected,long catalogRevision,String evidence,
            CommitPermit permit,LongSupplier clock){
        if(catalogRevision<0)throw new IllegalArgumentException("Catalog revision");
        text(evidence,MAX_PAYLOAD);
        return finish(id,expected,evidence,false,false,catalogRevision,true,permit,Objects.requireNonNull(clock));
    }
    private CompletableFuture<Operation> finish(UUID id,long expected,String evidence,boolean rollback,boolean allowReceipt,long catalogRevision){
        return finish(id,expected,evidence,rollback,allowReceipt,catalogRevision,false,null,null);
    }
    private CompletableFuture<Operation> finish(UUID id,long expected,String evidence,boolean rollback,boolean allowReceipt,long catalogRevision,
            boolean managedOnly,CommitPermit permit,LongSupplier exitClock){Objects.requireNonNull(id);return submit(()->transaction(()->{
        if(!managedOnly&&catalogRevision>=0&&loadCatalog().revision()!=catalogRevision)throw new RepositoryException.CatalogSuperseded();
        Operation op=loadOperation(id);require(op!=null,"Unknown operation");Phase terminal=rollback?Phase.ROLLED_BACK:Phase.COMPLETE;
        if(op.phase()==terminal){require(allowReceipt,"Reconciliation was already finalized");return op;} // Receipts never grant a second effect owner.
        require(op.rowVersion()==expected&&op.phase()==(rollback?Phase.RECONCILING:Phase.EFFECTS_APPLIED),"Operation is not ready for completion");
        ownedTower(op.intent().towerId(),next(op.intent().expectedRowVersion()),id);
        if(managedOnly&&catalogManaged(op.before().tower().variantId())&&loadCatalog().revision()!=catalogRevision)throw new RepositoryException.CatalogSuperseded();
        Escrow escrow=loadEscrow(id);
        if(escrow!=null)require(rollback?(escrow.payment()==PaymentPhase.REFUNDED&&escrow.hpPhase()==HpPhase.NOT_APPLIED):(escrow.payment()==PaymentPhase.COMMITTED&&escrow.hpPhase()==HpPhase.APPLIED),"Escrow not settled; retain reconciliation");
        State after=op.intent().after();
        if(!rollback){
            require(timedStructureExit(op.intent().kind())==(exitClock!=null),"Timed exit completion requires a verified exit clock");
            if(exitClock!=null)after=stampExit(op,exitClock,managedOnly);
        }
        writeTower(rollback?op.before():after,Math.addExact(op.intent().expectedRowVersion(),2),null,false,next(op.intent().expectedRowVersion()),id);
        int saved=exitClock==null
                ?update("UPDATE operations SET phase=?,row_version=?,evidence=? WHERE id=? AND row_version=?",
                        terminal.name(),next(expected),evidence,id,expected)
                :update("UPDATE operations SET phase=?,row_version=?,evidence=?,after_state=? WHERE id=? AND row_version=?",
                        terminal.name(),next(expected),evidence,SnapshotCodec.encode(after),id,expected);
        require(saved==1,"Completion CAS lost");return loadOperation(id);
    },permit));}
    private State stampExit(Operation op,LongSupplier exitClock,boolean nativeCompletion)throws Exception{
        var recipe=exitRecipe(op.intent());
        if(!nativeCompletion)require(RecoveryEnvelope.supports(op,recipe),"Timed exit recovery requires a typed structure recipe");
        long duration=recipe.exitCooldownMillis();
        Tower before=op.before().tower();State after=op.intent().after();Tower tower=after.tower();
        Form expectedForm=STRUCTURE_EXIT_RECOVER.equals(op.intent().kind())?Form.GROUND:Form.COMPACT;
        require(before.form()==Form.EXPANDED&&tower.form()==expectedForm&&tower.expandAfter()==before.expandAfter()
                        &&(!STRUCTURE_DEFEAT.equals(op.intent().kind())||before.hp()==0&&tower.hp()==0),
                "Invalid timed structure exit");
        if(nativeCompletion&&catalogManaged(tower.variantId()))requireCatalogExitDuration(tower.variantId(),duration);
        long now=exitClock.getAsLong();nonnegative(now);
        long deadline=duration==0?0:Math.addExact(now,duration);
        Tower timed=new Tower(tower.id(),tower.variantId(),tower.hp(),tower.maximumHp(),tower.form(),tower.generation(),tower.deploymentRevision(),
                tower.controller(),tower.position(),tower.light(),tower.defence(),deadline,tower.repairAfter());
        return new State(timed,after.ground(),after.components());
    }
    private static RecoveryEnvelope.Context exitRecipe(Intent intent){
        var recipe=RecoveryEnvelope.decode(intent.effects()).orElse(null);
        require(recipe!=null&&recipe.kind().equals(intent.kind())&&recipe.exitCooldownMillis()!=null,"Missing timed exit evidence");
        return recipe;
    }
    private void requireCatalogExitDuration(String variantId,long duration)throws SQLException{
        Variant variant=loadVariant(variantId);
        require(CatalogCodec.definition(variant.lastValidDefinition()).expansionCooldownMillis()==duration,
                "Timed exit duration does not match the current catalog");
    }
    private Operation requireOperation(UUID id,long version)throws Exception{Operation op=loadOperation(id);require(op!=null&&op.rowVersion()==version&&op.pending(),"Stale or terminal operation");return op;}
    private Operation loadOperation(UUID id)throws Exception{
        try(var p=statement("SELECT * FROM operations WHERE id=?",id);var r=p.executeQuery()){
            if(!r.next())return null;
            State before=SnapshotCodec.decode(r.getBytes("before_state")),after=SnapshotCodec.decode(r.getBytes("after_state"));
            Intent intent=new Intent(uuid(r.getString("id")),uuid(r.getString("tower_id")),r.getLong("expected_version"),r.getLong("expected_generation"),r.getLong("expected_revision"),r.getString("kind"),after,new Document(r.getInt("effects_version"),r.getString("effects")));
            if(!before.tower().id().equals(intent.towerId())||before.tower().generation()!=intent.expectedGeneration()||before.tower().deploymentRevision()!=intent.expectedDeploymentRevision())throw new RepositoryException("Corrupt journal ownership");
            return new Operation(intent,before,Phase.valueOf(r.getString("phase")),r.getLong("row_version"),r.getString("evidence"));
        }
    }

    /** Award metadata never creates/delivers an item; quantity is not bounded by a gameplay tower count. */
    public CompletableFuture<Award> saveAward(Award award,long expected){Objects.requireNonNull(award);return submit(()->transaction(()->{
        Award old=loadAward(award.id());
        Variant variant=loadVariant(award.variantId());require(variant!=null,"Missing award variant");
        boolean managed;try(var p=statement("SELECT 1 FROM catalog_managed_variants WHERE variant_id=?",award.variantId());var rows=p.executeQuery()){managed=rows.next();}
        boolean held=managed&&variant.status()==VariantStatus.RETIRED&&award.status()==AwardStatus.PENDING;
        require(managed||variant.status()!=VariantStatus.RETIRED||award.status()!=AwardStatus.PENDING,"Retired unmanaged award requires explicit hold");
        require(variant.status()!=VariantStatus.RETIRED||award.status()!=AwardStatus.DELIVERED,"Retired award cannot deliver");
        Award effective=held?new Award(award.id(),award.sourceKey(),award.recipient(),award.variantId(),award.quantity(),AwardStatus.PAUSED,award.rowVersion()):award;
        if(expected==-1){
            require(old==null&&award.rowVersion()==0&&award.status()!=AwardStatus.DELIVERED,"New award must be pending or paused");
            update("INSERT INTO awards VALUES(?,?,?,?,?,?,?,1)",award.id(),award.sourceKey(),award.recipient(),award.variantId(),award.quantity(),effective.status().name(),0);
        }else{
            require(old!=null&&old.rowVersion()==expected&&award.rowVersion()==next(expected),"Award CAS lost");
            require(old.sourceKey().equals(award.sourceKey())&&old.recipient().equals(award.recipient())&&old.variantId().equals(award.variantId())&&old.quantity()==award.quantity(),"Award provenance is immutable");
            if(award.status()==AwardStatus.DELIVERED)require(awardCount(award.id())==award.quantity(),"Award identities incomplete");
            require(update("UPDATE awards SET status=?,row_version=? WHERE id=? AND row_version=?",effective.status().name(),award.rowVersion(),award.id(),expected)==1,"Award CAS lost");
        }
        if(held)update("INSERT OR IGNORE INTO catalog_award_holds VALUES(?)",award.id());
        else update("DELETE FROM catalog_award_holds WHERE award_id=?",award.id());
        return effective;
    }));}
    /** Append a bounded batch of existing, unclaimed identities, atomically with award CAS. */
    public CompletableFuture<Award> associateAwardTowers(UUID awardId,long expected,Map<UUID,Long> towerVersions){
        Map<UUID,Long> versions=Map.copyOf(towerVersions);page(versions.size());
        return submit(()->transaction(()->{
            Award award=loadAward(awardId);require(award!=null&&award.rowVersion()==expected&&award.status()==AwardStatus.PENDING,"Stale or paused/delivered award");
            require(loadVariant(award.variantId()).status()==VariantStatus.ACTIVE,"Retired award cannot allocate identities");
            require(awardCount(awardId)<=award.quantity()-versions.size(),"Award quantity exceeded");
            for(var pair:versions.entrySet()){
                TowerSnapshot tower=ownedTower(pair.getKey(),pair.getValue(),null);
                require(tower.state().tower().variantId().equals(award.variantId()),"Award variant mismatch");
                update("INSERT INTO award_towers VALUES(?,?)",awardId,pair.getKey());
            }
            require(update("UPDATE awards SET row_version=? WHERE id=? AND row_version=?",next(expected),awardId,expected)==1,"Award CAS lost");
            return loadAward(awardId);
        }));
    }
    public CompletableFuture<List<UUID>> awardTowerIds(UUID awardId,UUID after,int limit){Objects.requireNonNull(awardId);page(limit);return submit(()->snapshot(()->List.copyOf(ids("SELECT tower_id AS id FROM award_towers WHERE award_id=? AND tower_id>? ORDER BY tower_id LIMIT ?",awardId,after==null?"":after.toString(),limit))));}
    private long awardCount(UUID id)throws SQLException{try(var p=statement("SELECT count(*) FROM award_towers WHERE award_id=?",id);var r=p.executeQuery()){r.next();return r.getLong(1);}}
    public CompletableFuture<Optional<Award>> award(UUID id){Objects.requireNonNull(id);return submit(()->snapshot(()->Optional.ofNullable(loadAward(id))));}
    public CompletableFuture<Optional<Award>> awardBySource(String source,UUID recipient,String variant){text(source,1024);Objects.requireNonNull(recipient);identifier(variant);return submit(()->snapshot(()->{
        List<UUID> ids=ids("SELECT id FROM awards WHERE source_key=? AND recipient=? AND variant_id=?",source,recipient,variant);return ids.isEmpty()?Optional.empty():Optional.of(loadAward(ids.getFirst()));
    }));}
    private Award loadAward(UUID id)throws Exception{
        try(var p=statement("SELECT * FROM awards WHERE id=?",id);var r=p.executeQuery()){
            if(!r.next())return null;
            Award award=new Award(uuid(r.getString("id")),r.getString("source_key"),uuid(r.getString("recipient")),r.getString("variant_id"),r.getLong("quantity"),AwardStatus.valueOf(r.getString("status")),r.getLong("row_version"));
            long count=awardCount(id);
            if(r.getInt("payload_version")!=1||count>award.quantity()||(award.status()==AwardStatus.DELIVERED&&count!=award.quantity()))throw new RepositoryException("Award allocation mismatch");
            try(var links=statement("SELECT 1 FROM award_towers a JOIN towers t ON a.tower_id=t.id WHERE a.award_id=? AND t.variant_id<>? LIMIT 1",id,award.variantId());var rows=links.executeQuery()){if(rows.next())throw new RepositoryException("Award variant link mismatch");}
            return award;
        }
    }
    /** Exact resource bytes must be captured on the owning scheduler before this call. */
    public CompletableFuture<Escrow> saveEscrow(Escrow escrow,long expected,long operationVersion){Objects.requireNonNull(escrow);return submit(()->transaction(()->{
        Operation op=requireOperation(escrow.operation(),operationVersion);
        require(op.intent().towerId().equals(escrow.towerId())&&op.intent().expectedDeploymentRevision()==escrow.deploymentRevision(),"Escrow operation mismatch");
        ownedTower(escrow.towerId(),next(op.intent().expectedRowVersion()),escrow.operation());
        Escrow old=loadEscrow(escrow.operation());
        if(expected==-1){require(old==null&&escrow.rowVersion()==0,"Escrow already exists");update("INSERT INTO repair_escrows VALUES(?,?,?,?,?,?,?,?,?,1)",escrow.operation(),escrow.towerId(),escrow.deploymentRevision(),escrow.contributor(),escrow.resourceFormat(),escrow.resources().copy(),escrow.payment().name(),escrow.hpPhase().name(),0);}
        else {
            require(old!=null&&old.rowVersion()==expected&&escrow.rowVersion()==next(expected),"Escrow CAS lost");
            require(old.towerId().equals(escrow.towerId())&&old.deploymentRevision()==escrow.deploymentRevision()&&old.contributor().equals(escrow.contributor())&&old.resourceFormat()==escrow.resourceFormat()&&old.resources().equals(escrow.resources()),"Escrow provenance/resources immutable");
            require(update("UPDATE repair_escrows SET payment=?,hp_phase=?,row_version=? WHERE operation_id=? AND row_version=?",escrow.payment().name(),escrow.hpPhase().name(),escrow.rowVersion(),escrow.operation(),expected)==1,"Escrow CAS lost");
        }return escrow;
    }));}
    public CompletableFuture<Optional<Escrow>> escrow(UUID operation){Objects.requireNonNull(operation);return submit(()->snapshot(()->Optional.ofNullable(loadEscrow(operation))));}
    private Escrow loadEscrow(UUID id)throws SQLException{
        try(var p=statement("SELECT * FROM repair_escrows WHERE operation_id=?",id);var r=p.executeQuery()){
            if(!r.next())return null;if(r.getInt("payload_version")!=1)throw new SQLException("Unknown escrow payload version");
            return new Escrow(uuid(r.getString("operation_id")),uuid(r.getString("tower_id")),r.getLong("deployment_revision"),uuid(r.getString("contributor")),r.getInt("resource_format"),new ResourceBytes(r.getBytes("resources")),PaymentPhase.valueOf(r.getString("payment")),HpPhase.valueOf(r.getString("hp_phase")),r.getLong("row_version"));
        }
    }
    private List<UUID> ids(String sql,Object...values)throws SQLException{var result=new ArrayList<UUID>();try(var p=statement(sql,values);var r=p.executeQuery()){while(r.next())result.add(uuid(r.getString("id")));}return result;}
    private void validateCells(TowerSnapshot tower)throws Exception{
        var states=new ArrayList<State>();states.add(tower.state());
        if(tower.activeOperation()!=null){Operation operation=loadOperation(tower.activeOperation());require(operation!=null,"Missing cell operation");states.add(operation.intent().after());}
        List<Reservation> actual=loadCells(tower.state().tower().id());
        require(actual.stream().allMatch(r->Objects.equals(r.operation(),tower.activeOperation()))
                &&new HashSet<>(actual.stream().map(Reservation::cell).toList()).equals(cells(states)),"Structure clearance reservation mismatch");
    }
    private void validateStoredRows()throws Exception{
        loadCatalog();
        try(var s=connection.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new SQLException("Database reference integrity failed");}
        for(String table:List.of("variants","towers","operations","awards","repair_escrows")){
            String column=table.equals("repair_escrows")?"operation_id":"id";String cursor="";
            while(true){
                var keys=new ArrayList<String>();try(var p=statement("SELECT "+column+" FROM "+table+" WHERE "+column+">? ORDER BY "+column+" LIMIT ?",cursor,MAX_PAGE);var r=p.executeQuery()){while(r.next())keys.add(r.getString(1));}
                if(keys.isEmpty())break;
                for(String key:keys){
                    switch(table){case "variants"->loadVariant(key);case "towers"->{TowerSnapshot t=loadTower(uuid(key));validateCells(t);if(t.activeOperation()!=null){Operation op=loadOperation(t.activeOperation());require(op!=null&&op.pending()&&op.intent().towerId().equals(t.state().tower().id())&&t.rowVersion()==next(op.intent().expectedRowVersion()),"Invalid journal ownership");}}case "operations"->{Operation op=loadOperation(uuid(key));if(op.pending())ownedTower(op.intent().towerId(),next(op.intent().expectedRowVersion()),op.intent().id());}case "awards"->loadAward(uuid(key));case "repair_escrows"->loadEscrow(uuid(key));default->throw new IllegalStateException();}
                }
                cursor=keys.getLast();
            }
        }
    }
}
