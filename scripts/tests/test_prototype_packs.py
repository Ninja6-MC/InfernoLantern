"""Verify closure, head transforms and reproducibility of the prototype delivery set."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "build_prototype_packs.py"
spec = importlib.util.spec_from_file_location("prototype_packs", SCRIPT)
packs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packs)


class PrototypePackTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        packs.build(self.output)

    def test_archives_and_digests_reproduce_byte_for_byte(self):
        original = {path.name: path.read_bytes() for path in self.output.iterdir()}
        packs.build(self.output)
        self.assertEqual(original, {path.name: path.read_bytes() for path in self.output.iterdir()})

    def test_java_model_paths_textures_and_head_dimensions(self):
        with zipfile.ZipFile(self.output / "infernolantern-prototype-java.zip") as archive:
            metadata = json.loads(archive.read("pack.mcmeta"))["pack"]
            self.assertEqual([97, 1], metadata["min_format"])
            for shape, height in (("compact", 1), ("expanded", 3)):
                definition = json.loads(archive.read(f"assets/infernolantern/items/prototype_{shape}.json"))
                self.assertEqual(f"infernolantern:prototype_{shape}", definition["model"]["model"])
                model = json.loads(archive.read(f"assets/infernolantern/models/prototype_{shape}.json"))
                head = model["display"]["head"]
                self.assertTrue(all(abs(value) <= 4 for value in head["scale"]))
                self.assertTrue(all(-16 <= value <= 32 for element in model["elements"] for key in ("from", "to") for value in element[key]))
                scale = head["scale"][0] * .625
                bottom = 1.6885 + head["translation"][1] / 16 * .625 - scale / 2
                self.assertAlmostEqual(0, bottom, places=5)
                top = max(element["to"][1] for element in model["elements"]) / 16
                low = min(element["from"][1] for element in model["elements"]) / 16
                self.assertAlmostEqual(height, (top - low) * scale, places=4)
                for name, texture in model["textures"].items():
                    if texture.startswith("#"):
                        continue
                    namespace, path = texture.split(":")
                    self.assertIn(f"assets/{namespace}/textures/{path}.png", archive.namelist())

    def test_bedrock_mapping_and_render_references_close(self):
        mappings = json.loads((self.output / "infernolantern-prototype-mappings.json").read_text())
        self.assertEqual(2, mappings["format_version"])
        with zipfile.ZipFile(self.output / "infernolantern-prototype-bedrock.mcpack") as archive:
            controllers = json.loads(archive.read("render_controllers/prototype.render_controllers.json"))["render_controllers"]
            for definition in mappings["items"]["minecraft:carved_pumpkin"]:
                shape = definition["model"].split("_")[-1]
                attach = json.loads(archive.read(f"attachables/prototype_{shape}.json"))["minecraft:attachable"]["description"]
                self.assertEqual(definition["bedrock_identifier"], attach["identifier"])
                self.assertIn("minecraft:armor_stand", attach["item"][attach["identifier"]])
                count = 0
                for material in ("frame", "glow"):
                    model = json.loads(archive.read(f"models/entity/prototype_{shape}_{material}.geo.json"))["minecraft:geometry"][0]
                    self.assertEqual(attach["geometry"][material], model["description"]["identifier"])
                    self.assertEqual([0, 23, 0], model["bones"][0]["pivot"])
                    self.assertIn("binding", model["bones"][0])
                    count += sum(len(b.get("cubes", [])) for b in model["bones"])
                    self.assertIn(attach["textures"][material] + ".png", archive.namelist())
                self.assertEqual(100 if shape == "compact" else 106, count)
                for reference in attach["render_controllers"]:
                    self.assertIn(reference, controllers)

    def test_inputs_not_rewritten_by_exporter(self):
        inputs = list(packs.ASSETS.rglob("*"))
        before = {p: p.read_bytes() for p in inputs if p.is_file()}
        packs.build(self.output)
        self.assertEqual(before, {p: p.read_bytes() for p in before})


if __name__ == "__main__":
    unittest.main()
