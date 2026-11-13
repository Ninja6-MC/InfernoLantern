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

These documents describe intended behaviour. The repository includes a minimal plugin build scaffold and repository checks. Gameplay and compatibility validation remain pending. See the [build guide](docs/BUILD.md).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
