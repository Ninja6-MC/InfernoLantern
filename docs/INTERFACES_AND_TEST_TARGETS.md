# Interfaces and Test Targets

Task: #3. Decision record prepared 2026-11-13. The maintainer selected the platform baseline, command root/alias and permission namespace below. The maintainer also approved the command tokens and permission defaults below on 2026-11-13. No implementation or runtime compatibility is claimed. For the selected names and platform baseline, this record updates the earlier undecided entries in the [requirements](REQUIREMENTS.md) and [technical solution](SOLUTION.md).

## Scope

Inferno Lantern is a standalone GPL-3.0 Paper plugin. The first milestone proves compact/deployed rendering, real illumination, interaction, cleanup and one zombie damage path with Java and Bedrock clients. Folia, PvP, final artwork and reward adapters do not gate that feasibility milestone. Do not introduce an acquisition-gating dependency.

## Version and provider matrix

| Component | Target | Selection and evidence status |
| :--- | :--- | :--- |
| Paper server | Minecraft 26.3, build 249, stable | Selected 26.3 baseline; proposed reproducible build pin. Official metadata checked; not executed. |
| Paper API | `io.papermc.paper:paper-api:26.3.build.249-stable` | Exact published POM verified; dependency has not been compiled or tested. |
| Java runtime/toolchain | Java 25; record exact vendor and patch when the test environment is prepared | Maintainer-approved repository baseline; Paper 26.3 requires Java 25. No runtime test yet. |
| Java client | Minecraft Java Edition 26.3, mouse/keyboard | Maintainer confirmed Java mouse/keyboard availability and can use this selected version; not tested. |
| Crossplay bridge | Geyser-Spigot 2.12.1 build 1302 | Official build metadata checked; not executed. |
| Bedrock client | Latest stable PE/mobile client available when testing begins, using touch input | Maintainer confirmed PE availability. Capture the exact installed version before execution and verify it against the pinned bridge. Geyser currently lists 26.40-26.63; this is upstream protocol support, not plugin compatibility. |
| Optional authentication provider | Floodgate-Spigot 2.2.5 build 141, only if the generic test setup needs Floodgate authentication | Official build metadata checked; not a mandatory plugin dependency or a selected authentication topology. |
| ViaVersion/proxy | None required for the proposed same-version baseline | Add only if a separately approved test matrix needs it. |
| Claim/protection and dragon providers | Not selected at this stage | Resolve in their later implementation tasks. No compatibility claim. |

Proposed reproducible artifact pins (SHA-256):

| Artifact | SHA-256 |
| :--- | :--- |
| paper-26.3-249.jar | 4c734aeb4da98845934af1e5481f0df7c80ad3ca50d272319ffe2b0e8a187c8a |
| Geyser-Spigot.jar, 2.12.1 build 1302 | e13592d8ef907657eb9bae6f778dcb6ccb7f4a5b4641baeeb298ff5d13ccc92f |
| floodgate-spigot.jar, 2.2.5 build 141, optional | 21570aff9ce17d6983928e8552777760e1ede5050026b04c686b0ae112e6fd7e |

These are planning pins, not instructions to upgrade a live server. Geyser and optional Floodgate are test-environment providers rather than mandatory runtime dependencies of Inferno Lantern. If a client update requires another build, revise the matrix explicitly and rerun affected evidence rather than silently changing targets.

