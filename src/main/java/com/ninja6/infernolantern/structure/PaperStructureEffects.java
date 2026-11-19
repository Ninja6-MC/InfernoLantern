package com.ninja6.infernolantern.structure;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryRecords.*;
import org.bukkit.*;
import org.bukkit.block.data.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import static com.ninja6.infernolantern.structure.StructureLifecycle.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.timedStructureExit;

/** Reusable Paper owner-thread effects; not registered as a native placement/menu handler. */
public final class PaperStructureEffects implements WorldEffects {
    public record Voxel(int x,int y,int z,String replacement) {}
    public record Visual(int x,int y,int z,ResourceBytes helmet) { public Visual { Objects.requireNonNull(helmet); } }
    /** Complete oriented rectangular clearance. A null replacement reserves unchanged terrain. */
    public record Layout(List<Voxel> voxels,List<Visual> visuals) {
        public Layout {
            voxels=List.copyOf(voxels);visuals=List.copyOf(visuals);
            if(voxels.isEmpty()||voxels.size()+visuals.size()>4096)throw new IllegalArgumentException("Manifest size");
            Set<String> unique=new HashSet<>();int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
            for(Voxel v:voxels){
                if(!unique.add(v.x()+":"+v.y()+":"+v.z()))throw new IllegalArgumentException("Duplicate voxel");
                minX=Math.min(minX,v.x());maxX=Math.max(maxX,v.x());minY=Math.min(minY,v.y());maxY=Math.max(maxY,v.y());minZ=Math.min(minZ,v.z());maxZ=Math.max(maxZ,v.z());
            }
            long dx=(long)maxX-minX+1,dy=(long)maxY-minY+1,dz=(long)maxZ-minZ+1;
            if(dx>4096||dy>4096||dz>4096||dx*dy*dz!=voxels.size()||!unique.contains("0:0:0"))throw new IllegalArgumentException("Incomplete clearance volume");
            for(Visual v:visuals)if(!unique.contains(v.x()+":"+v.y()+":"+v.z()))throw new IllegalArgumentException("Visual outside declared volume");
        }
    }
    /** Must verify current config/variant, native/eligibility/world/protection/reach and explicit session override. */
    @FunctionalInterface public interface Access {
        boolean allowed(Request request,State before);
        default CommitPermit commitPermit() { return null; }
        /** Current protection authorization for restoring only the adapter's exact owned changes. */
        default boolean mayRestore(Request request,State before) { return true; }
    }
    /** System source has distinct protection checks for applying and restoring the owned geometry. */
    @FunctionalInterface public interface DefeatAccess {
        boolean allowed(Request request,State before,boolean restoring);
    }
    /** Blast cleanup and compensation need separately supplied physical authority. */
    public interface BlastAccess extends Access {
        @Override boolean mayRestore(Request request,State before);
    }
    private static final NamespacedKey TOWER=key("structure_tower"),REVISION=key("structure_revision"),OPERATION=key("structure_operation"),STAGED=key("item_staged");
    private static NamespacedKey key(String name){return new NamespacedKey("infernolantern",name);}
    private final Player player;
    private final Supplier<UUID> currentSession;
    private final Access access;
    private final Action systemAction;
    private final Layout layout;
    private final int sourceSlot;
    private final ItemStack appearance;
    private final GroundPolicy groundPolicy;
    private final Consumer<Item> groundAdmission;
    private final boolean sourceWaterAllowed;
    private final PaperItemIdentity identity=new PaperItemIdentity();
    private final Map<Cell,String> captured=new LinkedHashMap<>(),mutated=new LinkedHashMap<>();
    private final List<ArmorStand> stagedVisuals=new ArrayList<>(),oldVisuals=new ArrayList<>();
    private final List<ItemStack> visualItems=new ArrayList<>();
    private final Map<UUID,ItemStack> oldHelmets=new HashMap<>();
    private record OldVisual(Location position,boolean marker,boolean invisible,boolean invulnerable,boolean gravity,String operation) {}
    private final Map<UUID,OldVisual> oldSnapshots=new HashMap<>();
    private final Set<UUID> quiesced=new HashSet<>();
    private final Map<UUID,Location> stageLocations=new HashMap<>();
    private Request request;
    private TowerSnapshot before;
    private Preparation preparation;
    private byte[] source;
    private boolean consumed,applied;
    private Item stagedDrop;
    /** One instance per request. All Bukkit values stay on the owner thread, never the SQL worker. */
    public PaperStructureEffects(Player player,Supplier<UUID> currentSession,Access access,Layout layout,int sourceSlot,
                                 ItemStack appearance,GroundPolicy groundPolicy,PaperGroundItems groundItems,boolean sourceWaterAllowed){
        this(player,currentSession,access,layout,sourceSlot,appearance,groundPolicy,Objects.requireNonNull(groundItems)::inspectCommittedDrop,sourceWaterAllowed);
    }
    PaperStructureEffects(Player player,Supplier<UUID> currentSession,Access access,Layout layout,int sourceSlot,
                          ItemStack appearance,GroundPolicy groundPolicy,Consumer<Item> groundAdmission,boolean sourceWaterAllowed){
        owner();this.systemAction=null;this.player=Objects.requireNonNull(player);this.currentSession=Objects.requireNonNull(currentSession);this.access=Objects.requireNonNull(access);
        this.layout=Objects.requireNonNull(layout);this.sourceSlot=sourceSlot;this.appearance=Objects.requireNonNull(appearance).clone();
        this.groundPolicy=Objects.requireNonNull(groundPolicy);this.groundAdmission=Objects.requireNonNull(groundAdmission);this.sourceWaterAllowed=sourceWaterAllowed;
        if(sourceSlot<0||sourceSlot>=player.getInventory().getSize())throw new IllegalArgumentException("Source slot");
    }
    /** World-only effects for defeat. The supplied authority must check current protection on each call. */
    public PaperStructureEffects(DefeatAccess authority,Layout compactLayout,boolean sourceWaterAllowed){
        owner();Objects.requireNonNull(authority);
        this.systemAction=Action.DEFEAT;this.layout=Objects.requireNonNull(compactLayout);this.sourceWaterAllowed=sourceWaterAllowed;
        this.access=new Access(){
            @Override public boolean allowed(Request request,State before){return authority.allowed(request,before,false);}
            @Override public boolean mayRestore(Request request,State before){return authority.allowed(request,before,true);}
        };
        this.player=null;this.currentSession=null;this.sourceSlot=-1;this.appearance=null;this.groundPolicy=null;this.groundAdmission=null;
    }
    /** World-only blast cleanup. Access supplies current physical authority, including restoration. */
    public static PaperStructureEffects blast(BlastAccess physical,ItemStack appearance,GroundPolicy groundPolicy,
                                               PaperGroundItems groundItems){
        return new PaperStructureEffects(physical,appearance,groundPolicy,
                Objects.requireNonNull(groundItems)::inspectCommittedDrop);
    }
    PaperStructureEffects(BlastAccess physical,ItemStack appearance,GroundPolicy groundPolicy,Consumer<Item> groundAdmission){
        owner();this.systemAction=Action.BLAST;this.access=Objects.requireNonNull(physical);
        this.player=null;this.currentSession=null;this.layout=null;this.sourceSlot=-1;
        this.appearance=Objects.requireNonNull(appearance).clone();
        this.groundPolicy=Objects.requireNonNull(groundPolicy);this.groundAdmission=Objects.requireNonNull(groundAdmission);
        this.sourceWaterAllowed=false;
    }
    private static void owner(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Paper owning thread required");}
    private boolean session(Request r){return systemAction!=null?r.action()==systemAction:r.action()!=Action.DEFEAT&&r.action()!=Action.BLAST&&player.isOnline()&&r.actor().equals(player.getUniqueId())&&r.session().equals(currentSession.get())&&player.getWorld().getUID().equals(r.base().world());}
    private World world(){World w=Bukkit.getWorld(request.base().world());if(w==null)throw new IllegalStateException("World unavailable");return w;}
    private Cell cell(int x,int y,int z){Position b=request.base();return new Cell(b.world(),Math.addExact(b.x(),x),Math.addExact(b.y(),y),Math.addExact(b.z(),z));}
    private void available(Cell c){World w=world();if(c.y()<w.getMinHeight()||c.y()>=w.getMaxHeight()||(c.x()>>4)!=request.anchor().chunkX()||(c.z()>>4)!=request.anchor().chunkZ()
            ||!w.isChunkLoaded(c.x()>>4,c.z()>>4)||!w.getWorldBorder().isInside(new Location(w,c.x()+.5,c.y()+.5,c.z()+.5)))throw new IllegalStateException("Unsupported bounds/chunk/border");}
    private String state(Cell c){available(c);return world().getBlockAt(c.x(),c.y(),c.z()).getBlockData().getAsString();}
    private void write(Cell c,String value){available(c);world().getBlockAt(c.x(),c.y(),c.z()).setBlockData(Bukkit.createBlockData(value),false);}
    private static Cell cell(Component c){return new Cell(c.world(),c.x(),c.y(),c.z());}
    private boolean exactSource(){return source!=null&&Arrays.equals(source,bytes(player.getInventory().getItem(sourceSlot)));}
    private static boolean air(Material m){return m==Material.AIR||m==Material.CAVE_AIR||m==Material.VOID_AIR;}
    private static byte[] bytes(ItemStack item){return item==null||air(item.getType())?null:item.serializeAsBytes();}
    private boolean emptySource(){return bytes(player.getInventory().getItem(sourceSlot))==null;}
    static boolean componentOwned(Entity e,UUID world,UUID tower,long revision){return e!=null&&e.isValid()&&e.getWorld().getUID().equals(world)
            &&tower.toString().equals(e.getPersistentDataContainer().get(TOWER,PersistentDataType.STRING))
            &&Long.valueOf(revision).equals(e.getPersistentDataContainer().get(REVISION,PersistentDataType.LONG));}
    private boolean owned(Entity e,long revision){return componentOwned(e,request.base().world(),request.tower(),revision);}
    private boolean atStage(Entity e){Location expected=stageLocations.get(e.getUniqueId());Location actual=e.getLocation();return expected!=null&&expected.getWorld().equals(actual.getWorld())&&expected.getX()==actual.getX()&&expected.getY()==actual.getY()&&expected.getZ()==actual.getZ();}
    private boolean visualStage(ArmorStand e){return ours(e)&&atStage(e)&&e.isMarker()&&e.isInvisible()&&e.isInvulnerable()&&!e.hasGravity()&&bytes(e.getEquipment().getHelmet())==null;}
    private boolean ours(Entity e){return owned(e,Math.incrementExact(request.revision()))&&request.operation().toString().equals(e.getPersistentDataContainer().get(OPERATION,PersistentDataType.STRING));}
    private boolean oldVisualExact(ArmorStand stand,boolean quiet){
        OldVisual captured=oldSnapshots.get(stand.getUniqueId());
        return captured!=null&&owned(stand,request.revision())&&captured.position().equals(stand.getLocation())
                &&captured.marker()==stand.isMarker()&&captured.invisible()==stand.isInvisible()
                &&captured.invulnerable()==stand.isInvulnerable()&&captured.gravity()==stand.hasGravity()
                &&Objects.equals(captured.operation(),stand.getPersistentDataContainer().get(OPERATION,PersistentDataType.STRING))
                &&Arrays.equals(quiet?null:bytes(oldHelmets.get(stand.getUniqueId())),bytes(stand.getEquipment().getHelmet()));
    }
    private boolean oldExact(){
        for(Component c:before.state().components()){
            if(c.kind()==ComponentKind.BLOCK){if(!state(cell(c)).equals(c.expectedState()))return false;}
            else {Entity e=world().getEntity(c.entity());if(!(e instanceof ArmorStand stand)||!owned(e,request.revision()))return false;
                Location at=e.getLocation();if(at.getX()!=c.x()+.5||at.getY()!=c.y()||at.getZ()!=c.z()+.5)return false;
                if(oldSnapshots.containsKey(c.entity())&&!oldVisualExact(stand,quiesced.contains(c.entity())))return false;}
        }return true;
    }
    private String replacement(Voxel voxel,String original){
        if(voxel.replacement()==null)return original;
        BlockData result=Bukkit.createBlockData(voxel.replacement()),terrain=Bukkit.createBlockData(original);
        if(air(terrain.getMaterial()))return result.getAsString();
        if(terrain.getMaterial()==Material.WATER&&sourceWaterAllowed&&terrain instanceof Levelled level&&level.getLevel()==0&&result instanceof Waterlogged waterlogged){waterlogged.setWaterlogged(true);return result.getAsString();}
        throw new IllegalStateException("Solid or unsupported fluid replacement");
    }
    private boolean spaceFree(Collection<Cell> cells){
        Set<UUID> own=new HashSet<>();stagedVisuals.forEach(e->own.add(e.getUniqueId()));if(stagedDrop!=null)own.add(stagedDrop.getUniqueId());before.state().components().stream().filter(c->c.entity()!=null).forEach(c->own.add(c.entity()));
        for(Cell c:cells){available(c);BoundingBox box=new BoundingBox(c.x(),c.y(),c.z(),c.x()+1,c.y()+1,c.z()+1);
            for(Entity e:world().getNearbyEntities(box))if(!own.contains(e.getUniqueId())&&e.getBoundingBox().overlaps(box))return false;
        }return true;
    }
    @Override public Preparation prepare(Request r,TowerSnapshot snapshot){
        owner();if(request!=null)throw new IllegalStateException("Effects already captured");request=r;before=snapshot;
        if(!session(r)||r.action()==Action.BLAST&&!r.impact().matches(before.state())
                ||!access.allowed(r,before.state())||!oldExact())return null;
        boolean dropping=r.action()==Action.BREAK||r.action()==Action.ADMIN_RECOVER||r.action()==Action.BLAST;
        if(r.action()==Action.PLACE){ItemStack item=player.getInventory().getItem(sourceSlot);
            if(identity.read(item).filter(ItemLifecycle.token(before.state().tower())::equals).isEmpty())return null;
            source=item.serializeAsBytes();
        }
        for(Component c:before.state().components()){
            if(c.kind()==ComponentKind.BLOCK)captured.put(cell(c),state(cell(c)));
            else {ArmorStand stand=(ArmorStand)world().getEntity(c.entity());oldVisuals.add(stand);ItemStack helmet=stand.getEquipment().getHelmet();oldHelmets.put(stand.getUniqueId(),helmet==null?null:helmet.clone());
                oldSnapshots.put(stand.getUniqueId(),new OldVisual(stand.getLocation().clone(),stand.isMarker(),stand.isInvisible(),stand.isInvulnerable(),stand.hasGravity(),stand.getPersistentDataContainer().get(OPERATION,PersistentDataType.STRING)));}
        }
        var manifest=new ArrayList<Component>();
        if(!dropping){
            var oldBlocks=new HashMap<Cell,Component>();before.state().components().stream().filter(c->c.kind()==ComponentKind.BLOCK).forEach(c->oldBlocks.put(cell(c),c));
            List<Cell> target=layout.voxels().stream().map(v->cell(v.x(),v.y(),v.z())).toList();
            if(!spaceFree(target))return null;
            int i=0;
            for(Voxel voxel:layout.voxels()){
                Cell c=cell(voxel.x(),voxel.y(),voxel.z());String current=state(c);captured.put(c,current);
                Component previous=oldBlocks.get(c);String original=previous==null?current:previous.originalState();
                // Unchanged clearance may contain air or explicitly supported source water, never solids/lava.
                BlockData terrain=Bukkit.createBlockData(original);
                if(!air(terrain.getMaterial())&&!(sourceWaterAllowed&&terrain.getMaterial()==Material.WATER&&terrain instanceof Levelled l&&l.getLevel()==0))return null;
                String expected;
                if(systemAction==Action.DEFEAT&&voxel.replacement()==null&&previous!=null&&!previous.originalState().equals(previous.expectedState()))return null;
                try { expected=voxel.replacement()==null?(previous==null?current:previous.expectedState()):replacement(voxel,original); }
                catch(IllegalArgumentException|IllegalStateException unsupported){return null;}
                if(systemAction==Action.DEFEAT&&Bukkit.createBlockData(expected).getMaterial()==Material.LIGHT)return null;
                manifest.add(new Component("cell."+i++,ComponentKind.BLOCK,c.world(),c.x(),c.y(),c.z(),original,expected,null,null));
            }
            for(Visual visual:layout.visuals()){
                ItemStack helmet=ItemStack.deserializeBytes(visual.helmet().copy());
                if(air(helmet.getType())||identity.marked(helmet))return null;
                helmet.setAmount(1);visualItems.add(helmet);
            }
        }
        String physicalEvidence="Source="+(source==null?"none":Base64.getEncoder().encodeToString(source))+";captured="+captured+";blocks="+manifest
                +";visualTemplates="+visualItems.stream().map(item->Base64.getEncoder().encodeToString(item.serializeAsBytes())).toList()
                +";beforeVisuals="+oldHelmets.entrySet().stream().map(e->e.getKey()+":"+(bytes(e.getValue())==null?"none":Base64.getEncoder().encodeToString(bytes(e.getValue())))).toList();
        if(physicalEvidence.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+(long)visualItems.size()*256+256>com.ninja6.infernolantern.repository.RepositoryRecords.MAX_PAYLOAD)return null;
        RecoveryEnvelope.Inventory inventory=source==null?null:new RecoveryEnvelope.Inventory(r.actor(),sourceSlot,new ResourceBytes(source),null);
        String anticipated=physicalEvidence+";stagedVisuals="+Collections.nCopies(visualItems.size(),new UUID(0,0))+";stagedGround="+(dropping?new UUID(0,0):"none");
        try{recoveryEvidence(r,anticipated,inventory);}catch(IllegalArgumentException oversized){return null;}
        if(!dropping){
            int i=0;
            for(Visual visual:layout.visuals()){
                Cell c=cell(visual.x(),visual.y(),visual.z());available(c);
                ArmorStand stand=world().spawn(new Location(world(),c.x()+.5,c.y(),c.z()+.5),ArmorStand.class,e->{
                    e.setInvisible(true);e.setMarker(true);e.setGravity(false);e.setInvulnerable(true);e.setPersistent(true);
                    e.getPersistentDataContainer().set(TOWER,PersistentDataType.STRING,r.tower().toString());
                    e.getPersistentDataContainer().set(REVISION,PersistentDataType.LONG,Math.incrementExact(r.revision()));
                    e.getPersistentDataContainer().set(OPERATION,PersistentDataType.STRING,r.operation().toString());
                });
                stagedVisuals.add(stand);stageLocations.put(stand.getUniqueId(),stand.getLocation().clone());manifest.add(new Component("visual."+i++,ComponentKind.ENTITY,c.world(),c.x(),c.y(),c.z(),null,null,stand.getUniqueId(),"minecraft:armor_stand"));
            }
        }
        Ground ground=null;
        if(dropping){
            Cell base=cell(0,0,0);available(base);
            ItemToken token=new ItemToken(ItemToken.SCHEMA,before.state().tower().variantId(),r.tower(),Math.incrementExact(r.generation()));
            ItemStack item=identity.create(appearance,token,groundPolicy);
            if(identity.read(item).filter(token::equals).isEmpty())return null;
            stagedDrop=world().dropItem(new Location(world(),base.x()+.5,base.y()+.5,base.z()+.5),item,e->{
                e.setCanPlayerPickup(false);e.setCanMobPickup(false);e.setInvulnerable(true);e.setGravity(false);e.setUnlimitedLifetime(true);e.setVelocity(new org.bukkit.util.Vector());
                e.getPersistentDataContainer().set(STAGED,PersistentDataType.BYTE,(byte)1);
                e.getPersistentDataContainer().set(PaperItemIdentity.OPERATION,PersistentDataType.STRING,r.operation().toString());
            });
            stageLocations.put(stagedDrop.getUniqueId(),stagedDrop.getLocation().clone());
            ground=new Ground(stagedDrop.getUniqueId(),base.world(),base.x()>>4,base.z()>>4,r.operation(),"active");
        }
        String evidence=physicalEvidence+";stagedVisuals="+stagedVisuals.stream().map(Entity::getUniqueId).toList()+";stagedGround="+(ground==null?"none":ground.entity());
        preparation=new Preparation(manifest,ground,recoveryEvidence(r,evidence,inventory));return preparation;
    }
    private static Document recoveryEvidence(Request request,String evidence,RecoveryEnvelope.Inventory inventory){
        String kind=kind(request);
        var payload=new Document(1,evidence);
        return timedStructureExit(kind)?RecoveryEnvelope.timedStructure(kind,request.anchor(),payload,request.policy().cooldownMillis())
                :RecoveryEnvelope.structure(kind,request.anchor(),payload,inventory);
    }
    @Override public CommitPermit commitPermit(){owner();return access.commitPermit();}
    @Override public boolean validate(Claim claim){owner();return claim.preparation()==preparation&&session(claim.request())&&access.allowed(request,before.state());}
    private boolean stageExact(){
        if(stagedDrop==null)return true;
        return stagedDrop.isValid()&&atStage(stagedDrop)&&stagedDrop.getWorld().getUID().equals(request.base().world())&&stagedDrop.getPersistentDataContainer().has(STAGED)
                &&!stagedDrop.canPlayerPickup()&&!stagedDrop.canMobPickup()&&stagedDrop.isInvulnerable()
                &&request.operation().toString().equals(stagedDrop.getPersistentDataContainer().get(PaperItemIdentity.OPERATION,PersistentDataType.STRING))
                &&identity.read(stagedDrop.getItemStack()).filter(t->t.tower().equals(request.tower())&&t.generation()==Math.incrementExact(request.generation())&&t.variant().equals(before.state().tower().variantId())).isPresent();
    }
    @Override public boolean apply(Claim claim){
        owner();if(applied||!validate(claim)||!oldExact()||!stagedVisuals.stream().allMatch(this::visualStage)||!stageExact()||(request.action()==Action.PLACE&&!exactSource()))return false;
        for(var c:captured.entrySet())if(!state(c.getKey()).equals(c.getValue()))return false;
        if(!spaceFree(preparation.manifest().stream().filter(c->c.kind()==ComponentKind.BLOCK).map(PaperStructureEffects::cell).toList()))return false;
        var target=new LinkedHashMap<Cell,String>();
        for(Component c:before.state().components())if(c.kind()==ComponentKind.BLOCK)target.put(cell(c),c.originalState());
        for(Component c:preparation.manifest())if(c.kind()==ComponentKind.BLOCK)target.put(cell(c),c.expectedState());
        for(var entry:target.entrySet())if(!state(entry.getKey()).equals(entry.getValue())){mutated.put(entry.getKey(),entry.getValue());write(entry.getKey(),entry.getValue());}
        for(ArmorStand stand:oldVisuals){if(!oldVisualExact(stand,false))return false;quiesced.add(stand.getUniqueId());stand.getEquipment().setHelmet(null);}
        if(request.action()==Action.PLACE){if(!exactSource())return false;player.getInventory().setItem(sourceSlot,null);consumed=true;}
        applied=true;return true;
    }
    @Override public boolean verify(Claim claim){
        owner();if(!applied||!validate(claim)||!stageExact()||(consumed&&!emptySource()))return false;
        for(var entry:mutated.entrySet())if(!state(entry.getKey()).equals(entry.getValue()))return false;
        for(Component c:preparation.manifest()){
            if(c.kind()==ComponentKind.BLOCK){if(!state(cell(c)).equals(c.expectedState()))return false;}
            else if(!(world().getEntity(c.entity()) instanceof ArmorStand stand)||!visualStage(stand))return false;
        }
        for(ArmorStand stand:oldVisuals)if(!oldVisualExact(stand,true))return false;
        return true;
    }
    @Override public boolean rollback(Claim claim){
        owner();boolean restored=session(request);
        boolean mayRestore=access.mayRestore(request,before.state());
        // Unrelated edits are never overwritten, even while compensating a partial operation.
        for(var entry:mutated.entrySet()){
            String current=state(entry.getKey());if(current.equals(captured.get(entry.getKey())))continue;
            if(!mayRestore||!current.equals(entry.getValue())){restored=false;continue;}
            write(entry.getKey(),captured.get(entry.getKey()));
        }
        for(ArmorStand stand:stagedVisuals){if(visualStage(stand))stand.remove();else restored=false;}
        for(ArmorStand stand:oldVisuals)if(quiesced.contains(stand.getUniqueId())){if(!mayRestore||!oldVisualExact(stand,true)){restored=false;continue;}stand.getEquipment().setHelmet(oldHelmets.get(stand.getUniqueId()));quiesced.remove(stand.getUniqueId());}
        if(stagedDrop!=null){if(stageExact())stagedDrop.remove();else restored=false;}
        if(consumed){if(mayRestore&&session(request)&&emptySource()){player.getInventory().setItem(sourceSlot,ItemStack.deserializeBytes(source));consumed=false;}else restored=false;}
        return restored&&oldExact()&&(request.action()!=Action.PLACE||exactSource());
    }
    @Override public void discard(Request r,Preparation prepared){
        owner();if(r!=request||prepared!=preparation)throw new IllegalStateException("Preparation identity changed");
        for(ArmorStand stand:stagedVisuals){if(!visualStage(stand))throw new IllegalStateException("Staged visual ownership changed");stand.remove();}
        if(stagedDrop!=null){if(!stageExact())throw new IllegalStateException("Staged ground changed");stagedDrop.remove();}
    }
    @Override public boolean activate(Claim claim,TowerSnapshot completed){
        owner();if(!completed.mutationReady()||completed.rowVersion()!=Math.addExact(request.rowVersion(),2)||!completed.state().equals(claim.operation().intent().after())||!verify(claim))return false;
        for(ArmorStand stand:oldVisuals){if(!oldVisualExact(stand,true))return false;stand.remove();}
        for(int i=0;i<stagedVisuals.size();i++)stagedVisuals.get(i).getEquipment().setHelmet(visualItems.get(i));
        if(stagedDrop!=null){
            stagedDrop.getPersistentDataContainer().remove(STAGED);stagedDrop.setInvulnerable(false);stagedDrop.setGravity(true);stagedDrop.setUnlimitedLifetime(!groundPolicy.despawns());
            groundAdmission.accept(stagedDrop);
        }
        return true;
    }
}
