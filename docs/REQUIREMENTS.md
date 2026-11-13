# Inferno Lantern Plugin Plan

Status: Proposed requirements for a planned plugin. No implementation or platform compatibility
is claimed.

Repository: InfernoLantern. License: GPL-3.0. Commands, permission nodes, artwork, numeric
balance, supported versions, and configuration schema remain undecided.

Companion document: [Technical solution](SOLUTION.md). This document defines behaviour; the
solution proposes implementation and validation gates.

## 1. Purpose

Build a public, configurable Minecraft plugin that provides compact custom lanterns combining
lighting and automated defence.

Administrators must be able to configure variants, permissions, reward quantities, wording, and
resource-pack appearance without source changes. Tester and dragon rewards are example
configurations rather than hardcoded editions.

## 2. Block Concept and Controls

The design is an antique street-lamp-inspired tower. Players initially place a compact 1 x 1 x 1
block, then press an expansion button to deploy its full structure. The deployed height and
footprint remain to be selected; the full tower is no longer assumed to be one block tall. Its
defence effect takes inspiration from an Inferno Tower: it acquires an enemy and attacks while
that target remains eligible.

### Placement and Expansion

1. Placing the item creates one compact base block. It does not immediately deploy the tower.
2. An authorized operator opens the base controls, deposits repair resources if needed, and
   presses the expansion button. Require the configured HP threshold and an expired expansion
   cooldown before deployment.
3. Before deployment, check the complete required volume for the selected variant and
   orientation, including its height, width, collision space, and any required clearance. Check
   world bounds, loaded chunks, overlapping lanterns, entities that could be trapped, and
   building permissions across the whole structure.
4. Expand only when the entire space is available. Do not overwrite existing solid blocks or
   partially deploy the tower. Water and lava follow the separate fluid policies below.
5. If expansion is blocked, keep the compact base unchanged and send a configurable private
   message only to the player who attempted it. Example: "Cannot deploy this tower: the space
   above it is blocked." Do not broadcast the error.

The plugin must define and validate the occupied volume independently of the visible
resource-pack model. Custom model overrides must fit that declared volume; changing a texture or
model must not silently change placement clearance.

Prevent repeated clicks or simultaneous expansion attempts from creating duplicate structures.
Revalidate the space when deployment commits, reserve overlapping deployment volumes, and roll
back interrupted deployment safely. Persist compact, deploying/recovery, and deployed states so
restarts cannot leave partial towers or extra items. Any expansion animation is cosmetic and
must not bypass the space check.

Proposed initial policy: lighting and defence activate only after successful deployment.
Non-explosion damage reaching zero HP automatically stops defence and collapses the tower back
to its compact 1 x 1 x 1 base without destroying the reward. Clean up deployed lighting,
display, interaction, and combat components while retaining the base and its persistent record.
Manual compaction is included and restricted to the expansion controller or explicit
administrative override; its physical/menu presentation remains undecided. Explosions instead
follow the ground-item rule below.

### Water and Lava Settings

Provide separate settings per variant for water and lava. These are configurable behaviours;
preserving fluids and performing clearance checks are mandatory correctness rules.

| Setting | Default |
| :--- | :--- |
| Allow compact-base placement in water | `true` |
| Allow expansion into water | `true` |
| Allow defence while submerged in water | `false` |
| Allow compact-base placement in lava | `false` |
| Allow expansion into lava | `false` |
| Allow defence while submerged in lava | `false` |

Check placement at the base and expansion across the full occupied volume, including flowing
fluids and waterlogged blocks. Allowing a fluid does not permit replacing a solid waterlogged
obstruction. When a structure encounters both fluids, each applicable policy must pass.

Lighting operates after deployment only where the effective feature, world, and variant settings
permit it. The proposed combat definition of submerged is fluid intersecting the attack head;
finalize this rule before implementation and apply it to later fluid changes as well as initial
deployment. Entering a disallowed combat fluid must suspend attacks immediately.

Preserve surrounding water and lava, including source/flow and waterlogged state where
applicable. On collapse or removal, restore only fluid space displaced by this tower without
overwriting unrelated later world changes. Deployment, rollback, and restart recovery must not
create fluid duplication, permanent drainage, or unwanted water/lava conversion. Unsupported
fluid arrangements must reject expansion privately rather than damage the world.

Dropped tower items survive fire/lava and float in lava by default, in the intended
netherite-like manner. Administrators can configure them to burn instead. This item-survival
policy is independent of permission to place or expand a tower in lava. Dropped items use normal
item despawning by default, with a per-variant option to disable despawning. “Never despawn”
does not imply immunity to other configured destruction sources or automatic replacement after
loss.

### World and Dimension Restrictions (Proposal)

Provide per-variant restrictions for placement, expansion, lighting, and defence. This lets
administrators, for example, allow decorative lighting in the End while disabling automated
combat there.

- Support dimension categories (Overworld, Nether, End, and custom environments where supported)
  and explicit world identifiers. A world filter distinguishes multiple worlds with the same
  dimension category, including resource and lobby worlds.
- For each action, support an optional world allowlist, a world denylist, and an optional
  dimension allowlist. An absent allowlist imposes no restriction; an explicitly empty allowlist
  permits nothing. Denylist matches always reject; otherwise every configured allowlist must
  match.
- Proposed public default: no world or dimension restrictions. Administrators opt into limits.
- Evaluate location restrictions together with fluid rules, access permissions, temporary
  controller restrictions where applicable, claims, and PvP protections. Passing one check never
  bypasses another.
- Denied placement or expansion leaves the item/base intact and gives private, configurable
  feedback. If a reload changes the rules for an existing tower, suspend prohibited lighting or
  defence without destroying it, and allow authorized safe recovery.
