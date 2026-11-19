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
the previously prepared snapshot. The adapter checks exact state before writing. A compact layout
can retain its exact already-owned waterlogged base during cleanup.

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

A piston can neither move reserved lantern components nor enter their reserved positions.
`PaperPistonGuard` checks the base, head, affected sources and moved destinations of the event.
The owner supplies a current loaded-cell lookup covering both committed and active-operation
before/after reservations. The listener preserves existing cancellation and allows disjoint
movements, including those in other worlds. Native structure routing requires the owner lookup
to be published before use. The listener performs coordinate lookups without chunk loading or a
database wait. [Paper piston event implementation](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/world/level/block/piston/PistonBaseBlock.java.patch)

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
