# Native Zombie Feasibility Experiment

This is executable preparation for #6. No server or client observations are recorded
here. #6 remains open and full G0 remains open until the end-to-end worksheet passes.
Production combat, repair locks, persistence, rewards and final balance are excluded.
The accepted rendering evidence remains [unchanged](RENDERING_EVIDENCE.md).

## Candidate and Instrumentation

Enable both `prototype.enabled` and `prototype.combat-experiment.enabled` in the generated
plugin configuration, then restart. Both default to false. Use only an operator in a
disposable area and the existing `/ilantern prototype` menu. Expand, then enable Defence.
Light remains independent. No new commands, permission nodes, packs or artwork are added.
Only one combat fixture can run at a time; this is a prototype-only limit and does
not restrict ordinary rendering fixtures. Defence off releases the experiment; enabling
it again starts fresh disposable HP. This is not a production healing mechanism.

The visual ArmorStand stays separate from the managed living target. An adult invisible,
silent Villager is centred at base Y+1. Its ordinary 0.6-wide, 1.95-high body is intended
to remain inside the declared 1 x 3 x 1 clearance. Its feet intersect the existing upper
barrier and its head extends into the LIGHT voxel. Both base/Y+1 barriers are retained.
AI, gravity and entity collision are disabled on the proxy, not on the zombie. No proxy
is moved outside the volume and no barrier is removed to obtain a hit. Displacement or
native health loss stops the experiment as a failure. Suffocation, burning, knockback,
potion effects, conversion, interaction/trading and normal damage are guarded; the death
fallback suppresses drops/XP, cancels death and ends the test. These guards need live
verification; arbitrary administrative entity edits are unsupported.

A bounded scan every ten ticks selects one nearby adult ordinary zombie. The proxy
must see it before attempting one real `damage(1, proxy)` call. Only an uncancelled,
positive final outgoing damage event increments `outgoing hits` and emits a twelve-point
FLAME beam. No cosmetic fire or damage-over-time is applied. The zombie's vanilla AI
must then emit an accepted `TARGET_ATTACKED_ENTITY` target event and physically attack.
Ambient villager acquisition is rejected before that event; unrelated mobs cannot
acquire this test proxy. No custom goals, forced acquisition using `setTarget`, synthetic
incoming damage events or timers simulating hits are used.

All numbers are **experiment values**, not selected gameplay defaults: 40 HP, outgoing
1 damage, six-block acquisition/range, ten-tick polling, 200 ticks without a native hit,
and 1200 ticks maximum session age. The scan holds one target without reacquisition.
Removal, death, range loss, another target, disabled configuration, expiry or unreachable
timeout releases the session. Release clears a zombie target only when it still equals
the owned proxy; no goals or unrelated targets are removed. Zero experiment HP queues
compaction bound to the same fixture and deployment generation. Old health/event
callbacks cannot debit a replacement session.

At HIGHEST priority the proxy's damage is captured and native damage is set to zero.
MONITOR observes the final cancellation state and debits experiment HP once per positive
leased-zombie `ENTITY_ATTACK` event identity. It never uncancels an event. Other damage
sources do not debit HP. Protections cancelling at any normal priority deny the debit;
protection providers inspecting the event after the guard see zero native proxy damage.
This is not a provider compatibility claim. Plugins modifying damage at MONITOR or
external direct health/entity edits require a separate compatibility investigation.

`/ilantern status` uses the existing admin inspection permission and reports only generic
aggregate counts: active proxy, current experiment HP, native melee events, denied events,
HP debits, actual outgoing hits, accepted native retaliation targets, denied proxy target
attempts, last accepted melee damage, releases, failures, proxy death attempts,
suppressed drop stacks and suppressed XP. `native retaliation targets` specifically counts accepted
`TARGET_ATTACKED_ENTITY` events after the outgoing hit; other acquisition reasons are
rejected until then. Counters last for the plugin process; record before/after differences.
No names, UUIDs, coordinates or world identifiers are logged.

Compaction, removal, Defence off, chunk unload and shutdown remove the owned proxy and
release its target. Startup/entity-load recovery removes stale tagged proxies, including
when creation is disabled. Proxies are nonpersistent. The existing block cleanup manifest
and conflict quarantine remain responsible for barrier/LIGHT recovery.