- Use stable runtime world identity for placed records. Define how configured selectors handle
  world renames, resource-world replacement, and custom dimensions; do not bind records from a
  deleted world to an unrelated new world with the same name.

Exact selector syntax and initial action granularity remain implementation decisions.

### Deployed Controls

A variant with both capabilities enabled offers two independent deployed controls:

| Control | Intended behaviour |
| :--- | :--- |
| Light | Toggle real world illumination on or off. Support a configurable emission level from 0 to 15. |
| Defence | Toggle automated attacks on or off. Targeting and combat settings are configurable. |

Administrators configure feature availability using server-wide defaults with per-variant
overrides. Both lighting and defence are available by default; variants may instead offer light
only or defence only. These settings determine which capabilities exist, not the player's
current switch choices.

When both capabilities are available, all four player-selected combinations must work: both off,
light only, defence only, or both on. For a single-feature variant, players may toggle only its
available feature. Player switches and administrative action override cannot enable a capability
disabled in the effective configuration. Make unavailable controls clearly disabled or hidden,
with an explanation where appropriate. Persist player switch states across chunk reloads and
restarts, but always apply current capability, retirement, world, fluid, and permission checks
before activation. A configuration reload disabling a capability must stop that feature on
existing towers without requiring removal.

The preferred visual design has two buttons on the model. Prototype their interaction areas
before committing to that interface. A right-click menu with two clearly labelled switches is
the proposed fallback, especially for mobile controls. Exact hitboxes, collision shape, and
interaction method remain review decisions.

## 3. Configurable Variants

Administrators can define multiple variants. Each variant has a stable internal identifier
independent of its display name and appearance.

Configurable properties should include:

- Display name, item description, menu wording, and award messages.
- Model identifiers for compact and deployed appearances, a declared deployment volume, and
  optional variant-specific sounds and particles.
- Lighting/defence availability inherited from server-wide defaults or overridden per variant,
  and default player switch states.
- Light level, defence range, damage, attack interval, and allowed targets.
- Maximum HP, minimum expansion HP percentage (default 50%), accepted repair resources, HP
  restored per resource item, repair cooling period, and expansion cooldown.
- Required access permission and supported reward quantities.
- Separate water/lava placement, expansion, and submerged-defence policies; proposed
  world/dimension restrictions for placement and operation.

Variants may share the same model geometry with different textures or use different models
entirely. Sharing the same identifier for every variant would prevent independent visual
overrides, so each independently customizable variant needs its own asset mapping.

### Missing Variants and Safe Retirement

Removing a variant's configuration retires it rather than deleting its towers or stopping the
server. Log a clear console warning naming the missing variant and retain its last valid
definition durably, including permission rules and existing occupied geometry needed for safe
recovery. Renaming an identifier counts as retiring the old identifier unless an explicit
migration is provided.

Retired variants cannot expand, emit light, or run defence. Keep existing towers, items, HP,
identities, and timers intact. Expanded towers retain their controller and can be manually
compacted/recovered under the normal rules; do not automatically compact them or restart timers
merely because configuration is missing. Compact bases remain recoverable normally. Reconcile
retirement on chunk load and startup so unloaded towers cannot resume combat. Restoring the same
variant identifier re-enables it subject to the current validated configuration and normal
checks.

Retirement disables new issuance for the missing variant. The explicit administrative override
must not silently bypass retirement. Preserve outstanding reward/item records without
duplicating or deleting them; define pending-award handling before implementing distribution.

### Future Work: Permanent Variant Deletion

An explicit admin-only permanent-deletion command may be considered later. It is outside initial
scope, and removing a configuration section must not trigger it. The future design should cover
confirmation, cleanup of loaded/unloaded structures, invalidation of inventory and dropped
items, and prevention of old copies recreating deleted towers. Refund/compensation policy,
command names, and exact deletion workflow are deferred; no deletion implementation is selected
by this plan.

### Example Variants

| Variant | Example award source | Quantity |
| :--- | :--- | :--- |
| Tester edition | Trusted administration or a reward system. | Independently configurable per recipient. |
| Dragon edition | A qualifying encounter outcome from an optional integration. | Independently configurable per recipient. |

These names and sources are generic examples. Administrators select eligibility, wording,
artwork, and quantities in configuration. The proposed starting balance is equal functional
strength with distinct visuals; different strengths remain an implementation decision.

## 4. Reward Distribution

Keep eligibility, permission grants, and item issuance separate. Obtaining an item never grants
permission, and permission to use an item never grants authority to create copies.

The trusted award workflow should grant the appropriate variant eligibility and deliver the
configured quantity. If permissions are managed externally, use an explicit integration or
documented server workflow rather than assuming the lantern plugin owns the permission system.

Proposed distribution requirements:

- Tester rewards are explicitly issued by trusted server administration or a connected reward
  system. Define a batch/campaign identifier so retrying an award does not unintentionally issue
  it twice.
- Dragon rewards are automatic after a qualifying defeat. Define whether recipients are the
  killer, eligible damage contributors, or another configured participant group before
  implementation.
- Configure whether first kills, repeat kills, or both qualify. Decide cooldowns, participation
  thresholds, and supported worlds.
- Use one authoritative dragon outcome event per encounter. Multiple dragons, duplicate
  callbacks, and restarts must not multiply an intended award.
- Persist pending rewards when inventories are full or players are offline. Do not spill
  exclusive rewards onto the ground as an overflow policy.
- Persist award records and define recovery for interruption between eligibility changes and
  item delivery.

The dragon integration consumes outcomes from the server's dragon-management system. It must not
introduce a second dragon-spawning, fight-scaling, or reset controller.

Initial reward sources are trusted administrative issuance and the optional dragon integration.
Crafting recipes, trading-system integrations, and other acquisition recipes are outside the
initial scope; ordinary gifting of existing items remains allowed. Public reward sources beyond
administrative issuance and the optional dragon integration can follow later.

