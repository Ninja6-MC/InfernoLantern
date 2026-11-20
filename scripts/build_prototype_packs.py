#!/usr/bin/env python3
"""Build deterministic, minimal head-slot test packs without editing model masters."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/infernolantern"


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=True) + "\n").encode()


def archive(path, files):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, content in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, content)


def bedrock_geometry(shape, material, identifier=None):
    """Derive material-separated, ground-centered head attachables from the masters."""
    master = json.loads((ASSETS / f"models/{shape}_lantern.bbmodel").read_text())
    glow_texture = 1 if shape == "compact" else master["textures"][1]["uuid"]
    cubes = []
    for element in master["elements"]:
        refs = {face["texture"] for face in element["faces"].values()}
        if len(refs) != 1:
            raise ValueError("Prototype exporter needs one material per cube")
        if (glow_texture in refs) != (material == "glow"):
            continue
        low, high = element["from"], element["to"]
        cube = {"origin": [round(8 - high[0], 6), low[1], round(low[2] - 8, 6)],
                "size": [round(high[i] - low[i], 6) for i in range(3)]}
        rotation = element.get("rotation", [0, 0, 0])
        if any(rotation):
            pivot = element.get("origin", [8, 0, 8])
            cube["pivot"] = [8 - pivot[0], pivot[1], pivot[2] - 8]
            cube["rotation"] = [-rotation[0], -rotation[1], rotation[2]]
        cube["uv"] = {}
        for face, source in element["faces"].items():
            uv = source["uv"]
            target = {"east": "west", "west": "east"}.get(face, face)
            cube["uv"][target] = {"uv": uv[:2], "uv_size": [uv[2] - uv[0], uv[3] - uv[1]]}
        cubes.append(cube)
    if len(cubes) != (8 if material == "glow" else len(master["elements"]) - 8):
        raise ValueError("Unexpected material cube count")
    return {"format_version": "1.16.0", "minecraft:geometry": [{"description": {
        "identifier": identifier or f"geometry.infernolantern.prototype_{shape}_{material}",
        "texture_width": 64, "texture_height": 64, "visible_bounds_width": 4,
        "visible_bounds_height": 8, "visible_bounds_offset": [0, 1, 0]},
        "bones": [{"name": "prototype_head", "pivot": [0, 23, 0],
                   "binding": "q.item_slot_to_bone_name(context.item_slot)", "cubes": cubes}]}]}


def java_head_model(shape):
    model = json.loads((ASSETS / f"models/block/{shape}_lantern.json").read_text())
    # ItemTransform clamps scale to four. Expand a derived copy by 1.2
    # (still legal: Y <= 19.2), then apply 4 * 0.625 = 2.5 in the head slot.
    scale = 1.6 if shape == "compact" else 4.0
    if shape == "expanded":
        for element in model["elements"]:
            vectors = [element["from"], element["to"]]
            if "rotation" in element:
                vectors.append(element["rotation"]["origin"])
            for vector in vectors:
                vector[:] = [round(8 + (vector[0] - 8) * 1.2, 6),
                             round(vector[1] * 1.2, 6), round(8 + (vector[2] - 8) * 1.2, 6)]
    translation_y = round(16 * (scale * .625 / 2 - 1.6885) / .625, 6)
    model["display"] = {"head": {"rotation": [0, 180, 0], "translation": [0, translation_y, 0], "scale": [scale] * 3}}
    return model


def build(destination):
    destination.mkdir(parents=True, exist_ok=True)
    java = {"pack.mcmeta": encoded({"pack": {"description": "InfernoLantern rendering prototype", "min_format": [97, 1], "max_format": [97, 1]}})}
    bedrock = {"manifest.json": encoded({"format_version": 2, "header": {
        "name": "InfernoLantern rendering prototype", "description": "Experimental head-slot fixtures; live calibration required",
        "uuid": "88668e93-c18b-5fdb-979e-66ccf0b57c12", "version": [0, 1, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"type": "resources", "uuid": "cd006bed-dbbe-5f01-96df-b528bc5fc718", "version": [0, 1, 0]}]})}
    definitions = []
    controllers = {}
    for shape in ("compact", "expanded"):
        identifier = f"infernolantern:prototype_{shape}"
        model = java_head_model(shape)
        java[f"assets/infernolantern/models/prototype_{shape}.json"] = encoded(model)
        java[f"assets/infernolantern/items/prototype_{shape}.json"] = encoded({"model": {"type": "minecraft:model", "model": identifier}})
        definitions.append({"type": "definition", "model": identifier, "bedrock_identifier": identifier,
                            "display_name": f"Prototype {shape}", "bedrock_options": {"icon": "infernolantern_prototype", "allow_offhand": False}})
        geometry_refs, textures, renderers = {}, {}, []
        for material in ("frame", "glow"):
            geometry = bedrock_geometry(shape, material)
            geometry_refs[material] = geometry["minecraft:geometry"][0]["description"]["identifier"]
            textures[material] = f"textures/infernolantern/lantern_{material}"
            bedrock[f"models/entity/prototype_{shape}_{material}.geo.json"] = encoded(geometry)
            controller = f"controller.render.infernolantern.prototype_{material}"
            renderers.append(controller)
            controllers[controller] = {"geometry": f"Geometry.{material}", "materials": [{"*": "Material.default"}], "textures": [f"Texture.{material}"]}
            if material == "glow":
                # Geometry UVs describe one 64px frame. Sample only frame zero for
                # this bounded prototype; block flipbook metadata doesn't animate attachables.
                controllers[controller]["uv_anim"] = {"offset": [0, 0], "scale": [1, .125]}
                controllers[controller]["materials"] = [{"*": "Material.glow"}]
        bedrock[f"attachables/prototype_{shape}.json"] = encoded({"format_version": "1.20.30", "minecraft:attachable": {"description": {
            "identifier": identifier, "item": {identifier: "query.is_owner_identifier_any('minecraft:armor_stand')"},
            "materials": {"default": "entity_alphatest", "glow": "infernolantern_prototype_uv"},
            "textures": textures, "geometry": geometry_refs,
            "animations": {"align": "animation.infernolantern.prototype_head"}, "scripts": {"animate": ["align"]},
            "render_controllers": renderers}}})
    bedrock["animations/prototype_head.animation.json"] = encoded({"format_version": "1.8.0", "animations": {
        "animation.infernolantern.prototype_head": {"loop": True, "bones": {"prototype_head": {"position": [0, 0, 0], "rotation": [0, 0, 0], "scale": 1}}}}})
    bedrock["render_controllers/prototype.render_controllers.json"] = encoded({"format_version": "1.8.0", "render_controllers": controllers})
    bedrock["materials/prototype.material"] = encoded({"materials": {"version": "1.0.0", "infernolantern_prototype_uv:entity_alphatest": {"+defines": ["USE_UV_ANIM"]}}})
    bedrock["textures/item_texture.json"] = encoded({"resource_pack_name": "infernolantern_prototype", "texture_name": "atlas.items", "texture_data": {
        "infernolantern_prototype": {"textures": "textures/infernolantern/lantern_frame"}}})
    for name in ("lantern_frame.png", "lantern_glow.png", "lantern_glow.png.mcmeta"):
        java[f"assets/infernolantern/textures/block/{name}"] = (ASSETS / f"textures/block/{name}").read_bytes()
        if name.endswith(".png"):
            bedrock[f"textures/infernolantern/{name}"] = (ASSETS / f"textures/block/{name}").read_bytes()
    archive(destination / "infernolantern-prototype-java.zip", java)
    archive(destination / "infernolantern-prototype-bedrock.mcpack", bedrock)
    (destination / "infernolantern-prototype-mappings.json").write_bytes(encoded({"format_version": 2, "items": {"minecraft:carved_pumpkin": definitions}}))
    names = ["infernolantern-prototype-java.zip", "infernolantern-prototype-bedrock.mcpack", "infernolantern-prototype-mappings.json"]
    (destination / "SHA256SUMS").write_text("".join(f"{hashlib.sha256((destination / name).read_bytes()).hexdigest()}  {name}\n" for name in names))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/prototype-packs")
    build(parser.parse_args().output)
