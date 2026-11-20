package com.ninja6.infernolantern.award;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.repository.CommitPermit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Approved command surface. A validated runtime owner must explicitly supply the award service. */
public final class CampaignAwardCommand {
    public static final String PERMISSION="infernolantern.admin.award";
    private static final String USAGE=" award <campaign> <player>";

    private final Server server;
    private final OverrideSessions sessions;
    private final Executor owner;
    private CampaignAwards awards;

    public CampaignAwardCommand(Server server,OverrideSessions sessions,Executor owner) {
        this.server=Objects.requireNonNull(server);
        this.sessions=Objects.requireNonNull(sessions);
        this.owner=Objects.requireNonNull(owner);
    }

    /** Bind only after the shared catalog, item lifecycle, and session readiness are in service. */
    public void bind(CampaignAwards service){
        requireOwner();
        if(awards!=null)throw new IllegalStateException("Campaign awards are already bound");
        awards=Objects.requireNonNull(service);
    }

    public void close(){
        requireOwner();
        if(awards!=null)awards.close();
        awards=null;
    }

    public boolean available(CommandSender sender){return sender.hasPermission(PERMISSION);}
    public void help(CommandSender sender,String label){
        if(available(sender))sender.sendMessage("/"+label+USAGE);
    }

    public void command(CommandSender sender,String label,String[] args){
        requireOwner();
        if(!available(sender)){sender.sendMessage("You do not have permission to issue awards.");return;}
        if(args.length!=3){sender.sendMessage("Use /"+label+USAGE+".");return;}
        CampaignAwards service=awards;
        if(service==null){sender.sendMessage("Campaign awards are unavailable until the shared runtime is ready.");return;}
        Player actor=sender instanceof Player player?player:null;
        var session=actor==null?null:sessions.capture(actor);
        if(actor!=null&&session==null){sender.sendMessage("A current player session is required.");return;}
        UUID recipient=recipient(args[2]);
        if(recipient==null){sender.sendMessage("No exact known player matched that name.");return;}
        CommitPermit authority=session==null?new CommitPermit():session.connectionPermit();
        service.award(args[1],recipient,authority).whenCompleteAsync((result,failure)->{
            if(awards!=service||!available(sender)||actor!=null&&!session.id().equals(sessions.currentSession(actor)))return;
            if(failure!=null){sender.sendMessage("The award result is unavailable. Check the stored receipt before retrying.");return;}
            sender.sendMessage(switch(result.stop()){
                case DELIVERED -> "Campaign award delivered.";
                case PENDING -> "Campaign award pending. Retry the same campaign when the player has room.";
                case PAUSED -> "Campaign award held while the variant is unavailable.";
                case AWAITING_SAVE -> "One campaign item awaits confirmed player-save evidence.";
                case REVIEW -> "Campaign delivery requires inspection. Preserve the journal and inventory evidence.";
                case UNAVAILABLE -> "The campaign or award service is unavailable.";
            });
        },owner);
    }

    private UUID recipient(String name){
        Player online=server.getPlayerExact(name);
        if(online!=null&&name.equalsIgnoreCase(online.getName()))return online.getUniqueId();
        OfflinePlayer cached=server.getOfflinePlayerIfCached(name);
        return cached!=null&&name.equalsIgnoreCase(cached.getName())?cached.getUniqueId():null;
    }

    private void requireOwner(){if(!server.isPrimaryThread())throw new IllegalStateException("Paper owning thread required");}
}