## 5. Defence Behaviour

The plugin owns target selection, attack effects, and combat behaviour.

### Initial Combat Scope

Boss interactions are deferred. The initial release must not target or provoke the Wither or
Ender Dragon, redirect their targeting toward towers, or implement boss-specific combat/damage
adapters. Focus mob combat and retaliation testing on explicitly supported ordinary mobs.
Configurable target lists must not silently enable unsupported bosses.

This exclusion does not promise tower immunity to incidental world destruction or damage near a
boss; environmental handling still follows the tower's defined protection and recovery rules.
The optional dragon-kill reward integration remains in scope: awarding items after a dragon
defeat is separate from towers fighting the dragon.

Boss support can be reconsidered as a separate future feature after ordinary mob interactions
are stable.

| Mode | Intended targeting |
| :--- | :--- |
| PvE | Configured hostile mobs, such as zombies. Ignore players, pets, villagers, and other excluded entities. |
| Optional PvP | Also allow eligible enemy players, subject to the world's PvP rules, alliances, claims, safe zones, and supported protection integrations. |

Proposed combat rules:

- PvP targeting is off by default and explicitly enabled by administrators.
- Exclude the current expansion controller from player targeting. No built-in trusted-player
  system is included initially. Optional external team/protection exclusions remain a separate
  integration decision; they do not grant recovery rights.
- Require range and line of sight; do not attack through solid walls.
- Configure attack interval, damage, targeting priority, and allowed entity types.
- A visible beam is the initial attack concept. Increasing damage while maintaining one target
  is optional future behaviour, pending balance review.
- Record damage attribution and define interactions with armour, invulnerability, death
  messages, drops, and progression credits.
- Respect protection and combat integrations explicitly; setting a general PvP flag alone is
  insufficient to establish all regional rules.
- Define what happens when the expansion controller logs out, chunks unload, a target leaves
  range, or permissions change.
- Bound processing per tick, schedule target scans, and cap particle output. These are technical
  workload budgets, not gameplay tower-count limits. Suspend work in unloaded chunks and respect
  the server's scheduling model.

Attack range, damage, and eligible incoming damage sources have not been selected. Resource
deposits restore HP; expansion and ordinary operation do not spend HP. Only damage reduces HP.
No separate fuel or energy mechanic is currently specified.

### Ordinary Mob Targeting and Incoming Damage

Support configurable mob behaviour modes: normal targeting, retaliation after the tower damages
a mob, and priority targeting of an eligible deployed tower. Retaliation is the proposed
default. Restore normal targeting when the tower collapses, is recovered, or stays unreachable
beyond a defined timeout. Specify target arbitration between multiple towers and avoid
redirecting unrelated mobs globally.

A custom visual block is not automatically a damageable mob target. Prototype a server-side
target/hitbox representation, associate every component with one tower, and route supported
incoming damage into its authoritative HP. One attack or explosion must not charge damage
repeatedly for overlapping components. Select and verify the representation before promising
support for a mob type.

Start with zombie melee, then verify skeleton projectiles, witches, and creeper explosions. An
explosion that affects the tower breaks it into one ground item rather than leaving an intact
deployed tower or merely collapsing it to a placed base. This includes creeper blasts and
incidental Wither blasts. Follow the explosion/recovery policy below and honour applicable
damage protections. Non-explosion damage reaching zero HP still follows the compact-collapse
flow.

Retain the witch scenario as a design/test case: the tower damages a witch, retaliation
redirects its attacks to the tower, and incoming potion effects follow explicit rules. Proposed
initial mapping is instant damage to HP, no poison/slowness/weakness effects on the machine, and
no healing from potions. Final potion mapping and beam damage type remain review decisions.
Splash effects may still affect nearby players; targeting the tower does not confer player
immunity.

Manual compaction is an authorized UI action, distinct from hostile hits. Normal players cannot
directly break/recover an expanded tower, including their own deployment: the current expander
must first use the compaction control, then break the compact base normally. An administrator
with explicit override enabled can directly break/recover an expanded tower into one ground
item. Define damageable geometry, damage-source attribution, invulnerability behaviour, and
environmental sources before implementation. Raw breaking of expanded components must not bypass
this distinction, health handling, or the recovery transaction. Boss interactions remain
excluded.

### Health, Repair Resources, and Defeat

Each individual tower has persistent health and a visible health indicator. Maximum HP is
configurable per variant. Newly issued towers start with zero HP in the compact state; players
restore health by depositing accepted resources through the base's controls.

| Property | Agreed behaviour |
| :--- | :--- |
| Initial HP | Zero for newly issued towers. Recovering an existing tower preserves its current HP. |
| Maximum HP | Configurable per variant; no numeric default selected yet. |
| Expansion threshold | Configurable percentage of maximum HP; default 50%. |
| Expansion cost | No HP consumption. HP is an eligibility threshold, not an expansion payment. |
| HP loss | Damage only; ordinary attacks, lighting, expansion, and cooling do not drain HP. |
| Below expansion threshold while deployed | Continue operating while HP is above zero. The threshold applies only when expanding. |
| Zero HP from non-explosion damage | Stop attacks and automatically collapse to a disabled compact base. Do not destroy the reward or refill health. |
| Recovery after defeat | Deposit resources to reach the threshold, wait out any remaining expansion cooldown, and explicitly press Expand again. |

Configure one or more accepted repair resources and the HP restored by each individual resource
item for every variant. Diamonds are an example server choice, not a hardcoded requirement.
Allow understandable per-item deposits rather than requiring a whole stack. Display current/max
HP, the expansion threshold, accepted resources, conversion rates, and the amount still needed.

