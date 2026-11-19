# Tower Health and Defeat

Task #16 supplies managed-catalog HP changes, an owner-authorized non-explosion damage
service, journaled defeat compaction, and a private target-health view port. Native event
routing, the display renderer, and Paper/Java/Bedrock acceptance are pending. The
existing prototype keeps its separate fixture behavior.

## Damage and defeat

A hit binds one ready expanded tower's world, position, row version, item generation, and
deployment revision. On the owner thread the caller proves that the source, current
components, current policy and protection rules allow damage. The caller supplies a
finite positive damage amount and the configured repair cooling duration. Damage rules,
combat integration and balance values are selected with the corresponding modules.

SQLite rechecks the current catalog revision, active variant, and exact unlocked tower
row. An optional revocable commit permit fences queued admissions. The write clamps HP at
zero and extends the repair lock to the later of its saved expiry and this hit's cooling
expiry. Damage never spends either switch choice or an expansion cooldown.
A deployed tower with positive HP continues to operate under its current capability
rules even when its HP would be too low for a fresh expansion.

Zero HP is durable before world compaction begins. Current capability checks suppress
defence and lighting at zero. The system-effect adapter reuses the structure journal
and current owner-thread protection and integrity checks. The verified expanded-to-compact
commit retains the identity and item generation, clears the controller, increments the
deployment revision and starts the configured expansion cooldown. It preserves zero HP,
the repair lock, and both player switch choices. It releases the upper footprint and
restores the original terrain at the owned lighting cell. The compact base has one voxel
and no light or defence. System defeat carries no player authority and does not grant a
permission or an override.

A failed attempt with proved restoration leaves a ready expanded row at zero HP. It
remains inactive and retains its previous expansion timestamp. On restart, a native
observer can read that exact row and use `compactDefeated` with the current published
catalog, a fresh operation ID, and current physical permission. Only a successful exit
starts the new cooldown. Pending or uncertain rows require #11 reconciliation before
another transition. Physical restoration requires its own authorization and exact ownership.
If a proved rollback restores an old emitting block, native light reconciliation must
remove it when authorized. Approved startup and chunk-load wiring must re-evaluate
zero-HP rows and stop stale service callbacks against the current row.

## Expansion and transfer

Only an explicit eligible expansion action can expand the compact base. Current positive
HP must meet the current percentage threshold; the saved cooldown must have expired unless
the approved admin override applies. Native eligibility, protection, and placement
conditions still apply. The requester receives private remaining-time feedback when
otherwise eligible and prevented by the timer.

A verified exit from the expanded form starts a fresh per-tower cooldown. A configured
duration of zero writes an immediate eligibility timestamp of zero. Failed transitions
preserve the previous timestamp. Successful expansion, compact breaking, placement, and
item transfer preserve the stored expansion timestamp and the separate repair lock.
Deadlines are based on elapsed wall time, including offline time. There is no expansion
action on timer expiry. A production caller supplies the current wall clock and uses
the chosen feedback presentation.

## Maximum HP and health visibility

Changing a variant maximum preserves each live tower's current HP up to the new maximum.
Lowering the cap clamps health only above that cap; raising it never heals a tower.
Publication advances affected row versions in the same transaction as the new catalog,
so prior requests cannot write back pre-publication HP. Active journal claims fence the
change. Terminal LOST history retains its prior values. New issue creation for managed
variants requires the current published maximum. The new maximum applies to future
expansion eligibility. Existing expanded towers remain subject to the normal zero-HP
boundary and current access/capability policies.

The targeted indicator is private to the player currently targeting a deployed tower.
`TargetHealth` verifies an exact ready deployment snapshot and asks the owner-bound
audience to confirm the live viewer session and exact current target component before
rendering current/maximum HP to that player. The audience must clear on target loss,
disconnect or world change. The native target resolver, rendering style, and client
behavior remain integration work.

## Verification

Local tests use temporary SQLite data, a controllable owner queue, and strict Paper API
doubles. They cover below-threshold operation, zero-HP compensation and retry after
reopen, quarantine on unproved effects, protected light restoration, timer persistence,
private targeting, and atomic cap changes including a forced publication failure.
Native source, lifecycle, cross-client targeting, collision, and crash acceptance remain
pending. Use a separately authorized environment with matching stopped-state saves.
