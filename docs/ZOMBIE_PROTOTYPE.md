# Native Zombie Feasibility Experiment

Task: #6. Live result record: 2026-11-17.
Status: The core native-zombie feasibility criteria of #6 and Gate G0 are demonstrated for the recorded one-zombie Java/Android Bedrock test pair. This is a scoped feasibility result, not a pass for every worksheet row or production compatibility.

The record below contains sanitized live observations supplied for the PR #42 harness, distinguished from unit coverage, implementation review and unexecuted cases. Production combat, protection-provider compatibility, repair locks, persistence, rewards and final balance remain excluded. The accepted [rendering evidence](RENDERING_EVIDENCE.md) supplies the #5 portion of G0; issue completion remains subject to the reviewed evidence record.

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
fallback suppresses drops/XP, cancels death and ends the test. The observed guard tests and remaining unexecuted cases are distinguished below; arbitrary administrative entity or direct-health edits remain unsupported.

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

## Recorded Live Software and Artifact Identity

The run used the software below. The planned pins in [the interface record](INTERFACES_AND_TEST_TARGETS.md) remain unchanged; testing a different build does not validate the planned build or every API/provider path.

| Component | Recorded test build | Evidence boundary |
| :--- | :--- | :--- |
| Server | Paper 26.3 build 211, `26.3-211-main@2d602d6` | Deviation from planned build 249; only the exercised prototype behavior is accepted. |
| Java runtime | OpenJDK 64-Bit Server VM `25.0.5.0.3+1-1-24.04.1-Ubuntu`, Linux | Recorded runtime, not a performance result. |
| Geyser | 2.12.1-SNAPSHOT development build 134, `git-DEV-e94bed1` | Deviation from planned official build 1302; observed protocol 2225 does not establish a broader supported-version range. |
| Floodgate | 2.2.5-SNAPSHOT build 141, `b141-81b65cc` | Recorded authentication-provider build. |
| Java client | Minecraft 26.3, Fabric Loader 0.20.1 | Executed operator client; unmodified-client coverage is not inferred. |
| Bedrock observer | Reported version `1.26.62.1`, Android tablet, touch; protocol 2225 | Version retained as reported; observer and input results apply to this tested pair. |

| Artifact | Verified identity / SHA-256 |
| :--- | :--- |
| Plugin | `InfernoLantern-0.0.0-SNAPSHOT-gd963eb9d215c.jar`, source `d963eb9d215c7e757adff48a920aec248cc766ec`; `0ffb64d984cc88c6bfad4d7d93b8bbf844d9183ba777b6c61c97d7ef31cca49f` |
| Java resource pack | `infernolantern-prototype-java.zip`; `2c21821cc2362cc1d9fc1d0e80aebb71de3e9c1e7c07a01b9899573451c1b84d` |
| Bedrock resource pack | `infernolantern-prototype-bedrock.mcpack`; `7845402cacff9fdac61384842368c5bd369591f6a5560dfe1b1f25dedd34a642` |
| Geyser mappings | `infernolantern-prototype-mappings.json`; `a3962bc5be25e94a2ef38b63c7e34d8eba49e5078f11b33043d78db1ad996119` |

The supplied artifacts matched their recorded digests. No raw configuration, environment addresses, player identity, private world coordinates or identifying screenshots are retained in this record.

## Observed Core Path and Results

One actual proxy-attributed outgoing hit reduced the ordinary adult zombie's health from 20.0 to 19.0. `outgoing hits` increased by one, followed by one accepted native `TARGET_ATTACKED_ENTITY` acquisition. Ambient proxy-target attempts were rejected. The zombie naturally reached the preserved barrier shaft and physically swung at the living proxy; no forced acquisition, collision removal, outside-volume proxy placement or synthetic incoming-hit timer was used. The outgoing twelve-point FLAME effect was reported visible on Java and the Bedrock observer.

On Normal difficulty, 14 positive uncancelled native melee events produced 14 authoritative HP updates. Each event supplied 3.0 pre-zero final damage to the disposable 40.0 HP pool: `40 -> 37 -> ... -> 1 -> 0`. The last update clamps the remaining 1.0 HP to zero; this is one update per hit, not one HP of damage per hit and not negative HP. Proxy native health remained 20.0, no-hit observation windows showed no HP drift, and zero experiment HP released the target/proxy and compacted the fixture to its base barrier.