Provide a deposit slot and explicit repair feedback. Clamp health to maximum, never accept
payment for an already full tower, and return unconsumed items when payment is rejected or the
menu closes. Define rounding and the final resource item's treatment before implementation; show
any partial final-item benefit before confirmation rather than silently wasting resources.
Reject invalid configurations, including non-positive maximum HP or repair rates and expansion
thresholds outside the supported positive percentage range up to 100%.

Repair is unavailable while the tower attacks. Attacking or taking damage refreshes a
configurable cooling period; deposits become available only after the tower has stopped
attacking and that period expires. Taking damage triggers the lock even if the attacker is
outside the tower's firing range. Defence toggling, defeat/collapse, breaking, replacement,
transfer, and restart must not clear an active repair lock. Show a private message such as
"Tower is too hot to repair. Cooling down: {time}." Reject locked deposits without consuming
resources.

The current expander may manually compact the tower during combat and then break the compact
base when normal permissions allow it. This can save remaining HP and is an accepted escape; no
additional combat lock prevents compaction or compact-base breaking. Normal players cannot skip
compaction by directly breaking an expanded tower. The recovered item retains HP, identity,
expansion eligibility time, and repair eligibility time. Defeat costs invested repair resources
and defence downtime rather than permanent loss of the reward.

Resource consumption and HP updates must be recorded as one recoverable transaction. Serialize
repairs, expansion, damage, and recovery for the same tower; prevent simultaneous viewers,
alternate inventory gestures, disconnects, or restarts from duplicating resources, health, or
tower items. Persist health independently of editable names or cosmetic models, with one
authoritative record per tower identity.

The health indicator may be displayed above the deployed tower. Exact presentation and
visibility (always visible, damaged only, or targeted only), eligible damage sources, repair
cooling duration, and repair conversion rates remain review decisions. No passive health
regeneration is part of the agreed repair flow.

### Expansion Cooldown

Use a configurable expansion cooldown to limit repeated deployment. There is no home-only
deployment requirement, claim-age rule, boundary-targeting restriction, additional defence
warm-up, or per-owner/claim tower limit introduced by this policy. Existing access, world,
fluid, clearance, and protection checks still apply.

- Players can place the compact 1 x 1 x 1 base or recover/break their tower whenever permitted
  by the normal access and protection rules. The cooldown never blocks placement or breaking.
- Pressing the expansion button checks the cooldown before deploying. While time remains, keep
  the base compact and send only that player a configurable message showing the remaining time.
- After the cooldown expires, expansion may proceed if the HP threshold and normal clearance and
  permission checks pass. Neither cooldown expiry nor reaching the HP threshold automatically
  expands the tower.
- The first expansion has no prior expansion timer but still requires sufficient restored HP.
  Breaking/recovering a deployed tower or collapsing it back to compact form starts a fresh
  expansion cooldown for that individual tower, including automatic collapse at zero HP. Running
  in deployed form does not count down a future relocation cooldown.
- Breaking/recovering or replacing an already compact base preserves its existing next-expansion
  timestamp; it neither restarts nor clears the timer. Failed expansion attempts and resource
  deposits do not start or extend it. A successful expansion does not itself start a cooldown.
- Retain a persistent individual tower identity and next-expansion timestamp through
  compact-base recovery, replacement, inventory storage, transfer, chunk unload, and server
  restart. Use elapsed real time, including time offline. An unload or restart alone is not a
  collapse and must not start a new timer. This is a per-tower cooldown, not an additional
  player-wide restriction.
- Configure the duration per variant; zero disables the cooldown. Select the actual duration
  before deployment.

Cooldown applies to expansion only, with placement and breaking available during the wait.
Record the transition out of deployed form and the new cooldown atomically; repeated recovery
callbacks must not extend it. Preserve the separate repair lock. Cooldown enforcement belongs to
the lantern plugin.

## 6. Identity, Deployment Control, and Persistence

### Explicit Administrative Override

An administrative permission authorizes use of an override toggle; holding that permission,
being an operator, or having recovery rights must not silently bypass restrictions. Normal
restrictions apply until the authorized player explicitly enables override mode with a command.
Running the same command again disables it. Final command and permission names remain undecided.

When an action is denied, send private feedback explaining the restriction. If the player has
permission to use the override toggle and the failed restriction is overridable, also show the
command they can use to enable override mode. Players without permission receive only the normal
rejection message.

The override is per administrator, not server-wide. Confirm activation/deactivation privately
and make enabled status visible to that administrator. Recheck toggle authorization on each
overridden action so permission revocation removes its effect. Record administrator, tower
identity, and overridden action for audit.

The scope includes expansion cooldown and expander-only manual compaction, recovery,
switch-operation, and repair-access restrictions. Overriding who may repair does not waive
repair cooling or resource payment. Claim/protection integration must explicitly support any
override of its rules; this toggle is not an implicit bypass of another plugin. Do not silently
expand the override to HP requirements, repair cooling, resources, or other restrictions without
a documented decision.

An override permits the selected action without clearing saved HP, cooldowns, or repair locks.
Subsequent actions by players without override still use the authoritative state.
Duplicate/stale identity rejection, transaction integrity, world bounds, and physical clearance
are correctness checks rather than bypassable gameplay restrictions.

Override is session-only and turns off when the administrator logs out or disconnects. Do not
persist enabled override across sessions or restore it after a server restart; reconnecting
starts with normal restrictions until the command is explicitly used again. The user-facing
command should report current mode and supported scope clearly; final command syntax is not
selected yet.

Store variant identity independently of display names and textures. Preserve identity through
inventory storage, placement, legitimate breaking, chunk unloading, and server restart.

Track placed lantern locations, variant, individual tower identity, deployment controller UUID
(expanded state only), deployment state, orientation, occupied volume, switch states, current
HP, next-expansion timestamp, repair-unlock timestamp, and any combat state that must survive
restart. Persist these through item recovery and replacement as well as chunk reloads. Associate
every deployed component with one authoritative base so interacting with or breaking a component
cannot create extra rewards. Use a reliable item-to-world identity mapping; item metadata is not
automatically preserved merely by placing a block. Define how maximum-HP configuration changes
affect existing health without granting free healing or exceeding the new maximum.

