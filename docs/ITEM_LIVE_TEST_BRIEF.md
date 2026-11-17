# Disposable Item Lifecycle Live Test Brief

Task #9 remains partial. Test only the approved item lifecycle harness in the authorized
disposable environment. This kit has not been deployed or tested in a Minecraft process.
No production award workflow or world placement handler is included. The existing
rendering/zombie fixtures remain separate and use their original cleanup manifest.

## Preparation

1. Read `BUILD_INFO.json` and verify every entry in `SHA256SUMS`. Record the source commit,
   actual deployed JAR digest and exact generic Paper/Java/client/bridge versions.
2. Inspect current intentional settings and provider behavior before changing anything.
   Preserve unrelated configuration. Back up offline plugin data and the disposable test
   world's/player saves together; do not copy a running SQLite database alone.
3. Install exactly the candidate JAR using the established authorized deployment procedure.
   This generic PAPER test item needs no resource pack or new Geyser custom mapping.
   Existing rendering test packs need not be replaced for item-only tests.
4. Start with `prototype.enabled: false` and `prototype.item-lifecycle.enabled: false`.
   `/ilantern status` must show the item harness disabled, opening or unavailable; creation
   must reject and stay absent from tab completion. Fresh disabled startup must not create
   `item-prototype.db`. Non-operators must be unable to issue fixtures under either flag.
5. Enable both gates using the example's test-only section, then restart cleanly. Wait for
   item status `ready; native acceptance pending`. Do not repeatedly issue while opening.
   A failed database, pending journal or observed unactivated item must stay unavailable;
   preserve that evidence rather than deleting the store to obtain a green startup.

## Execute

Use `/infernolantern prototype item` or `/ilantern prototype item`, without extra
arguments. It requires an operator, both gates, a ready store and an empty selected
hotbar slot. It creates exactly one generic disposable PAPER fixture with zero HP,
no controller, no permission grant and no owner. Keep that selected slot empty and
remain connected until the private completion message. This interface is temporary;
`award` remains unimplemented and its contract belongs to #24.

Run the numbered matrix in `ITEM_LIFECYCLE.md` with Java mouse/keyboard and the exact
installed PE touch client. Prioritize native drop/pickup, manual chest transfer, denied
hopper/hopper-minecart collection, merge/stack abuse, fire/lava buoyancy, despawn,
ordinary death and reconnect/unload/restart. Repeat the two survival/despawn switches
independently with fresh disposable fixtures and a clean restart between policy changes.
The final production variant/configuration catalog belongs to #13.

World placement attempts must reject intact: **there is no #10 placement handler yet**.
Duplicate placement claims currently have local service/CAS evidence only. Record that
row `PENDING #10`, rather than substituting a rendering fixture placement or claiming
native tower placement passed. Exact copies can still exercise drop/pickup contention;
first valid ground transition wins and rejected inventory copies must remain intact.

Check that a valid active item has the four identity fields, max-stack one and no
`item_delivery` marker. While issuance is unavailable, the temporary `item_delivery`
marker must prevent use/drop/pickup claims. Interrupting an ambiguous delivery must retain
that item and durable journal evidence. Startup must not clear its marker or issue an
extra identity automatically. Do not manufacture arbitrary replacement items to repair it.

Test external pickup denial independently before and at the commit boundary. The adapter
uses a synthetic cancellable pickup validation event; report actual provider ordering and
whether denial remains intact. Do not infer integration support from unit tests.

## Fault Boundaries and Evidence

Exercise interruption before intent, after intent, after inventory staging, after
verification, after durable completion and before final activation. Expected outcomes
are source preservation before effects or unavailable/quarantined evidence after effects;
no automatic refund, replacement, age reset or inferred loss from an unloaded chunk.
A completed database receipt alone does not prove player/chunk saves committed together.
Full crash reconciliation is #11 and must remain pending where evidence is ambiguous.

Publish only generic versions/specifications, artifact hashes, synthetic observation
labels, counters and pass/fail findings. Keep player/server names, UUIDs, real world IDs,
coordinates, host/network details, private configuration, unsanitized logs/screenshots
and chat/organization information out of repository evidence. Use generic labels such as
fixture A and operator A. Keep any necessary raw local evidence private.

Report each worksheet row as PASS, FAIL, PARTIAL or PENDING with the actual observed
boundary. Include whether flags were enabled, inventory source was retained, marker was
present/absent, row was frozen/usable, current ground entity matched and cleanup completed.
Do not report #9 or G1 accepted solely because the local suite or this harness compiled.

## Teardown

Disable fixture creation only after recording the current authoritative state. Existing
item protections must remain active when either creation flag is false. A pending journal
keeps mutations unavailable on restart; do not remove it just to enable more tests.

Do not use `/ilantern prototype cleanup` to claim item teardown: it clears rendering
fixtures only. Inspect each disposable identity and its current portable/ground/LOST row
before removing physical test copies through an agreed inspection procedure. Preserve
all unavailable markers, pending operations and ambiguous independent-save evidence for
#11. Do not blanket-delete database files, staged entities or player inventories.
For an unambiguous current disposable item, use the validated ordinary ground/drop and
configured despawn/destruction path to reach LOST; retain historical records. Verify
aggregate counts only after a clean stop/close, for example with read-only SQL:

```sql
SELECT form, COUNT(*) FROM towers
WHERE variant_id = 'prototype.item-fixture' GROUP BY form;
SELECT phase, COUNT(*) FROM operations
WHERE phase IN ('INTENT', 'EFFECTS_APPLIED', 'RECONCILING') GROUP BY phase;
SELECT COUNT(*) FROM ground_representations;
```

Keep local database locations and row identities private. Do not edit rows to manufacture
a successful teardown. No item cleanup/recovery command is introduced by this harness. If evidence cannot be
safely resolved, stop item testing and hand back the bounded quarantine state.

Leave a sanitized report with the actual final generic counts, creation gates, pending
operation status and unresolved rows. Restore intentional environment settings according
to the deployment procedure; do not publish environment-specific backup paths.
