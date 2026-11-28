package com.ninja6.infernolantern.acceptance.recovery;

import com.ninja6.infernolantern.item.GroundPolicy;
import com.ninja6.infernolantern.item.ItemLifecycle;
import com.ninja6.infernolantern.item.PaperItemIdentity;
import com.ninja6.infernolantern.recovery.MatchedItemSaves;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort.GroundEvidence;
import com.ninja6.infernolantern.recovery.ReconciliationService;
import com.ninja6.infernolantern.recovery.RecoveryEnvelope;
import com.ninja6.infernolantern.repository.SqliteRepository;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

final class FixtureRecovery {
    private static final NamespacedKey STAGED = new NamespacedKey("infernolantern", "item_staged");
    private static final String VARIANT = "fixture.recovery";
    private static final int MAX_DATABASE_BYTES = 4 * 1024 * 1024;
    private static final PaperItemIdentity IDENTITY = new PaperItemIdentity();

    private FixtureRecovery() {}

    static ItemStack source(UUID tower) { return item(tower, 1, Form.PORTABLE); }

    private static ItemStack item(UUID id, long generation, Form form) {
        return IDENTITY.create(new ItemStack(Material.AMETHYST_SHARD),
                ItemLifecycle.token(tower(id, generation, form)), GroundPolicy.DEFAULT);
    }

    private static Tower tower(UUID id, long generation, Form form) {
        return new Tower(id, VARIANT, 100, 100, form, generation, 0, null, null, false, false, 0, 0);
    }

    static Item stage(World world, Location location, UUID tower, UUID operation) {
        return world.dropItem(location, item(tower, 2, Form.GROUND), drop -> {
            drop.setVelocity(new Vector());
            drop.setCanMobPickup(false);
            drop.setInvulnerable(true);
            drop.setPickupDelay(Short.MAX_VALUE);
            drop.setTicksLived(1200);
            drop.setUnlimitedLifetime(true);
            drop.getPersistentDataContainer().set(STAGED, PersistentDataType.BYTE, (byte) 1);
            drop.getPersistentDataContainer().set(PaperItemIdentity.OPERATION, PersistentDataType.STRING, operation.toString());
        });
    }

    static CompletableFuture<Void> seed(Path file, Player actor, int slot, UUID towerId, UUID operation,
            UUID entity, UUID session, WorldScheduler.Anchor anchor) {
        if (Files.exists(file)) {
            return CompletableFuture.failedFuture(new IOException("Fixture requires a fresh pending journal"));
        }
        String source = Base64.getEncoder().encodeToString(source(towerId).serializeAsBytes());
        var recipe = new Document(1, "action=drop;actor=" + actor.getUniqueId() + ";session=" + session
                + ";slot=" + slot + ";source=" + source);
        var ground = new Ground(entity, anchor.world(), anchor.chunkX(), anchor.chunkZ(), operation, "active");
        var after = new State(tower(towerId, 2, Form.GROUND), ground, List.of());
        var intent = new Intent(operation, towerId, 0, 1, 0, "item-drop", after,
                RecoveryEnvelope.item("item-drop", anchor, recipe));
        return SqliteRepository.open(file, 32).thenCompose(repo -> closing(repo,
                repo.createVariant(new Variant(VARIANT, 1, VariantStatus.ACTIVE, new Document(1, "Fixture"), 0))
                        .thenCompose(ignored -> repo.createTower(tower(towerId, 1, Form.PORTABLE)))
                        .thenCompose(ignored -> repo.beginNewIntent(intent)).thenAccept(ignored -> {})));
    }

    private record Inspection(CapturedItemDrop.Seal seal, Operation operation, TowerSnapshot tower,
                              CapturedItemDrop.PhysicalInputs inputs) {}

    static CompletableFuture<String> reconcile(JavaPlugin plugin, Player actor, Path fixture, UUID operation, String digest) {
        Path capture = fixture.resolve("capture");
        Path readCopy = fixture.resolve("journal-read.db");
        Path writeCopy = fixture.resolve("journal-work.db");
        var scheduler = owner(plugin);
        return CompletableFuture.supplyAsync(() -> prepare(capture, readCopy, writeCopy, digest))
                .thenCompose(seal -> SqliteRepository.open(readCopy, 32).thenCompose(repository ->
                        closing(repository, repository.operation(operation).thenCompose(found -> {
                            Operation row = found.orElseThrow();
                            return repository.tower(row.intent().towerId())
                                    .thenApply(tower -> inspect(capture, seal, row, tower.orElseThrow()));
                        }))))
                .thenCompose(inspected -> SqliteRepository.open(writeCopy, 32).thenCompose(repository ->
                        closing(repository, recover(repository, scheduler, actor, inspected))));
    }

