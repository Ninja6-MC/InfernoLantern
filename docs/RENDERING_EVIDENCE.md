# Rendering and Interaction Feasibility Evidence

Task: #5. Gate: G0 (Rendering and Mob Feasibility).
Asset analysis: 2026-11-15. Live result record: 2026-11-16.
Status: The core rendering feasibility criteria of #5 are demonstrated on the recorded Java/Android Bedrock test pair, with the limitations and pending follow-ups below. Full Gate G0 remains open for #6's zombie targeting, retaliation, damage and target-release proof.

## Scope and Evidence Boundary

This record contains sanitized live observations supplied for the PR #40 fixture build, the editable model/export specification and the selected representation for the next feasibility task. It records what was exercised without extending those observations to untested versions, controller input, production persistence, crash durability or combat. The executable procedure and recovery details are in [the fixture guide](RENDERING_PROTOTYPE.md).

The #5 scope is compact/deployed appearance, independent controls on Java mouse and Bedrock touch, real light, declared clearance/collision, compaction and cleanup including chunk unload and a clean restart. Two-operator concurrency and an active Bedrock pack-refusal test were not completed; they remain explicit production follow-ups rather than rendering passes. Final art and pack delivery belong to #23, production lifecycle concurrency to #10, production fluid preservation to #21 and durable recovery to #8-#11. The defence switch is only a flag: no zombie proof is implied by a successful toggle.

The planned test pins in [the interface record](INTERFACES_AND_TEST_TARGETS.md) are unchanged. The actual run used the deviations below; this record does not claim live validation of Paper build 249 or Geyser build 1302.

## Tested Software and Artifact Identity

| Component | Recorded live build | Evidence boundary |
| :--- | :--- | :--- |
| Server | Paper 26.3 build 211, `26.3-211-main@2d602d6` | Deviation from planned build 249; only the exercised fixture behavior is accepted. |
| Java runtime | OpenJDK 64-Bit Server VM `25.0.5.0.3+1-1-24.04.1-Ubuntu` | Recorded runtime, not a performance result. |
| Geyser | 2.12.1-SNAPSHOT development build 134, `git-DEV-e94bed1` | Deviation from planned official build 1302; no blanket bridge/version compatibility claim. |
| Floodgate | 2.2.5-SNAPSHOT build 141, `b141-81b65cc` | Matches the planned authentication-provider build. |
| Java client/input | Minecraft 26.3, Fabric Loader 0.20.1, mouse input | Executed client; unmodified-client and controller coverage are not inferred. |
| Bedrock client/input | Reported version `1.26.62.1`, Android tablet, touch; observed protocol 2225 | Version retained as reported, without converting it into a supported-version range or changing the planned PE target. Findings apply to this tested input mode. |

| Artifact | Source / SHA-256 |
| :--- | :--- |
| Plugin | `InfernoLantern-0.0.0-SNAPSHOT-gf3aea45255cd.jar`, source `f3aea45255cddfe3c602bb531b297800f949d3e0`; `617980ec1d742b2a277d9c35a270649626694cd3ad41ad3bccfd5f872ad364fa` |
| Java resource pack | `infernolantern-prototype-java.zip`; `2c21821cc2362cc1d9fc1d0e80aebb71de3e9c1e7c07a01b9899573451c1b84d` |
| Bedrock resource pack | `infernolantern-prototype-bedrock.mcpack`; `7845402cacff9fdac61384842368c5bd369591f6a5560dfe1b1f25dedd34a642` |
| Geyser mapping | `infernolantern-prototype-mappings.json`; `a3962bc5be25e94a2ef38b63c7e34d8eba49e5078f11b33043d78db1ad996119` |

The deployed artifacts were checked against the retained build digests. This report contains no environment addresses, player identity, private world coordinates, raw configuration/log dumps or identifying screenshots.

---

## 1. Prototype Models and Asset Specifications

Editable source models, textures, animations, and client format exports are committed in
`src/main/resources/assets/infernolantern/`. The switch and attack-head descriptions below identify artwork roles; the executed fixture uses menu controls and implements no combat or individually clickable model switches.

### 1.1 Compact Lantern Base (`compact_lantern`)

