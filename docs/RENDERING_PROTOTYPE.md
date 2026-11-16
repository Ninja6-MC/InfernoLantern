# Rendering Prototype

Task #5 remains pending live evidence. This opt-in experiment connects the merged models to
an operator-only disposable fixture. It does not implement production lantern items, HP,
combat, repair, permissions policy, rewards or durable tower state.

## Build and Delivery

```bash
./gradlew clean check prototypePacks
```

The single plugin JAR is in `build/libs/`. The deterministic pack builder writes these
files to `build/prototype-packs/`:

- `infernolantern-prototype-java.zip`
- `infernolantern-prototype-bedrock.mcpack`
- `infernolantern-prototype-mappings.json`
- `SHA256SUMS`

The pack-only equivalent is `python3 scripts/build_prototype_packs.py`. No network or
image conversion service is needed. The builder reads the committed masters and textures
without modifying them. Generated delivery archives stay under ignored `build/`.

Use a disposable, unbranded test area with the pinned Paper/Java/Geyser versions in
[the test matrix](INTERFACES_AND_TEST_TARGETS.md). Capture the actual PE build before
execution and check that the selected bridge supports it. Install the plugin JAR in the
test server's `plugins/` directory. Enable the Java ZIP in the Java client's resource-pack
screen. Put the MCPACK in Geyser's `packs/` directory and the mappings JSON in Geyser's
`custom_mappings/` directory, then restart the test server. Accept Geyser's supplied pack
when the PE client connects. These steps do not configure public pack hosting or alter a
live server's resource-pack settings. Missing/declined packs are a failed visual test;
this experiment does not implement production pack-readiness enforcement.

Set `prototype.enabled: true` in the plugin's generated `config.yml` and restart. The
prototype is disabled by default. Only operators may create or operate its fixtures;
there is no new permanent permission node. Run `/infernolantern help` for available
commands. `/infernolantern status` requires the approved administrative inspection
permission and reports counts without world/player identifiers.

## Fixture Controls

The maintainer approved the temporary `/infernolantern prototype` action on 2026-11-16.
It is experimental and is not a production issuance interface. `/ilantern` is the declared root alias.

| Action | Behaviour |
| :--- | :--- |
| `prototype place` | Look at the top face of solid support within five blocks. Create a compact fixture in the empty block immediately above it and open its menu. No inventory item is consumed or awarded. |
| `prototype` | Look at an existing fixture to open its menu. Right-click/tap its base or collision shaft also opens the same menu. |
| `prototype remove` | Remove the targeted fixture and restore recognizable fixture block states. |
| `prototype cleanup` | Remove active fixtures and reconcile recorded cells in loaded chunks. Unloaded cells wait for their chunk to load; external-edit conflicts remain quarantined. |

The nine-slot menu exposes Expand/Compact, Light, Defence flag and Remove. Light and the
defence flag are independent. The flag never attacks anything: zombie combat belongs to
#6. Both switches require expansion. Expanding or compacting resets them to off so this
experiment cannot be mistaken for production switch persistence. All menu clicks/drags
are intercepted, including player-inventory transfers. Operator status and distance are
checked again when actions execute.

## Representation and Limits

Both clients receive a normal-size invisible ArmorStand with a custom carved-pumpkin
helmet, not a display entity. Geyser maps its `minecraft:item_model` component using v2
custom-item definitions. The Bedrock pack supplies head-bound attachables with separate
frame/glow geometry and render controllers. The existing artwork remains editable; this
is a derived test delivery, not final pack packaging for #23.