    private static CapturedItemDrop.Seal prepare(Path capture, Path readCopy, Path writeCopy, String digest) {
        try {
            var seal = CapturedItemDrop.readSeal(capture, digest);
            byte[] database = CapturedPaperFiles.sealed(capture, Path.of("journal.db"), MAX_DATABASE_BYTES, seal.database());
            Files.write(readCopy, database, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.write(writeCopy, database, java.nio.file.StandardOpenOption.CREATE_NEW);
            return seal;
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
    }

    private static Inspection inspect(Path capture, CapturedItemDrop.Seal seal, Operation row, TowerSnapshot tower) {
        try {
            return new Inspection(seal, row, tower, CapturedItemDrop.inspect(capture, row, seal));
        } catch (IOException | DataFormatException failure) {
            throw new CompletionException(failure);
        }
    }

    private static CompletableFuture<String> recover(SqliteRepository repository, WorldScheduler scheduler,
            Player actor, Inspection inspected) {
        var anchor = inspected.inputs().context().anchor();
        Ground ground = inspected.inputs().reference();
        var oracle = new RecoveryOracle();
        var service = new ReconciliationService(repository, scheduler);
        return scheduler.supply(anchor, () -> {
            var physical = CapturedItemDrop.pair(inspected.seal(), inspected.operation(), inspected.tower(),
                    inspected.inputs(), actor.getWorld());
            if (actor.getWorld().getEntity(ground.entity()) instanceof Item item && staged(item)
                    && ground.deliveryOperation().toString().equals(item.getPersistentDataContainer()
                            .get(PaperItemIdentity.OPERATION, PersistentDataType.STRING))) {
                item.setCanMobPickup(false);
            }
            oracle.saved = physical.ground().item();
            var witness = new MatchedItemSaves(() -> Optional.of(physical));
            return new PaperRecoveryPort((operation, tower, context, view) -> {
                if (view.ground() != null && view.ground().persisted().staged()) {
                    oracle.stage = new Clock(view.ground().persisted().age(), Bukkit.getCurrentTick());
                }
                return witness.assess(operation, tower, context, view);
            }, operation -> actor.isConnected() && actor.getUniqueId().equals(physical.player().player())
                    && actor.getWorld().getUID().equals(anchor.world()),
                    variant -> GroundPolicy.DEFAULT, item -> oracle.admit(item, ground));
        }).thenCompose(port -> service.reconcile(inspected.operation().intent().id(), port))
                .thenCompose(result -> repository.operation(result.operation()).thenCompose(row -> scheduler.supply(anchor,
                        () -> oracle.result(result.outcome(), row.orElseThrow(), actor.getWorld(), ground))))
                .whenComplete((ignored, failure) -> service.close());
    }

    private static boolean staged(Item item) {
        return item.getPersistentDataContainer().has(STAGED, PersistentDataType.BYTE);
    }

    private record Clock(int age, int tick) {
        boolean continues(Item item) {
            int elapsed = Bukkit.getCurrentTick() - tick;
            int advanced = item.getTicksLived() - age;
            return advanced >= 0 && advanced <= elapsed;
        }
    }

    /** Confined to the Paper owner thread, including the publication callback. */
    private static final class RecoveryOracle {
        private GroundEvidence saved;
        private Clock stage;
        private Clock activation;

        private void admit(Item item, Ground ground) {
            // The port's last staged observation and admission occur within one owner callback.
            if (activation != null || !item.getUniqueId().equals(ground.entity()) || stage == null
                    || stage.tick() != Bukkit.getCurrentTick() || !stage.continues(item)
                    || !(item instanceof CraftItem active) || active.getHandle().getAge() != stage.age()) {
                throw new IllegalStateException("Fixture activation changed the staged age or identity");
            }
            activation = stage;
        }

        private String result(ReconciliationService.Outcome outcome, Operation row, World world, Ground ground) {
            Item drop = world.getEntity(ground.entity()) instanceof Item item ? item : null;
            if (outcome == ReconciliationService.Outcome.COMPLETED) {
                if (activation == null || !(drop instanceof CraftItem item) || staged(item) || !item.canPlayerPickup()
                        || item.isInvulnerable() || item.isUnlimitedLifetime() || !activation.continues(item)
                        || item.getHandle().getAge() != item.getTicksLived()) {
                    throw new IllegalStateException("Unexpected final fixture drop state or age");
                }
            } else if (activation != null) {
                throw new IllegalStateException("Unexpected admission without a winning recovery");
            } else if (outcome == ReconciliationService.Outcome.ADMIN_REQUIRED) {
                requireHeldStage(drop, ground);
            }
            String state = drop == null ? "ABSENT" : staged(drop) ? "STAGED" : "ACTIVE";
            return "RECOVERY-RESULT " + outcome + " " + row.phase() + " " + state;
        }

        private void requireHeldStage(Item drop, Ground ground) {
            if (drop == null || saved == null || stage == null || !stage.continues(drop) || !staged(drop)
                    || drop.canPlayerPickup() || drop.getPickupDelay() != Short.MAX_VALUE || drop.canMobPickup()
                    || !drop.isInvulnerable() || !drop.isUnlimitedLifetime() || drop.hasGravity() != saved.gravity()
                    || !ground.deliveryOperation().toString().equals(drop.getPersistentDataContainer()
                            .get(PaperItemIdentity.OPERATION, PersistentDataType.STRING))
                    || !saved.stack().equals(Base64.getEncoder().encodeToString(drop.getItemStack().serializeAsBytes()))) {
                throw new IllegalStateException("Mixed saves did not preserve the unavailable staged drop");
            }
        }
    }

    private static WorldScheduler owner(JavaPlugin plugin) {
        return new WorldScheduler() {
            @Override
            public <T> CompletableFuture<T> supply(Anchor anchor, Supplier<T> callback) {
                var result = new CompletableFuture<T>();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        result.complete(callback.get());
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                });
                return result;
            }
        };
    }

    private static <T> CompletableFuture<T> closing(SqliteRepository repository, CompletableFuture<T> work) {
        return work.handle((result, failure) -> repository.closeAsync().thenApply(ignored -> {
            if (failure != null) throw new CompletionException(failure);
            return result;
        })).thenCompose(closed -> closed);
    }
}
