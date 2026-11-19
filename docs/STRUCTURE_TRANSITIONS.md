# Journaled Structure Transition Foundation

Task #10 is **partial**. `StructureLifecycle` implements the transaction service and `PaperStructureEffects` supplies reusable owner-thread effects. Neither is registered by the plugin entry point. No native placement, expansion menu, compaction, breaking or administrative recovery action is enabled. No command or permission is added, and existing prototype commands retain their meaning. Configuration/catalog, access/session override and HP/repair modules still own their later policies. Task #9 and G1 acceptance remain open.

This is development in isolation. The existing quarantined test environment must remain untouched. This change does not inspect, prune, import, complete, repair or otherwise reconcile its database or world/player evidence. Task #11 owns the recovery matrix and separately reviewed reconciliation implementation. No deployment or live test was performed.

## Active schema migration boundary

**Schema 3 is active whenever `SqliteRepository.open` opens a store**, including the existing item prototype harness opening an existing store while creation gates are disabled. Unwired structure handlers do not mean database behavior is unchanged. Migration 3 creates `structure_cells`, whose unique world/x/y/z key excludes overlapping tower footprints. Every declared clearance voxel is represented, including unchanged air/source-water cells; visual anchors must lie inside that full volume.

Versions 1/2 retain their immutable migration checksums. Portable/ground/lost-only older stores can migrate when no pending operation introduces a structure. Older placed manifests did not prove their complete clearance geometry. A version-2 store with COMPACT/EXPANDED towers or a pending after-state containing components therefore fails migration transactionally, preserving the old schema and data rather than inventing a footprint. A separately reviewed evidence-based migration is needed for such data. Missing or mismatched version-3 reservations also fail startup; the repository does not rebuild authority from uncertain physical state.

After migration, older candidates reject schema 3 as a future schema. Do not downgrade, manually lower `user_version`, delete the store, or install this candidate into the quarantined runtime as a way to resume testing. A future approved deployment needs complete matched stopped-state backups of database companions and world/entity/player/container saves, not a database-only swap. The current development work does not authorize that deployment.

## Immutable requests and transition rules

Each request binds operation/tower identity, expected row version, item generation, deployment revision, actor, current session capability, base/world/orientation and an immutable effective policy revision. The caller supplies validated expansion percentage, cooldown duration, current action time and explicit session override. No permanent holder/owner or numerical production variant is created. The selected requirements' default expansion threshold is 50%; actual effective settings remain caller supplied. Timed exits retain the prior deadline while pending. The verified completion samples the exit clock after SQLite writer admission and stores the stamped deadline in both the tower and terminal receipt. Trusted matched-save observation may roll back a timed exit or verify its saved after-effects. AFTER recovery starts the full originally admitted duration at the recovery commit. See [Tower Health](TOWER_HEALTH.md).

| Action | Authoritative state effect |
| :--- | :--- |
| PLACE | PORTABLE to COMPACT; consume one item generation; null controller; new deployment revision; preserve HP, switches and both eligibility timestamps. |
| EXPAND | COMPACT to EXPANDED; require HP threshold and expired expansion timestamp unless the timestamp alone is explicitly overridable; actor becomes controller; new deployment revision; no HP debit or generation rotation. |
| COMPACT | EXPANDED to COMPACT; current controller or explicit session override; clear controller; fresh expansion eligibility time; new deployment revision; preserve HP and repair timestamp. |
| DEFEAT | System-only EXPANDED zero-HP to COMPACT; clear controller; fresh expansion eligibility time and revision; preserve zero HP, generation, switches and repair lock. See [Tower Health](TOWER_HEALTH.md). |
| BREAK | COMPACT to GROUND; one actual staged ground UUID; consume generation and invalidate deployment revision; preserve HP and existing timestamps; no inventory delivery. |
| ADMIN_RECOVER | EXPANDED to GROUND only with explicit override; clear controller; fresh expansion eligibility time and revision; one ground item; preserve HP and repair timestamp. |