- **Source File:** `models/compact_lantern.bbmodel`
- **Bounds:** Strictly $16 \times 16 \times 16$ Blockbench units ($1 \times 1 \times 1$ block volume, $[0, 0, 0]$ to $[16, 16, 16]$).
- **Element Count:** 100 cubes across 7 hierarchical groups.
- **Structural Features:**
  - 16-sided stepped ground anchor pedestal ($Y: 0 \to 3$).
  - 16-sided inner core with translucent glowing molten glass cylinder ($Y: 3 \to 12$).
  - 4 rounded corner columns with brass rivet fittings.
  - 16-sided stepped pagoda roof ($Y: 12 \to 15.5$).
  - Central telescoping plunger rod ($Y: 3 \to 14.5$, 16-sided cylinder) that drives the mechanical compaction/expansion mechanism.
  - Rectangular finial base and arched wrought-iron pull handle ($Y: 14.5 \to 16$).
- **Client Exports:**
  - Java Block Model: `models/block/compact_lantern.json` (100 elements, strictly $[0, 16]$ bounds).
  - Java Item Model: `models/item/compact_lantern.json` (configured with thirdperson, firstperson, and GUI display transforms).
  - Bedrock Geometry: `models/bedrock/compact_lantern.geo.json` (`geometry.infernolantern.compact_lantern`).

### 1.2 Expanded Antique Tower (`expanded_lantern`)

- **Source File:** `models/expanded_lantern.bbmodel`
- **Master Bounds:** $16 \times 48 \times 16$ Blockbench units ($1 \times 3 \times 1$ block volume, $[0, 0, 0]$ to $[16, 48, 16]$ / 3.0m height). Zero horizontal overhang beyond the 1-block base.
- **Element Count:** 106 cubes organized into 6 functional root groups:
  1. `Base_Pedestal` ($Y: 0 \to 14$): 16-sided stepped base anchor, reinforced footing, and maintenance access hatch.
  2. `Operational_Switches` ($Y: 11 \to 13$, reach height $0.7\text{m} \to 0.8\text{m}$):
     - **Switch A (Light Toggle):** Left collar lever controlling real world light emission.
     - **Switch B (Defence Toggle):** Right collar lever controlling automated combat targeting.
     - Rotation angles set to vanilla-compatible $\pm 22.5^\circ$.
  3. `Column_Shaft` ($Y: 14 \to 32$): Fluted 16-sided antique wrought iron column with brass banding.
  4. `Combustion_Chamber` ($Y: 34 \to 42$): Elevated attack emitter with 16-sided molten glowing core. Beam origin elevated at $Y \approx 2.4\text{m}$ ($Y=38$ units) to maintain clear line of sight over surrounding mobs and terrain.
  5. `Outer_Columns`: 4 reinforced vertical corner pillars shielding the combustion core.
  6. `Tower_Roof` ($Y: 43 \to 48$): 16-sided stepped pagoda roof crowned with an antique spear finial.
