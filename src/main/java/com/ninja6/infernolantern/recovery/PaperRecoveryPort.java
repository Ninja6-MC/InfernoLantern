package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.repository.RepositoryRecords.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.function.*;
import static com.ninja6.infernolantern.recovery.ReconciliationService.*;

/** Owner-thread item observation/activation, never a save scanner, repair command or drop factory. */
public final class PaperRecoveryPort implements WorldPort {
    /** Must independently establish coverage/provenance; no permissive default is supplied. */
    public record CoverageProof(Coverage coverage,String provenance) {
        public CoverageProof {Objects.requireNonNull(coverage);Objects.requireNonNull(provenance);if(provenance.isBlank()||provenance.length()>2048)throw new IllegalArgumentException("Coverage provenance");}
    }
    @FunctionalInterface public interface CoverageCheck {
        CoverageProof assess(Operation operation,TowerSnapshot tower,RecoveryEnvelope.Context context,ItemView items);
    }
    private static final NamespacedKey STAGED=new NamespacedKey("infernolantern","item_staged");
    private final CoverageCheck coverage;
    private final Predicate<Operation> authorized;
    private final Function<String,GroundPolicy> policies;
    private final Consumer<Item> admission;
    private final PaperItemIdentity identity=new PaperItemIdentity();
    private final Map<UUID,Snapshot> observations=new HashMap<>();
    private boolean inspected;
    /** Encoded API item bytes and the exact entity state required by the item observation. */
    public record GroundView(UUID entity,String stack,UUID world,int chunkX,int chunkZ,double x,double y,double z,
                             boolean staged,boolean pickup,boolean mob,boolean invulnerable,boolean unlimited,boolean gravity,int age,String operation) {
        boolean stillMatches(GroundView other) {
            return other!=null&&other.age>=age&&new GroundView(entity,stack,world,chunkX,chunkZ,x,y,z,
                    staged,pickup,mob,invulnerable,unlimited,gravity,other.age,operation).equals(other);
        }
    }
    /** Inventory is serialized as base64, or the literal empty/none for an empty or unreferenced slot. */
    public record ItemView(String inventory,GroundView ground,Physical physical) {}
    private record Snapshot(ItemView items,CoverageProof scope) {}
    public PaperRecoveryPort(CoverageCheck coverage,Predicate<Operation> authorized,Function<String,GroundPolicy> policies,PaperGroundItems adapter){this(coverage,authorized,policies,Objects.requireNonNull(adapter)::inspectCommittedDrop);}
    /** Integration callback must track the existing item; it must not issue a replacement. */
    public PaperRecoveryPort(CoverageCheck coverage,Predicate<Operation> authorized,Function<String,GroundPolicy> policies,Consumer<Item> admission){
        this.coverage=Objects.requireNonNull(coverage);this.authorized=Objects.requireNonNull(authorized);this.policies=Objects.requireNonNull(policies);this.admission=Objects.requireNonNull(admission);
    }
    private static void owner(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Paper owner thread required");}
    private static boolean air(ItemStack item){return item==null||item.getType()==Material.AIR||item.getType()==Material.CAVE_AIR||item.getType()==Material.VOID_AIR;}
    private static String bytes(ItemStack item){return air(item)?"empty":Base64.getEncoder().encodeToString(item.serializeAsBytes());}
    private Item ground(Operation op){Ground g=op.before().ground()!=null?op.before().ground():op.intent().after().ground();if(g==null)return null;World world=Bukkit.getWorld(g.world());Entity entity=world==null?null:world.getEntity(g.entity());return entity instanceof Item item?item:null;}
    private static Snapshot incomplete(Physical physical,String reason){
        return new Snapshot(new ItemView("unavailable",null,physical),new CoverageProof(Coverage.INCOMPLETE,reason));
    }
    private Snapshot capture(Operation op,TowerSnapshot tower,RecoveryEnvelope.Context c){
        if(!authorized.test(op))return incomplete(Physical.UNKNOWN,"Owner scope unavailable");
        World world=Bukkit.getWorld(c.anchor().world());
        if(world==null||!world.isChunkLoaded(c.anchor().chunkX(),c.anchor().chunkZ()))return incomplete(Physical.UNKNOWN,"Owner scope unavailable");
        if(c.profile()==RecoveryEnvelope.Profile.STRUCTURE){
            return new Snapshot(new ItemView("unsupported",null,Physical.UNSUPPORTED),
                    new CoverageProof(Coverage.UNMATCHED,"Structure observation unsupported by the item port"));
        }
        ItemStack inventory=null;String inventoryBytes="none";
        if(c.inventory()!=null){Player player=Bukkit.getPlayer(c.inventory().actor());
            if(player==null||!player.isOnline()||c.inventory().slot()>=player.getInventory().getSize())return incomplete(Physical.UNKNOWN,"Owner scope unavailable");
            inventory=player.getInventory().getItem(c.inventory().slot());inventoryBytes=bytes(inventory);
        }
        Ground expected=op.before().ground()!=null?op.before().ground():op.intent().after().ground();
        if(expected!=null){World source=Bukkit.getWorld(expected.world());Entity entity=source==null?null:source.getEntity(expected.entity());
            if(entity!=null&&!(entity instanceof Item))return new Snapshot(new ItemView(inventoryBytes,null,Physical.MIXED),
                    new CoverageProof(Coverage.UNMATCHED,"Ground identity belongs to a different entity type"));}
        Item item=ground(op);GroundView actual=null;boolean beforeGround=false,afterGround=false,missing=item==null;
        if(expected!=null&&item!=null){
            if(!item.isValid())return incomplete(Physical.UNKNOWN,"Ground scope unavailable");
            Location at=item.getLocation();String operation=item.getPersistentDataContainer().get(PaperItemIdentity.OPERATION,PersistentDataType.STRING);
            boolean staged=Byte.valueOf((byte)1).equals(item.getPersistentDataContainer().get(STAGED,PersistentDataType.BYTE));
            boolean anchor=item.getUniqueId().equals(expected.entity())&&item.getWorld().getUID().equals(expected.world())&&(at.getBlockX()>>4)==expected.chunkX()&&(at.getBlockZ()>>4)==expected.chunkZ();
            boolean tag=expected.deliveryOperation().toString().equals(operation);
            var token=identity.read(item.getItemStack());
            beforeGround=anchor&&tag&&!staged&&token.filter(ItemLifecycle.token(op.before().tower())::equals).isPresent();
            afterGround=anchor&&tag&&token.filter(ItemLifecycle.token(op.intent().after().tower())::equals).isPresent()
                    &&(!staged||!item.canPlayerPickup()&&!item.canMobPickup()&&item.isInvulnerable()&&item.isUnlimitedLifetime()&&item.hasGravity());
            actual=new GroundView(item.getUniqueId(),bytes(item.getItemStack()),item.getWorld().getUID(),at.getBlockX()>>4,at.getBlockZ()>>4,at.getX(),at.getY(),at.getZ(),
                    staged,item.canPlayerPickup(),item.canMobPickup(),item.isInvulnerable(),item.isUnlimitedLifetime(),item.hasGravity(),item.getTicksLived(),operation);
        }
        boolean empty=air(inventory);
        boolean exact=c.inventory()!=null&&inventoryBytes.equals(Base64.getEncoder().encodeToString(c.inventory().bytes().copy()));
        if(c.kind().equals("item-drop"))exact=exact&&identity.read(inventory).filter(ItemLifecycle.token(op.before().tower())::equals).isPresent();
        Physical state=switch(c.kind()){
            case "item-drop"->exact&&missing?Physical.BEFORE:empty&&afterGround?Physical.AFTER:Physical.MIXED;
            case "item-pickup"->empty&&beforeGround?Physical.BEFORE:missing&&identity.read(inventory).filter(ItemLifecycle.token(op.intent().after().tower())::equals).isPresent()?Physical.AFTER:Physical.MIXED;
            case "item-loss"->beforeGround?Physical.BEFORE:missing&&op.phase()==Phase.EFFECTS_APPLIED?Physical.AFTER:missing?Physical.ABSENT:Physical.MIXED;
            case "fixture-item-delivery"->empty?Physical.BEFORE:exact||activatedFixture(inventory,c,op)?Physical.AFTER:Physical.MIXED;
            default->Physical.UNKNOWN;
        };
        ItemView items=new ItemView(inventoryBytes,actual,state);
        return new Snapshot(items,Objects.requireNonNull(coverage.assess(op,tower,c,items)));
    }
    private boolean activatedFixture(ItemStack item,RecoveryEnvelope.Context c,Operation op){
        if(identity.read(item).filter(ItemLifecycle.token(op.intent().after().tower())::equals).isEmpty())return false;
        ItemStack copy=item.clone();var meta=copy.getItemMeta();meta.getPersistentDataContainer().set(PaperItemIdentity.DELIVERY,PersistentDataType.STRING,c.inventory().deliveryOperation().toString());copy.setItemMeta(meta);
        return bytes(copy).equals(Base64.getEncoder().encodeToString(c.inventory().bytes().copy()));
    }
    @Override public Observation inspect(Operation op,TowerSnapshot tower,RecoveryEnvelope.Context context){owner();if(inspected)throw new IllegalStateException("One Paper observation port per attempt");inspected=true;
        Snapshot snapshot=RecoveryEnvelope.supports(op,context)?capture(op,tower,context):incomplete(Physical.UNSUPPORTED,"Recipe unsupported by this port");
        observations.put(op.intent().id(),snapshot);return Observation.of(op,tower,snapshot.scope().coverage(),snapshot.items().physical(),
                "Owner item observation; coverage="+snapshot.scope().coverage()+"; provenance="+snapshot.scope().provenance());}
    private static boolean same(Snapshot a,Snapshot b){
        if(a==null||b==null||!a.scope().equals(b.scope())||!a.items().inventory().equals(b.items().inventory())
                ||a.items().physical()!=b.items().physical())return false;
        GroundView x=a.items().ground(),y=b.items().ground();return x==null?y==null:x.stillMatches(y);
    }
    @Override public boolean revalidate(Inspection inspection){owner();return same(observations.get(inspection.operation().intent().id()),capture(inspection.operation(),inspection.tower(),inspection.context()));}
    @Override public boolean publish(Inspection inspection,TowerSnapshot resolved){
        owner();UUID id=inspection.operation().intent().id();
        if(!resolved.mutationReady()||resolved.rowVersion()!=inspection.operation().intent().expectedRowVersion()+2)return false;
        Snapshot captured=observations.get(id);
        if(captured==null||captured.scope().coverage()!=Coverage.MATCHED_COMPLETE
                ||captured.items().physical()!=inspection.observation().physical()||!revalidate(inspection))return false;
        if(inspection.observation().physical()==Physical.BEFORE){
            if(!resolved.state().equals(inspection.operation().before()))return false;
            if(resolved.state().ground()!=null){Item existing=ground(inspection.operation());if(existing==null)return false;admission.accept(existing);}
            observations.remove(id);return true;
        }
        if(!resolved.state().equals(inspection.operation().intent().after()))return false;
        Item item=ground(inspection.operation());
        if(captured.items().physical()!=Physical.AFTER)return false;
        if(captured.items().ground()!=null&&captured.items().ground().staged()){
            if(item==null||!same(captured,capture(inspection.operation(),inspection.tower(),inspection.context())))return false;
            GroundPolicy policy=Objects.requireNonNull(policies.apply(resolved.state().tower().variantId()));
            item.getPersistentDataContainer().remove(STAGED);item.setInvulnerable(false);item.setUnlimitedLifetime(!policy.despawns());item.setGravity(true);item.setCanPlayerPickup(true);item.setPickupDelay(20);
        }else if(inspection.context().kind().equals("fixture-item-delivery")){
            var source=inspection.context().inventory();Player player=Bukkit.getPlayer(source.actor());if(player==null||!player.isOnline())return false;
            ItemStack stack=player.getInventory().getItem(source.slot());
            if(stack==null)return false;
            if(stack.getPersistentDataContainer().has(PaperItemIdentity.DELIVERY)){
                if(!source.deliveryOperation().toString().equals(stack.getPersistentDataContainer().get(PaperItemIdentity.DELIVERY,PersistentDataType.STRING)))return false;
                var meta=stack.getItemMeta();meta.getPersistentDataContainer().remove(PaperItemIdentity.DELIVERY);stack.setItemMeta(meta);player.getInventory().setItem(source.slot(),stack);
            }
            if(identity.read(player.getInventory().getItem(source.slot())).filter(ItemLifecycle.token(resolved.state().tower())::equals).isEmpty())return false;
        }
        if(resolved.state().ground()!=null){if(item==null)return false;admission.accept(item);}
        observations.remove(id);return true;
    }
}
