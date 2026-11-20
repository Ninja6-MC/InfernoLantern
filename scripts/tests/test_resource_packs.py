"""Code and archive contract checks for the generic two-edition pack sets."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_resource_packs as packs


def read_files(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


class ResourcePackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        packs.build(self.output)
        self.manifest = json.loads((self.output / "pack-set.json").read_bytes())

    def contents(self, edition, name):
        return read_files(self.output / self.manifest["packs"][edition][name]["file"])

    def test_byte_for_byte_build_and_every_download_digest(self):
        original = {path.name: path.read_bytes() for path in self.output.iterdir()}
        packs.build(self.output)
        self.assertEqual(original, {path.name: path.read_bytes() for path in self.output.iterdir()})
        expected = {name: hashlib.sha256(data).hexdigest() for name, data in original.items() if name != "SHA256SUMS"}
        actual = {name: digest for digest, name in (line.split("  ") for line in original["SHA256SUMS"].decode().splitlines())}
        self.assertEqual(expected, actual)
        for download in self.manifest["packs"]["java"].values():
            self.assertEqual(download["sha1"], hashlib.sha1(original[download["file"]]).hexdigest())
        mapping = self.manifest["mappings"]
        self.assertEqual(expected[mapping["file"]], mapping["sha256"])

    def test_stage_and_variant_identifiers_resolve_on_both_editions(self):
        java, bedrock = self.contents("java", "generic"), self.contents("bedrock", "generic")
        definitions = json.loads((self.output / self.manifest["mappings"]["file"]).read_bytes())["items"][packs.CARRIER]
        mappings = {definition["model"]: definition for definition in definitions}
        self.assertEqual(4, len(definitions))
        self.assertEqual(4, len(mappings))
        self.assertEqual({model for variant in self.manifest["variants"].values() for model in variant.values()}, set(mappings))
        controllers = json.loads(bedrock["render_controllers/generic.render_controllers.json"])["render_controllers"]
        animations = json.loads(bedrock["animations/generic_head.animation.json"])["animations"]
        materials = json.loads(bedrock["materials/generic.material"])["materials"]
        icons = json.loads(bedrock["textures/item_texture.json"])["texture_data"]
        for stages in self.manifest["variants"].values():
            for stage, identifier in stages.items():
                stem = identifier.split(":")[1]
                definition = json.loads(java[f"assets/infernolantern/items/{stem}.json"])
                self.assertEqual(identifier, definition["model"]["model"])
                model = json.loads(java[f"assets/infernolantern/models/{stem}.json"])
                self.assertEqual(packs.java_head_model(stage)["elements"], model["elements"])
                self.assertEqual(packs.java_head_model(stage)["display"], model["display"])
                attach = json.loads(bedrock[f"attachables/{stem}.json"])["minecraft:attachable"]["description"]
                self.assertEqual(identifier, attach["identifier"])
                self.assertEqual(identifier, mappings[identifier]["bedrock_identifier"])
                self.assertNotIn("display_name", mappings[identifier])
                self.assertIn("minecraft:armor_stand", attach["item"][identifier])
                self.assertIn(attach["animations"]["align"], animations)
                self.assertIn(attach["materials"]["glow"] + ":entity_alphatest", materials)
                icon = icons[mappings[identifier]["bedrock_options"]["icon"]]
                self.assertEqual(icon["textures"], attach["textures"]["frame"])
                for material in packs.MATERIALS:
                    geometry = json.loads(bedrock[f"models/entity/{stem}_{material}.geo.json"])["minecraft:geometry"][0]
                    self.assertEqual(geometry["description"]["identifier"], attach["geometry"][material])
                    self.assertEqual(packs.bedrock_geometry(stage, material)["minecraft:geometry"][0]["bones"], geometry["bones"])
                    bedrock_texture = attach["textures"][material] + ".png"
                    java_texture = "assets/infernolantern/textures/" + bedrock_texture.removeprefix("textures/infernolantern/")
                    self.assertIn("infernolantern:" + java_texture.removeprefix("assets/infernolantern/textures/").removesuffix(".png"),
                                  model["textures"].values())
                    self.assertEqual(bedrock[bedrock_texture], java[java_texture])
                self.assertTrue(set(attach["render_controllers"]) <= controllers.keys())
        glow = lambda variant: java[f"assets/infernolantern/textures/generic/{variant}_glow.png"]
        self.assertNotEqual(packs.rgba_png(glow("ember"))[1], packs.rgba_png(glow("frost"))[1])

    def test_override_priority_and_complete_combined_set(self):
        self.assertEqual({"default": ["generic"], "stacked": ["generic", "override"], "combined": ["combined"]},
                         self.manifest["delivery"])
        for edition, metadata in (("java", "pack.mcmeta"), ("bedrock", "manifest.json")):
            base, override, combined = (self.contents(edition, name) for name in ("generic", "override", "combined"))
            for files in (base, override, combined):
                del files[metadata]
            self.assertEqual(1, len(override))
            self.assertTrue(override.keys() <= base.keys())
            for path, content in override.items():
                self.assertNotEqual(packs.rgba_png(base[path])[1], packs.rgba_png(content)[1])
            self.assertEqual(base | override, combined)
        uuids = {pack["uuid"] for pack in self.manifest["packs"]["bedrock"].values()}
        self.assertEqual(3, len(uuids))

    def test_custom_overrides_without_source_changes_and_reject_unknown_entries(self):
        local = self.output / "local"
        local.mkdir()
        java, bedrock = (self.output / self.manifest["packs"][edition]["override"]["file"]
                         for edition in ("java", "bedrock"))
        packs.build(local, java, bedrock)
        self.assertEqual((local / "pack-set.json").read_bytes(), (self.output / "pack-set.json").read_bytes())
        invalid = local / "invalid.zip"
        packs.archive(invalid, {"assets/infernolantern/items/generic_ember_compact.json": b"{}"})
        with self.assertRaisesRegex(ValueError, "Unsupported override entry"):
            packs.build(local, invalid, bedrock)
        with self.assertRaisesRegex(ValueError, "Supply both"):
            packs.build(local, java)