- **Client Exports:**
  - Java Block Model: `models/block/expanded_lantern.json` (106 elements). To satisfy vanilla Java client parser limits (`CuboidModelElement.Deserializer` enforcing $[-16.0, 32.0]$), coordinates are normalized to $[0.0, 16.0]$ centered horizontally at $[8, 8]$. This is the normalized source export. The executed fixture derives an ArmorStand head-slot item from it; its additional head transform is documented in [the fixture guide](RENDERING_PROTOTYPE.md#representation-and-limits). It does not spawn a display entity.
  - Bedrock Geometry: `models/bedrock/expanded_lantern.geo.json` (`geometry.infernolantern.expanded_lantern`, 6 bones, 106 cubes).
  - Procedural Generator: `scripts/generate_expanded_lantern.py`.

### 1.3 Textures and Animation Metadata

- `textures/block/lantern_frame.png` ($64 \times 64$): High-resolution palette texture featuring dark oxidized wrought iron base plates, antique brass trims, and highlighted column fluting.
- `textures/block/lantern_glow.png` ($64 \times 512$): 8-frame seamless vertical strip depicting molten gold/lava liquid convection.
- `textures/block/lantern_glow.png.mcmeta`: Java vanilla animation descriptor configured with `"interpolate": true` and `"frametime": 3` for interpolated three-tick keyframes.
- `textures/flipbook_textures.json`: Source block flipbook metadata. The executed Bedrock attachable uses glow frame zero; animated attachable parity is not implemented or accepted by this test.

### 1.4 Reproducible Export and Normalization Pipeline

Vanilla Java client parsers (`CuboidModelElement.Deserializer`) reject any coordinate
outside $[-16.0, 32.0]$ with `JsonParseException`. The expanded master model is authored
at full $1 \times 3 \times 1$ scale ($Y \in [0.0, 48.0]$), so exporting the Java block model
requires normalizing vertices and rotation origins into standard 1-block bounds ($[0.0, 16.0]$).

#### Mathematical Normalization Specification
To transform full-scale coordinates $(X, Y, Z)$ into legal client bounds:
- **Horizontal Anchor:** Centered at block coordinates $X_c = 8.0, Z_c = 8.0$.
- **Vertical Anchor:** Ground baseline $Y_0 = 0.0$.
- **Scale Factor:** Uniform reduction factor $S = 1/3$.
- **Vertex Transformation:**
  $$\begin{aligned}
  X' &= 8.0 + \frac{X - 8.0}{3.0} \\
  Y' &= \frac{Y}{3.0} \\
  Z' &= 8.0 + \frac{Z - 8.0}{3.0}
  \end{aligned}$$
- **Rotation Origin Transformation:**
  $$\text{origin}' = \left[ 8.0 + \frac{\text{origin}_X - 8.0}{3.0}, \; \frac{\text{origin}_Y}{3.0}, \; 8.0 + \frac{\text{origin}_Z - 8.0}{3.0} \right]$$
- **Rotation Angles:** All cube rotation angles must strictly conform to vanilla permitted angles:
  $\{-45^\circ, -22.5^\circ, 0^\circ, 22.5^\circ, 45^\circ\}$.
- **UV Space Conversion:** Blockbench master and Bedrock geometry specify face UVs in texture pixel
  coordinates ($0 \to 64$). Java block models require UVs in vanilla $0 \to 16$ units
  (`CuboidFace.getU/getV` divides UV coordinates by 16.0). Master pixel UVs are mapped to Java UV space via:
  $$U_{\text{Java}} = U_{\text{pixel}} \times \frac{16}{\text{width}}, \quad V_{\text{Java}} = V_{\text{pixel}} \times \frac{16}{\text{height}}$$
  For the $64 \times 64$ texture palette, this applies a factor of $16/64 = 0.25$, mapping an $8 \times 8$
  pixel texture region to $[0.0, 0.0, 2.0, 2.0]$.
- **Display-Entity Option (not the executed fixture):** A display entity could scale the normalized model by $[3.0, 3.0, 3.0]$. The selected fixture instead uses the derived head-slot model and transform described in the fixture guide; its Java transform respects the item scale clamp.

#### Reproducing Exports via Script Pipeline
The pipeline is fully automated and deterministic in `scripts/generate_expanded_lantern.py`:
```bash
python3 scripts/generate_expanded_lantern.py
```
This command reproduces all three assets:
1. `src/main/resources/assets/infernolantern/models/expanded_lantern.bbmodel` (master full-scale model, 106 elements).
2. `src/main/resources/assets/infernolantern/models/block/expanded_lantern.json` (normalized Java block JSON with 106 elements in $[0.0, 16.0]$).
3. `src/main/resources/assets/infernolantern/models/bedrock/expanded_lantern.geo.json` (Bedrock geometry with 6 bones and 106 cubes).

#### Manual Export in Blockbench
1. Open Blockbench and load `compact_lantern.bbmodel` or `expanded_lantern.bbmodel`.
2. For `compact_lantern`:
   - Navigate to `File` > `Export` > `Export Block/Item Model` (saved to `models/block/compact_lantern.json`).
   - Convert to Bedrock project and navigate to `File` > `Export` > `Export Bedrock Geometry` (saved to `models/bedrock/compact_lantern.geo.json`).
3. For `expanded_lantern`:
   - Blockbench direct export outputs master 48-unit coordinates; run `python3 scripts/generate_expanded_lantern.py` to regenerate the normalized Java block JSON and Bedrock geometry without coordinate clipping.

---

## 2. Representation Decision for Rendering Feasibility

| Candidate | Decision and evidence |
| :--- | :--- |
| Mapped vanilla block plus a future living target proxy | Not selected for this fixture. The full-height master needs legal Java model exports and additional mapping/collision design. No empirical rejection or multi-block seam measurement is claimed. |
| Native `ItemDisplay` / `BlockDisplay` plus separate hitboxes | Not selected for this fixture. The run did not establish direct display-entity translation on Bedrock; an additional translation path would need its own proof. |
| Invisible ArmorStand wearing a mapped custom helmet | Selected for the next feasibility step. Compact and expanded models rendered on both recorded clients using the derived Java item models, Geyser v2 item mappings and Bedrock attachables with the integrated-pack head pivot `[0, 23, 0]`. A separate damageable target path is still required by #6. |

The executed fixture places no `Interaction` entities and does not test separate on-model switch hitboxes. Right-click/tap opens its nine-slot menu. Both clients receive the ArmorStand helmet representation; this does not rely on native Java display-entity translation. The ArmorStand is a visual component, not a demonstrated zombie target or authoritative production tower record.

| Fixture state | Visible model / clearance | Implemented collision | World light |
| :--- | :--- | :--- | :--- |
| Compact | 1 x 1 x 1 | Barrier at the base | None |
| Expanded | 1 x 3 x 1 | Barriers at base and base+1, a 1 x 2 x 1 shaft | `LIGHT` at base+2, level 0 or 15 |

The upper light cell is not also a collision barrier. The selected visible volume is a single three-block column, not a 3 x 1 x 3 footprint. The primitive collision shaft and opt-in operator-only fixture are experiment constraints, not selected production geometry or access policy.

## 3. Executed Rendering and Lifecycle Results

The following table distinguishes reported live observations from implementation/policy review. No cell means that all client versions, devices or production conditions passed.

| Test | Steps and observed result | Result / scope |
| :--- | :--- | :--- |
| Join and pack use | Java and the recorded Android Bedrock client joined and rendered the supplied custom items. | Observed pass for the recorded pair; no mobile stability or tick-time measurement beyond this run. |
| Compact placement | Place on solid support; compact model centred in one block with textures present and support unchanged. | Observed pass on Java and Bedrock. |
| Expansion | Expand from the menu; three-block model aligned within the 1 x 3 x 1 clearance, without horizontal overhang. | Observed pass on Java and Bedrock. Compact-model shimmer is retained as an artwork limitation. |
| Independent controls | Exercise off/off, light/on only, defence/on only and both/on; no combat occurs. | Observed pass. Light is level 0 or 15; defence is only a flag. |
| Input and inventory | Operate menu actions using Java mouse and tested Android touch mode; no menu-item extraction or inventory loss reported. | Observed pass with the touch gesture and layout limitations in section 4. No controller claim. |
| Real light | Toggle the apex LIGHT block and observe surrounding illumination as well as level-15 source state. | Observed pass; emissive textures alone were not counted as illumination. No fixed-radius or mob-spawn guarantee is inferred. |
| Collision | Move against the fixture; the two-block barrier shaft prevents passage. | Observed pass for the tested movement; upper light-cell collision differs by design. |
| Obstruction and overlap rejection | Solid apex obstruction blocks expansion; water/entity occupancy rejects placement; targeting an existing fixture does not create a duplicate. Unrelated state remains unchanged. | Observed pass for those cases. The implementation rejects all fluids; lava placement is not separately claimed as observed. |
| Bucket protection | Try water/lava from main/offhand, including placement against an adjacent untracked face into the fixture volume. Fixture cells remain protected and buckets remain available. | Observed pass for the reported test cases. |
| Rapid repeated actions | Burst expand/compact and light actions from one operator session; one visual and no escaped menu items or partial transitions. | Observed pass for one actor. Two-operator concurrency not executed; follow-up in #10. |
| Compaction and removal | Compact clears upper barrier/light cells; remove clears the visual and base cell, retaining the support. | Observed pass on Java and Bedrock. |
| Legacy manifest recovery | Deploy PR #40 with the original manifest; three retained barrier cells restore to air. | Observed pass; no manifest deletion or force-clear was used. |
| Chunk unload/reload | Expand, leave the world, verify its loaded-chunk count reaches zero, then return. Fixture volume is clear and no tagged visual remains. | Observed pass, with `active fixtures=0; cleanup cells=0` after return. |
| Clean restart with active fixture | Restart cleanly while expanded. Shutdown removes the visual and restores three cells; after boot the volume is clear and status is zero/zero. | Observed pass for graceful shutdown/restart; not an abrupt-crash or power-loss test. |
| Disabled and nonoperator restrictions | Disabled creation commands reject. Nonoperator usage cannot create fixtures or inspect administrative status; generic help remains available. | Observed pass for command restrictions. Recovery listeners while disabled were audited; a retained-cell recovery run specifically under the disabled flag was not separately demonstrated. |
| External block edit | Change a tracked cell to a distinct block, clean up, verify that block and one retained conflict survive; restore the recognizable fixture state and run normal cleanup. | Observed pass; final counts return to zero. Genuine waterlogging remains protected by the regression tests, not a new live waterlogging claim. |
| Java pack disabled | Disable the Java pack; the custom helmet falls back to the vanilla carved-pumpkin appearance. | Observed failure-mode behavior, not successful custom rendering without a pack. |
| Bedrock pack refusal | Inspect effective forced-pack policy with integrated pack active. | Policy audited; an actual refusal/disconnect run was not separately demonstrated. Production delivery acceptance remains in #23. |

Final recorded state: zero active fixtures, zero cleanup cells and an 8-byte empty manifest header, with tested fixture cells restored to air and no orphaned visual entities. These are lifecycle observations, not proof of durable production transactions or unlimited repetition.

## 4. Client and Artwork Limitations

- On the tested Android touch mode, a single tap selected a chest item locally. A selection/transfer gesture was needed to reach the server-side menu action. Each required control was operated using that gesture; single-tap chest operation was not established.
- On the tested tablet layout, the ninth slot moved to another row and its barrier icon appeared blank. The slot was operated, but fixed nine-column presentation is not accepted for production touch UX. This observation is not a rule for all Bedrock devices or control schemes.
- Compact-model shimmering while moving the Java camera was reported. Coplanar faces on rotated tiers are a suspected cause and are tracked for #23. A blanket 0.01-unit offset has not been validated; any art fix must preserve geometry bounds, legal rotations, UVs, source/editability and both export paths.
- Paper Dialog controls are a future interface proposal. This fixture has no Dialog implementation and the proposal was not tested on this bridge. It is not an adopted production decision and does not turn #6 into a UI rewrite.
- The Bedrock attachable uses glow frame zero. Animated Java textures do not establish animated Bedrock attachable parity. Final artwork, variant packaging and production pack readiness remain in #23.

## 5. Environmental and Recovery Boundaries

The fixture rejects fluid-filled placement/expansion space and does not replace or preserve water/lava as a deployed feature. Waterlogged LIGHT preservation remains production work in #21. The source-level piston, fluid-entry and explosion block guards are not a completed production environmental/protection matrix.

The initial shorthand barrier state did not equal Paper's canonical `minecraft:barrier[waterlogged=false]`, causing false cleanup conflicts. PR #40 canonicalizes full block states for new operations and old ILP1 manifest entries. The observed legacy cleanup and graceful lifecycle tests demonstrate that fix on the recorded build; they do not prove world-save ordering, abrupt-process recovery, power-loss durability or the operation journal planned in #8-#11. The existing manifest format and original-state snapshots were retained.

The visual ArmorStand is not proof of zombie reachability, retaliation, one-debit-per-hit routing, target release or loot/XP suppression. Those are #6's dedicated end-to-end acceptance requirements. Broader combat, final pack adapters and reward logic must not infer them from rendering success.

## 6. Scoped Acceptance and Remaining Gate

- [x] Editable compact/expanded Blockbench sources, textures and reproducible export instructions are included.
- [x] Same compact/deployed representation rendered within declared dimensions on the recorded Java and Android Bedrock clients.
- [x] Independent menu controls were operated with Java mouse and tested touch gestures, with input/layout limits recorded.
- [x] Real LIGHT source, the declared collision shaft, compaction/removal, legacy cleanup, actual unload/reload and a clean restart were observed.
- [x] A representation is selected for the next feasibility task, with unsupported cases and actual build deviations recorded.
- [ ] Two-operator concurrency remains unexecuted; track production transition validation in #10.
- [ ] Active Bedrock pack refusal and production delivery failure handling remain unexecuted; track in #23.
- [ ] Zombie reachability/retaliation, one HP debit per melee hit, release after compaction/removal and proxy loot/XP suppression remain #6.

This evidence supports scoped #5 rendering acceptance with the documented prototype limitations. It does not mark full G0 complete. G0 remains open until #6's zombie proof passes; production lifecycle, recovery, UI, environmental and pack-delivery gates remain separate.
