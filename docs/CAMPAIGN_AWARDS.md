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
the prepared active item. Successful activation acknowledges receipt before continuing
with the remaining quantity. Every new tower has zero HP and no controller. The existing
item identity codec supplies one non-stackable representation.

A SQLite commit and a Minecraft player save are independent. If stage, commit, activation
or acknowledgment is uncertain, the outstanding operation remains on the award and the
normal retry returns inspection required. A terminal operation receipt alone does not
authorize activation or a replacement. Standard item operations cannot take ownership
while that delivery is unresolved. Preserve both saves and the exact journal evidence.
The administrative campaign recipe requires reviewed disposition instead of automatic
recovery, refund, or replay.

## Schema and runtime boundary

Schema 5 adds the campaign progress table when a repository opens, including the existing
item harness. It does not adopt historical award rows, modify their quantities or issue
items. Older builds reject the future schema. Preserve the evidence before considering
migration and do not open preserved test data with this candidate for live acceptance.

The command, service and Paper inventory adapter are available for an explicit shared
runtime binding. The current plugin entry point reports awards unavailable until that
binding. A production owner must supply the published catalog, known player sessions,
authorized pending-resume scheduling, exact appearance and pack readiness, and the
production item lifecycle and save reconciliation. The prototype remains separate.
Local SQLite and Paper-adapter tests do not establish live crash recovery or client
acceptance.
