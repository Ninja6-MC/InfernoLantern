# Bounded Pickup Cancellation Acceptance Helper

Task #9 is still open. This kit prepares a separate default-disabled listener for explicit maintainer review and installation approval. Building or merging it does not authorize deployment, arming, a gate pass or a release. No live installation or server test has been performed for this helper.

## Artifact and behavior boundary

`./gradlew clean itemAcceptanceKit --offline --no-daemon` builds a checked core JAR plus a separate `InfernoLanternItemAcceptance` JAR in `build/item-acceptance-kit`, with exact source metadata, scenario examples and `SHA256SUMS`. The sole production artifact stays in `build/libs`; helper classes, descriptor, configuration and data are absent from that JAR. The helper has no commands, permission nodes, database access, inventory mutation, world mutation or item issuance. It only observes and, while explicitly armed, cancels selected pickup events at LOW priority.

This is a **new core candidate**, because a read-only `PickupValidationContext` now identifies the existing synthetic check by its exact event instance and current thread. It distinguishes PRE_INTENT and EFFECT without changing the event class, listeners, cancellation result, readiness or journal behavior. Context restores in `finally`, including nested dispatch and exceptions. The helper requires its matching core version. Do not pair this helper with the previous `77e1387` JAR; preserve that tested artifact and all earlier evidence.

The listener defaults to `enabled: false` and registers no event handlers while unarmed. Its configuration chooses exactly one of `control`, `original`, `pre-intent` or `effect`. Before registering handlers, it saves `enabled: false` and verifies that the arm flag was consumed. A clean restart requires deliberate re-arming. Invalid bounds, missing context or mismatched core version leave it unarmed. No reload command is provided; do not use plugin hot reload.

The scope is the first valid one-count schema-1 `prototype.item-fixture` token encountered in a player pickup event after arming. Scope is pinned to that tower identity only in memory; it is never printed or persisted. The helper does not scan inventories, worlds, saved players or database files. Other fixtures and ordinary items are untouched. It rejects partial, malformed, stacked or unavailable delivery-marked identities through the normal identity reader.

The TTL is 10–300 seconds and the callback budget is 1–4096 matching events. The default limits are 120 seconds/512 callbacks; examples use 300 seconds/4096 callbacks. Expiry is enforced by elapsed monotonic time as events arrive, plus scheduled unarming. Once the selected boundary is denied, later native pickup attempts for the same fixture are denied until expiry/cap/disable. This prevents an immediate retry from being mistaken for a successful denied pickup. After unarming, normal plugin behavior resumes and later pickups are outside the scenario.

## Approval and isolated setup

Before installation, obtain approval for the exact checked core/helper pair, one separate disposable test setup and the four bounded scenarios. Preserve the existing store containing two RECONCILING operations, its ground records, unavailable items and related evidence. Those pending operations may intentionally prevent the existing harness from becoming ready. **Do not delete, rename, replace or reset its database to enable testing.** Use a separately authorized disposable instance/data directory with a distinct fresh test store and isolated disposable identities. If this setup is unavailable, stop and request that capability; this brief does not authorize modifying the quarantined store.

The EFFECT scenario intentionally leaves a pending operation. Each scenario therefore needs an independent clean disposable setup, or an explicitly approved baseline clone prepared before any identities are issued. Preserve every scenario result independently. Do not roll back a used store to replay identities, clear pending operations, edit rows or recycle fixture generations. Do not copy a running SQLite database. Inspect the plugin-owned database only after a clean server stop using read-only tooling, never another in-process native SQLite driver.

Verify all SHA256SUMS entries, exact deployed core and helper digests, generic Paper/Java/bridge/client versions and the actual `validated SQLite 3.54.1` readiness diagnostic. Enable the existing approved item creation gates according to `ITEM_LIVE_TEST_BRIEF.md`. The helper does not change those gates or bypass unavailable storage. Install the helper disabled first and confirm `Acceptance helper unarmed; no listeners registered.` No resource pack or mapping change is needed for the generic PAPER fixture.

## Four independent scenarios

