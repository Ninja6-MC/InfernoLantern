package com.ninja6.infernolantern.access;

import com.ninja6.infernolantern.catalog.CatalogConfig;
import com.ninja6.infernolantern.catalog.CatalogService;
import com.ninja6.infernolantern.catalog.PaperWorldRef;
import com.ninja6.infernolantern.repository.CommitPermit;
import com.ninja6.infernolantern.repository.RepositoryException;
import com.ninja6.infernolantern.repository.RepositoryRecords.Form;
import com.ninja6.infernolantern.repository.RepositoryRecords.Position;
import com.ninja6.infernolantern.repository.RepositoryRecords.State;
import com.ninja6.infernolantern.repository.RepositoryRecords.Tower;
import com.ninja6.infernolantern.repository.RepositoryRecords.TowerSnapshot;
import com.ninja6.infernolantern.repository.RepositoryRecords.VariantStatus;
import com.ninja6.infernolantern.structure.PaperStructureEffects;
import com.ninja6.infernolantern.structure.StructureLifecycle;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Binds current native access to the owner callbacks of a single catalog and repository owner. */
public final class PaperNativeAccess {
    public enum Action { PLACE, EXPAND, COMPACT, BREAK, ADMIN_RECOVER, LIGHT, DEFENCE, REPAIR }
    public enum Decision {
        DENIED, OVERRIDE_REQUIRED, ALLOWED, COOLDOWN, CONTROLLER, EXPANDED_RECOVERY;

        public boolean allowed() { return this != DENIED && this != OVERRIDE_REQUIRED; }
        public boolean bypassed() { return allowed() && this != ALLOWED; }
    }

    /** Must prove reach, fluid, claims/protection and any independent physical/payment constraints. */
    @FunctionalInterface
    public interface PhysicalAccess {
        boolean allowed(Player actor, Action action, State before, Position target, boolean restoring);
    }

    private final OverrideSessions sessions;
    private final CatalogService catalog;
    private final Logger audit;

    public PaperNativeAccess(OverrideSessions sessions, CatalogService catalog, Logger audit) {
        this.sessions = Objects.requireNonNull(sessions);
        this.catalog = Objects.requireNonNull(catalog);
        this.audit = Objects.requireNonNull(audit);
    }

    private Decision eligibility(OverrideSessions.Session actor, CatalogService.Snapshot settings,
                                 Action action, State state, Position target, long now) {
        if (!sessions.current(actor) || !catalog.isPublished(settings) || now < 0) return Decision.DENIED;
        Player player = actor.player();
        Tower tower = state.tower();
        if (!player.getWorld().getUID().equals(target.world())
                || (action != Action.PLACE && !target.equals(tower.position()))
                || !player.hasPermission("infernolantern.use")) return Decision.DENIED;

        CatalogService.Entry entry = settings.entries().get(tower.variantId());
        if (entry == null) return Decision.DENIED;
        CatalogConfig.Definition definition = entry.definition();
        if (definition.permission() != null && !player.hasPermission(definition.permission())) return Decision.DENIED;
        if (entry.status() != VariantStatus.ACTIVE && action != Action.COMPACT
                && action != Action.BREAK && action != Action.ADMIN_RECOVER) return Decision.DENIED;

        CatalogConfig.Action filter = switch (action) {
            case PLACE -> CatalogConfig.Action.PLACE;
            case EXPAND -> CatalogConfig.Action.EXPAND;
            case LIGHT -> CatalogConfig.Action.LIGHT;
            case DEFENCE -> CatalogConfig.Action.DEFENCE;
            default -> null;
        };
        if (filter != null && !definition.worlds().get(filter).allows(PaperWorldRef.capture(player.getWorld()))) {
            return Decision.DENIED;
        }

        Decision required = switch (action) {
            case PLACE -> tower.form() == Form.PORTABLE ? Decision.ALLOWED : Decision.DENIED;
            case EXPAND -> {
                if (tower.form() != Form.COMPACT || tower.hp() < tower.maximumHp() * definition.expansionPercent() / 100) {
                    yield Decision.DENIED;
                }
                yield now >= tower.expandAfter() ? Decision.ALLOWED : Decision.COOLDOWN;
            }
            case COMPACT -> controller(actor, tower);
            case BREAK -> tower.form() == Form.COMPACT ? Decision.ALLOWED : Decision.DENIED;
            case ADMIN_RECOVER -> tower.form() == Form.EXPANDED ? Decision.EXPANDED_RECOVERY : Decision.DENIED;
            case LIGHT -> definition.lightAvailable() && tower.hp() > 0 ? controller(actor, tower) : Decision.DENIED;
            case DEFENCE -> definition.defenceAvailable() && tower.hp() > 0 ? controller(actor, tower) : Decision.DENIED;
            case REPAIR -> {
                if (now < tower.repairAfter()) yield Decision.DENIED;
                yield tower.form() == Form.COMPACT ? Decision.ALLOWED : controller(actor, tower);
            }
        };
        return required.bypassed() && !sessions.enabled(actor) ? Decision.OVERRIDE_REQUIRED : required;
    }

