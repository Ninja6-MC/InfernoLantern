# Unique Item and Ground Lifecycle Foundation

Task #9 is **partial**. This change adds internal services and a compiled Paper adapter;
it does not register them in the plugin entry point, open a production database, issue
items, change the existing rendering/combat experiments, or introduce a command or
permission. Runtime wiring and disposable fixture issuance await a separate interface
decision. All native/live acceptance below is pending. Task #10 still owns world
placement; #11 owns restart/save-boundary reconciliation; #13 owns the variant catalog
and final configuration; #24 owns production awards. No playable release is claimed.

## Identity Contract

`PaperItemIdentity` writes four PDC fields under the `infernolantern` namespace:

| Key | Primitive type | Meaning |
| :--- | :--- | :--- |
| `item_schema` | integer | Exactly `1`; other schemas reject closed. |
| `item_variant` | string | Stable validated variant identifier. |
| `item_tower` | string | Tower UUID. |
| `item_generation` | long | Nonnegative representation generation. |

Identity never comes from material, display name or lore. HP, maximum HP, switch states,
controller and timers remain in the repository. An appearance template and effective
`GroundPolicy` are supplied by the future catalog. Creation clones the template,
forces amount/max-stack-size one and sets the native fire-resistance component according
to policy. Neither the material nor variant identifier is a fixed production default.
Unknown, partial, malformed and illegally stacked tokens remain recognizable but unusable.
Rejected inventory copies remain intact and receive private contact-admin guidance.

Exact copies have identical tokens. The first valid journal/CAS claim wins; there is no
claim that metadata can identify which physical duplicate was the original. Copying,
stacking or splitting never allocates a new identity. Portable transfers, gifting,
manual chest storage and withdrawals neither rotate generations nor create an owner.
The item service does not grant or inspect a native usage permission for acquisition.

Entity PDC `item_operation` identifies delivery work. `item_staged` marks an unavailable
future-generation staging entity. These are entity evidence, not portable ownership or
HP fields. Malformed markers are preserved for inspection rather than guessed away.

## Claim and Effect Protocol

`ItemLifecycle.Request` contains immutable token/amount/action/entity/anchor/effect data.
Callers capture item bytes and other source evidence on the owning scheduler. Requests
carry no Bukkit inventory, ItemStack, Player, World or Entity into repository work.

1. Read the row asynchronously and validate token, stable form and absence of a pending
   operation. Ground pickup/loss additionally require the exact entity UUID and world.
2. Persist a journal intent with row/generation/deployment CAS. Drop, pickup, loss and
   placement claims each consume a generation. Only one operation may own the row.
3. Marshal `Effects.apply` to `WorldScheduler`. Revalidate exact source bytes, slot,
   session, entity, action and relevant location before effects. A reconnect is a new
   session even when the player's UUID is unchanged.
4. Marshal a separate `Effects.verify` callback before committing. Verify named physical
   outcomes and the still-current session/source evidence. Missing/unloaded owning chunks
   reject the callback without force loading.
5. Record applied effects and complete the database operation. Existing operation receipts
   never replay physical effects. Failure or mismatch after intent freezes RECONCILING;
   uncertain database intent/completion also returns a quarantine result. No path refunds,
   respawns, resets HP/timers, or allocates a replacement identity automatically.

The placement API accepts a caller-prepared COMPACT snapshot with generation+1 and
revision+1, preserving authoritative HP, switches and timestamps. It is a claim boundary
for #10, **not** an implementation of placement permissions, clearance, reservations,
block effects or world-save atomicity. Those checks/effects must be supplied before
runtime world placement can be enabled.

A database receipt is not proof that Minecraft player/entity/chunk saves committed.
An inventory source can be saved before or after its corresponding entity save; a crash
can restore old item bytes despite a completed row. Generation/CAS rejects the stale
copy, but automatically deciding which independent save to repair belongs to #11.
The service never waits for database futures from a world thread.

## Paper Adapter and Staging

The adapter is owner-thread-only and currently unwired. Its caller must provide an
initialized repository, scheduler, catalog policy function and controlled lifecycle;
close the adapter/service and scheduler before closing the repository asynchronously.
Do not use an adapter instance as a production startup-reconciliation gate.

