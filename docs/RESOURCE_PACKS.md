# Generic Resource Packs

The plugin JAR includes the generic Java and Bedrock packs, Geyser item mappings,
a texture override example, and complete combined packs under `resource-packs/`.
Build the same files locally with `./gradlew resourcePacks` and find them in
`build/resource-packs/`. Use `SHA256SUMS` to verify the archives, mapping, and
`pack-set.json`. The latter records the supported model pairs, archive identities,
delivery order, Java download SHA-1 values, and Bedrock pack UUIDs and versions.
Host Java downloads at administrator-selected HTTPS URLs for the exact checked
archive bytes.

## Identifiers

The two default palettes reuse the committed model geometry and animation.
Ember has the authored warm accents. Frost swaps red and blue while keeping
the UVs and alpha. The Bedrock attachables sample the first glow frame; Java
reads the included texture animation metadata. The original editable masters
and the prototype archives retain their existing content.

| Example | Catalog `compact-model` | Catalog `expanded-model` |
| --- | --- | --- |
| Ember | `infernolantern:generic_ember_compact` | `infernolantern:generic_ember_expanded` |
| Frost | `infernolantern:generic_frost_compact` | `infernolantern:generic_frost_expanded` |

For each identifier `infernolantern:<stem>`, the Java item definition lives in
`assets/infernolantern/items/<stem>.json` and resolves to
`assets/infernolantern/models/<stem>.json`. The renderer applies the item-model
component to the `minecraft:carved_pumpkin` carrier. Geyser maps the same
carrier and item-model pair to the same namespaced Bedrock identifier. The
Bedrock pack supplies `attachables/<stem>.json` and the frame/glow geometry for
that stage. Two visual stages can share their palette's inventory icon.
The catalog supplies item names, lore, and reward text. These examples only
supply assets; select an existing valid catalog definition to activate a variant.
[Geyser custom item mappings](https://geysermc.org/wiki/geyser/custom-items/)

## Delivery modes

| Mode | Java | Bedrock through Geyser |
| --- | --- | --- |
| Default | Send `infernolantern-generic-java.zip`. | Serve `infernolantern-generic-bedrock.mcpack` alongside the mapping. |
| Stacked | Send base and higher-priority override in that order in a single request. Wait for both to load. | Serve both packs with higher priority for the override and supply the mapping once. Verify the actual bridge order. |
| Combined | Configure server-managed delivery of `infernolantern-combined-java.zip`. The plugin sends no duplicate request. | Serve only `infernolantern-combined-bedrock.mcpack` alongside the mapping. |

For Geyser v2 custom items, enable `enable-custom-content`, put
`infernolantern-generic-mappings.json` in the instance's `custom_mappings/`,
and supply the Bedrock archives through its `packs/` folder or pack API.
The same mapping applies to all three modes. An ordered Bedrock stack needs
an integration using the Geyser resource-pack API. Combined mode can deliver
both layers through one archive. The Java format range is
`[97, 1]`, matching the existing Java 26.3 rendering target. The Bedrock pack
declares engine version 1.21.0 and uses v2 Geyser custom item definitions.
The actual Java, Bedrock, and bridge version combination requires live testing.
[Paper resource pack delivery](https://docs.papermc.io/adventure/resource-pack/)
[Geyser resource packs](https://geysermc.org/wiki/geyser/packs/)

## Enable bundled Java delivery

This mode is for a direct Java-only Paper server. Leave it disabled on mixed-edition
servers and on servers behind any proxy or bridge. The plugin rejects an installed
Geyser or Floodgate plugin and Paper's configured proxy mode. A standalone Geyser
process or a transparent proxy can be invisible to Paper, so confirm the entire
connection path before opting in. If a known bridge is enabled after delivery
starts, the plugin revokes existing readiness and stops offering the pack.

Extract `resource-packs/infernolantern-generic-java.zip` from the same plugin JAR
you will run. Verify it against the bundled `SHA256SUMS` and serve the unchanged
archive over HTTPS. Set the following options in `config.yml` and restart. See
[Native Catalog Startup](CORE_RUNTIME.md) for configuring `catalog.yml`.

```yaml
native-catalog:
  enabled: true
  java-pack:
    direct-java-only: true
    url: "https://assets.example.invalid/infernolantern-generic-java.zip"
```

Delivery is off by default. Once the catalog publishes, the plugin offers the
verified bundled archive to connected and joining players. A fresh connection
gets a fresh request. For the bundled example override, extract
`resource-packs/infernolantern-override-java.zip` from the same JAR, verify it
against `SHA256SUMS`, and host it over HTTPS too:

```yaml
native-catalog:
  enabled: true
  java-pack:
    direct-java-only: true
    mode: stacked
    url: "https://assets.example.invalid/infernolantern-generic-java.zip"
    override-url: "https://assets.example.invalid/infernolantern-override-java.zip"
    required: false
```

The plugin sends the base first and the override second in one request. Both
archive identities are checked, and the current connection must report a
successful load for both request IDs. If either archive is unverified or a
required URL is missing or invalid, delivery stays unavailable. The default
mode uses only `url`; leave `override-url` empty or omit it.

Both modes default to optional delivery. Refusal, failure, or 1,200 ticks
without a successful load leaves the connection unready and gives private
feedback. With `required: true`, the server disconnects a client that
declines, fails, or times out while its offer is active. The client still
uses the standard optional-pack screen and decline button. The prompt
explains the server's required policy so a withdrawn offer cannot leave
the client with a button that disconnects it locally. Catalog reload
retains the mode, policy and pack identity. Restart to change the delivery
configuration.

Combined session readiness, Geyser, and native gameplay are separate
integration work.

## Override example

The override archive changes only Ember's glow texture. Keep these paths for
a texture swap.

- Java uses `assets/infernolantern/textures/generic/ember_glow.png`.
- Bedrock uses `textures/infernolantern/generic/ember_glow.png`.

Each edition also has `ember_frame.png`, `frost_frame.png`, and
`frost_glow.png` beside that path. The frame and glow textures retain their
original 64 × 64 or 64 × 512 dimensions and non-interlaced 8-bit RGBA layout.
The Java base and combined archives register those four textures in Minecraft's
block-model atlas. The Java override relies on the base archive's registration
and replaces texture files at the same paths.
The generated Java override puts the original `.png.mcmeta` animation
sidecar next to each overridden animated glow PNG. Java resolves the metadata
at the winning texture pack's priority, so the sidecar must travel with
that texture. The combined build includes every base file and overlays the
replacement PNG and its sidecar.

To prepare a texture override, put the desired replacement PNGs in the paths
above in two archives. Then build a separate output directory.

```sh
python3 scripts/build_resource_packs.py --output build/local-resource-packs \
  --java-override /path/to/java-override.zip \
  --bedrock-override /path/to/bedrock-override.mcpack
```

The generator checks the existing target paths and supported PNG dimensions and
adds the original Java animation sidecar where needed. An input archive may
include the matching sidecar unchanged; a different sidecar or a sidecar by
itself is rejected. The generator writes new checksums and content-derived
Bedrock pack identities.

To deliver a generated Java texture override in stacked mode:

1. Verify the generated `infernolantern-override-java.zip` against the output
   `SHA256SUMS`. Copy the ZIP unchanged to `java-override.zip` in the plugin data
   directory, next to `catalog.yml`.
2. Set `native-catalog.java-pack.local-override: true`. Set `override-url` to the
   HTTPS URL serving the exact same ZIP bytes. Keep the base URL pointed at
   the generic archive from the installed plugin JAR.
3. Restart. The server checks the local ZIP and derives both hashes from its
   bytes. The client uses the resulting SHA-1 to verify its download.

The archive must be a regular file (up to 5 MiB) with generator pack metadata,
at least one supported texture, and the original animation sidecar for each
glow texture. An invalid or missing local archive prevents stacked delivery.
Omit `local-override` or leave it false to select the bundled override example.
Replace the local and hosted copies together and restart to publish a new
identity.

Keep any branding artwork and its licensing with the administrator-managed
pack. For model overrides, preserve the documented identifiers and verify each
platform's complete reference set before publishing a new asset identity.

## Readiness and client acceptance

`PackPlan` describes a validated artifact set. The deployment binding verifies
the actual archives, model pairs, download digests and bridge mapping before
creating the plan. Its model coverage can validate catalog definitions.
For Java default and stacked delivery, `PaperPackSessions` sends the ordered
Paper request and accepts successful load reports for every request ID in
the current connection. It gates model-dependent interaction and returns a
revocable commit permit. A timeout or terminal refusal/failure leaves the
permit unavailable. The server enforces required delivery with a kick reason
while the offer is active. After checking the connection and topology, the
server admits the action against the catalog's lifetime permit. Revocation
before admission prevents enforcement. An action admitted first may finish.
Optional delivery reports the failure privately and keeps custom interaction
gated.

Server-managed Java combined delivery and all Bedrock modes require explicit
current-connection readiness for the complete configured identity. For
Bedrock this includes the archive stack and the matching bridge mapping.
The trusted integration reports load, refusal, failure, missing state, and
replacement. Configuration saying packs are required does not establish
readiness. A plan replacement starts a fresh attempt and revokes the previous
permit before sending new packs. Disconnect and shutdown also revoke it.
Callers should compose this permit with their existing native action fences.
Java client responses describe what the client reports. They cannot prove that
a modified client rendered the assets.

The native Java binding registers this adapter after catalog publication using
the verified bundled default or stacked plan. Issue #23 remains open for live Java and Bedrock
acceptance.
Exercise both variants and both stages in all three delivery modes. Check that
the override takes priority, the combined packs contain the unchanged visuals,
and optional failure blocks model-dependent interaction. Exercise required
refusal, failed download, changed pack identity and reconnect. Confirm the
bridge's reported ready state for that exact .mcpack and mapping combination.
The existing compact-model shimmer remains an artwork acceptance item.
