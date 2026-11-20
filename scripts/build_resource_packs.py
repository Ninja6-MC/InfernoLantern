#!/usr/bin/env python3
"""Build the generic InfernoLantern delivery sets and an illustrative texture override."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import uuid
import zipfile
import zlib

from build_prototype_packs import ASSETS, ROOT, archive, bedrock_geometry, encoded, java_head_model

NAMESPACE = "infernolantern"
CARRIER = "minecraft:carved_pumpkin"
VARIANTS = ("ember", "frost")
STAGES = ("compact", "expanded")
MATERIALS = ("frame", "glow")
PNG = b"\x89PNG\r\n\x1a\n"


def manifest(name, files):
    # A changed payload has a new pack identity, even with the same display name.
    payload = encoded({path: hashlib.sha256(data).hexdigest() for path, data in sorted(files.items())})
    identity = hashlib.sha256(payload).hexdigest()
    return encoded({"format_version": 2, "header": {
        "name": name, "description": "Generic InfernoLantern assets", "min_engine_version": [1, 21, 0],
        "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"infernolantern:{name}:{identity}")), "version": [1, 0, 0]},
        "modules": [{"type": "resources", "version": [1, 0, 0],
                     "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"infernolantern:{name}:{identity}:module"))}]})


def java_metadata(name):
    return encoded({"pack": {"description": name, "min_format": [97, 1], "max_format": [97, 1]}})


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def rgba_png(data):
    """Return the supported PNG header and filtered RGBA rows of an authored texture."""
    if not data.startswith(PNG):
        raise ValueError("Expected a PNG texture")
    header = None
    compressed = bytearray()
    finished_data = False
    offset = len(PNG)
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 8 + length
        if end + 4 > len(data) or zlib.crc32(data[offset + 4:end]) != struct.unpack_from(">I", data, end)[0]:
            raise ValueError("Truncated or corrupt PNG texture")
        payload = data[offset + 8:end]
        offset = end + 4
        if header is None:
            if kind != b"IHDR" or length != 13:
                raise ValueError("Invalid PNG header")
            header = payload
        elif kind == b"IDAT" and not finished_data:
            compressed.extend(payload)
        elif kind == b"IEND" and length == 0:
            break
        elif not kind[0] & 32:
            raise ValueError("Unsupported critical PNG chunk")
        elif compressed:
            finished_data = True
    if header is None or kind != b"IEND" or offset != len(data) or not compressed:
        raise ValueError("Incomplete PNG texture")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", header)
    if width != 64 or height not in (64, 512) or (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
        raise ValueError("Expected the original dimensions and non-interlaced 8-bit RGBA")
    stride = 1 + width * 4
    decoder = zlib.decompressobj()
    rows = decoder.decompress(compressed, stride * height + 1)
    if len(rows) != stride * height or not decoder.eof or decoder.unused_data or any(f > 4 for f in rows[::stride]):
        raise ValueError("Invalid PNG scanlines")
    return header, bytearray(rows), stride


def swap_channels(data, left, right):
    header, rows, stride = rgba_png(data)
    # PNG filters predict each channel from that same channel in adjacent pixels or rows.
    # Permuting channels in filtered RGBA preserves the filters and the alpha mask.
    for row in range(0, len(rows), stride):
        for pixel in range(row + 1, row + stride, 4):
            rows[pixel + left], rows[pixel + right] = rows[pixel + right], rows[pixel + left]
    return PNG + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(rows, 9)) + png_chunk(b"IEND", b"")


def generic_assets():
    java = {}
    bedrock = {}
    catalog = {}
    definitions = []
    controllers = {
        f"controller.render.infernolantern.generic_{material}": {
            "geometry": f"Geometry.{material}", "materials": [{"*": f"Material.{material}"}],
            "textures": [f"Texture.{material}"],
        } for material in MATERIALS
    }
    # Geometry UVs describe one 64 px frame. Attachables use frame zero of the glow strip.
    controllers["controller.render.infernolantern.generic_glow"]["uv_anim"] = {"offset": [0, 0], "scale": [1, .125]}
    icons = {}
    for variant in VARIANTS:
        icon = f"infernolantern_generic_{variant}"
        textures = {material: f"generic/{variant}_{material}" for material in MATERIALS}
        bedrock_textures = {material: f"textures/infernolantern/{path}" for material, path in textures.items()}
        icons[icon] = {"textures": bedrock_textures["frame"]}
        catalog[variant] = {}
        for material, path in textures.items():
            data = (ASSETS / f"textures/block/lantern_{material}.png").read_bytes()
            if variant == "frost":
                data = swap_channels(data, 0, 2)
            java[f"assets/infernolantern/textures/{path}.png"] = data
            bedrock[f"{bedrock_textures[material]}.png"] = data
            if material == "glow":
                java[f"assets/infernolantern/textures/{path}.png.mcmeta"] = (
                    ASSETS / "textures/block/lantern_glow.png.mcmeta").read_bytes()
        for stage in STAGES:
            stem = f"generic_{variant}_{stage}"
            identifier = f"{NAMESPACE}:{stem}"
            catalog[variant][stage] = identifier
            model = java_head_model(stage)
            model["textures"] = {key: f"{NAMESPACE}:{textures[value.rsplit('_', 1)[1]]}"
                                 for key, value in model["textures"].items()}
            java[f"assets/infernolantern/models/{stem}.json"] = encoded(model)
            java[f"assets/infernolantern/items/{stem}.json"] = encoded({
                "model": {"type": "minecraft:model", "model": identifier}})
            geometry = {}
            for material in MATERIALS:
                geometry[material] = f"geometry.infernolantern.{stem}_{material}"
                bedrock[f"models/entity/{stem}_{material}.geo.json"] = encoded(
                    bedrock_geometry(stage, material, geometry[material]))
            bedrock[f"attachables/{stem}.json"] = encoded({"format_version": "1.20.30",
                "minecraft:attachable": {"description": {
                    "identifier": identifier, "item": {identifier: "query.is_owner_identifier_any('minecraft:armor_stand')"},
                    "materials": {"frame": "entity_alphatest", "glow": "infernolantern_generic_uv"},
                    "textures": bedrock_textures, "geometry": geometry,
                    "animations": {"align": "animation.infernolantern.generic_head"}, "scripts": {"animate": ["align"]},
                    "render_controllers": list(controllers),
                }}})
            definitions.append({"type": "definition", "model": identifier, "bedrock_identifier": identifier,
                                "bedrock_options": {"icon": icon, "allow_offhand": False}})
    bedrock["animations/generic_head.animation.json"] = encoded({"format_version": "1.8.0", "animations": {
        "animation.infernolantern.generic_head": {"loop": True, "bones": {
            "prototype_head": {"position": [0, 0, 0], "rotation": [0, 0, 0], "scale": 1}}}}})
    bedrock["render_controllers/generic.render_controllers.json"] = encoded({
        "format_version": "1.8.0", "render_controllers": controllers})
    bedrock["materials/generic.material"] = encoded({
        "materials": {"version": "1.0.0", "infernolantern_generic_uv:entity_alphatest": {"+defines": ["USE_UV_ANIM"]}}})
    bedrock["textures/item_texture.json"] = encoded({
        "resource_pack_name": "infernolantern_generic", "texture_name": "atlas.items", "texture_data": icons})
    return java, bedrock, {"format_version": 2, "items": {CARRIER: definitions}}, catalog


def overlay_assets(path, base, metadata):
    """Consume an explicitly supplied texture-only archive without extracting it."""
    files = {}
    supplied_sidecars = set()
    with zipfile.ZipFile(path) as overlay:
        seen = set()
        for entry in overlay.infolist():
            if entry.filename in seen:
                raise ValueError(f"Duplicate override entry {entry.filename}")
            seen.add(entry.filename)
            if entry.filename == metadata:
                continue
            if entry.filename.endswith(".png.mcmeta") and entry.filename in base:
                expected = base[entry.filename]
                if entry.file_size != len(expected) or overlay.read(entry) != expected:
                    raise ValueError(f"Animation metadata changed for {entry.filename}")
                supplied_sidecars.add(entry.filename)
                continue
            if entry.filename not in base or not entry.filename.endswith(".png") or entry.file_size > 1024 * 1024:
                raise ValueError(f"Unsupported override entry {entry.filename}")
            texture = overlay.read(entry)
            if rgba_png(texture)[0] != rgba_png(base[entry.filename])[0]:
                raise ValueError(f"Override dimensions changed for {entry.filename}")
            files[entry.filename] = texture
    if any(path.removesuffix(".mcmeta") not in files for path in supplied_sidecars):
        raise ValueError("Animation metadata needs its matching override texture")
    if not files:
        raise ValueError("An override must replace at least one generic texture")
    return files


def write_pack(destination, name, files, *, java):
    label = f"InfernoLantern {name}"
    metadata = {"pack.mcmeta": java_metadata(label)} if java else {"manifest.json": manifest(label, files)}
    path = destination / f"infernolantern-{name}-{'java.zip' if java else 'bedrock.mcpack'}"
    archive(path, files | metadata)
    data = path.read_bytes()
    identity = {"file": path.name, "sha256": hashlib.sha256(data).hexdigest()}
    if java:
        identity["sha1"] = hashlib.sha1(data).hexdigest()
    else:
        header = json.loads(metadata["manifest.json"])["header"]
        identity.update(uuid=header["uuid"], version=header["version"])
    return identity


def build(destination, java_override=None, bedrock_override=None):
    if (java_override is None) != (bedrock_override is None):
        raise ValueError("Supply both platform overlays")
    java, bedrock, mapping, catalog = generic_assets()
    java_path = "assets/infernolantern/textures/generic/ember_glow.png"
    bedrock_path = "textures/infernolantern/generic/ember_glow.png"
    if java_override is None:
        # A public texture-only example with no branding assets.
        glow = swap_channels(java[java_path], 0, 1)
        java_overlay = {java_path: glow}
        bedrock_overlay = {bedrock_path: glow}
    else:
        java_overlay = overlay_assets(java_override, java, "pack.mcmeta")
        bedrock_overlay = overlay_assets(bedrock_override, bedrock, "manifest.json")
    # Java looks up texture metadata within the winning pack priority and above.
    # Supply each original sidecar again next to its replaced PNG in the override.
    for path in list(java_overlay):
        sidecar = path + ".mcmeta"
        if sidecar in java:
            java_overlay[sidecar] = java[sidecar]
    destination.mkdir(parents=True, exist_ok=True)
    packs = {"java": {}, "bedrock": {}}
    for edition, base, overlay in (("java", java, java_overlay), ("bedrock", bedrock, bedrock_overlay)):
        for name, files in (("generic", base), ("override", overlay), ("combined", base | overlay)):
            packs[edition][name] = write_pack(destination, name, files, java=edition == "java")
    mapping_path = destination / "infernolantern-generic-mappings.json"
    mapping_path.write_bytes(encoded(mapping))
    metadata = {"schema": 1, "carrier": CARRIER, "variants": catalog, "packs": packs,
                "mappings": {"file": mapping_path.name, "sha256": hashlib.sha256(mapping_path.read_bytes()).hexdigest()},
                "delivery": {"default": ["generic"], "stacked": ["generic", "override"], "combined": ["combined"]}}
    manifest_path = destination / "pack-set.json"
    manifest_path.write_bytes(encoded(metadata))
    names = sorted([manifest_path.name, mapping_path.name] + [pack["file"] for edition in packs.values() for pack in edition.values()])
    checksums = "".join(f"{hashlib.sha256((destination / name).read_bytes()).hexdigest()}  {name}\n"
                        for name in names)
    (destination / "SHA256SUMS").write_bytes(checksums.encode("ascii"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/resource-packs")
    parser.add_argument("--java-override", type=Path)
    parser.add_argument("--bedrock-override", type=Path)
    args = parser.parse_args()
    build(args.output, args.java_override, args.bedrock_override)
