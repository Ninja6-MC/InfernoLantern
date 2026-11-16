# Rendering Prototype

The core #5 rendering criteria have been exercised on the recorded Java/Android Bedrock pair. Results, actual build deviations and remaining checks are in [the evidence record](RENDERING_EVIDENCE.md). Full G0 remains open for #6. This opt-in experiment is an operator-only disposable fixture; it does not implement production lantern items, HP, combat, repair, permissions policy, rewards or durable tower state.

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
`custom_mappings/` directory, then restart the test server. Complete the [Geyser deployment preflight](#geyser-deployment-preflight) before connecting PE. These build/install instructions do not change an existing server automatically. This experiment does not implement production pack-readiness enforcement.

After the preflight's initial join checks pass, set `prototype.enabled: true` in the plugin's generated `config.yml` and restart. The
prototype is disabled by default. Only operators may create or operate its fixtures;
there is no new permanent permission node. Run `/infernolantern help` for available
commands. `/infernolantern status` requires the approved administrative inspection
permission and reports counts without world/player identifiers.

## Geyser Deployment Preflight

The following staged settings are for the pinned **Geyser-Spigot 2.12.1 build 1302** only. Its JAR SHA-256 is `e13592d8ef907657eb9bae6f778dcb6ccb7f4a5b4641baeeb298ff5d13ccc92f`. The artifact, configuration classes and bundled integrated pack were inspected without starting a server. A synthetic configuration loaded through that JAR's configuration loader returned `custom=true, integrated=true, configuredForce=false`; this validates the settings paths, not live client compatibility.

This build reads the settings below from the existing `gameplay` section of Geyser's `config.yml`. Old root-level snippets are not evidence of the effective settings after migration. Inspect the configuration generated/loaded by the selected build and any hosting or environment overrides locally; do not paste a complete configuration into an issue. A different Geyser build needs its own path and behavior check before using this example.

| Setting | Requirement for this candidate | Effect / limitation |
| :--- | :--- | :--- |
| `gameplay.enable-custom-content` | `true` | Enables the custom-item mapping system; `false` cannot render the prototype's mapped custom items. Changing it requires a Geyser restart. |
| `gameplay.enable-integrated-pack` | `true` | This candidate was derived for the bundled integrated pack's ArmorStand head pivot `[0, 23, 0]`. Disabled-integrated-pack alignment is unvalidated and is outside this candidate's acceptance matrix. |
| `gameplay.force-resource-packs` | Preserve the installation's existing configured value | When the integrated pack is active, this build forces acceptance regardless of a configured `false`. Enabling the integrated pack therefore changes effective pack-refusal behavior. |
| Other gameplay, authentication and network settings | Preserve existing values | Custom skull limits and unrelated settings are not prerequisites of this helmet-item representation. Do not reset the full configuration to defaults. |

The pinned handshake calculates forced acceptance as `forceResourcePacks() || isIntegratedPackActive()`. With the integrated pack active, a client that refuses packs cannot complete the expected join flow. Do not describe this setup as optional pack delivery merely because the stored force flag is `false`. An installation that must retain optional pack refusal is not supported by this candidate without a separately validated representation.

1. Prepare an isolated, disposable test instance and a local backup of its configuration, plugin versions and existing pack/mapping set. Keep those private backups outside repository artifacts. If custom content or the integrated pack was disabled to avoid mobile crashes or preserve a network's switching behavior, retain that baseline and its reason; enabling these features is a controlled experiment, not an established fix.
2. Record exact Paper, Java vendor/patch, Geyser, optional Floodgate and client builds plus plugin/pack digests using the generic test matrix. Obtain the installed PE version from the client; a Geyser configuration or an old inventory cannot establish it. Confirm that the chosen bridge supports that PE protocol. Unsupported proxy switching is a reason to stop this candidate's test rather than blindly enable custom content.
3. While the test instance is stopped, manually merge the two keys in [the generic gameplay fragment](examples/geyser-prototype-gameplay.yml) into its existing `gameplay` section. The fragment is not a replacement configuration and must not create a second `gameplay` section. Review only that intended diff locally. Do not change authentication, addresses, ports, existing pack policy values or unrelated stability settings.
4. Install the generated pack, mappings and plugin as described above, initially leaving `prototype.enabled: false`. Restart the test instance fully; do not rely on a plugin reload for Geyser registry/pack changes. Check locally for configuration, mapping, pack or plugin load errors. Retain only sanitized error descriptions and version information, never raw dumps or full logs.
5. Confirm that Java joins and PE accepts the supplied packs and joins without a crash before enabling fixture creation. Then set `prototype.enabled: true`, restart, and run the worksheet below. Compare compact and expanded rendering, controls and mobile stability. If PE crashes, cannot join, shows a missing model or disagrees with Java alignment, mark the candidate failed and follow rollback; do not proceed to #6 or a shared deployment.
6. Keep each runtime/client row pending until actually observed. Only after the isolated mobile test passes should a deployment plan consider changing purpose-disabled settings elsewhere. Passing configuration parsing or automated pack tests does not remove that gate.

### Rollback and Cleanup Order

Use Java operator access if a PE crash prevents reconnecting. While the prototype plugin is still installed and enabled, run `/ilantern prototype cleanup`; revisit fixture chunks with deferred cleanup as needed and inspect `/ilantern status`. Both active fixtures and cleanup cells must reach zero before removing the plugin. Any retained conflict requires local inspection while preserving the cleanup manifest; do not delete it to hide a failed cleanup.

Set `prototype.enabled: false` and restart with the plugin still available for recovery. Verify zero remaining fixture/cleanup counts again. Then stop the test instance, restore the backed-up Geyser values and previous pack/mapping set, and remove the prototype JAR only after cleanup is confirmed. Remove only this prototype's added pack/mapping files, preserving unrelated delivery assets. Restart and verify the baseline Java/PE join behavior. Disabling custom content, removing packs or deleting the plugin before cleanup can make diagnosis harder and does not restore fixture blocks.

Do not publish backups, server/player identifiers, private paths or complete configurations as rollback evidence. Record only the generic steps, exact versions, observed result and any unresolved cleanup count.

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

Vanilla block breaking/placement, bucket placement into recorded destination cells,
fluid entry, piston movement and explosion block
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

### Updating a Prototype with Retained Barrier Cells

Earlier prototype builds recorded shorthand `minecraft:barrier` while Paper serializes the placed state as `minecraft:barrier[waterlogged=false]`. That mismatch could reject subsequent transitions and retain otherwise unchanged fixture cells during cleanup. The updated ledger canonicalizes full block data for new writes and legacy manifest entries; it preserves the existing ILP1 format. A barrier with `waterlogged=true`, or any different external block, is still a conflict and is not forcibly restored.

1. Stop fixture creation and stop the test instance cleanly. Back up the existing plugin JAR and the entire plugin data directory privately, including `prototype-cleanup.bin`. Do not delete or hand-edit the manifest or break the retained cells to make the count disappear.
2. Replace only the prototype JAR with the independently reviewed fix, keeping the original data directory and manifest in place. Confirm that no second prototype JAR remains installed. The packs and mappings are unchanged by this fix.
3. Start the test instance, initially with `prototype.enabled: false` if further fixture creation should remain disabled. Startup recovery runs even while creation is disabled. Loaded recorded cells are checked immediately; unloaded cells wait for their chunk to load without force-loading it. Visit the affected chunks with an authorized operator and inspect `/ilantern status`.
4. If explicit cleanup is needed, enable the prototype and restart as documented, then run `/ilantern prototype cleanup` from an in-game operator session. Console/RCON cannot execute the player-bound cleanup command; it can inspect status. Require both `active fixtures=0` and `cleanup cells=0`. Any retained conflict must be inspected locally while preserving the manifest and backup.
5. Repeat Java placement, expansion, light toggles, compaction, removal, unload/reload and restart checks before continuing PE testing. The automated legacy-manifest regressions establish the comparison fix, not a live recovery pass. Record the updated JAR digest and actual observations; #5 remains open until its complete worksheet passes.

## Live Acceptance Worksheet

Use this worksheet for each new build or client pair; prior outcomes are not automatic passes for a new run. The 2026-11-16 results below summarize the [recorded live evidence](RENDERING_EVIDENCE.md#3-executed-rendering-and-lifecycle-results). Retain exact builds, artifact digests, generic steps and observations only. Exclude identities, addresses, private world coordinates/paths, organizations, Discord data and raw dumps.

| Test | Expected observation | Recorded outcome |
| :--- | :--- | :--- |
| Geyser preflight and join | Effective settings confirmed; Java/PE join with supplied packs. | Observed on the tested pair. Different Paper/Geyser builds from the planned pins are recorded; no general stability claim. |
| Default-disabled and nonoperator | Creation denied; help safe; administrative status permission respected. | Observed command restrictions. Disabled-mode recovery binding audited; a retained-cell recovery specifically while disabled was not separately observed. |
| Compact placement | Same centred 1 x 1 x 1 model with support preserved. | Observed Java/Bedrock. |
| Expansion | Same centred 1 x 3 x 1 model and clearance. | Observed Java/Bedrock; compact shimmer tracked for #23. |
| Light/defence combinations | All four independent combinations; no combat. | Observed. Defence remains a flag. |
| Mouse and touch | All controls usable; no extraction or inventory loss. | Observed with tested touch selection/transfer gesture and ninth-slot layout limitation. No controller coverage. |
| Real illumination | Level-15 source and surrounding block illumination change. | Observed. |
| Collision | Two-block shaft blocks movement; upper cell remains light-only. | Observed movement collision. |
| Obstruction/fluid/entity/overlap | Private rejection with no partial deployment or unrelated changes. | Observed solid apex, water, entity and overlap cases. Other fluid arrangements remain unsupported. |
| Bucket placement | Water/lava in either hand cannot overwrite/waterlog fixture cells, including adjacent-face placement. | Observed reported cases. |
| Repeated actions | Single-actor bursts do not duplicate components or extract items. | Observed. |
| Two operators | Concurrent/stale actions do not corrupt transitions or duplicate components. | Pending; production transition follow-up #10. |
| Compact/remove/cleanup | Visuals and recorded blocks removed; original air/support preserved. | Observed Java/Bedrock, including PR #40 legacy barrier recovery. |
| Chunk unload/reload | Actual unload, then no stale fixture on return. | Observed unload verified by loaded-chunk count reaching zero. |
| Clean restart | Active fixture cleaned during graceful shutdown; zero counts and clear volume after boot. | Observed. No abrupt-crash/power-loss claim. |
| External edit | Foreign block preserved and conflict retained; controlled restoration then normal cleanup. | Observed; final counts zero/zero. |
| Java pack disabled | Explicit visual fallback/failure; not a custom-rendering pass. | Observed carved-pumpkin fallback. |
| Bedrock pack refusal | Refusal/disconnect observed under effective forced policy. | Policy audited only; actual refusal run pending, production delivery follow-up #23. |

Final recorded baseline is `active fixtures=0; cleanup cells=0` and an 8-byte empty manifest. Preserve any future conflicts rather than deleting the manifest. The evidence supports scoped #5 rendering acceptance; issue sign-off remains with the maintainer. #6 must still establish zombie targeting/damage/release before full G0 is complete. Production fluid preservation, simultaneous-user transactions and pack readiness are not established by this worksheet.

## Upstream Format References

- [Geyser custom item v2 definitions and `item_model`](https://geysermc.org/wiki/geyser/custom-items/)
- [Geyser pack delivery](https://geysermc.org/wiki/geyser/packs/)
- [Microsoft attachable binding format](https://learn.microsoft.com/en-us/minecraft/creator/documents/attachables?view=minecraft-bedrock-stable)
- [GeyserIntegratedPack ArmorStand geometry](https://github.com/GeyserMC/GeyserIntegratedPack/blob/master/src/main/resources/integratedpack/models/entity/armor_stand.geo.json)
- [Geyser head-slot scaling explanation](https://github.com/GeyserMC/GeyserIntegratedPack/blob/master/developer_documentation.md#geometry-and-attachables)
- [Microsoft UV-animation material requirement](https://learn.microsoft.com/en-us/minecraft/creator/documents/practices/improvingperformanceandresourceusage?view=minecraft-bedrock-stable)

Pinned preflight implementation references (build 1302, commit `cbd42b8a0c47cef046b408411508c67f82cd8e3c`):

- [Official build metadata and artifact digests](https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/1302)
- [Gameplay settings and integrated-pack acceptance consequence](https://github.com/GeyserMC/Geyser/blob/cbd42b8a0c47cef046b408411508c67f82cd8e3c/core/src/main/java/org/geysermc/geyser/configuration/GeyserConfig.java#L465-L485)
- [Effective forced-pack handshake and refusal handling](https://github.com/GeyserMC/Geyser/blob/cbd42b8a0c47cef046b408411508c67f82cd8e3c/core/src/main/java/org/geysermc/geyser/network/bedrock/UpstreamPacketHandler.java#L98-L178)
- [Build-pinned configuration migrations](https://github.com/GeyserMC/Geyser/blob/cbd42b8a0c47cef046b408411508c67f82cd8e3c/core/src/main/java/org/geysermc/geyser/configuration/ConfigMigrations.java)