## Generic Live Worksheet

Record exact plugin commit/JAR digest, Paper, Java, Geyser/Floodgate if present, Java client
and actual Android Bedrock build, pack/mapping digests and test result. Reuse the accepted
rendering preflight and rollback order. Keep infrastructure names, paths, addresses,
players, coordinates and full logs private. Every row below is currently **pending**.

1. With both gates false, verify no combat/proxy can start. With rendering enabled and
   combat disabled, verify Defence cannot start and Light still operates. Enable both
   gates, restart, create one fixture and expand. Confirm both barriers and LIGHT remain
   at their accepted positions. Confirm clear access to the menu from both clients.
2. Use a flat dry disposable area with stable lighting and one ordinary adult zombie,
   avoiding survival players, villagers, golems or unrelated targets. Record difficulty
   and baseline counters. Place the zombie within six blocks without teleporting it
   through the shaft or enclosing it against the proxy. Enable Defence. Verify one actual
   outgoing damage reaction, beam visible to Java and Bedrock, and `outgoing hits +1`.
3. Verify `native retaliation targets +1` specifically follows that accepted hit. Ambient
   villager acquisition alone is a failure of the test, even if a zombie happens to stand
   near the tower. Check that the zombie walks to the retained shaft and physically
   swings/hits without assistance. If LOS, native target or physical melee fails, record
   the reason and stop this candidate. Revisit representation feasibility before broad
   combat; do not weaken collision or move the proxy outside the volume.
4. Observe several distinct native melee events. For each positive uncancelled event,
   verify exactly one added event and one HP debit, with HP lowered by the event's
   pre-zero final damage. Confirm actual proxy native health stays unchanged. Compare
   isolated swings/counter deltas; unit tests do not prove this. No-hit periods must not
   change HP. Both clients should see sensible beam/damage behavior and stable models.
5. In a disposable protection test, cancel outgoing damage before and after this listener's
   normal priority: no outgoing-hit increment, no beam, no remaining proxy or target.
   Separately cancel incoming melee at an earlier priority and after HIGHEST: the event
   counter can rise but denied rises, debit/HP remain unchanged. Exercise legitimate
   distinct swings as well as duplicate delivery in a controlled local harness. Record
   provider/build and sanitized outcome; no general provider support is inferred.
6. Exercise Defence off, compaction, removal, cleanup, actual chunk unload/reload and clean
   restart separately while a zombie is attacking. Confirm target release, no living proxy
   or visual artifacts and expected block recovery. Re-enable quickly and ensure an old
   session cannot debit or compact the new one. Set a newer unrelated zombie target in a
   controlled harness before release and confirm it survives cleanup. Check unrelated
   mobs cannot acquire this proxy and unrelated targets/goals remain intact.
7. Move the zombie out of range, kill/remove it, obstruct access and wait for the bounded
   no-hit timeout; separately wait for session expiry. Each ends Defence and removes the
   proxy. Exhaust HP by real swings and verify automatic compaction, retaining the compact
   barrier only. Do not manufacture incoming damage using a timer.
8. Attempt player attacks, projectiles, burning, suffocation, potion effects, displacement,
   conversion and interaction/trading. Confirm no independent proxy death, loot, XP or
   extra damage debit. A failure counter, native health change or displacement is a failed
   candidate needing investigation. Confirm no proxy rewards even at lethal damage.
9. Run `/ilantern prototype cleanup`; active fixtures, proxy and cleanup cells must reach
   zero, with no zombie targeting a removed proxy. Disable both gates and restart with
   the plugin still available for recovery, then follow the existing rollback order.

Retain observations and counter deltas for each row, including failure/unsupported cases.
Automated contract tests cover outgoing/incoming cancellation, duplicate identities,
distinct events, closed sessions, clamping and preservation of newer external targets only. Compilation verifies the exact cached pinned Paper API;
neither establishes native pathfinding, event ordering in a running server, physical reach,
proxy environmental safety or Bedrock visibility. Only recorded live observations can
complete #6 and full G0.
