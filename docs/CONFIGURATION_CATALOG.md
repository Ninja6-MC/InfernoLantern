# Configuration, Capabilities and Retirement Foundation

Task #13 is **partial**. This change supplies validated immutable catalog services, durable publication/retirement, independent switch-preference persistence and a journaled real-LIGHT adapter. None is registered by the plugin entry point. There is no new command, permission, production variant, native control, automatic startup catalog/recovery or deployment approval. Existing prototype interfaces and their isolated fixture store retain their meaning. Native access/routing, asset-readiness, service lifecycle and actual compatibility evidence remain integration requirements; #9/#10/#11/G1 remain open.

The maintainer selected two policies for this implementation. Compact placed bases are inert: they emit no light and do not attack. Only EXPANDED, positive-HP, ready representations can operate, subject to every current policy. Saved light/defence preferences survive compaction, transfer and restart; inertness never resets those choices. The approved world-filter syntax and precedence are specified below. These are policy decisions, not claims that native gameplay has been enabled.

## Validated configuration boundary

`CatalogConfig.parse(Map)` accepts a primitive immutable snapshot boundary; `CatalogService.reloadYaml(String)` uses the strict `CatalogYaml` reader. YAML uses the platform-provided SnakeYAML safe constructor, rejects duplicate keys/object tags/collection aliases, bounds depth/input size and preserves explicit null for validation. Do not substitute Bukkit `ConfigurationSection.getValues` without equivalent conversion: dropping null can incorrectly turn an invalid allowlist into an absent unrestricted filter. File reading and reload-command dispatch are not implemented here.

The supported schema validates every effective field before any database publication. Unknown keys, wrong types, non-finite or invalid numbers, invalid selectors/models, malformed geometry, reserved IDs and missing required values reject the whole candidate. Server capability availability defaults to both lighting and defence. Per-variant availability overrides inherit those defaults. Player initial choices are explicit configuration and are separate from feature availability. Expansion threshold defaults to the already selected 50%; light level, maximum HP, expansion cooldown, player choices and geometry are explicit required values. No new combat, repair, or balance defaults are selected. A variant can supply explicit repair rates and cooling under the [repair contract](RESOURCE_REPAIR.md).

This synthetic example describes the API schema, not a selected production variant, a working pack or a deployment recommendation. Its resource identifiers require a caller-supplied `DefinitionValidator` that proves actual model/asset availability. Syntax validation alone cannot prove a resource pack or bridge mapping is ready.

```yaml
schema: 1
capabilities:
  light: true
  defence: true
variants:
  example:
    capabilities:
      defence: false
    player-switches:
      light: true
      defence: false
    light-level: 15
    maximum-hp: 100
    expansion-hp-percent: 50
    expansion-cooldown-ms: 60000
    geometry:
      compact-model: "example:compact"
      expanded-model: "example:expanded"
      width: 1
      height: 3
      depth: 1
      light-x: 0
      light-y: 2
      light-z: 0
    world-rules:
      light:
        allow-dimensions: [overworld, end]
      defence:
        allow-worlds: []
```

The geometry descriptor declares a positive-axis rectangular expanded clearance with a light voxel inside it; the native geometry factory must produce the matching complete oriented #10 layout and non-emitting compact base. Model identifiers are independent compact/expanded resources, not hardcoded branding. The existing 4096-component snapshot bound yields at most 4095 declared voxels plus a visual; it is a technical representation bound, not a gameplay tower-count limit. Actual block/model/fluids, source eligibility, reach, pack readiness and protection are mandatory validated caller inputs. Geometry cannot be used as a claimed proof of physical save state.

## Approved worlds and dimensions

Each action `place`, `expand`, `light`, `defence` may define `allow-worlds`, `deny-worlds`, `allow-dimensions`. World selectors are `uuid:<canonical-uuid>` and `name:<exact-name>`. Dimension categories are `overworld`, `nether`, `end`, `custom`. Omitted allowlists are unrestricted; explicit empty allowlists allow none. Denylist matches always reject; all supplied allowlists must match. No world restrictions apply by default.

Names are current policy selectors only. Stored tower positions retain actual world UUIDs; a rename may change a name rule's result but never moves/rebinds a tower. A replacement world sharing a name can satisfy that name selector for new activity, but cannot adopt an old UUID-bound record. An unloaded/unknown world is not a new identity or proof of loss. The caller captures current `WorldRef` on its owner, never permanently resolves a name into an unrelated record.