    private static Decision controller(OverrideSessions.Session actor, Tower tower) {
        if (tower.form() != Form.EXPANDED) return Decision.DENIED;
        return actor.actor().equals(tower.controller()) ? Decision.ALLOWED : Decision.CONTROLLER;
    }

    private Decision decide(OverrideSessions.Session actor, CatalogService.Snapshot settings, Action action,
                            State before, Position target, long now, PhysicalAccess physical) {
        Decision initial = eligibility(actor, settings, action, before, target, now);
        if (initial == Decision.DENIED) return initial;
        if (initial == Decision.OVERRIDE_REQUIRED && !sessions.available(actor.player())) return initial;
        if (!physical.allowed(actor.player(), action, before, target, false)) return Decision.DENIED;
        // Protection callbacks may synchronously call other listeners that change authorization.
        return eligibility(actor, settings, action, before, target, now);
    }

    /** Creates a request whose exact access inputs are checked again by the real Paper effects adapter. */
    public Optional<StructureAccess> structure(OverrideSessions.Session actor, CatalogService.Snapshot settings,
                                               UUID operation, TowerSnapshot before, StructureLifecycle.Action action,
                                               Position target, long now, PhysicalAccess physical) {
        sessions.requireOwner();
        if (!before.mutationReady()) return Optional.empty();
        Action nativeAction = Action.valueOf(action.name());
        Decision decision = decide(actor, settings, nativeAction, before.state(), target, now, physical);
        if (!decision.allowed()) {
            hint(actor, decision);
            return Optional.empty();
        }

        Tower tower = before.state().tower();
        CatalogConfig.Definition definition = settings.entries().get(tower.variantId()).definition();
        var policy = new StructureLifecycle.Policy(settings.revision(), definition.expansionPercent(),
                definition.expansionCooldownMillis(), now, decision.bypassed());
        var request = new StructureLifecycle.Request(operation, tower.id(), before.rowVersion(), tower.generation(),
                tower.deploymentRevision(), actor.actor(), actor.id(), action, target, policy);
        return Optional.of(new StructureAccess(actor, settings, request, before.state(), nativeAction, decision, physical));
    }

    public final class StructureAccess implements PaperStructureEffects.Access {
        private final OverrideSessions.Session actor;
        private final CatalogService.Snapshot settings;
        private final StructureLifecycle.Request request;
        private final State state;
        private final Action action;
        private final Decision decision;
        private final PhysicalAccess physical;

        private StructureAccess(OverrideSessions.Session actor, CatalogService.Snapshot settings,
                                StructureLifecycle.Request request, State state, Action action,
                                Decision decision, PhysicalAccess physical) {
            this.actor = actor;
            this.settings = settings;
            this.request = request;
            this.state = state;
            this.action = action;
            this.decision = decision;
            this.physical = physical;
        }

