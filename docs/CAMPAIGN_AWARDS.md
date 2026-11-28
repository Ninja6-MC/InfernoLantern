# Administrative Campaign Awards

The administrative interface is `/infernolantern award <campaign> <player>`, also available
through `/ilantern`. It uses the existing `infernolantern.admin.award` permission. Recipient
lookup accepts an exact online or locally cached player name and records the resolved UUID.
It does not query a remote service or route an award to a partial name match. An uncached
offline recipient can become known by joining before the administrator submits the award.

## Definition and retries

The shared runtime supplies an explicit mapping of campaign IDs to variant and quantity.
The following example illustrates the mapping accepted by `CampaignAwards.parse`. It
does not install a campaign or add a production configuration loader.

```yaml
welcome:
  variant: ember
  quantity: 2
```

A campaign ID and recipient UUID identify one award. The first committed registration
captures variant and quantity. Revising or removing the campaign does not replace that
receipt. Repeat the same command for a pending receipt; use a new campaign ID for an
additional award. Missing or unknown managed variants cannot supply new items.

An offline recipient or a full inventory stays pending without allocating a tower.
A removed catalog variant places its awards on a durable catalog-owned hold. Restoring
the same managed variant lifts that hold. Delivery remains bound to current catalog and
item readiness even for a previously registered award. Bounded pending queries also let
a future shared runtime resume a known recipient on a fresh inventory opportunity.
Initial delivery uses empty storage slots only. It does not spill to the ground.
Existing ground recovery, gifting, and permission assignments use their own policies.
Awarding or receiving the item confers no permissions.

## Delivery and interruption

Each award allocates and journals at most one current tower at a time. The fresh
transaction links a dormant zero-HP, generation-zero identity, captures the exact
recipient connection and slot proof, and reserves the attempt. A proved no-write outcome
rolls back the attempt and retains the dormant identity for a retry. A retry never creates
a second identity for that position in the award.

The Paper inventory port writes a single unavailable item with its operation marker and
checks the exact stored bytes. Only the winning current-catalog transaction can commit
generation one and authorize a fresh activation. The owner rechecks the connection,
readiness, exact slot bytes and durable tower observation before replacing the stage with
the prepared active item. Every new tower has zero HP and no controller. The existing
item identity codec supplies one non-stackable representation.

Live activation leaves the receipt pending. It does not acknowledge a save or allocate the
next item. A runtime binding must prove that its native fence protects each exact stage
and active delivery from use, slot movement, transfer, death loss and dropping while the
receipt remains unresolved. The binding must restore that protection from outstanding
receipts before admitting interactions after reconnect or restart. A missing fence prevents
stage and activation. An empty or changed slot cannot serve as proof of delivery. The
repository also prevents ordinary item mutations and tower writes while the outstanding
delivery owns the tower.

An explicit owner binding can register `PaperAwardSaveFence` and await its complete
outstanding-delivery projection before admitting new deliveries. This includes paused,
in-progress and completed receipts awaiting acknowledgment. A failed, stale or oversized
projection keeps marked-item interactions held during bootstrap. Once installed, the Paper
listener holds staged items and the identities of outstanding deliveries through ordinary
inventory actions, hand swaps, transfer, use, pickup and dropping. Unrelated ordinary
items remain usable. A delivery write still requires the exact recipient connection and
slot evidence. The runtime can refresh this projection after a durable disposition to
release its pin.

During death processing, the listener asks Paper to keep a held item when the drop matches
a unique inventory stack. It leaves the world's existing inventory rule in place. Only
an observed exact item in the same slot can qualify as continuation. If that cannot be
observed, the receipt stays held for inspection. The fence leaves all item movement and
reissue decisions to their existing guarded paths. Direct inventory edits by other plugins are outside these event hooks.

A SQLite commit and a Minecraft player save are independent. The Paper inventory adapter
supplies no durable witness. A live slot read, an operation receipt or a return from
`Player.saveData` cannot prove that the exact item survived the save boundary. If the process
stops after live activation, a retry retains the same outstanding item and requests
inspection. Missing or mismatched save evidence never authorizes reissue.

A trusted reconciliation binding can read the exact completed receipt and submit fresh
matched evidence from an independently inspected durable player checkpoint. The
checkpoint must cover the recipient's exact active item in the recorded slot and match
the current operation and tower versions. The binding supplies the checkpoint hash and
must revoke the proof if its coverage ceases to be current before commit. Setting the
coverage value alone establishes no evidence. Acknowledgment changes only the durable
receipt. It never activates or replays an item, does not lift a retirement hold, and
does not automatically issue the next item. After acknowledgment, a new explicit
opportunity may resume the remaining quantity.

## Schema and runtime boundary

Schema 5 adds the campaign progress table when a repository opens, including the existing
item harness. It does not adopt historical award rows, modify their quantities or issue
items. Older builds reject the future schema. Preserve the evidence before considering
migration and do not open preserved test data with this candidate for live acceptance.

The command, service and Paper inventory adapter are available for an explicit shared
runtime binding. The current plugin entry point reports awards unavailable until that
binding. A production owner must supply the published catalog, known player sessions,
authorized pending-resume scheduling, exact appearance and pack readiness, and the
production item lifecycle, persistent interaction fence, and independent save reconciliation. The prototype remains separate.
Local SQLite and Paper-adapter tests do not establish live crash recovery or client
acceptance.