The selected Java 25 baseline departs from the inherited [contribution guidance](https://github.com/Ninja6-MC/.github/blob/main/CONTRIBUTING.md#3-development-workflow), which currently names Java 21. Paper 26.3 requires Java 25, and the maintainer approved that repository-specific baseline on 2026-11-13. Task #4 must document and pin the selected toolchain.

## Public interface decisions

The maintainer approved the standalone registered root `/infernolantern`, declared alias `/ilantern`, and permission namespace `infernolantern` on 2026-11-13. Do not register or advertise a shared organization command root. The long root remains usable if the short alias is unavailable. These are selected future names, not implemented commands.

### Selected command tokens and permissions

The maintainer approved the following command tokens and permission defaults on 2026-11-13. These names define the intended interface; no command is implemented yet.

| Command token | Purpose | Permission |
| :--- | :--- | :--- |
| `help` | Available usage, with unauthorized admin actions hidden | No permission for generic help; action details filtered by authorization. |
| `status` | Administrative inspection of configuration/operation readiness without credentials or player inventories | `infernolantern.admin.status` |
| `reload` | Validate and publish effective configuration without discarding last-good settings | `infernolantern.admin.reload` |
| `award` | Trusted idempotent issuance; exact arguments and campaign-key contract deferred to #24 | `infernolantern.admin.award` |
| `override on\|off\|status` | Explicit actor/session override control; player session required | `infernolantern.admin.override` |

Command tokens identify future interfaces, not a requirement to implement gameplay or reward commands in #4. Do not document an executable command before its implementation exists. Approve names before their implementation; any token later documented as a third-party configuration hook needs a compatible dispatcher alias if renamed.

Native usage permission: `infernolantern.use`, default true. Each native action still checks applicable variant eligibility, current controller, world/fluid restrictions, protection, HP, cooldown and lifecycle conditions. Possession does not grant permission. No permission in this plugin gates manual pickup/storage withdrawal; separately configured external acquisition policy remains independent.

Administrative parent: `infernolantern.admin`, default op, with an explicit `children` mapping granting `infernolantern.admin.status`, `infernolantern.admin.reload`, `infernolantern.admin.award` and `infernolantern.admin.override`. Individual admin children default false and are granted through the parent or explicit external assignments. Dot notation alone grants no inheritance. The admin parent must not implicitly grant arbitrary configured variant permissions.

Holding `infernolantern.admin.override` permits toggling override; it never activates bypass by itself. The toggle is player-specific, in-memory and cleared on logout/disconnect/restart. Administrative issuance, inspection and reload do not activate override. The override cannot bypass disabled capabilities, retirement, clearance, identity, repair payment/cooling or other integrity requirements.

Variant IDs and optional configured eligibility-node names are chosen with variant configuration; no tester/dragon node is pre-granted by the generic default. Final action-specific usage-node granularity can be considered before #14 without silently changing this approved contract.

## Generic test-world matrix

Prepare disposable, unbranded test fixtures described by their characteristics, not real world names/UUIDs, server addresses or private infrastructure. The following matrix is the test plan; it does not claim that fixtures have already been provisioned or tested. Initial scenarios propose normal difficulty with controlled lighting and mob conditions; this is a reproducible test setting rather than a gameplay default.

| Generic fixture | Required checks |
| :--- | :--- |
| Flat Overworld test area | Compact 1 x 1 x 1 base; full declared deployment volume; independent visible controls/menu; Java and Bedrock appearance; real block light; safe compaction/removal. |
| Obstruction/collision area | Wall/ceiling and entity obstruction, competing footprints, orientation, private rejection, no partial structure or unrelated block overwrite. |
| Dark zombie lane | Reachability, actual tower hit then retaliation, one HP debit per zombie melee hit, no proxy loot/XP, target release after compaction. |
| Chunk-boundary area | Loaded/unloaded boundaries, unload/reload and restart cleanup; no false claim of production journal durability in the prototype. |
| Controlled water/lava cells | Compare candidate representation constraints and cleanup without terrain loss; unsupported arrangements reject. Full production fluid policies remain #21. |
| Later Nether/End test fixtures | World/environment behaviour in the relevant later gates; no direct boss targeting. These do not expand the first prototype's compatibility claims. |

For each executed scenario record exact software builds, plugin commit/artifact digest, selected representation and generic geometry, input type, relevant configuration, steps, expected/actual result and remaining limitations. Hardware/OS/resource specifications are recorded only where they affect reproduction or measurements. Do not publish blanket compatibility from passing automated tests or upstream version ranges.

Only sanitized generic evidence may be retained. Exclude player names/UUIDs, private world identifiers/paths, server names/addresses, private server organizations/repositories and Discord details. Do not attach raw environment dumps, complete configurations, player/chat logs or identifying screenshots. Use neutral labels such as test world and tester A. All results in this document remain pending until actually executed.

## Decision and execution gates

Selected for planning:

- Paper/Minecraft 26.3 and Java 25, with the repository-specific reason recorded above.
- Command root `/infernolantern`, alias `/ilantern`, permission namespace `infernolantern`, command tokens and permission defaults above.
- Java 26.3 mouse/keyboard and latest stable PE/mobile touch as the initial client/input targets. Controller coverage is not claimed.

The #3 planning decisions are approved. Implementation and passing runtime evidence remain separate gates.

Execution prerequisites for #4-#6:

- Carry the verified exact Paper API coordinate into #4; verify dependency resolution and packaged runtime behaviour there.
- Capture exact Java vendor/patch and PE client version before testing. If PE has moved beyond the pinned bridge's supported protocols, explicitly revise the bridge pin and rerun affected evidence.
- Prepare the generic initial fixtures and select whether optional Floodgate authentication is needed. No server access, private topology or identifying details belong in this record.
- Record actual test results only after execution; build pins and available input types are not passing compatibility evidence.

Numeric balance, final geometry, final control UX, dragon reward policy and later provider choices remain at their documented gates. They do not block the representation experiments.

## Sources

- [Paper requirements](https://docs.papermc.io/paper/getting-started/)
- [Paper project setup and pinned API builds](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper 26.3 build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds)
- [Geyser supported versions](https://geysermc.org/wiki/geyser/supported-versions/)
- [Geyser 2.12.1 build 1302](https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/1302)
- [Floodgate 2.2.5 build 141](https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141)
- [Geyser custom item mappings](https://geysermc.org/wiki/geyser/custom-items/)
- [Geyser custom block mappings](https://geysermc.org/wiki/geyser/custom-blocks/)
