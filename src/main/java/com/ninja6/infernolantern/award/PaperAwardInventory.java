package com.ninja6.infernolantern.award;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.item.GroundPolicy;
import com.ninja6.infernolantern.item.ItemToken;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.repository.CampaignDelivery;
import com.ninja6.infernolantern.repository.CommitPermit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

public final class PaperAwardInventory implements CampaignAwards.Inventory {
    /** Validated portable appearance and the variant's independent ground lifetime policy. */
    public record Appearance(ItemStack item,GroundPolicy ground) {
        public Appearance {Objects.requireNonNull(item);Objects.requireNonNull(ground);}
    }

    private final Server server;
    private final OverrideSessions sessions;
    private final PaperItemIdentity identity;
    private final Function<String,Appearance> appearances;
    private final BiPredicate<Player,String> ready;

    public PaperAwardInventory(Server server,OverrideSessions sessions,PaperItemIdentity identity,
                               Function<String,Appearance> appearances,BiPredicate<Player,String> ready) {
        this.server=Objects.requireNonNull(server);
        this.sessions=Objects.requireNonNull(sessions);
        this.identity=Objects.requireNonNull(identity);
        this.appearances=Objects.requireNonNull(appearances);
        this.ready=Objects.requireNonNull(ready);
    }

    @Override public CampaignAwards.Prepared prepare(CampaignProgress progress,UUID operation) {
        requireOwner();
        UUID recipient=progress.award().recipient();
        String variant=progress.award().variantId();
        Player player=server.getPlayer(recipient);
        if(player==null||!ready.test(player,variant))return null;
        var session=sessions.capture(player);
        if(session==null||!recipient.equals(session.actor()))return null;
        int slot=player.getInventory().firstEmpty();
        if(slot<0||slot>=36||!empty(player.getInventory().getItem(slot)))return null;
        Appearance appearance=appearances.apply(variant);
        if(appearance==null||identity.marked(appearance.item()))return null;
        ItemToken token=new ItemToken(ItemToken.SCHEMA,variant,progress.plannedTower(),1);
        ItemStack active=identity.create(appearance.item(),token,appearance.ground());
        if(!identity.read(active).filter(token::equals).isPresent())return null;
        ItemStack staged=active.clone();
        var meta=staged.getItemMeta();
        meta.getPersistentDataContainer().set(PaperItemIdentity.DELIVERY,PersistentDataType.STRING,operation.toString());
        staged.setItemMeta(meta);
        CampaignDelivery proof=new CampaignDelivery(progress.award().id(),token.tower(),operation,recipient,session.id(),slot,
                new ResourceBytes(staged.serializeAsBytes()),new ResourceBytes(active.serializeAsBytes()));
        return new Delivery(player,session,token,proof,staged,active);
    }

    private final class Delivery implements CampaignAwards.Prepared {
        private final Player player;
        private final OverrideSessions.Session session;
        private final ItemToken token;
        private final CampaignDelivery proof;
        private final ItemStack staged,active;

        Delivery(Player player,OverrideSessions.Session session,ItemToken token,CampaignDelivery proof,ItemStack staged,ItemStack active) {
            this.player=player;
            this.session=session;
            this.token=token;
            this.proof=proof;
            this.staged=staged;
            this.active=active;
        }

        @Override public CampaignDelivery proof(){return proof;}
        @Override public CommitPermit permit(){return session.connectionPermit();}

        private boolean current(){
            requireOwner();
            return session.id().equals(sessions.currentSession(player))&&ready.test(player,token.variant())
                    &&session.id().equals(sessions.currentSession(player));
        }
        @Override public CampaignAwards.Stage stage(BooleanSupplier admission){
            if(!current()||!empty(player.getInventory().getItem(proof.slot()))||!admission.getAsBoolean())
                return CampaignAwards.Stage.NO_WRITE;
            player.getInventory().setItem(proof.slot(),staged.clone());
            return exact(proof.staged())?CampaignAwards.Stage.VERIFIED:CampaignAwards.Stage.UNCERTAIN;
        }
        @Override public boolean verify(){return current()&&exact(proof.staged());}
        @Override public boolean activate(BooleanSupplier admission){
            if(!verify()||!admission.getAsBoolean())return false;
            player.getInventory().setItem(proof.slot(),active.clone());
            return exact(proof.active())&&identity.read(player.getInventory().getItem(proof.slot())).filter(token::equals).isPresent();
        }
        private boolean exact(ResourceBytes bytes){
            var item=player.getInventory().getItem(proof.slot());
            return !empty(item)&&Arrays.equals(item.serializeAsBytes(),bytes.copy());
        }
    }

    private void requireOwner(){if(!server.isPrimaryThread())throw new IllegalStateException("Paper owning thread required");}

    private static boolean empty(ItemStack item){
        if(item==null)return true;
        Material type=item.getType();
        return type==Material.AIR||type==Material.CAVE_AIR||type==Material.VOID_AIR;
    }
}
