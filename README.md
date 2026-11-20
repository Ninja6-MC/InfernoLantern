# Inferno Lantern

A configurable lantern that unfolds into a defensive tower.

Inferno Lantern is a planned public Minecraft server plugin. Players place a compact
1 x 1 x 1 base, restore its health with configured resources, and expand it when
health, space, and cooldown requirements are met.

## Planned Features

- Independent lighting and defence controls, with configurable feature availability.
- Beam attacks against supported hostile mobs.
- Resource-based repairs, persistent health, and expansion cooldowns.
- Configurable variants, permissions, world rules, and water/lava behaviour.
- Customizable models and resource packs, with Java and Geyser/Bedrock compatibility
  to be verified during development.

## Development Status

The project is in the design and prototyping stage. No playable release or platform
compatibility is currently promised. Implementation starts by proving rendering,
lighting, and a basic mob interaction on the selected server and client versions.

## Design

- [Requirements](docs/REQUIREMENTS.md)
- [Technical solution and validation gates](docs/SOLUTION.md)
- [Interface decisions and test targets](docs/INTERFACES_AND_TEST_TARGETS.md)
- [Rendering and interaction feasibility evidence](docs/RENDERING_EVIDENCE.md)

These documents describe intended behaviour. The repository includes a minimal plugin build scaffold and repository checks. Gameplay and compatibility validation remain pending. See the [build guide](docs/BUILD.md).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

Repository and journal foundation: [storage contract and benchmark](docs/REPOSITORY_AND_JOURNAL.md). It supports the default-off item test harness; rendering/combat experiments retain their separate cleanup path.

Item identity and ground lifecycle foundation: [service contract and pending live worksheet](docs/ITEM_LIFECYCLE.md). The approved default-off disposable harness is described in the [live test brief](docs/ITEM_LIVE_TEST_BRIEF.md); task #9 remains partial until native acceptance.

Structure transition foundation: [journaled service, Paper effect port and pending integration](docs/STRUCTURE_TRANSITIONS.md). Native structure handlers remain unwired; schema 3 migration is active on repository open. Task #10 is partial.

Reconciliation foundation: [evidence contracts, recovery matrix and pending integration](docs/RECONCILIATION.md). The service is unwired and does not authorize repair of existing test saves. Task #11 remains partial.

The partial configuration/catalog and journaled real-light foundation is documented in [Configuration Catalog](docs/CONFIGURATION_CATALOG.md). Native configuration/control integration and acceptance remain pending.

[Native access](docs/NATIVE_ACCESS.md) documents the session override command, permission
checks and transaction boundaries for future native action routing.

[Tower health](docs/TOWER_HEALTH.md) documents durable damage, system defeat compaction,
maximum-HP changes, and the private target view. Native health routing and acceptance
remain pending.

[Resource repair](docs/RESOURCE_REPAIR.md) documents revision-bound quotes, durable
payment and cooling. Native repair menus and acceptance remain pending.

[Ordinary zombie combat](docs/ZOMBIE_COMBAT.md) documents the initial bounded owner loop,
native beam and melee adapter, and remaining production and client acceptance work.

[Lantern blast transactions](docs/BLAST_TRANSACTIONS.md) documents whole-lantern protection,
journaled damage and drop, and the pending native exposure and provider routing.

[Fluid placement and cleanup](docs/FLUID_POLICIES.md) documents per-variant water and lava settings,
supported terrain changes, and pending native acceptance.

[Generic Java and Bedrock packs](docs/RESOURCE_PACKS.md) documents model identifiers,
override packaging, delivery modes, and per-connection readiness.