For each run, configure the helper while the server is stopped using one scenario example, explicitly arm it and perform a clean start. Record the helper's armed scenario/limits; confirm the persisted `enabled` flag is already false. Enter the isolated test area promptly, issue one fixture through the already approved `/ilantern prototype item` interface and perform a normal drop. Keep unrelated fixtures and players outside the test. Capture the authoritative current GROUND generation/entity and no-pending baseline privately; publish only synthetic labels and aggregate results.

| Scenario | Expected selected boundary | Required observation before unarming |
| :--- | :--- | :--- |
| `control` | No helper cancellation | Native event plus PRE_INTENT and EFFECT counters observed; actual pickup reaches PORTABLE with rotated generation and no current ground record. Confirm the native-use permission is absent. The helper never un-cancels another listener's decision. |
| `original` | Original native event at LOW | Native cancellation prevents admission: same ground entity/generation, no usable inventory copy, no new pickup intent. No PRE_INTENT or EFFECT check should execute for that denied attempt. |
| `pre-intent` | Exact synthetic PRE_INTENT event | Native event reaches the adapter; PRE_INTENT cancellation rejects before intent. Same ground row/entity/generation and no usable inventory copy or pickup journal. EFFECT count stays zero. |
| `effect` | Exact synthetic EFFECT event after admitted intent | PRE_INTENT is allowed; EFFECT is cancelled. No physical pickup or usable inventory copy. The source remains and the admitted operation is quarantined/unavailable according to the actual lifecycle result. Preserve the pending journal for #11; never claim a clean rollback or clear it. |

Record the selected boundary cancellation log, MONITOR result for that same boundary, generic counters and actual item/database outcome. `final-cancelled` on an ORIGINAL event is **not** proof the helper denied it: InfernoLantern intentionally cancels native pickup even when its journaled pickup later succeeds. Use the helper's own boundary cancellation count plus physical/durable observations, not the event cancellation bit alone. Other installed listeners can also deny a control; report that as a failed/blocked control rather than force un-cancelling it.

Move the tester away from pickup range immediately after the expected denial and take the scenario observations before TTL/event-cap/disable. Observe the explicit unarmed log and counters afterwards. A later ordinary pickup after expiry is outside the scenario and cannot validate or invalidate its earlier denial without timing evidence. If timeout/cap is reached before the required observation, report PENDING/INCONCLUSIVE rather than PASS. Do not keep the server running indefinitely with the helper armed.

A successful synthetic-listener test demonstrates the adapter's external-cancellation boundary, **not** GriefPrevention-specific compatibility or a claim that any untested provider cancels these items. Keep actual provider name/version and supported real denial contracts separate from this helper evidence.

## Remaining concurrency and recovery boundaries

This helper does not synchronize competing drops, delay the database, inject packets, replay removed-entity events, kill a JVM or inject delivery/activation faults. Standard owner-thread packet handling does not prove that asynchronous database claims cannot overlap; existing service race tests and the live sequential stale-copy observation remain distinct evidence. Deterministic live contention is still PENDING and needs a separately reviewed barrier at the normal adapter admission boundary, using actual exact-copy sources and owner-thread callbacks. No threaded Bukkit access or synthetic packet client is requested by this kit.

Native placement remains #10; interrupted independent save reconciliation remains #11. Report unsupported replay/fault boundaries as pending. Do not mark #9/G1 accepted from these listener tests or reclassify the earlier quarantined records as clean teardown.

## Teardown and evidence

Record authoritative state privately before a clean stop, preserve the scenario store and disable creation gates. The EFFECT run can legitimately retain a pending operation and current ground evidence. Report that as preserved quarantine. Remove the helper JAR from the test instance only after a clean stop and retain the disabled helper configuration and generic evidence as needed. No helper shutdown path changes item, entity or database state; removing it cannot repair or erase quarantined work. The rendering `prototype cleanup` command is unrelated to item teardown.

Publish only generic exact versions, source/artifact digests, synthetic fixture labels, counters, actions and bounded outcomes. Omit real coordinates, player/server/world identifiers, private paths, host details and raw logs/configuration/screenshots. Report PASS/FAIL/PARTIAL/PENDING per boundary and keep live, code-inspection and automated evidence separate.
