# Resource Repair and Confirmation

Task #17 adds a revision-bound quote, owner-checked payment, durable escrow and
repair cooling. The Paper storage-slot adapter and native eligibility checks
are available to the production router. The repository entry point does not
register a repair menu, deposit or confirmation listeners, or a repair startup
handler. Java and Bedrock gestures and faulted native saves remain acceptance work.

## Configuration and Quote

Each variant may specify `repair.resources`, a map from canonical resource
keys to positive finite HP per item, and `repair.cooling-ms`, a non-negative
whole duration. Both are required when `repair` is present. The native
definition validator must check resource keys against the item registry and
the server's resource policy. Existing variants without `repair` remain
readable and cannot admit payment. The durable catalog codec writes version 2
when repair is configured and reads versions 1 and 2. Existing version-1
payloads are unchanged. An older binary cannot read a version-2 catalog; keep
data backups paired with the corresponding build. There is no SQLite schema
migration or chosen repair material, rate, or duration.

One quote addresses an exact tower row and deployment, catalog revision,
viewer connection, source slot, and serialized stack. The caller supplies the
selected number of whole items. The quote carries current and maximum HP,
expansion threshold and shortfall, configured rates, and the exact cost and
health gain. Decimal arithmetic caps the resulting health at the
authoritative maximum. If fewer items reach the same representable stored HP,
the quote charges only that smaller count. The expansion shortfall uses the
same decimal threshold as expansion eligibility. A quote distinguishes a
partial final item so the caller can show the actual benefit
before confirmation. Explicit confirmation consumes the quoted count once.
Full health, no representable gain, incompatible resource, stale revisions or
a different source stack cannot charge a player. An expanded tower at zero HP
must resolve the defeat transition before any repair.

The inventory port starts from a selected player storage slot. It retains all
unspent items in that slot. The router must protect the exact slot through
confirmation, debit, and any authorized restoration. This requires covering
native transfer, drop, drag, swap, creative, death, disconnect, and external
mutation paths before enabling the native menu. The adapter excludes marked
lantern items from resource payment. Closing an undecided quote releases its
slot lease without moving anything. A rejected or closed quote does not need
a separate refund for unspent resources. Exact active-debit restoration does
not need a free inventory slot.

## Transaction and Cooling

1. Owner callbacks validate current viewer and permissions, target, reach,
   protection, slot continuity, and inactive combat. The quote's display and
   confirm callback must be private to the captured viewer. Administrative
   override may waive expanded controller access only.
2. SQLite atomically reserves the exact ready tower row and writes the repair
   intent plus RESERVED escrow. The escrow retains the contributor, source
   session and slot, resource key and count, and exact source/debit/remainder
   bytes. A second viewer of the same tower or another unsettled repair by
   that contributor cannot admit payment.
3. The owner revalidates authorization and debits only the quoted portion
   from the exact source. The operation records an observed debit and then
   rechecks current permissions, protection, inactivity and remainder.
   Session, override, slot, idle-combat and menu revocation fence the final
   SQLite commit even when the writer is delayed.
4. SQLite completes the operation, marks payment COMMITTED and HP APPLIED,
   and updates the tower in one transaction. It preserves controller,
   deployment and item generations, switches and both saved timers.
   Receiving a duplicate callback does not authorize another debit.
5. A rejected commit with a proved outcome may restore the original slot
   only with original connection, uninterrupted lease and exact remainder.
   If debit never started, no inventory write is needed. Restoration is
   marked REFUNDED/NOT_APPLIED and leaves HP unchanged. If lease or save
   continuity is uncertain, preserve the pending operation and escrow for
   review. There is no drop, automatic item delivery or blind refund.

The native authorizer must report idle combat and provide a revocable idle
permit. A valid attack attempt invalidates that permit as soon as the
inactivity observation is stale, including between attack ticks. The
authoritative cooling update uses the variant's duration and keeps the later
of the saved expiry and the current hit's expiry. A deadline beyond the
representable range saturates at that range's endpoint, so an authorized hit
still settles. Once repair owns the tower,
damage and attack-cooling changes cannot mutate that row until settlement.
Native callers must deny a conflicting damage effect before admission or
defer its exact authorized receipt until the same deployment can be
revalidated. A stale revision or duplicate callback must not repeat a hit.
The actual damaging-attack router supplies source and provider authorization
and coordinates which hits happened. `recordRepairCooling` is the durable
cooling boundary, not an instruction to refresh cooling for target
acquisition, a toggle, an idle tick, or a canceled attack. Positive incoming
damage through the health service uses the configured cooling duration.
Compaction, transfer and restart preserve the saved deadline.

## Recovery Scope and Acceptance

SQLite settlement and a Paper player save are independent. The built-in
reconciliation port does not settle repair recipes automatically. For a
pending escrow, a trusted matched save showing the exact original slot and
unmodified HP can establish BEFORE for an authorized rollback; a matched
save proving the exact debit, tower, contributor and deployment can establish
AFTER for reviewed reconciliation. An empty or similar-looking slot alone
cannot prove either outcome. Mismatched save sets, unknown custody, lost
connection proof or altered bytes remain administrative. Maintain terminal
receipt provenance and never repeat its debit or refund.

The local regressions cover decimal and partial quotes, simultaneous
viewers, cross-tower payer contention, revoked queued commits, exact slot
restoration, failed database settlement, interrupted state after reopen,
configured cooling, and use of the Paper adapter and access policy with
strict API doubles. Production routing still needs Java/Bedrock gesture and
close tests, real protection integration, and matched player-save failures
in a disposable environment. Do not infer compatibility from local tests.
