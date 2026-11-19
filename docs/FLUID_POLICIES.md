# Fluid placement and cleanup

Each variant can define optional `fluids.water` and `fluids.lava` maps. The fields are Boolean
and independent. Missing fields take the documented defaults.

| Field | Water default | Lava default |
| :--- | :--- | :--- |
| `place` | `true` | `false` |
| `expand` | `true` | `false` |
| `submerged-defence` | `false` | `false` |

The catalog still accepts schema 1 YAML. Stored catalogs and definitions with default fluid
rules retain their version 1 or 2 encoding. A rule that differs from the defaults uses version 3.
Publication checks the whole candidate. Existing towers keep their recorded original and expected
terrain, and an in-flight managed operation still requires the catalog revision that authorized it.

## Supported placement and expansion

The owner-thread Paper adapter inspects the compact base for placement and the complete oriented
clearance for expansion. It checks the relevant water and lava rules independently, including the
original terrain beneath retained owned blocks. It still requires loaded bounds, usable space and
current native authorization.

- Empty air supports a normal dry replacement.
- Permitted open source or flowing fluid can remain in a voxel that needs no replacement. The
  adapter retains its precise block state and makes no write.
- Permitted source water can support a replacement that implements Paper's `Waterlogged` contract.
  The adapter captures the source and writes the replacement with waterlogging enabled.
- Flowing water and lava cannot be displaced by this adapter. A solid waterlogged block is an
  obstruction, even when water is allowed. Unsupported layouts reject before staging or changing
  blocks. A dry replacement cannot introduce water or lava.

Changing fluid state in the target clearance after preparation requires a fresh request. In
particular, a flow becoming source water is a distinct observation and does not silently authorize
the previously prepared snapshot. The adapter checks exact state before writing. Compaction can
replace a still-owned waterlogged block with a different waterloggable compact block while preserving
the same recorded source. Rollback restores the exact prior waterlogged block when the operation
still owns its replacement.

## Submerged Defence

Submersion is measured at the attack head. The native layout binding identifies one attack-head
voxel in the current expanded manifest and supplies the tower identity, item generation and
deployment revision. The Paper adapter checks that proof and reads the current head block only
within the loaded owner chunk. A dry head permits Defence even when the base is in water. Source
water, flowing water and a waterlogged head use the water rule. Open lava uses the lava rule.
A missing or stale proof, unloaded world, or unsupported fluid representation leaves Defence
unavailable. The current surface is observed at attack admission; the bounded owner loop releases
its target and proxy when it discovers disallowed submersion. This preserves the player's saved
Defence preference for when the current conditions allow operation again.

## Piston movement and compact hits

Preparation and apply exclude existing piston bases, heads and moving blocks from the planned
reservation. A one-block neighborhood check also rejects adjacent moving pistons and extended
pistons facing into the reservation. The check uses loaded chunks only. An unobserved neighbor
prevents admitting the affected layout. The layout cannot introduce a piston component.

The runtime must publish its loaded-cell lookup for committed and pending before/after reservations
before enabling native placement or structure effects. `PaperPistonGuard` then prevents piston
placement in these cells, plus extension by a piston whose base or head overlaps them. Extend and
retract callbacks check reported affected sources and moved destinations. The guard preserves
existing cancellation and leaves disjoint movements unaffected. Lookups use exact coordinates
without a chunk load or database wait.

On pinned Paper a sticky retraction can write its base before its callback or remove its head
without a callback. Admission and earlier extension establish the protected footprint before either
path. The retract handler protects its reported moves. Direct world edits that bypass native
admission remain outside this guarantee. A conflicting external edit stays in place for
reconciliation. [Pinned Paper piston implementation](https://github.com/PaperMC/Paper/blob/1a995d67e68af9a46eb5fde023b3309921311f70/paper-server/patches/sources/net/minecraft/world/level/block/piston/PistonBaseBlock.java.patch#L16-L49)

Ordinary non-blast hits affect expanded towers only. Compact bases remain subject to normal
authorized breaking and the separate permitted blast transaction. Local tests confirm that the
existing health service and SQLite commit both reject ordinary compact damage.

## Cleanup and verification

Unchanged clearance reserves space but owns no terrain. Compaction, removal and permitted blast
cleanup restore recorded terrain only for changed, still-owned blocks. A later external fluid or
block change in released unchanged clearance is left in place. An external change to an owned block
remains an integrity conflict. Compensation verifies its exact writes before restoration and leaves
external edits in place. Recovery uses the existing journal and exact evidence gates.

Dropped-item fire and lava survival and despawn behavior keep their separate settings. They are
independent of placement and expansion permissions.

Local Paper API tests exercise these owner-thread decisions and exact writes. Native structure
routing and acceptance for fluid propagation, light physics, attack-head occupancy, and piston
event ordering remain open under #21.