`PaperWorldRef` maps the pinned platform's NORMAL/NETHER/THE_END environment categories. CUSTOM is represented but reports unsupported until an actual native support gate establishes reliable behavior. A `custom` allowlist cannot match an unsupported custom observation. No internal dimension IDs or name heuristics are used. An independent platform integration may provide a reliably supported custom category later, with evidence. The rolling [Paper environment API](https://jd.papermc.io/paper/26.3/org/bukkit/World.Environment.html) is reference only; compilation uses the repository's pinned 26.3.build.249-stable API.

## Durable publication and ownership

**Schema 4 migrates on every repository open**, including an existing item harness opening a store with creation disabled. It adds catalog revision/managed-ID metadata and catalog-owned award holds; versions 1–3 retain their checksums. Migration does not import, adopt, retire or alter existing legacy/prototype variants. Older candidates reject the future schema; never lower a schema version, delete data or deploy this candidate into preserved quarantined evidence to resume testing. A future approved deployment needs matched complete stopped-state backups, not database-only swaps.

`CatalogService` takes an already owned `SqliteRepository`; it creates no second item authority and opens no new file. `load()` only inspects durable data and does not publish active runtime policy. Startup must publish the current fully validated configuration through `reload`/`reloadYaml` before enabling native activity. An empty current variant section retires missing managed definitions. An invalid first configuration leaves the service unavailable, without stopping the server; an invalid reload or failed database publication preserves the prior valid runtime snapshot.

Publication atomically checks the catalog revision and complete managed-row snapshot, writes last-good definitions/statuses, holds/reactivates eligible pending awards and advances the durable revision. Only IDs explicitly created by the catalog are managed. `prototype.*` is reserved; a candidate colliding with any existing unmanaged ID rejects rather than adopting it. Missing/renamed managed IDs retire, retain last-good definitions and preserve every tower/controller/HP/timer/manifest. Restoring the same ID reactivates it after validation. There is no permanent deletion or compensation path.

Catalog-owned holds move PENDING awards to PAUSED without delivery/allocation/new identities. Explicit/manual PAUSED awards are not catalog-owned and do not silently resume. New pending awards for a retired managed variant are held; retired variants cannot issue new towers or allocate award identities. Reactivation resumes only the catalog's own holds. Source/recipient/quantity/IDs remain unchanged. Unknown legacy/prototype IDs are untouched by publication.

Geometry changes are rejected while the variant has placed COMPACT/EXPANDED instances or any INTENT/EFFECTS_APPLIED/RECONCILING operation can resolve to a placed before/after manifest. Maximum-HP changes migrate non-LOST rows atomically with catalog publication, preserving absolute HP up to a reduced cap and advancing affected row versions. Pending journal ownership fences the change. Terminal LOST history keeps its old values. See [Tower Health](TOWER_HEALTH.md). Other definition changes preserve old manifests and saved player choices. Definition revisions and catalog publication revisions are immutable, guarded and distinct from tower deployment revisions. Managed IDs reject the legacy `compareAndSetVariant` path; only catalog publication may update their definition/status. Unmanaged legacy APIs retain their behavior. Managed manifest-changing intent admission must carry the current catalog revision. `StructureLifecycle.Policy.revision` is that catalog revision for managed variants; stale geometry requests cannot enter just after a newer publication wins. Unmanaged definitions keep their independent policy revision contract.

After durable publication, `Reconciler` must schedule loaded world effects/service stop/restart on owning threads and keep unloaded instances suspended under the new effective policy. It is called from an asynchronous completion, not a world-thread grant. The service publishes the new policy immediately but remains `ready() == false` until the reconciliation future succeeds. `Reload(published=true,effectsReconciled=false)` means the valid new policy committed but effects need reconciliation; it must not be falsely reported as a failed validation preserving the old policy. Native mutations must check readiness and `isCurrent(snapshot)` as well as current owner authorization. A cached snapshot or static effective calculation grants no native authority; multiple independent runtime catalog owners are unsupported, and cross-instance database revision guards prevent stale commits.

## Player choices and effective capability