### Duplicate and Restored Items

Each legitimately awarded tower receives a distinct identity. Maintain one authoritative current
form for that identity: an issued/recovered item, a compact placed base, or a deployed
structure, with recoverable transition records for placement and recovery.

Use a persistent generation or equivalent single-use placement token for each valid item
representation. Placement consumes that representation; authorized recovery creates a new
generation and invalidates previous item copies. Validate and commit these transitions
atomically so simultaneous or sequential attempts cannot place the same identity twice. HP and
timers come from the authoritative record, not an older item's saved values.

Extend the authoritative lifecycle to inventory items, specific ground entities, placed
structures, and intentionally lost items. A valid drop registers the ground entity UUID with a
fresh generation; a valid pickup retires that ground representation and issues a fresh inventory
generation. Burning or despawning marks the tower lost only if both the entity UUID and
generation still match its current authoritative ground record. Late events or disappearance of
stale copies must not invalidate a newer item or placed structure. Persist transitions and
reconcile interrupted delivery without issuing two valid representations.

Exact inventory duplicates can carry identical tokens; neither Minecraft metadata nor a dropped
entity UUID proves which inventory copy was the original. The first successfully committed valid
action claims the current representation and makes the competing copy stale. Serialize competing
placement/drop/pickup operations. Reject placement if the tower is already placed, the token is
stale, or another placement has won the transaction. Leave rejected items in inventory without
changing authoritative HP, timers, or controller, and send private feedback: "This tower copy is
no longer valid or is already placed. Please contact an admin."

An inventory rollback, such as InventoryRollbackPlus restoring an earlier inventory, must not
create a second valid tower when the original is already placed or has since been recovered into
a newer item. Reject placement of the duplicate/stale copy without modifying the valid tower or
silently deleting the restored item. Send configurable feedback privately to the player. Example
when the original is placed: "This tower is already placed. This restored copy cannot be
deployed. Please contact an admin." For other stale copies: "This tower item is no longer valid.
Please contact an admin."

Provide an administrative recovery procedure that checks the authoritative record before
resolving a restored item. A deliberate additional award creates a new tower identity; restoring
an existing tower does not. Do not automatically replace an unavailable or unloaded placed tower
merely because the holder presents a restored copy.

Variant eligibility does not bypass ordinary world/claim protections. There is no permanent item
owner; the only player association is the temporary expansion controller described below.

### Transferable Items and Temporary Expansion Control

Tower items have no permanent owner and can be gifted; recipients require the configured variant
permission to use them. External acquisition rules, if configured, may also restrict who can
receive them. Award recipients and first placers are not ownership locks. Placing the compact
base assigns no controller. No transfer command or built-in trusted-player system is required.

On successful expansion, record the expanding player's UUID as the controller for that
deployment. Only that player may manually change the expanded tower back to compact form, except
an administrator using the explicitly enabled supported override. Normal players must compact
first and then break the compact base; direct expanded-tower break/recovery is available only to
an administrator with override enabled. Ordinary raw breaking must not bypass this rule.

Manual compaction leaves a 1 x 1 x 1 base and clears the controller association. Automatic
zero-HP collapse also clears it; this system action does not require the controller to be
online. A permitted explosion removing the tower clears it as well. Update deployment state,
controller removal, and cooldown atomically.

Compact bases and item forms have no controller. Eligible players may recover, carry, give away,
place, repair, and attempt expansion subject to normal permissions, world protections, HP, and
timers. The next successful expander becomes the new temporary controller. Retain the
association across unloads and restarts while the tower remains expanded; logout alone does not
clear it.

Normal breaking of a compact 1 x 1 x 1 base works like breaking an ordinary block: any player
with the variant permission may break it, subject to applicable world/claim protections, and it
drops exactly one valid tower item. Preserve its identity, HP, expansion cooldown, and repair
lock; breaking a compact base does not restart or clear those timers. The item follows the
configured ground-item survival/despawn rules. Complete the world removal and valid-item
transition once, without both a vanilla drop and an additional plugin drop. This rule does not
bypass expanded-tower controller restrictions.

Clearing the controller must never clear tower identity, HP, expansion cooldown, repair lock, or
duplicate-copy protections. Historical award/audit records may retain player references, but
must not act as ownership checks. While expanded, only the current expander may operate
light/defence switches or deposit repair resources, subject to normal variant permission and
repair rules, except an administrator with explicit override enabled. Check access again when
committing a switch change or resource transaction; opening the menu earlier does not retain
rights after deployment changes.

### Ground Drops, Explosions, and Recovery Delivery

Physical breaking and authorized administrative recovery always produce one valid ground item at
the tower location, regardless of whether the actor has inventory space. There is no
inventory-first recovery path. Manual compaction by a normal player leaves a placed compact
base; breaking that base produces the drop. Ground drops are an accepted loss risk under
configured survival/despawn rules. Initial reward issuance uses pending delivery for
full/offline recipients and remains a separate workflow.

When a permitted explosion affects a compact base or deployed tower, subtract its applicable
explosion damage from current HP, clamp the result to zero, remove placed components, and
produce exactly one valid dropped tower item with the remaining HP. For example, 80 HP minus 30
explosion damage produces a 50 HP item. The blast drops the tower even if HP remains above zero.
Configure blast resistance or a non-negative explosion-damage multiplier per variant; exact
defaults and exposure calculation remain undecided. Do not let damage resistance alone silently
suppress the agreed blast-to-item transition.