The Java pack targets resource format **97.1**, read from the official 26.3 client
`version.json`. Its download is identified by SHA-1
`e877b6a07acd633fb3bb475002175cec036e7b87` in
[official metadata](https://piston-meta.mojang.com/v1/packages/9f67e10ed013deeaad20ac3c800652765d493408/26.3.json).
The normal ArmorStand head-item scale is 0.625. Compact head scale 1.6 restores a
one-block model. The Java item-transform parser clamps scale to four, so the expanded
derived model multiplies normalized coordinates/origins by 1.2 about horizontal centre
8 and ground baseline 0, then applies head scale 4. Its effective scale is 2.5 and its
model height is 19.2/16 blocks: together these produce height 3. Its coordinates remain
inside the parser's legal bounds. UVs are unchanged from the normalized exports.

Initial Java vertical alignment uses the inspected 26.3 normal-head transform:
`1.501 - 1/16 + 0.25 = 1.6885` blocks above the stand origin. The item model is centred
before its head transform. Head translation is
`16 * (0.625 * scale / 2 - 1.6885) / 0.625`, giving its unrotated lower baseline at the
stand origin. The Bedrock derived geometry is centred horizontally at zero, retains
full master height and binds at head pivot `[0, 23, 0]`, matching GeyserIntegratedPack's
normal ArmorStand head. The fixture stand origin is the base block's horizontal centre
and lower face. `prototype.visual-y-offset` defaults to zero; it is an experimental
calibration adjustment shared by both clients, not a claim that independent client
alignment has passed. If clients disagree, revise the pack transform and rerun the
matrix rather than hiding a mismatch with a server-wide offset.

The generated Bedrock glow uses frame zero of the strip with an explicit UV scale. Java
retains its animation metadata. Animated Bedrock attachable parity is not claimed;
block flipbook metadata does not animate an entity attachable. Texture animation and
final polish remain outside this feasibility fixture.

| State | Visible/clearance volume | Physical collision | Light |
| :--- | :--- | :--- | :--- |
| Compact | 1 x 1 x 1 | Barrier at base Y | None |
| Expanded | 1 x 3 x 1 | Barriers at base Y and Y+1: 1 x 2 x 1 | LIGHT block at Y+2, level 0 or 15 |

The upper light voxel is deliberately not also a barrier. The collision shape is an
experimental two-block shaft inside the three-block declared clearance, not a selected
production hitbox. Placement/expansion rejects fluids, solids, reserved cells, entities,
unloaded chunks and invalid heights before mutation. Compact placement reserves only
one block; expansion checks all three again. There is no fluid replacement/waterlogging
in this experiment. This limitation does not change the production water/lava policy.

Vanilla block breaking/placement, fluid entry, piston movement and explosion block
removal are intercepted for recorded cells. No regional-protection provider integration
is claimed; use only an explicitly disposable fixture area. External administrative
world-edit tools may bypass events. They must not be used on active fixtures.

## Cleanup and Recovery

The prototype writes `prototype-cleanup.bin` in its plugin data directory before world
writes. It records only the cleanup originals and intended fixture states. Ordinary
chunk unload removes fixtures rather than preserving deployed gameplay state. Plugin
shutdown removes visuals and restores loaded cells. Startup and chunk-load recovery
restore remaining recognizable fixture cells without force-loading chunks. Visual
ArmorStands are nonpersistent and equipment-locked, and stale tagged loaded entities
are removed. This is a disposable-fixture cleanup manifest, not the production state
repository or operation journal planned in #8.

Cleanup restores a cell only if its current block data matches a recorded fixture
state. Already-restored original data is harmless. A different block is preserved and
its manifest entry remains quarantined; a warning reports only the conflict count.
Inspect it locally before editing or discarding the cleanup manifest. Do not delete the
manifest while recorded fixture blocks remain. If manifest I/O fails, stop testing and
preserve the file; recovery waits for a successful later attempt. Exact state comparison
cannot distinguish another tool deliberately replacing a barrier/light with identical
block data. Do not overlap administrative edits with this experiment.

The manifest uses a flushed temporary file and atomic rename. Filesystem/world-save
ordering, abrupt process death and power-loss durability are not proven. The automated
partial-write/reopen test exercises cleanup logic; it is not an in-game crash-recovery
claim. Production crash durability and state restoration remain gated by #8-#11.

## Live Acceptance Worksheet

All rows below are **pending**, including server execution. Automated checks do not
establish client rendering, actual emitted light, input usability or lifecycle events.
Record exact builds, plugin commit/JAR digest and pack digests with each run. Retain only
generic geometry, steps, observations and limitations; exclude player names/UUIDs,
server addresses/names, private paths/world identifiers, organizations and Discord data.

| Test | Expected observation | Result |
| :--- | :--- | :--- |
| Default-disabled and nonoperator | No fixture mutation; generic help remains available; status respects its permission. | Pending |
| Compact placement, Java and PE | Same compact model, centred in one block; source support unchanged. | Pending |
| Expand, Java and PE | Same three-block model, correctly centred with no horizontal overhang, acceptable textures and no invisible/missing parts. | Pending |
| Light/defence combinations | Both off, light only, flag only and both on remain independent; no combat occurs. | Pending |
| Mouse and PE touch | Each menu action is independently selectable, including compaction; no menu item escapes or inventory loss. | Pending |
| Real illumination | Actual block light changes with the LIGHT level, including adjacent dark blocks after updates; emissive appearance alone does not count. | Pending |
| Collision | Player/mob movement collides with the declared two-block shaft; upper voxel difference is recorded. | Pending |
| Obstructed/fluid/entity placement and expansion | Private rejection; compact fixture and unrelated blocks/entities unchanged. | Pending |
| Repeat clicks and two operators | No duplicate components, partial deployment or inventory transfer. | Pending |
| Compact/remove/cleanup | Visuals, barriers and LIGHT cells disappear appropriately; original air restored; support remains. | Pending |
| Chunk unload/reload | Fixture is removed before unload; returning leaves no visual, barrier or light. | Pending |
| Clean restart and disabled-after-restart | No stale entities/blocks, including deferred unloaded cleanup. | Pending |
| External changed block | Changed block survives cleanup; conflict is retained/reported; no public identifiers in evidence. | Pending |
| Missing/declined pack | Record explicit visual failure; do not mark crossplay gate passed. | Pending |

After the worksheet passes, update [rendering evidence](RENDERING_EVIDENCE.md) with
observations and select/reject the tested candidate. Keep #5 open until its acceptance
criteria are met. Only then start the dedicated zombie retaliation/damage proof in #6.

## Upstream Format References

- [Geyser custom item v2 definitions and `item_model`](https://geysermc.org/wiki/geyser/custom-items/)
- [Geyser pack delivery](https://geysermc.org/wiki/geyser/packs/)
- [Microsoft attachable binding format](https://learn.microsoft.com/en-us/minecraft/creator/documents/attachables?view=minecraft-bedrock-stable)
- [GeyserIntegratedPack ArmorStand geometry](https://github.com/GeyserMC/GeyserIntegratedPack/blob/master/src/main/resources/integratedpack/models/entity/armor_stand.geo.json)
- [Geyser head-slot scaling explanation](https://github.com/GeyserMC/GeyserIntegratedPack/blob/master/developer_documentation.md#geometry-and-attachables)
- [Microsoft UV-animation material requirement](https://learn.microsoft.com/en-us/minecraft/creator/documents/practices/improvingperformanceandresourceusage?view=minecraft-bedrock-stable)