        public StructureLifecycle.Request request() { return request; }
        public UUID currentSession() { return sessions.currentSession(actor.player()); }
        @Override public CommitPermit commitPermit() {
            sessions.requireOwner();
            return actor.commitPermit(decision.bypassed());
        }

        @Override public boolean allowed(StructureLifecycle.Request candidate, State before) {
            sessions.requireOwner();
            return request.equals(candidate) && state.equals(before)
                    && decide(actor, settings, action, before, request.base(), request.policy().now(), physical) == decision;
        }

        @Override public boolean mayRestore(StructureLifecycle.Request candidate, State before) {
            sessions.requireOwner();
            return request.equals(candidate) && state.equals(before) && sessions.current(actor)
                    && actor.player().getWorld().getUID().equals(request.base().world())
                    && physical.allowed(actor.player(), action, before, request.base(), true) && sessions.current(actor)
                    && actor.player().getWorld().getUID().equals(request.base().world());
        }
    }

    /** Re-evaluates a revision-bound menu action before preference CAS and again on the owner. */
    public CompletableFuture<TowerSnapshot> switchPreference(OverrideSessions.Session actor,
            CatalogService.Snapshot settings, TowerSnapshot before, CatalogConfig.Action action,
            boolean value, PhysicalAccess physical) {
        sessions.requireOwner();
        if (action != CatalogConfig.Action.LIGHT && action != CatalogConfig.Action.DEFENCE) {
            throw new IllegalArgumentException("Player switch action required");
        }
        Tower tower = before.state().tower();
        Action nativeAction = Action.valueOf(action.name());
        Position target = tower.position();
        Decision decision = before.mutationReady() && target != null
                ? decide(actor, settings, nativeAction, before.state(), target, 0, physical) : Decision.DENIED;
        if (!decision.allowed()) {
            hint(actor, decision);
            return CompletableFuture.failedFuture(new RepositoryException.Conflict("Switch access denied"));
        }
        return catalog.switchPreference(tower.id(), before.rowVersion(), tower.deploymentRevision(), settings, action, value,
                new CatalogService.SwitchAccess() {
                    @Override public CatalogService.SwitchObservation inspect(TowerSnapshot row,
                            CatalogConfig.Action selected, boolean selectedValue) {
                        sessions.requireOwner();
                        if (!row.mutationReady() || row.state().tower().deploymentRevision() != tower.deploymentRevision()
                                || decide(actor, settings, nativeAction, row.state(), target, 0, physical) != decision) return null;
                        return new CatalogService.SwitchObservation(PaperWorldRef.capture(actor.player().getWorld()), true);
                    }

                    @Override public void committed(TowerSnapshot row) {
                        if (decision.bypassed()) {
                            audit.info("Lantern override applied: actor=" + actor.actor() + " tower=" + tower.id()
                                    + " action=" + nativeAction + " row=" + row.rowVersion());
                        }
                    }
                }, actor.commitPermit(decision.bypassed()));
    }

    /** Repair routing supplies current payment, protection and physical checks separately. */
    public Decision repair(OverrideSessions.Session actor, CatalogService.Snapshot settings, TowerSnapshot row,
                           long deploymentRevision, long now, PhysicalAccess physical) {
        sessions.requireOwner();
        Tower tower = row.state().tower();
        if (!row.mutationReady() || tower.deploymentRevision() != deploymentRevision || tower.position() == null) {
            return Decision.DENIED;
        }
        Decision decision = decide(actor, settings, Action.REPAIR, row.state(), tower.position(), now, physical);
        hint(actor, decision);
        return decision;
    }

    private void hint(OverrideSessions.Session actor, Decision decision) {
        if (decision != Decision.OVERRIDE_REQUIRED || !sessions.available(actor.player())) return;
        if (!sessions.enabled(sessions.capture(actor.player()))) {
            actor.player().sendMessage("Use /infernolantern override on to permit this action for your current session.");
        }
    }
}