In deployed state, immediately stop combat and lighting and start the expansion cooldown. A
compact tower retains its existing cooldown. Preserve identity and existing repair locks,
refreshing the repair cooling period when damage is taken; clear the deployment controller when
the tower becomes an item. Apply HP damage and the drop transition once through one
authoritative transaction so component callbacks cannot cause duplicate damage, items, or a
second zero-HP collapse. A zero-HP dropped item requires repair to the expansion threshold
before it can expand again.

The expanded-to-item transition produces the compact tower item directly; do not leave a compact
base placed in the world or require player recovery first. An already compact 1 x 1 x 1 base
affected by a Wither blast also drops as one item. Protect the resulting item from the
originating explosion so it is not immediately destroyed by that same event. Subsequent hazards
and despawning follow configured item policies.

Provide a tower-specific setting to respect explosion protection, enabled by default. When
applicable protection rules block the blast from affecting the tower, leave its HP, deployment
state, and timers unchanged and do not emit a drop. When allowed, apply the normal
damage-and-drop transaction. Administrators may explicitly disable this setting so the tower's
own blast handling ignores protection denial; this must not uncancel the world explosion or
change protection for unrelated blocks, entities, or players. The setting applies to creeper and
incidental Wither blasts alike. Document supported protection integrations. The exact exposure
test and resolution of mixed entity-damage/block-destruction decisions still require an
integration-level definition before implementation.

This is an environmental rule, not direct boss combat support. Wither spawn explosions must not
leave an intact tower that can be used as a Wither cage. Test actual blast exposure and any
tower collision/barrier representation, including towers overlapping existing terrain; do not
promise that this prevents every possible Wither trap. Towers still do not target bosses.

Ground pickup is not owner-only. Where external acquisition restrictions are configured, pickup
follows that external permission policy; the lantern itself permits player pickup regardless of
variant permission. Native placement/use still require the configured variant permission. Pickup
cannot confer control over any tower that remains placed. Without such external rules, pickup is
not permission-gated, but native lantern usage checks still apply. Preserve identity, HP, and
timers regardless of which eligible player collects it. Initially block hopper and
hopper-minecart collection of dropped tower items. The item remains on the ground for player
pickup or configured destruction/despawning; a blocked collection attempt must not change its
authoritative entity/generation. Manual storage in chests remains supported. Other automated
collection integrations are unsupported initially and must not silently bypass the lifecycle or
acquisition checks. Verify these integrations before exclusive rewards go live.

Persist dropped-item lifecycle and delivery transitions so concurrent recovery, explosions,
disconnects, unloads, or restarts cannot lose the item during the transaction or create a second
valid copy. After a successful ground drop, configured despawning or burning is intentional
loss, not failed delivery. Mark that representation expired in the authoritative record so
restoring an old inventory copy does not resurrect it automatically. Administrator recovery
requires deliberate validation.

Administrative recovery with explicit override drops exactly one valid tower item at the tower's
location, like breaking a normal block; do not insert it directly into the administrator's or
previous controller's inventory. Remove deployed components, clear the controller, preserve HP
and repair locks, and start the expansion cooldown if the tower was expanded. Recovery of a
compact base preserves its existing cooldown. Any player may pick up the item unless separately
configured external acquisition rules deny them; native placement/use eligibility remains
unchanged. The override must not silently replace the controller while the tower remains
expanded.

Define behaviour for explosions, pistons, fluids, environmental destruction, world resets, and
missing assets so these cannot strip identity, duplicate rewards, or leave invisible light
sources behind. Removing a lantern must clean up its light, display, interaction, and combat
components.

## 7. Resource Packs and Server Branding

The public plugin ships or publishes a default resource pack with generic, documented assets. It
must include usable default visuals for the bundled example variants. Administrator-defined
variants can map to existing assets or assets supplied by their own pack.

A server-specific pack may supply custom logos, tester artwork, dragon artwork, and other
branding. Overrides use the matching documented asset identifiers and paths. Assets not
overridden retain their default appearance when the base assets are present.

Players should receive the final intended appearance when the required packs have loaded. A
temporary generic appearance followed by a visible branding swap is not a product requirement.

Support these deployment approaches:

| Approach | Behaviour |
| :--- | :--- |
| Default pack | The plugin delivers its generic pack. Administrators can require acceptance. |
| Stacked packs | Keep the base pack and apply a server branding pack with higher priority for matching assets. Coordinate delivery order. |
| Combined server pack | Merge required base assets and server overrides into one server-managed pack; disable duplicate automatic delivery by the plugin. |

For combined-pack delivery, replacing the base pack with an override-only pack would lose any
base assets that were not copied across, so the combined pack must contain the complete required
asset set.

Names, descriptions, and reward messages belong in configuration. Models, logo artwork,
textures, and custom sound assets belong in the resource pack. Actual world lighting and defence
remain server-side behaviour; a visually bright model alone does not illuminate surrounding
blocks.

Document pack download URLs, hashes, supported client formats, load/failure handling, and asset
compatibility across updates. With required delivery enabled, players who decline cannot
continue joining; failed downloads need clear feedback. Server-managed delivery needs an
explicit readiness contract before custom-block use.

Java and Geyser/Bedrock clients are initial feasibility targets; compatibility requires
verification. Java pack assets alone do not provide equivalent Bedrock visuals. Plan
corresponding Bedrock assets/mappings and verify lighting, interaction, and defence on both
editions before claiming crossplay support.

## 8. Standalone Permissions and External Acquisition Policy

The lantern plugin enforces configured variant permission for placement, expansion, repairs,
controls, manual compaction, recovery, and normal breaking, alongside temporary-controller and
world-protection rules. A variant without a configured access permission is available to all
players under the other normal rules. Administrative override authorization remains separately
restricted.

Player pickup and storage withdrawal are not permission-gated by the lantern plugin. Without
separately configured acquisition rules, anyone may collect or store an item but cannot use it
without its configured permission. Native identity validation and hopper blocking remain
lifecycle safeguards. Obtaining an item never grants permission; permission never authorizes
item issuance.