Ordinary expanded breaking is rejected. A permission grant alone is never an enabled session override. Override does not waive HP, clearance, integrity, external protection or other unimplemented policies. Retired variants reject place/expand while permitting authorized compaction/recovery. The service checks variant status and owner authorization before applying, verifying and
committing. [Native access](NATIVE_ACCESS.md) supplies the binding to current catalog,
connection and override. Managed structure completion also checks the catalog revision
inside the database transaction. The native adapter also passes its revocable session
or override permit to the transaction. Revocation before commit admission rejects that
completion and leaves any compensation subject to the guarded restoration checks.

A stale menu/hit/action must pass the exact request revision, generation and row version, even if the same actor re-expanded the tower. An old component cannot be adopted into a newer deployment. HP, maximum HP, repair lock and light/defence preferences are copied from repository state, never from item lore or caller-provided cached HP.

## Durable exclusion and effect protocol

1. Read an immutable current row and a non-replaying operation receipt asynchronously. Capture the physical source, full oriented layout, exact block states and policy/session evidence on the owning scheduler. Any prospective visual/drop is staged unavailable; the existing source remains unchanged.
2. Persist a fresh-only intent and tower CAS together with spatial exclusion. `beginNewIntent` rejects an existing operation identity instead of granting a second caller permission to replay effects. The older low-level `beginIntent` retains its receipt behavior for existing callers; a receipt is not permission to apply effects again.
3. Pending reservations hold the union of the before and after manifests. They remain exclusive through INTENT, EFFECTS_APPLIED and RECONCILING, survive reopen, and are checked across separate repository connections. A visible replacement is not required for a clearance voxel to remain reserved. Reservation rows are technical conflict protection, not a gameplay tower-count limit.
4. Return to the owner scheduler. Revalidate session, policy/access, exact inventory source, entire captured terrain, old component identity and staged delivery. Apply only named effects; then verify physical results in a separate owner callback. No database future is joined from a world thread.
5. Persist verified effect phase and complete tower/ground/manifest/reservations atomically in SQLite. Read the completed row again, then activate the existing staged visual/drop on the owning scheduler. Completion is not an atomic save with Minecraft. A failed/ambiguous activation freezes a new exact-row review intent; it never issues another item or replays the original physical effects.
6. On failed preconditions or partial effects, compensate only exact owned writes. Rollback requires proof that the original source/structure was restored; only then does the journal reach ROLLED_BACK and release obsolete cells. An unrelated block/entity edit, changed source/session, unloaded chunk or failed compensation retains quarantine and union reservations. Database uncertainty never triggers an automatic refund, respawn or age reset.

Completed/rolled-back representations replace the pending union with their final footprint in the same database transaction. Old receipts cannot overwrite a newer row. Spatial constraints do not replace physical clearance, entity checks, world bounds, access or protection.

## Paper effect adapter contract

`PaperStructureEffects` is one-shot and owner-thread-only. A caller must provide a current Player/session supplier, mandatory `Access` callback, complete already-oriented rectangular `Layout`, exact source slot, validated recovery-item appearance/policy and the existing active `PaperGroundItems` adapter. `Access` must validate native permission, variant eligibility, current effective configuration, capability/world/dimension restrictions, reach, supported protection providers and explicitly enabled administrative override. There is no permissive built-in production access policy or native listener registration in this change.

Each `Layout` contains every voxel in its bounding volume, including cells whose replacement is null (unchanged clearance). Missing interior cells, duplicate voxels, visuals outside that volume and oversized manifests reject. COMPACT is one base voxel. The technical bound is the existing 4096-component snapshot limit, not a gameplay count cap. All before/after cells and visuals must be within the same already-loaded chunk and world/border/height bounds. Unsupported cross-chunk/cross-region geometry rejects without force loading; Folia support is not claimed.

The adapter verifies the entire target volume and entity intersections before effects, then checks the captured states again at commit. Air is supported. Optional source-water support requires a zero-level water source and a waterloggable physical replacement, or an unchanged clearance cell. Flowing water, lava, solids (including waterlogged solids) and unsupported representations reject. Restoring original water state has local service coverage, not native fluid-physics acceptance; production fluid policy remains #21 work.

