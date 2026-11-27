# Native Catalog Startup

The catalog runtime owns configuration and one SQLite store. It supports
validation, durable publication, and administrator reload for bundled models.
A direct Java-only server can opt in to bundled Java pack delivery with an
optional local texture override in stacked mode.
Gameplay remains unavailable while its owner bindings are pending.

## Startup and reload

Set `native-catalog.enabled: true` in the plugin's `config.yml` and restart to
select the catalog runtime. It reads a separate `catalog.yml` beside `config.yml`.
The mode defaults to disabled. With the mode disabled, the plugin uses its existing
prototype lifecycle. A failed catalog-mode startup keeps that startup in catalog mode,
with marked items protected and prototype commands unavailable. To return to prototype
mode, set the flag to false and restart.

Catalog mode requires an absent or valid empty rendering cleanup manifest and an absent
manifest staging file. Nonempty, malformed, inaccessible, or oversized evidence blocks
startup before the native database opens. A bounded read of the committed manifest
extracts exact cell coordinates without loading their worlds or chunks. While
startup is blocked, existing block and bucket protection applies to those recorded
cells, including when their chunks load again. A staged file never supplies trusted
coordinates. If the committed manifest cannot be read safely, status reports unknown
cells. Fixture recovery remains in prototype mode.

`/infernolantern status` reports catalog publication and gameplay availability
separately. The existing `infernolantern.admin.reload` permission controls
`/infernolantern reload` and its alias. Reload reads `catalog.yml`, validates it and
publishes a complete catalog atomically. It leaves the mode and prototype settings
as selected at startup. Failed validation leaves the previous durable publication
intact. If startup could not publish, correct the file and reload once storage and
bundled assets are available. A storage failure closes catalog admission for that
startup. A storage or asset failure requires a restart after the cause is resolved.

The catalog is a regular UTF-8 file limited to 1 MiB. It uses the
[strict catalog schema](CONFIGURATION_CATALOG.md), including explicit null and
omission semantics. `variants: {}` intentionally retires all active definitions.
The catalog validator checks the embedded pack manifest and digests for the
generic Java and Bedrock archives and Geyser mapping. It accepts only compact and
expanded model pairs from that verified bundled inventory. See
[Enable bundled Java delivery](RESOURCE_PACKS.md#enable-bundled-java-delivery)
for default and stacked Java pack delivery, local texture overrides, and the
optional required policy. [Server-managed combined Java readiness](RESOURCE_PACKS.md#enable-server-managed-combined-java-readiness)
uses an exact receipt from a configured delivery owner. Custom model sets and Geyser session readiness
require their runtime bindings.

This example exercises publication with the bundled Ember models. The values are
synthetic inputs for a catalog check.

```yaml
schema: 1
variants:
  example:
    player-switches: {light: true, defence: false}
    light-level: 12
    maximum-hp: 100
    expansion-cooldown-ms: 0
    geometry:
      compact-model: infernolantern:generic_ember_compact
      expanded-model: infernolantern:generic_ember_expanded
      width: 1
      height: 3
      depth: 1
      light-x: 0
      light-y: 2
      light-z: 0
```

## Storage and shutdown

Catalog mode uses `native-runtime/lanterns.db` inside the plugin data directory.
It has its own SQLite application identity, the pinned schema and an exclusive
owner lock. Existing prototype storage and cleanup evidence remain separate.
Aliases through symbolic links, hard links on supported filesystems, copied
prototype databases and journals without their database are rejected. Preserve
the database and sidecars together if a native startup reports unavailable.

First creation initializes SQLite in a uniquely owned staging directory beside the
database. It closes and syncs the completed candidate before publishing with an atomic
hard link that cannot replace an existing file. Native storage requires filesystem
hard-link support. The owner file records the exact candidate name before the link. An
interruption after linking can safely finish unlinking that candidate. An interruption
before linking leaves the fixed database path absent, and the next startup initializes
a separate candidate. Unrecognized files, journals, and abandoned stages stay preserved.

An existing native store needs its recognized owner marker and exact pinned
SQLite application identity, schema and migration history. Startup holds any
WAL, shared-memory or rollback journal sidecars before opening the database.
A sidecar-free store is inspected through the private driver in immutable,
read-only mode under its owner lock. Pending journal phases, terminal records,
other gameplay state and unmanaged variants hold publication and reload. A
terminal receipt alone does not establish that the physical saves match.

The status command distinguishes pending operations, recorded state and
unexamined sidecars from an unavailable store with unknown evidence. Preserve
the original files, including any sidecars and matching saves, for
[recovery review](RECONCILIATION.md). A hold leaves recorded files in place
and performs no recovery. An empty existing store can still publish, and
each catalog-only transaction rechecks the restriction before commit.
Shutdown immediately stops new reload admission and revokes queued publication.
The database worker drains admitted work and closes asynchronously. Shutdown
never waits on the Paper thread for SQL or reload authorization.

## Integration and acceptance

| Route | Current boundary |
| --- | --- |
| Catalog startup, status and reload | Wired through the plugin and validated against bundled assets. |
| Item lifecycle, placement and recovery | Requires the native token admission, world/provider checks, and matched durable evidence from their respective owners. |
| Switches, lighting, combat, blasts and pistons | Requires loaded component, protection-provider and owner registration. |
| Repair, health view and campaign awards | Requires native menu/session routing and the inventory, protection and save witness bindings. |
| Resource packs | Default and stacked Java delivery, local texture overrides, and per-connection readiness are wired for direct Java-only servers. Combined Java can consume exact receipts from a configured external owner. Geyser and full live acceptance remain open. |
| Core release acceptance | Requires exact Paper/Geyser/Java/Bedrock tests, isolated matched saves and load measurements under #27. |

The local suite uses temporary SQLite files and Paper API doubles. Its results
cover the startup selector, token protection, catalog publication, rejected
reload, storage isolation, and shutdown ordering.
