package com.ninja6.infernolantern;

import com.ninja6.infernolantern.access.OverrideSessions;
import com.ninja6.infernolantern.award.CampaignAwardCommand;
import com.ninja6.infernolantern.prototype.RenderingPrototype;
import com.ninja6.infernolantern.prototype.PrototypeBucketGuard;
import com.ninja6.infernolantern.prototype.PrototypeCellGuard;
import com.ninja6.infernolantern.prototype.PrototypeLedger;
import com.ninja6.infernolantern.prototype.ItemPrototype;
import com.ninja6.infernolantern.item.GroundPolicy;
import com.ninja6.infernolantern.item.PaperItemFreeze;
import com.ninja6.infernolantern.pack.NativeJavaPackDelivery;
import com.ninja6.infernolantern.runtime.NativeCatalogRuntime;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException.CommitRevoked;
import com.ninja6.infernolantern.scheduling.PaperWorldScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns either the feasibility fixtures or the native catalog runtime for this startup. */
public final class InfernoLanternPlugin extends JavaPlugin {
    private static final String RELOAD_PERMISSION = "infernolantern.admin.reload";
    private static final String JAVA_PACK_CONFIG = "native-catalog.java-pack.";

    private RenderingPrototype prototype;
    private ItemPrototype items;
    private OverrideSessions override;
    private CampaignAwardCommand awards;
    private NativeCatalogRuntime nativeCatalog;
    private PaperItemFreeze nativeFreeze;
    private JavaPackConfig javaPackConfig;
    private String javaPackStatus = "disabled";
    private NativeJavaPackDelivery javaPacks;
    private PaperWorldScheduler nativeScheduler;
    private ExecutorService catalogIo;
    private OwnerDispatch catalogDispatch;