Prospective model visuals are empty, invisible, marker ArmorStands with gravity disabled and tower/revision/operation tags. Their actual UUIDs enter the intended manifest before any inventory consumption. Visible helmet bytes are applied only after completion. Old visuals are quiesced until completion; their exact snapshots can be restored during proved compensation. The adapter refuses changed tags, positions, staging properties or helmets rather than deleting/replacing unrelated entities. This is a visual effect port, not the later combat proxy/service.

For BREAK/ADMIN_RECOVER, one real future-generation Item is staged at the base with pickup disabled, invulnerability, gravity disabled, an operation tag and `item_staged`. Its actual UUID is persisted before components are removed. Activation requires the exact completed ground row and unchanged stage; it removes the staging marker without calling `setTicksLived`, then asks `PaperGroundItems.inspectCommittedDrop` to perform its normal authoritative validation/tracking. This does not create a second native item-lifecycle implementation. A lost/changed stage remains unavailable for #11; it is never replaced automatically.

Block compensation writes only where the exact state written by this operation remains; later external edits stay untouched. Visual ownership includes exact tower and deployment revision, with operation/position/unavailable properties checked for new stages. Native event cancellation, revision-bound UI/hit routing, piston/explosion guards, lifecycle/service stop hooks and publication of a read-only placed-structure view remain integration requirements. Calling the effect port directly is not a supported native player interface.

## Verification and pending integration worksheet

Local tests execute real temporary SQLite databases with a controllable owning scheduler, plus strict Bukkit API doubles that execute the actual Paper adapter. Coverage includes unchanged clearance reservation, overlaps across repository writers, pending reopen, commit-only release, missing-reservation rejection, transactional legacy migration refusal, full lifecycle state/controller/HP/timer rules, stale same-actor revisions, retirement, repeated operation identity across service instances, source/policy/session/unload changes, compensated partial effects, external-edit preservation, SQL completion failure and post-completion activation quarantine. No Paper process/client or native world-save test was run.

All rows below are **PENDING INTEGRATION/LIVE**. The service is reusable for subsequent modules, but no executable structure test kit or newly enabled command is advertised by this change.

| Row | Required evidence before acceptance |
| :--- | :--- |
| 1 | Select validated catalog geometry/appearance, access/protection/config adapters and revision-bound UI/hit routing; verify startup pending-journal gate and native event cancellations before enabling use. Record exact candidate digests and generic versions. |
| 2 | Use genuinely independent matched stopped-state database/world/entity/player/container baselines. Preserve existing quarantined evidence separately; do not swap only databases or claim the historical test state was repaired. |
| 3 | Java/PE compact placement consumes exactly one current source, preserves HP/timers and leaves null controller; stale copies remain intact with private guidance. Race two real source claims and overlapping declared volumes, including unchanged clearance voxels. |
| 4 | Expand only after HP/cooldown/native access/config/world/protection/solid/entity/fluid checks across the entire footprint. Intervening changes and unloads preserve source or quarantine without overwriting terrain. Cross-chunk/border/height rejection remains bounded and private. |
| 5 | Controller compaction and explicit admin recovery stop the actual later light/combat services; old components, hits and menus cannot affect same-actor re-expansion. Verify current authorization/session override at commit; permission alone must not enable override. |
| 6 | Compact break and explicit expanded recovery deliver exactly one ground item, no inventory-first fallback, correct generation/revision/HP/timers, expected lighting/collision/render cleanup and native age behavior. |
| 7 | Inject interruption before intent, after named effects, during rollback, after completion and before stage activation; retain journal/union exclusion and exact physical evidence. Independent player/chunk save orders and any evidence-based recovery remain #11. |
| 8 | Verify real fluid restoration, external same/different-state edits, supported protection-provider behavior, piston/explosion guards and measured multi-tower load. Do not promote API doubles or local SQL probes into native compatibility claims. |

Task #10 remains open until integrated behavior and its actual acceptance criteria pass. Task #9/G1 pending evidence is carried forward explicitly; this foundation neither closes those gates nor authorizes tests against the frozen live environment.