A manual drop cancels vanilla mutation so Paper retains the original inventory item.
The following owner tick captures the exact restored selected slot. Inventory/cursor
paths that cannot prove this source reject privately and preserve the item; they do not
pick an arbitrary matching inventory copy. The explicit `dropSlot` internal API can
capture another known slot when the caller has established its source action.

Before intent, the adapter stages an entity with its real UUID, future-generation token,
operation marker, disabled player/mob pickup, invulnerability and unlimited lifetime.
The source stays untouched and the future token is invalid against the PORTABLE row.
Merge and hopper collection are blocked. The intent therefore records an actual UUID
without exposing a second usable representation. Its owner callback checks exact source
bytes/session/slot plus staging entity/operation/token before removing the source.
The staged entity remains unavailable through database completion. Activation requires
a fresh durable ground-row read and final owner-thread entity/token/operation/world/session
validation. Only then are normal pickup and the effective destruction/despawn policy
restored. The generation on every competing source is now stale.

Pre-intent rejection disposes only the unavailable entity created by that action,
leaving its inventory source intact. A loaded marked staging entity with no corresponding
intent can likewise be disposed after owner revalidation. Loaded staging with a pending
or completed intent stays unavailable for #11; startup does not replay activation or
manufacture a new entity. Missing/unknown markers are preserved. Entity age is never reset
on load or recovery. Changing unlimited lifetime does not call `setTicksLived` or create
a replacement. Failed scheduling can leave evidence unavailable until reconciliation.

Pickup cancels vanilla collection and journals a transition into an empty inventory slot.
It rechecks an uncancelled pickup event through external providers at the effect boundary;
a denial cannot be bypassed. It then rechecks source/slot/session again, writes a fresh
portable token and removes the exact entity. No native acquisition-permission gate is
added. The caller must test provider interaction, including the synthetic validation event,
before claiming a particular protection integration works.

Ordinary death drops use Paper's per-item keep facility temporarily: remove each affected
item from the vanilla drop list, retain exactly that source, then capture its exact retained
inventory bytes on the following owner tick and run the ordinary journaled drop at the
captured death location. Global keep-inventory and existing individual provider keeps
remain keeps. If the chunk/session/source is unavailable before admission, the retained
item stays untouched with private guidance. Native retention timing, immediate respawn,
provider ordering and save boundaries need the live checks below; no death-path runtime
success is claimed by unit tests. Arbitrary third-party marked-item spawns are not adopted
as legitimate new deliveries. They remain unavailable unless the row/entity/token already
matches an authoritative ground representation.

## Ground Safety and Policy

- Dropped-item hopper and hopper-minecart pickup is cancelled without changing the row.
  Non-player entity pickup and item merging are blocked. Ordinary manual storage is
  supported; other automated acquisition integrations remain unsupported until verified.
- Vanilla use, placement, consumption and crafting/processing of marked items are blocked
  by the adapter until their native lifecycle integrations exist. Storage moves remain
  available. Invalid stack amounts cannot enter a lifecycle claim.
- `GroundPolicy.DEFAULT` survives fire/lava and uses normal despawn. Both policy booleans
  can be supplied independently. The final catalog/config schema is outside this change.
- Native fire resistance plus narrowly scoped item-damage handling protect these items.
  Surviving items in lava receive bounded upward buoyancy. Processing is limited to 64
  tracked entities per tick with rotating work; this is a technical budget, not a gameplay
  count limit. No unrelated item/entity properties are changed.
- Intentional despawn or lethal permitted damage is cancelled first, then journaled before
  the exact entity is removed. Only current entity/generation can become LOST. External
  removals produce reconciliation evidence, not an automatic LOST inference.
- Entity/chunk unload is suspension. It removes runtime tracking and never marks LOST.
  Loading/registering an existing valid entity preserves its identity and age. Missing
  entities, stale copies, unloads, reconnects and shutdown do not trigger replacement drops.

## Verification and Remaining Acceptance

Local verification uses real temporary SQLite databases and an explicit controllable
owner-thread scheduler, plus strict PDC API doubles. It covers exact duplicate placement
and drop races, placement-vs-drop competition, stale/restored copies, entity-specific
expiry, pickup generation rotation, LOST rejection, authoritative HP/timer preservation,
wrong-world/forged-state rejection, callback/source/session mismatch, unload rejection,
pending reopen, non-replay receipts, partial physical failure and injected SQL completion
failure. PDC tests cover exact field names/types, malformed/partial/newer schemas,
non-stackable issuance and template immutability. These are service/boundary tests;
they do not execute Paper, vanilla physics, inventory/death events or a client.