    private boolean itemCreationEnabled() { return getConfig().getBoolean("prototype.enabled") && getConfig().getBoolean("prototype.item-lifecycle.enabled"); }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!startRuntime(getConfig().getBoolean("native-catalog.enabled"), this::startNativeCatalog, this::startPrototypes)) return;
        override = new OverrideSessions();
        override.start(getServer().getOnlinePlayers());
        getServer().getPluginManager().registerEvents(override, this);
        awards = new CampaignAwardCommand(getServer(), override, task -> getServer().getScheduler().runTask(this, task));
    }

    static boolean startRuntime(boolean useNativeCatalog, Consumer<PaperItemFreeze> nativeStartup, BooleanSupplier prototypeStartup) {
        if (!useNativeCatalog) return prototypeStartup.getAsBoolean();
        nativeStartup.accept(new PaperItemFreeze());
        return true;
    }

    private void startNativeCatalog(PaperItemFreeze freeze) {
        nativeFreeze = freeze;
        configureJavaPack();
        Path directory = getDataFolder().toPath();
        var cleanup = installNativePreservation(directory, freeze, listener -> getServer().getPluginManager().registerEvents(listener, this));
        nativeScheduler = new PaperWorldScheduler(this);
        catalogIo = Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "infernolantern-catalog-io");
            worker.setDaemon(true);
            return worker;
        });
        OwnerDispatch dispatch = new OwnerDispatch(task -> {
            if (Bukkit.isPrimaryThread()) task.run();
            else getServer().getScheduler().runTask(this, task);
        });
        catalogDispatch = dispatch;
        nativeCatalog = new NativeCatalogRuntime(directory, cleanup, catalogIo, nativeScheduler,
                javaPackConfig != null && javaPackConfig.localOverride());
        nativeCatalog.startup().thenAccept(result -> dispatch.check(() -> {
            updateJavaPacks(result);
            if (result != NativeCatalogRuntime.Result.PUBLISHED) {
                getLogger().warning("Native catalog startup did not publish. Check the catalog and storage status.");
            }
            return true;
        }));
    }

    private void configureJavaPack() {
        javaPackConfig = null;
        javaPackStatus = "disabled";
        if (!getConfig().getBoolean(JAVA_PACK_CONFIG + "direct-java-only")) return;
        try {
            javaPackConfig = parseJavaPackConfig(getConfig().get(JAVA_PACK_CONFIG + "url"),
                    getConfig().get(JAVA_PACK_CONFIG + "mode"), getConfig().get(JAVA_PACK_CONFIG + "override-url"),
                    getConfig().get(JAVA_PACK_CONFIG + "required"), getConfig().get(JAVA_PACK_CONFIG + "local-override"),
                    getConfig().get(JAVA_PACK_CONFIG + "combined-provider"));
            javaPackStatus = "waiting for catalog publication";
        } catch (IllegalArgumentException invalid) {
            javaPackStatus = "configuration rejected";
            getLogger().warning("Java pack delivery configuration rejected. Check the mode, HTTPS URLs, pack flags, and provider.");
        }
    }

    record JavaPackConfig(URI url, URI overrideUrl, boolean required, boolean localOverride, String combinedProvider) {}

    static JavaPackConfig parseJavaPackConfig(Object url, Object mode, Object overrideUrl, Object required,
            Object localOverride, Object combinedProvider) {
        if (required != null && !(required instanceof Boolean)) {
            throw new IllegalArgumentException("Java pack required flag must be a boolean");
        }
        if (localOverride != null && !(localOverride instanceof Boolean)) {
            throw new IllegalArgumentException("Local Java override flag must be a boolean");
        }
        boolean local = Boolean.TRUE.equals(localOverride);
        if ("combined".equals(mode)) {
            if (configured(url) || configured(overrideUrl) || Boolean.TRUE.equals(required) || local
                    || !(combinedProvider instanceof String name) || !name.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("Combined Java packs require only an exact external provider name");
            }
            return new JavaPackConfig(null, null, false, false, name);
        }
        if (configured(combinedProvider)) throw new IllegalArgumentException("A combined provider requires combined mode");
        URI override;
        if (mode == null || "default".equals(mode)) {
            if (local || configured(overrideUrl)) {
                throw new IllegalArgumentException("A Java pack override requires stacked mode");
            }
            override = null;
        } else if ("stacked".equals(mode)) {
            override = parseJavaPackUrl(overrideUrl);
        } else {
            throw new IllegalArgumentException("Unsupported bundled Java pack mode");
        }
        URI base = parseJavaPackUrl(url);
        if (base.equals(override)) throw new IllegalArgumentException("Stacked pack URLs must be distinct");
        return new JavaPackConfig(base, override, Boolean.TRUE.equals(required), local, null);
    }

    private static boolean configured(Object value) {
        return value != null && !"".equals(value);
    }

    static URI parseJavaPackUrl(Object raw) {
        if (!(raw instanceof String value)) throw new IllegalArgumentException("Java pack URL must be a string");
        URI url = URI.create(value);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getFragment() != null || url.getPort() > 65_535) {
            throw new IllegalArgumentException("Invalid HTTPS pack URL");
        }
        return url;
    }

    private void updateJavaPacks(NativeCatalogRuntime.Result result) {
        if (javaPackConfig == null) return;
        if (result == NativeCatalogRuntime.Result.UNAVAILABLE) {
            javaPackStatus = "catalog unavailable";
            stopJavaPacks();
        } else if (result == NativeCatalogRuntime.Result.PUBLISHED && javaPacks == null) {
            String provider = javaPackConfig.combinedProvider();
            var pack = provider == null
                    ? nativeCatalog.javaPack(javaPackConfig.url(), javaPackConfig.overrideUrl(), javaPackConfig.required())
                    : nativeCatalog.javaCombinedPack();
            if (pack == null) {
                javaPackStatus = javaPackConfig.localOverride()
                        ? "local override unavailable or rejected; check the generated archive and restart"
                        : "verified bundled pack plan unavailable";
                return;
            }
            javaPacks = new NativeJavaPackDelivery(getServer(), pack,
                    (task, delay) -> getServer().getScheduler().runTaskLater(this, task, delay), provider);
            try {
                javaPacks.start(listener -> getServer().getPluginManager().registerEvents(listener, this));
            } catch (RuntimeException unavailable) {
                javaPackStatus = "unavailable";
                stopJavaPacks();
                getLogger().warning("Bundled Java pack delivery could not start.");
            }
        }
    }

    private void stopJavaPacks() {
        var delivery = javaPacks;
        javaPacks = null;
        if (delivery != null) {
            try {
                delivery.close();
            } catch (RuntimeException unavailable) {
                getLogger().warning("Bundled Java pack delivery closed with a client removal failure.");
            }
        }
    }

    static PrototypeLedger.CleanupInspection installNativePreservation(Path directory, PaperItemFreeze freeze,
            Consumer<Listener> register) {
        register.accept(freeze);
        var cleanup = PrototypeLedger.inspectCleanup(directory);
        if (!cleanup.cells().isEmpty()) {
            var guard = new PrototypeCellGuard(cleanup.cells()::contains);
            register.accept(guard);
            register.accept(new PrototypeBucketGuard(guard::recorded));
        }
        return cleanup;
    }

    private boolean startPrototypes() {
        double offset = getConfig().getDouble("prototype.visual-y-offset", 0.0);
        if (!Double.isFinite(offset) || Math.abs(offset) > 3) {
            getLogger().severe("prototype.visual-y-offset must be finite and between -3 and 3.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        try {
            // Recovery runs even when creation is disabled after a previous test.
            prototype = new RenderingPrototype(this);
        } catch (IOException | RuntimeException failure) {
            getLogger().severe("Prototype cleanup manifest could not be loaded. Preserve it and inspect locally before testing.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        items = new ItemPrototype(this, this::itemCreationEnabled, new GroundPolicy(
                getConfig().getBoolean("prototype.item-lifecycle.survives-fire-lava", true),
                getConfig().getBoolean("prototype.item-lifecycle.despawns", true)));
        return true;
    }

    @Override
    public void onDisable() {
        if (awards != null) awards.close();
        if (override != null) override.close();
        if (nativeCatalog != null) nativeCatalog.close();
        if (catalogDispatch != null) catalogDispatch.close();
        stopJavaPacks();
        if (nativeScheduler != null) nativeScheduler.close();
        if (catalogIo != null) catalogIo.shutdown();
        if (nativeFreeze != null) nativeFreeze.close();
        if (items != null) items.close();
        if (prototype != null) prototype.close();
        nativeCatalog = null;
        catalogDispatch = null;
        nativeScheduler = null;
        catalogIo = null;
        nativeFreeze = null;
        items = null;
        prototype = null;
    }

    private void reload(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage("You do not have permission to reload the catalog.");
            return;
        }
        if (args.length != 1) {
            sender.sendMessage("Use /" + label + " reload.");
            return;
        }
        if (nativeCatalog == null) {
            sender.sendMessage("Native catalog mode is disabled. Select it in config.yml and restart.");
            return;
        }
        CommitPermit authority = new CommitPermit();
        if (sender instanceof Player player) {
            var session = override.capture(player);
            if (session == null) {
                sender.sendMessage("The player session is unavailable.");
                return;
            }
            authority = session.connectionPermit();
        }
        CommitPermit request = authority;
        OwnerDispatch dispatch = catalogDispatch;
        nativeCatalog.reload(request, () -> dispatch.check(() -> authorizedReload(sender, request)))
                .thenAccept(result -> dispatch.check(() -> {
                    updateJavaPacks(result);
                    if (authorizedReload(sender, request)) sender.sendMessage(switch (result) {
                        case PUBLISHED -> "Catalog published. Native gameplay remains unavailable.";
                        case REJECTED -> "Catalog reload rejected. Check the file and current permissions.";
                        case UNAVAILABLE -> "Native catalog runtime is unavailable.";
                        case BUSY -> "Native catalog startup or reload is in progress.";
                    });
                    return true;
                }));
    }

    private static boolean authorizedReload(CommandSender sender, CommitPermit authority) {
        try {
            authority.requireValid();
            return sender.hasPermission(RELOAD_PERMISSION);
        } catch (CommitRevoked revoked) {
            return false;
        }
    }

    /** Catalog callbacks never depend on a loaded world or survive the owner lifetime. */
    private static final class OwnerDispatch implements AutoCloseable {
        private final Consumer<Runnable> schedule;
        private final Set<CompletableFuture<Boolean>> pending = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        private OwnerDispatch(Consumer<Runnable> schedule) { this.schedule = schedule; }

        private CompletableFuture<Boolean> check(BooleanSupplier action) {
            var result = new CompletableFuture<Boolean>();
            pending.add(result);
            result.whenComplete((allowed, failure) -> pending.remove(result));
            if (closed) {
                result.complete(false);
                return result;
            }
            try {
                schedule.accept(() -> {
                    if (result.isDone()) return;
                    try {
                        result.complete(!closed && action.getAsBoolean());
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException unavailable) {
                result.complete(false);
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
            pending.forEach(future -> future.complete(false));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> {
                sender.sendMessage("/" + label + " help");
                if (sender.hasPermission("infernolantern.admin.status")) sender.sendMessage("/" + label + " status");
                if (sender.hasPermission(RELOAD_PERMISSION)) sender.sendMessage("/" + label + " reload");
                override.help(sender, label);
                awards.help(sender, label);
                if (prototype != null && sender.isOp() && getConfig().getBoolean("prototype.enabled")) sender.sendMessage("/" + label + " prototype [place|remove|cleanup] - disposable rendering fixtures");
                if (items != null && sender.isOp() && itemCreationEnabled()) sender.sendMessage("/" + label + " prototype item - one disposable item in an empty selected slot");
            }
            case "status" -> {
                if (!sender.hasPermission("infernolantern.admin.status")) sender.sendMessage("You do not have permission to inspect status.");
                else if (nativeCatalog != null) sender.sendMessage("Native runtime: " + nativeCatalog.status()
                        + ". Java pack delivery: " + (javaPacks == null ? javaPackStatus : javaPacks.status()) + ".");
                else sender.sendMessage("Rendering prototype: " + (getConfig().getBoolean("prototype.enabled") ? "enabled" : "disabled")
                        + "; active fixtures=" + prototype.count() + "; cleanup cells=" + prototype.pendingCleanupCells() + prototype.combatStatus() + ". Runtime feasibility evidence pending. Item prototype: " + items.status() + ".");
            }
            case "reload" -> reload(sender, label, args);
            case "override" -> override.command(sender, label, args);
            case "award" -> awards.command(sender, label, args);
            case "prototype" -> {
                if (prototype == null) sender.sendMessage("Prototype fixtures are unavailable in native catalog mode.");
                else if (!(sender instanceof Player player)) sender.sendMessage("Prototype fixtures require a player session.");
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
            if (sender.hasPermission(RELOAD_PERMISSION)) actions.add("reload");
            if (override.available(sender)) actions.add("override");
            if (awards.available(sender)) actions.add("award");
            if (prototype != null && sender.isOp() && getConfig().getBoolean("prototype.enabled")) actions.add("prototype");
            return actions.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("override")) return override.complete(sender, args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("prototype") && prototype != null && sender.isOp() && getConfig().getBoolean("prototype.enabled"))
            return (itemCreationEnabled() ? List.of("place", "remove", "cleanup", "item") : List.of("place", "remove", "cleanup")).stream().filter(action -> action.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