| Worksheet area | Supplied observation | Result / boundary |
| :--- | :--- | :--- |
| Gates and rendering baseline | Combat-disabled Defence cannot start; independent Light works. With both gates enabled, fixture volume and menu remain usable on Java and tested Bedrock touch mode. | Observed pass for the reported gate/menu cases; unchanged rendering limits still apply. |
| Actual hit, retaliation and reachable melee | One outgoing hit, one native retaliation acquisition, natural approach and physical melee with both collision barriers retained. | Observed pass for one ordinary adult zombie and this representation. |
| HP accounting and zero-HP compaction | 14 melee events and 14 HP updates; 3.0 damage per event before clamping; HP reaches zero, proxy is removed and only the compact base remains. Failure and death-attempt counters remain zero. | Observed pass; experiment values are not production balance. |
| Outgoing denial | Experiment stopped with `outgoing-denied-or-no-positive-damage`, no outgoing-hit increment or beam, and proxy/target release. | Observed denial/no-positive-damage path. That reason alone does not prove cancellation by a particular protection provider. |
| Incoming protection cancellation | Contract tests cover cancelled incoming events and event identity deduplication. No live third-party claim/provider cancellation case was configured. | Unit coverage only; live provider cancellation remains pending for #19/#20. No provider compatibility claim. |
| Manual release and session isolation | Defence off, compaction and removal release the target and remove the living proxy; rapid re-enable starts fresh 40.0 experiment HP without carried-over debits. | Observed pass for those paths. Preserving a newer external zombie target was contract-tested, not separately demonstrated live. |
| Combat-specific unload/load and shutdown | A clean shutdown was reported. `ChunkUnloadEvent` and stale-proxy entity-load hooks were audited. | Do not count source review as a live active-combat-proxy unload/reload pass. That edge and newer-external-target preservation remain production combat lifecycle follow-ups in #19; #5's rendering unload/restart result is separate. |
| Range, target loss and no-hit timeout | Out-of-range and target death/removal ended sessions; the no-hit case reported `unreachable-or-no-native-melee` after the 200-tick bound. | Observed release outcomes; no general pathfinding or mob support inferred. |
| Maximum session age | `experiment-expired` was reported at the 1200-tick limit. | Expiry outcome reported; the exact no-attack setup was not supplied. The code increments age while waiting for a target, while the 200-tick no-hit path applies after a target is selected. Do not equate expiry with 60 seconds of an engaged, unreachable zombie. |
| Proxy safety attempts | Player melee, arrow, fire/suffocation, potion, displacement/trading and a server-issued damage attempt were reported blocked/zeroed with proxy health 20.0, no extra debit, zero drops/XP and zero failures/death attempts. | Observed pass for the listed attempts. The command/damage mechanism is not a proof of immunity to arbitrary administrative removal or direct-health edits; conversion and death-fallback coverage are not inferred from zero counters. |
| Final cleanup | Zero active fixtures, zero cleanup cells and zero living proxies; support unchanged, tracked cells restored to air and manifest reduced to the empty 8-byte ILP1 header. Both gates were disabled and the instance was cleanly stopped. | Observed final state, not proof of abrupt-crash or power-loss durability. No subsequent disabled-gate boot is inferred from a stopped instance. |

## Scoped Acceptance and Follow-ups

- [x] One real outgoing hit precedes native zombie retaliation, approach and melee within the selected representation.
- [x] One authoritative HP update occurs per accepted native melee event, with observed damage and zero-HP clamping.
- [x] Compaction/removal and observed release cases clear the target/proxy; no independent proxy death, loot or XP occurred in the recorded run.
- [x] Java and the recorded Bedrock observer see sensible model/beam behavior.
- [ ] Live incoming third-party protection cancellation and provider/event-order compatibility remain untested; track in #19/#20.
- [ ] Active-combat-proxy unload/reload, a newer external target and additional environmental/conversion/death-fallback edges need their own live observations; track production combat lifecycle in #19.

Together with [#5's rendering evidence](RENDERING_EVIDENCE.md), these observations support core #6 and Gate G0 feasibility acceptance for the exact tested pair. They do not complete every row of the supplementary worksheet or certify production combat/protection. G1-G5, production persistence/recovery, repair, rewards, final balance, wider mob support and production UI remain pending.

## Generic Live Worksheet

Record exact plugin commit/JAR digest, Paper, Java, Geyser/Floodgate if present, Java client
and actual Android Bedrock build, pack/mapping digests and test result. Reuse the accepted
rendering preflight and rollback order. Keep infrastructure names, paths, addresses,
players, coordinates and full logs private. This is the reusable procedure; the result table above is authoritative for what the supplied run actually observed, reviewed or left unexecuted.

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

Retain observations and counter deltas for each executed row, including failure/unsupported cases. Automated contract tests cover outgoing/incoming cancellation, duplicate identities, distinct events, closed sessions, clamping and preservation of newer external targets. Compilation verifies the exact cached pinned Paper API; neither is a substitute for the live native-path or client observations recorded above. Pending supplemental cases stay pending after the scoped #6/G0 feasibility result.
