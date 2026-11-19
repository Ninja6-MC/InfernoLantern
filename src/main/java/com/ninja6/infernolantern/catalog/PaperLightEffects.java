package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.RepositoryRecords.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Light;
import java.util.*;
import java.util.function.*;
import static com.ninja6.infernolantern.catalog.LightReconciler.*;

/** Exact owner-thread LIGHT block effects. Caller supplies fresh policy/world observations. */
public final class PaperLightEffects implements Effects {
    private final World world;private final Function<String,BlockData> parser;private final BooleanSupplier ownerThread;private final Function<TowerSnapshot,View> view;
    private Block block;private Component original;private BlockData before,target;private boolean applied;
    public PaperLightEffects(World world,Function<TowerSnapshot,View> view){this(world,Bukkit::createBlockData,Bukkit::isPrimaryThread,view);}
    public PaperLightEffects(World world,Function<String,BlockData> parser,BooleanSupplier ownerThread,Function<TowerSnapshot,View> view){this.world=Objects.requireNonNull(world);this.parser=Objects.requireNonNull(parser);this.ownerThread=Objects.requireNonNull(ownerThread);this.view=Objects.requireNonNull(view);}
    @Override public View view(TowerSnapshot row){owner();return view.apply(row);}
    @Override public Prepared prepare(TowerSnapshot row,Component cell,int level){
        owner();if(block!=null)throw new IllegalStateException("Light effects are one-shot");
        if(!row.mutationReady()||cell.kind()!=ComponentKind.BLOCK||!world.getUID().equals(cell.world())||cell.y()<world.getMinHeight()||cell.y()>=world.getMaxHeight()||!world.isChunkLoaded(cell.x()>>4,cell.z()>>4)||!world.getWorldBorder().isInside(new Location(world,cell.x()+0.5,cell.y()+0.5,cell.z()+0.5)))return null;
        BlockData expected=parser.apply(cell.expectedState()),terrain=parser.apply(cell.originalState());Block candidate=world.getBlockAt(cell.x(),cell.y(),cell.z());
        if(!same(candidate.getBlockData(),expected)||!supported(expected)||!supportedTerrain(terrain))return null;
        BlockData desired;
        if(level==0)desired=terrain.clone();else{Light light=(Light)parser.apply("minecraft:light[level="+level+"]");light.setLevel(level);light.setWaterlogged(terrain.getMaterial()==Material.WATER);desired=light;}
        original=cell;block=candidate;before=expected.clone();target=desired.clone();
        return new Prepared(new Component(cell.id(),cell.kind(),cell.world(),cell.x(),cell.y(),cell.z(),cell.originalState(),desired.getAsString(),null,null),level,new Document(1,"Owner captured exact canonical light voxel and terrain; recovery requires administrative evidence"));
    }
    @Override public boolean apply(Claim claim){owner();if(!bound(claim)||!view.apply(claim.before()).writeAuthorized()||!same(block.getBlockData(),before))return false;applied=true;block.setBlockData(target.clone(),false);return same(block.getBlockData(),target);}
    @Override public boolean verify(Claim claim){owner();return bound(claim)&&applied&&same(block.getBlockData(),target);}
    @Override public boolean rollback(Claim claim){owner();if(!bound(claim))return false;BlockData actual=block.getBlockData();if(!applied||same(actual,before))return same(actual,before);if(!same(actual,target)||!view.apply(claim.before()).writeAuthorized())return false;block.setBlockData(before.clone(),false);return same(block.getBlockData(),before);}
    private boolean bound(Claim claim){return block!=null&&world.isChunkLoaded(original.x()>>4,original.z()>>4)&&claim.before().state().components().contains(original)&&claim.prepared().after().expectedState().equals(target.getAsString())&&claim.request().tower().equals(claim.before().state().tower().id())&&claim.request().rowVersion()==claim.before().rowVersion()&&claim.request().deploymentRevision()==claim.before().state().tower().deploymentRevision();}
    private static boolean same(BlockData a,BlockData b){return a.getAsString().equals(b.getAsString());}
    private static boolean supported(BlockData data){return supportedTerrain(data)||data instanceof Light;}
    private static boolean supportedTerrain(BlockData data){Material material=data.getMaterial();return material==Material.AIR||material==Material.CAVE_AIR||material==Material.VOID_AIR||(material==Material.WATER&&data instanceof Levelled level&&level.getLevel()==0);}
    private void owner(){if(!ownerThread.getAsBoolean())throw new IllegalStateException("Light world owner required");}
}