All rows below are **PENDING LIVE**, and runtime wiring/fixture setup must exist before
executing them. Use disposable identities and record only generic versions, artifact
hashes, synthetic counters and pass/fail findings. Do not publish player/server identity,
world identifiers, real coordinates, host details, private configuration or raw logs.

| Row | Required native test and evidence |
| :--- | :--- |
| 1 | Record exact Paper build, Java patch/vendor, Java client, bridge/auth versions and exact PE version/input. Verify deployed JAR digest. Startup/open failure and pending journal must keep mutations unavailable. |
| 2 | Produce one approved disposable fixture in one empty slot. Confirm amount/max-stack one and four PDC fields only; no permission grant or permanent controller. Confirm default-disabled harness and ordinary prototype cleanup remain separate. |
| 3 | Two operators hold exact copies; race portable placement claims through #10 and drop claims separately. Exactly one current row/form and one effect must win; losing inventory items stay intact with private guidance. Repeat sequentially with a restore-like old copy after recovery. |
| 4 | Java hotbar drop/pickup, PE touch drop/pickup, gifting, shift-click/manual chest/ender/shulker storage and withdrawal with usage permission absent. Rotations occur only at real ground transitions. Inventory/cursor drops without provable source reject intact. Test full inventory, immediate movement, rapid repeated input and reconnect between capture/apply/verify/activation. |
| 5 | Hoppers and hopper minecarts beneath the same item cannot collect it or change entity/generation. Mobs and merging cannot acquire/combine it. Creative/command stack-size abuse and split attempts cannot create two usable identities. Manual storage remains available. |
| 6 | Default item survives fire/lava and floats; unrelated ordinary items retain vanilla behavior. Burn-enabled policy permits destruction; default despawn permits intentional LOST. No-despawn prevents expiration but does not grant unrelated immunity. Record actual native age before/after reload without resetting it. |
| 7 | After pickup/newer drop/placement, deliver old entity UUID/generation expiry/destruction callbacks. Newer portable/ground/placed form stays unchanged. Current intentional destruction yields one LOST transition. |
| 8 | Unload/load and clean restart with an existing ground item preserve row/entity/generation and age; no LOST inference or replacement. Interrupt before intent, after intent, after source removal, after entity effects, after completion and before activation. Pre-intent stage is unavailable/disposable; pending/ambiguous state is quarantined, with no blind refund/respawn. Include independent player/chunk save orders under #11. |
| 9 | Ordinary death with keep-inventory false produces one journaled ground item at the death location; retained source is removed only after intent. Keep-inventory true and individual provider keep produce no additional drop. Exercise armor/offhand sources, exact duplicate copies, immediate respawn, reconnect and unloaded death chunk. Failure retains source or freezes evidence, never keep plus vanilla drop. |
| 10 | A real acquisition/protection provider cancels original and commit-boundary pickup independently. Denial leaves the item intact; permission-free acquisition still works without a denial. Test provider listener ordering and synthetic validation behavior. |
| 11 | Fault injection at every SQL/effect/scheduler/disable boundary leaves at most one usable row. Shutdown never joins database I/O on the world thread. Verify no orphan usable staging entities, no global item changes, and precise quarantine records for unresolved save differences. |
| 12 | Remove disposable items only after authoritative inspection, preserve unresolved evidence, verify no current test ground entities or pending transitions remain, then disable fixture creation. This is separate from `prototype-cleanup.bin` teardown. |

Task #9 remains open until runtime integration and its actual acceptance evidence pass.
Unresolved save differences stay frozen for #11; they are not counted as a passed recovery
scenario merely because no duplicate was immediately visible.

## Sources

- [Paper persistent data containers](https://docs.papermc.io/paper/dev/pdc/)
- [Paper item metadata API](https://jd.papermc.io/paper/26.3/org/bukkit/inventory/meta/ItemMeta.html)
- [Paper dropped item API](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Item.html)
- [Paper player drop event](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerDropItemEvent.html)
- [Paper death keep/drop contract](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/PlayerDeathEvent.html#getItemsToKeep())
- [Paper entity removal causes](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityRemoveEvent.Cause.html)

The online API documentation is rolling. Compilation and local API inspection use the
exact cached `paper-api:26.3.build.249-stable` artifact selected by this repository.