Administrators may configure external generic acquisition rules using documented item/variant
identifiers. Such policy is independent: the lantern plugin has no runtime dependency,
detection, API call, configuration synchronization, or adapter for an acquisition-gating plugin.
External rules may address pickup, withdrawal, nested containers, or automation only where that
external system has verified support; no such integration support is promised here.

Trusted administration or a reward integration decides eligibility, grants any required
permissions separately, and initiates issuance. An optional dragon integration supplies
qualifying encounter outcomes without duplicating dragon management. Neither award history nor
acquisition eligibility establishes permanent ownership.

## 9. Public Plugin Documentation

The public plugin description should explicitly advertise customizable variants and
resource-pack support.

Provide:

- Installation, platform requirements, and optional integration instructions.
- A variant configuration reference and generic sample configurations.
- A resource-pack customization guide listing asset identifiers, paths, and supported formats.
- An example override pack demonstrating separate appearances for two variants.
- Instructions for default, stacked, and combined pack delivery.
- Reward issuance, permission setup, inventory overflow, and recovery documentation.
- Health, repair resource conversion, cooling periods, health indicators, expansion thresholds,
  and zero-HP recovery instructions.
- Targeting, temporary deployment control, claim/PvP integration, and performance settings.
- A compatibility table distinguishing tested Java and Bedrock support.

Server branding is an optional deployment choice. Decide which branding assets to publish and
their license separately from the plugin code and generic pack.

## 10. Proposed Implementation Stages

1. Settle the remaining public interfaces, initial platform/version targets, representation,
   reward policy, and release boundary.
2. Prototype compact placement, explicit full-volume deployment, private failure, persistent
   identity, recovery, cleanup, real light, and one zombie interaction. Verify Java and
   Bedrock/Geyser together before fixing rendering. Demonstrate acquisition/retaliation, exactly
   one HP debit per hit, and target release after compaction.
3. Add the durable lifecycle, operation journal, generation validation, crash reconciliation,
   and safe ground-drop handling before building reward distribution.
4. Add configurable variants, resource-pack modes and overrides, independent controls,
   world/fluid policies, HP repair escrow, cooling, expansion cooldown, and explicit
   administrative override.
5. Add bounded ordinary-mob defence, line of sight, incoming-damage routing, supported
   protection integration, and explosion damage-and-drop handling.
6. Add idempotent administrative/tester issuance, durable pending awards, and an optional
   dragon-outcome adapter after selecting its provider and eligibility rules.
7. Add optional PvP only after PvE is stable and its protection/provider tests pass. Folia
   remains a separate compatibility gate.
8. Complete compatibility/load tests and public configuration, recovery, pack, and integration
   documentation before a release.

These stages express dependency order, not assigned milestones or deadlines. Paper is the
proposed initial server platform. All server/client compatibility must be implemented and
verified before being advertised.

## 11. Decisions Still Open

- Supported Minecraft/server/client versions and the initial release boundary. Repository and
  GPL-3.0 license are selected.
- Final tester and dragon edition names, wording, logos, and models.
- Physical model buttons versus a control menu, including mobile usability.
- Deployed dimensions, orientation, clearance rules, compaction-control presentation, and
  unresolved environmental removal sources. Confirm the proposed policy that compact bases do
  not emit light or attack.
- Final submerged detection rule; dropped-item fire/lava survival defaults to enabled, normal
  despawning defaults to enabled, both configurable.
- Proposed world/dimension selectors and per-action restrictions.
- Tester award quantity and duplicate-award policy.
- Dragon award quantity, qualifying participants, repeat-kill policy, cooldowns, and supported
  encounter provider.
- Controller eligibility revocation and abandoned expanded-tower handling. Expanded switches and
  repairs are current-expander-only; temporary control clears on compaction and permanent
  ownership is removed.
- Pending awards for retired variants and reactivation/migration validation. Permanent variant
  deletion and compensation policy are deferred future work.
- Administrative override command/permission names. Override clears on logout/disconnect and is
  not restored after restart. Expander-only manual compaction can be overridden explicitly;
  independent protections require their own supported authorization. Permission alone must never
  activate bypass.
- Defence damage, range, attack interval, eligible incoming damage sources, and maximum HP
  default.
- Repair resources/rates, cooling duration, final-item rounding/payment behaviour, health
  indicator presentation, and maximum-HP configuration migration.
- Expansion cooldown duration per variant.
- Whether both editions remain functionally identical.
- Timing and scope of Folia, Bedrock, optional PvP, and other compatibility validation.
- Blast exposure calculation, resistance defaults, and mixed entity/block protection precedence
  within supported integrations. Respecting explosion protection defaults to enabled and is
  configurable. Interrupted ground-drop reconciliation. Explosion damage is deducted before
  break-and-drop; direct boss combat remains deferred.

## 12. Acceptance Checklist

- [ ] Another administrator can configure variants and branding without changing source code.
- [ ] Missing variant configuration retires its towers without stopping the server or deleting
      items; durable definitions support recovery after restart, and unloaded towers cannot
      resume retired operation.
- [ ] Tester and dragon award quantities are independently configurable.
- [ ] Light and defence controls operate independently and retain state after restart.
- [ ] Server defaults and per-variant capability overrides support both/light-only/defence-only
      variants; player controls cannot activate disabled features, including after reload or
      restart.
- [ ] Initial placement creates only a compact 1 x 1 x 1 base; full deployment requires an
      explicit button action.
- [ ] Deployment checks the entire structure's volume and permissions, including height, without
      overwriting blocks or trapping entities.
- [ ] Blocked deployment leaves the base unchanged and sends only the initiating player a
      configurable private explanation.