`switchPreference` writes one selected LIGHT/DEFENCE preference through catalog/tower/deployment CAS. Its owner-thread `SwitchAccess` must prove current actor/controller/session/native eligibility/protection/reach and current world observation; permission alone is not an enabled session override. Unavailable capabilities cannot be switched on. Compact/pending/retired representations cannot use the deployed switch API. Reload does not change saved preferences, and the static effective result intersects availability, retirement, stored UUID/current world rules, pending state, EXPANDED/positive-HP lifecycle and the caller's current access/fluid/component-integrity inputs. Override cannot re-enable a disabled capability.

A preference commit is separate from physical light or defence. A later owner failure is reported as requiring effect reconciliation and does not guess a preference rollback or clear another saved choice. Callers must re-read the authoritative row and schedule `LightReconciler`/the later combat service. Shutdown or a failed callback never grants permission to keep unsafe services running. Defence targeting/damage is #19; this module supplies its effective availability and a publication reconciliation hook, not simulated combat.

## Real LIGHT effects and safe cleanup

`LightReconciler` journals a `catalog-light` intent against exact tower/deployment/catalog versions. It updates only the declared reserved light voxel in an EXPANDED manifest, preserving generation, controller, HP, timers and both saved switches. Levels 0–15 are supported; zero/off/retirement/disallowed world restores the recorded terrain. The Paper port supports air and source-water terrain only; flowing water, solids and unsupported waterlogged combinations reject. Compact cleanup remains the #10 compaction path; compact operation is always effectively off and native layout integration must never introduce an emitting compact base.

`PaperLightEffects` canonicalizes both expected/original BlockData through the pinned API before comparison, rather than comparing shorthand to Paper's fully expanded property string. A source-water light is waterlogged and restores the original source water. It never infers illumination from texture appearance. The [Paper LIGHT API](https://jd.papermc.io/paper/26.3/org/bukkit/block/data/type/Light.html) exposes level and waterlogged state; actual lighting/fluid propagation still requires native tests.

The owner captures exact terrain and current eligibility, including the current published catalog revision and a separate `writeAuthorized` capability. Operational eligibility requiring light to turn off is not authority to edit a protected voxel. Initial/apply writes and mutating compensation recheck current write authorization; revoked protection preserves the voxel and quarantine rather than forcing cleanup. Intent admission and completion also check the catalog in the SQLite transaction. Effects verify and compensate only their exact writes; an external edit is preserved and uncertainty freezes RECONCILING with reservations retained. A typed catalog-supersession rejection at completion proves that transaction made no writes; it is distinct from unknown SQL outcomes. The service re-proves exact pending operation/tower ownership, CAS-claims compensation and restores only the exact owned write with current write authorization. Proven rollback releases the row for a fresh request under the newest catalog. The original state may itself have emitted light, so the publication reconciler must coalesce/retry current-policy work after the old operation settles rather than treat rollback as final policy cleanup. External edits, revoked write authority or unknown SQL/physical outcomes keep quarantine. After successful completion, final read-only verification checks the committed physical/manifest result; a routine newer policy alone does not freeze that matching result. A new reconciliation request applies the newer policy. A changed physical result creates a new exact-row review only if that receipt still owns the current state; a newer row is not overwritten. Completed operation receipts never replay light writes. Light-operation evidence is intentionally administrative to #11 until a full structure/lighting observer proves its save contract; no automatic recovery is silently enabled.

## Verification and native integration remaining

Local tests use real temporary SQLite stores and strict Paper API doubles. They cover whole-candidate/SQL rollback, strict YAML/codec/filter semantics, managed-only retirement/rename/reactivation, unknown/prototype isolation, owned/manual award holds, startup with missing configuration, geometry/HP migration deferral, all four player choices across close/reopen, stale/current owner/config checks, inert compact/pending/replacement-world rows, every LIGHT level, source-water restoration, external edits, partial-write compensation, SQL/after-completion failures, no replay and schema-3 migration without adoption. These are local tests, not Paper process/client/save/physics evidence.

All native acceptance remains pending: wire one repository/catalog owner with current asset/access/protection/session/runtime readiness; connect owner reconciliation to loaded instances and chunk-load retirement; route revision-bound native switches; test actual Java/PE UI, all four combinations, light levels/cleanup/fluid physics, reload/missing configuration and unloaded retired instances. Use independent matched stopped-state save sets. Preserve the existing quarantined environment; this change authorizes no deployment, repair, pruning, replay or test there. #13 remains open until integrated behavior and acceptance pass.
