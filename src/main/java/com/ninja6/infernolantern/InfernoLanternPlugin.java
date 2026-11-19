package com.ninja6.infernolantern;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.prototype.RenderingPrototype;
import com.ninja6.infernolantern.prototype.ItemPrototype;
import com.ninja6.infernolantern.item.GroundPolicy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Plugin entry point and opt-in feasibility fixture wiring. */
public final class InfernoLanternPlugin extends JavaPlugin {
    private RenderingPrototype prototype;
    private ItemPrototype items;
    private OverrideSessions override;
    private boolean itemCreationEnabled() { return getConfig().getBoolean("prototype.enabled") && getConfig().getBoolean("prototype.item-lifecycle.enabled"); }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        double offset = getConfig().getDouble("prototype.visual-y-offset", 0.0);
        if (!Double.isFinite(offset) || Math.abs(offset) > 3) {
            getLogger().severe("prototype.visual-y-offset must be finite and between -3 and 3.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            // Recovery runs even when creation is disabled after a previous test.
            prototype = new RenderingPrototype(this);
        } catch (IOException | RuntimeException failure) {
            getLogger().severe("Prototype cleanup manifest could not be loaded. Preserve it and inspect locally before testing.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        items = new ItemPrototype(this, this::itemCreationEnabled, new GroundPolicy(
                getConfig().getBoolean("prototype.item-lifecycle.survives-fire-lava", true),
                getConfig().getBoolean("prototype.item-lifecycle.despawns", true)));
        override = new OverrideSessions();
        override.start(getServer().getOnlinePlayers());
        getServer().getPluginManager().registerEvents(override, this);
    }

    @Override
    public void onDisable() {
        if (override != null) override.close();
        if (items != null) items.close();
        if (prototype != null) prototype.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> {
                sender.sendMessage("/" + label + " help");
                if (sender.hasPermission("infernolantern.admin.status")) sender.sendMessage("/" + label + " status");
                override.help(sender, label);
                if (sender.isOp() && getConfig().getBoolean("prototype.enabled")) sender.sendMessage("/" + label + " prototype [place|remove|cleanup] - disposable rendering fixtures");
                if (sender.isOp() && itemCreationEnabled()) sender.sendMessage("/" + label + " prototype item - one disposable item in an empty selected slot");
            }
            case "status" -> {
                if (!sender.hasPermission("infernolantern.admin.status")) sender.sendMessage("You do not have permission to inspect status.");
                else sender.sendMessage("Rendering prototype: " + (getConfig().getBoolean("prototype.enabled") ? "enabled" : "disabled")
                        + "; active fixtures=" + prototype.count() + "; cleanup cells=" + prototype.pendingCleanupCells() + prototype.combatStatus() + ". Runtime feasibility evidence pending. Item prototype: " + items.status() + ".");
            }
            case "override" -> override.command(sender, label, args);
            case "prototype" -> {
                if (!(sender instanceof Player player)) sender.sendMessage("Prototype fixtures require a player session.");
                else if (args.length >= 2 && args[1].equalsIgnoreCase("item")) {
                    if (args.length != 2) player.sendMessage("Use /" + label + " prototype item with an empty selected hotbar slot.");
                    else items.command(player);
                } else prototype.command(player, Arrays.copyOfRange(args, 1, args.length));
            }
            default -> sender.sendMessage("Unknown action. Use /" + label + " help.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            var actions = new java.util.ArrayList<>(List.of("help"));
            if (sender.hasPermission("infernolantern.admin.status")) actions.add("status");
            if (override.available(sender)) actions.add("override");
            if (sender.isOp() && getConfig().getBoolean("prototype.enabled")) actions.add("prototype");
            return actions.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("override")) return override.complete(sender, args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("prototype") && sender.isOp() && getConfig().getBoolean("prototype.enabled"))
            return (itemCreationEnabled() ? List.of("place", "remove", "cleanup", "item") : List.of("place", "remove", "cleanup")).stream().filter(action -> action.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