- [ ] Repeated clicks, overlapping deployments, and interrupted deployment cannot duplicate
      rewards or leave partial structures.
- [ ] Water and lava policies are independently configurable per variant, including placement,
      expansion, and submerged defence.
- [ ] Fluid changes suspend prohibited combat; removal and failed deployment preserve fluids
      without duplication or drainage.
- [ ] Proposed world/dimension rules distinguish individual worlds, respect denylist precedence,
      and suspend prohibited operation after configuration changes without destroying towers.
- [ ] The lantern provides real lighting, with level 15 available.
- [ ] Separate variants render with separate server-overridden appearances.
- [ ] Default, stacked, and combined resource-pack modes are documented and tested for the
      supported clients.
- [ ] Reward delivery survives full inventories, offline recipients, duplicate events, and
      interruption without unintended duplication or loss.
- [ ] Combat honours target exclusions, line of sight, deployment control, and supported
      regional protections.
- [ ] Supported mobs can target and damage the chosen tower representation; one hit/explosion
      applies damage once, and targeting is released after recovery/collapse or an unreachable
      timeout.
- [ ] Items and compact bases have no owner/controller. Successful expansion records the
      expander; only that player or explicit admin override can manually compact it. Direct
      expanded-tower breaking remains admin-override-only. Manual/automatic compaction clears
      the controller without clearing HP or timers. No trusted-player layer is introduced.
- [ ] Expanded light/defence controls and repair deposits require current-expander access or
      explicit admin override; authorization is rechecked at transaction commit, and override
      does not waive repair payment/cooling.
- [ ] Eligible players can normally break a compact base subject to world protections, producing
      one valid item while preserving HP and timers; expanded controller checks remain separate.
- [ ] Initial target configurations exclude bosses; no boss-specific retaliation or combat
      adapter is enabled. Dragon-kill rewards remain independent of this exclusion.
- [ ] Expansion cooldown blocks only expansion; compact placement and authorized breaking remain
      available while it runs.
- [ ] Remaining cooldown is reported privately, failed expansion does not extend it, and each
      tower retains its timer through recovery, transfer, and restart.
- [ ] Breaking or collapsing a deployed tower starts a fresh expansion cooldown even after
      prolonged use; moving an already compact base neither restarts nor clears it.
- [ ] New towers start at zero HP; maximum HP and expansion threshold are configurable per
      variant, with a default threshold of 50%.
- [ ] Resource deposits restore configured HP per item; expansion and operation spend no HP, and
      deployed towers continue below the expansion threshold until zero.
- [ ] Non-explosion damage reaching zero HP stops defence and collapses the tower to its
      recoverable 1 x 1 x 1 base, starting the expansion cooldown without deleting the reward or
      clearing the repair lock.
- [ ] Physical breaks and admin recovery always drop one valid transferable item; player pickup
      follows any external acquisition rules, and native placement requires configured variant
      permission. Items retain identity/HP/timers, survive and float in lava by default, and
      follow configurable burning/despawn rules.
- [ ] Permitted blasts, including Wither spawn blasts, remove affected tower components and emit
      exactly one item; deployed towers start their cooldown and cannot remain as an intact
      blast-proof cage.
- [ ] Explosion damage is deducted once with configurable variant resistance/multiplier, HP is
      clamped at zero, and the emitted item preserves remaining HP and repair cooling without
      triggering duplicate collapse/drop paths.
- [ ] Explosion protection is respected by default: a protected tower remains unchanged.
      Disabling this setting affects only tower blast handling and never uncancels
      damage/destruction for unrelated world objects or players.
- [ ] Attacking and taking damage enforce the repair cooling lock; rejected deposits consume
      nothing, and recovery, toggling, or restart cannot clear the lock.
- [ ] Breaking/replacing preserves health; repairs, damage, recovery, and concurrent inventory
      transactions cannot duplicate HP, resources, or towers.
- [ ] Inventory rollback and stale item copies cannot place an already placed or
      newer-generation tower; private rejection messages direct the player to contact an admin,
      while preserving the legitimate tower's HP and timers.
- [ ] Hoppers and hopper minecarts cannot collect dropped towers or alter their lifecycle on a
      rejected attempt; players can still manually deposit valid items into storage.
- [ ] Ground pickup/drop transitions rotate representation generations; a stale entity
      burning/despawning cannot expire a newer valid copy, and simultaneous identical-copy
      placement permits only one successful commit.
- [ ] Administrative permission alone bypasses nothing; an explicit per-admin command toggles
      override, rejection feedback offers it only to authorized players, and saved
      state/correctness checks remain protected.
- [ ] Admin override clears on logout/disconnect; reconnecting or restarting never silently
      restores it.
- [ ] Administrative recovery drops one item at the tower location, clears expanded control, and
      preserves HP/locks with the appropriate expanded-versus-compact cooldown behaviour.
- [ ] Normal players cannot directly break/recover expanded towers; the current expander must
      compact first. Explicit admin override permits direct expanded-tower break/recovery as one
      valid ground item.
- [ ] Repair UI explains resource rates, HP, expansion requirements, and cooldowns; unconsumed
      deposits are returned safely.
- [ ] Placement/removal and environmental events preserve identity and clean up all components.
- [ ] The lantern plugin runs independently and enforces native action permissions without any
      acquisition-gating plugin; permissionless variants use normal public access. No
      cross-plugin detection, adapter, or API call is required.
- [ ] Supported platforms and crossplay behaviour are verified under realistic multi-lantern
      load.

## References

- [Paper resource-pack delivery and stacking](https://docs.papermc.io/adventure/resource-pack/)
- [Paper persistent data containers and placement
  considerations](https://docs.papermc.io/paper/dev/pdc/)
- [Minecraft: glowstone and maximum light
  level](https://www.minecraft.net/en-us/article/block-week-glowstone)
