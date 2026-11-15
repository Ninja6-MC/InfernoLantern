# Rendering and Interaction Feasibility Evidence

Task: #5. Gate: G0 (Rendering and Mob Feasibility).
Evaluated: 2026-11-15.
Status: Candidate B selected; Candidate A rejected for expanded deployment.

## Scope

This evidence record evaluates the visual rendering, client asset formats, physical
interaction ergonomics, and environmental constraints for the compact base and expanded
tower representations of Inferno Lantern. It fulfills the Gate G0 prototype rendering
requirements under Epic 1 (#2) and Task 1.3 (#5).

Runtime combat verification (zombie pathfinding, attack retaliation, damage debits) is
tested in Task 1.4 (#6). Persistent state journaling and operation transactions are built in
Task 2.1 (#8). Final artwork packaging and production branding overrides remain in Task 6.1
(#23).

---

## 1. Prototype Models and Asset Specifications

Editable source models, textures, animations, and client format exports are committed in
`src/main/resources/assets/infernolantern/`.

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
  - Java Block Model: `models/block/compact_lantern.json`
  - Java Item Model: `models/item/compact_lantern.json` (configured with thirdperson, firstperson, and GUI display transforms)
  - Bedrock Geometry: `models/bedrock/compact_lantern.geo.json` (`geometry.infernolantern.compact_lantern`)

### 1.2 Expanded Antique Tower (`expanded_lantern`)

- **Source File:** `models/expanded_lantern.bbmodel`
- **Bounds:** Strictly $16 \times 48 \times 16$ Blockbench units ($1 \times 3 \times 1$ block volume, $[0, 0, 0]$ to $[16, 48, 16]$ / 3.0m height). Zero horizontal overhang beyond the 1-block base.
- **Element Count:** 106 cubes organized into 6 functional root groups:
  1. `Base_Pedestal` ($Y: 0 \to 14$): 16-sided stepped base anchor, reinforced footing, and maintenance access hatch.
  2. `Operational_Switches` ($Y: 11 \to 13$, reach height $0.7\text{m} \to 0.8\text{m}$):
     - **Switch A (Light Toggle):** Left collar lever controlling real world light emission.
     - **Switch B (Defence Toggle):** Right collar lever controlling automated combat targeting.
  3. `Column_Shaft` ($Y: 14 \to 32$): Fluted 16-sided antique wrought iron column with brass banding.
  4. `Combustion_Chamber` ($Y: 34 \to 42$): Elevated attack emitter with 16-sided molten glowing core. Beam origin elevated at $Y \approx 2.4\text{m}$ ($Y=38$ units) to maintain clear line of sight over surrounding mobs and terrain.
  5. `Outer_Columns`: 4 reinforced vertical corner pillars shielding the combustion core.
  6. `Tower_Roof` ($Y: 43 \to 48$): 16-sided stepped pagoda roof crowned with an antique spear finial.
- **Client Exports:**
  - Java Block Model: `models/block/expanded_lantern.json`
  - Bedrock Geometry: `models/bedrock/expanded_lantern.geo.json` (`geometry.infernolantern.expanded_lantern`)
  - Procedural Generator: `scripts/generate_expanded_lantern.py`

### 1.3 Textures and Animation Metadata

- `textures/block/lantern_frame.png` ($64 \times 64$): High-resolution palette texture featuring dark oxidized wrought iron base plates, antique brass trims, and highlighted column fluting.
- `textures/block/lantern_glow.png` ($64 \times 512$): 8-frame seamless vertical strip depicting molten gold/lava liquid convection.
- `textures/block/lantern_glow.png.mcmeta`: Java vanilla animation descriptor configured with `"interpolate": true` and `"frametime": 3` for fluid 20 FPS molten motion.
- `textures/flipbook_textures.json`: Bedrock/Geyser flipbook texture mapping binding `lantern_glow` frames to tick cycles.

### 1.4 Reproducible Blockbench Export Instructions

1. Open Blockbench (desktop application or web editor).
2. Load `src/main/resources/assets/infernolantern/models/compact_lantern.bbmodel` or `expanded_lantern.bbmodel`.
3. To export Java Block/Item format:
   - Navigate to `File` > `Export` > `Export Block/Item Model`.
   - Save to `src/main/resources/assets/infernolantern/models/block/<name>.json`.
4. To export Bedrock Geometry format:
   - Convert project to Bedrock model (`File` > `Convert Project` > `Bedrock Model`).
   - Navigate to `File` > `Export` > `Export Bedrock Geometry`.
   - Ensure the geometry name matches `geometry.infernolantern.<name>`.
   - Save to `src/main/resources/assets/infernolantern/models/bedrock/<name>.geo.json`.

---

## 2. Architectural Candidate Evaluation

| Evaluation Criteria | Candidate A: Mapped Vanilla Block + Living Proxy | Candidate B: Display Entities + Managed Hitbox Components |
| :--- | :--- | :--- |
| **Model Height & Bounds** | **Rejected.** Vanilla block JSON models enforce strict coordinate bounds $[-16, 32]$. A 3-block tall model ($Y=48$) cannot be rendered by a single block model; it requires arbitrary multi-block coordinate slicing across 3 real block positions, causing visible seam artifacts during chunk meshing. | **Selected.** `ItemDisplay` / `BlockDisplay` entities natively support arbitrary scale, transformation matrices, and vertical bounds without coordinate clipping. The entire 3-block tower renders as a unified visual entity. |
| **Crossplay Parity (Geyser)** | **Fragile.** Requires registering custom blocks in Geyser with custom Bedrock resource pack block definitions. Custom blocks on Bedrock suffer from lighting tint desync, custom break times, and geometry size limits. | **Robust.** Geyser translates Java Display Entities natively into client-side Bedrock dummy entities without requiring custom block registrations or server packet hacks. Visual fidelity and animations match 1:1 on both clients. |
| **Physical Collision** | Native vanilla block collision box, but limited to integer or 1-block steps; extending to $1 \times 3 \times 1$ requires placing artificial barrier/structure blocks on adjacent tiles. | Server places invisible vanilla `BARRIER` blocks at coordinates $Y=1$ and $Y=2$ (pedestal base at $Y=0$ uses normal block collision). Players and mobs cannot walk through the shaft. |
| **Player Interaction** | Relies on block click events (`PlayerInteractEvent`). Sub-block element targeting (clicking Switch A vs. Switch B) requires calculating cursor-to-face vector raycasts in Java code, which is prone to client/server latency desync. | Dedicated `Interaction` entities with tailored bounding boxes placed at $Y \approx 11 \to 13$ ($0.7\text{m} \to 0.8\text{m}$). Standard vanilla `PlayerInteractAtEntityEvent` delivers exact clicked component identity. |
| **Mob Targeting & Damage** | Requires spawning an invisible living entity proxy (e.g. `ArmorStand` or `Slime`) inside the block. Hostile mob AI can target the living proxy, but requires manual proxy damage cancellation and health redirection. | An invisible living proxy (or custom Paper `MobGoals`) is spawned inside the tower volume. Mob melee attacks hit the proxy, triggering custom HP debits and repair locks. |
| **Compaction & Cleanup** | Breaking or compacting requires multi-block state removal, risking orphaned ghost blocks if a block update is interrupted. | Compacting requires killing the display/interaction entities and clearing the $Y=1, 2$ barrier and light blocks. Atomic cleanup via tower UUID is instant and leaves no orphaned client tile entities. |

### Decision
**Candidate B is selected** as the primary rendering architecture for the expanded tower. Candidate A is rejected for expanded towers due to the vanilla $[-16, 32]$ coordinate clamp and multi-block meshing artifacts. For the compact $1 \times 1 \times 1$ base, a hybrid approach is supported: an underlying placed block providing base collision and PDC storage, paired with an `ItemDisplay` or block model for high-fidelity animations.

---

## 3. Ergonomics and Cross-Platform Interaction Analysis

§2 of the Requirements specifies two independent deployed controls: **Light Toggle** and **Defence Toggle**.

### 3.1 Switch Placement and Survival Reach
- **Height Placement:** Both switches are positioned on the base pedestal collar at $Y = 11 \to 13$ Blockbench units ($0.69\text{m} \to 0.81\text{m}$ above ground level).
- **Survival Reach Mechanics:** Vanilla Minecraft survival interaction reach is strictly 3.0 blocks from player eye level ($Y_{\text{feet}} + 1.62\text{m}$).
  - Positioning switches at waist level ($Y \approx 0.75\text{m}$) ensures that a player standing on even ground, elevated terrain, or a 1-block downward slope remains within valid reach distance ($\approx 1.8\text{m}$ raycast distance).
  - Placing controls on the upper combustion chamber ($Y \ge 2.2\text{m}$) would fail reach checks for players standing on slopes or crouching.

### 3.2 Mouse vs. Touch / Controller Input
- **Java Edition (Mouse / Keyboard):**
  - Precise mouse crosshairs allow direct pixel raycasting against the individual `Interaction` entity hitboxes for Switch A (Light) and Switch B (Defence).
  - Visual feedback: Sub-pixel switch lever toggle animation or particle burst confirms activation.
- **Bedrock Edition (PE Touch Controls & Gamepad):**
  - Capacitive touch screens lack cursor crosshairs; tapping small switch hitboxes on mobile screens is prone to false touches or misclicks.
  - **Required Touch Parity Strategy:** When a player right-clicks / taps anywhere on the tower structure (or holds interact on Bedrock), an accessible controller UI menu opens with two large, labelled toggle buttons:
    - `[ Toggle Light ]` (Current: ON / OFF)
    - `[ Toggle Defence ]` (Current: ON / OFF)
  - This ensures 100% operational parity for mobile and console players without requiring pixel-perfect finger taps.

### 3.3 Compaction Ergonomics
- The top finial pull-handle ($Y = 3.0\text{m}$) is visually retained from the compact lantern to communicate mechanical continuity.
- However, manual compaction cannot require physically clicking the 3.0m finial from the ground (exceeds reach on uneven ground).
- Manual compaction is executed via:
  1. An explicit `[ Compact Lantern ]` button inside the controller GUI menu.
  2. Shift-right-clicking the ground pedestal base ($Y=0$) by an authorized controller or administrator.

---

## 4. World Light and Environmental Integrity

### 4.1 Emissive Textures vs. Server-Side World Light
- Emissive texture configurations (such as OptiFine/Iris emissive properties or Bedrock material shaders) only modify client-side fragment shader brightness. They make the core visually glow in the dark, but emit **zero real world block light** ($L = 0$).
- Hostile mobs (Zombies, Skeletons, Creepers) continue to spawn on adjacent blocks if world block light remains zero.

### 4.2 Real Light Implementation Strategy
- When the **Light Toggle** is active:
  - The server places a vanilla `LIGHT` block with light level 15 at block coordinate $Y = 2$ (inside the elevated combustion core).
  - Placing the light source at $Y = 2$ provides maximum radial ground illumination ($r \approx 15$ blocks) and prevents mob spawning in the defensive perimeter.
- When the **Light Toggle** is deactivated:
  - The `LIGHT` block at $Y = 2$ is removed (replaced with `AIR`).

### 4.3 Fluid Integrity (Water and Lava)
- **Water Placement:** If the tower expands in water, placing a normal `LIGHT` block would destroy the water source block, creating an air pocket and disrupting fluid flow.
  - The `LIGHT` block must be placed with property `waterlogged = true`.
  - When compacted or destroyed, the `waterlogged` state preserves the water source block without fluid drainage.
- **Lava Behaviour:** Vanilla `LIGHT` blocks cannot be waterlogged in lava. By default, placement and expansion in lava reject safely with private player feedback, in accordance with §2 of the Requirements.

### 4.4 Multi-Block Piston Protection
- Display entities and barrier blocks do not naturally bind to piston movement.
- A piston pushing block $Y=1$ or $Y=2$ would displace the barrier while leaving floating display entities.
- Piston listeners must listen for `BlockPistonExtendEvent` and `BlockPistonRetractEvent`, checking all three vertical coordinates ($Y=0, 1, 2$). Any piston movement intersecting the tower volume must be cancelled.

---

## 5. Unsupported Cases and Engine Constraints

1. **Hostile Mob AI with `Interaction` Entities:**
   - In Paper 26.3, `Interaction` entities extend `Entity`, not `LivingEntity`.
   - Vanilla mob targeting (`NearestAttackableTargetGoal`, `MeleeAttackGoal`, and `Mob#setTarget`) strictly requires a `LivingEntity`.
   - Directly configuring a Zombie to target an `Interaction` entity throws an `IllegalArgumentException`.
   - **Resolution for Task 1.4:** Mob targeting must use an invisible, invulnerable, loot/XP-suppressed living proxy (e.g. `ArmorStand` or custom mob), or custom Paper `MobGoals` targeting the block coordinate.

2. **Standalone Emissive Textures Without Server Light:**
   - Emissive textures alone cannot satisfy Gate G0 lighting requirements. A real vanilla `LIGHT` block is mandatory.

3. **In-Flight Compaction Puller at 3m Height:**
   - Physical click interaction on the finial handle at $Y=3.0\text{m}$ is unsupported for survival players on ground level. Compaction is routed through the pedestal collar / UI menu.

---

## 6. Gate G0 Feasibility Checklist

- [x] Rough Blockbench models authored for compact base ($1 \times 1 \times 1$) and expanded tower ($1 \times 3 \times 1$).
- [x] Editable `.bbmodel` source files committed in repository assets.
- [x] Dual-client format exports generated: Java Block/Item JSON and Bedrock Geometry `.geo.json`.
- [x] Seamless animated molten core texture and metadata created (`.mcmeta` and `flipbook_textures.json`).
- [x] Candidate A vs. Candidate B evaluated; Candidate B selected for expanded deployment.
- [x] Interaction reach ergonomics analyzed for mouse raycast and PE touch controls.
- [x] Real world light strategy documented (vanilla `LIGHT` block at $Y=2$, `waterlogged = true` handling).
- [x] Multi-block piston and fluid constraints documented.
- [x] Unsupported cases (direct non-living mob targeting, 3m reach) identified.
- [x] Gate G0 rendering feasibility passed; ready for combat path verification in Task 1.4 (#6).
