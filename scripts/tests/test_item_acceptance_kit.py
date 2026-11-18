import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "build_item_acceptance_kit.py"
spec = importlib.util.spec_from_file_location("acceptance_kit", MODULE)
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


class AcceptanceKitTests(unittest.TestCase):
    def artifacts(self, root, *, commands=False, core_helper=False, version="fixture"):
        core, helper = root / "core.jar", root / "helper.jar"
        with zipfile.ZipFile(core, "w") as archive:
            archive.writestr("plugin.yml", "name: InfernoLantern\nversion: fixture\n")
            archive.writestr("com/ninja6/infernolantern/item/PickupValidationContext.class", b"context")
            if core_helper:
                archive.writestr("com/ninja6/infernolantern/acceptance/ItemAcceptancePlugin.class", b"bad")
        with zipfile.ZipFile(helper, "w") as archive:
            archive.writestr("plugin.yml", "name: InfernoLanternItemAcceptance\n" + ("commands: {}\n" if commands else ""))
            archive.writestr("config.yml", "enabled: false\n")
            archive.writestr("acceptance-build.properties", "core-version=" + version + "\n")
            archive.writestr("com/ninja6/infernolantern/acceptance/ItemAcceptancePlugin.class", b"helper")
        return core, helper

    def test_complete_pair_hashes_examples_and_reproducible_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            for name in ("ITEM_ACCEPTANCE_HELPER.md", "ITEM_LIFECYCLE.md"):
                (root / "docs" / name).write_text("bounded native acceptance pending\n")
            core, helper = self.artifacts(root)
            target = root / "build" / "item-acceptance-kit"
            kit.build(root, core, helper, target, "a" * 40)
            original = {path.name: path.read_bytes() for path in target.iterdir()}
            for line in (target / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ")
                self.assertEqual(digest, hashlib.sha256((target / name).read_bytes()).hexdigest())
            self.assertEqual(len(original) - 1, len((target / "SHA256SUMS").read_text().splitlines()))
            self.assertEqual(4, len(list(target.glob("helper-*-example.yml"))))
            kit.build(root, core, helper, target, "a" * 40)
            self.assertEqual(original, {path.name: path.read_bytes() for path in target.iterdir()})

    def test_unsafe_or_mismatched_pair_preserves_existing_evidence(self):
        for options in ({"commands": True}, {"core_helper": True}, {"version": "other"}):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                core, helper = self.artifacts(root, **options)
                target = root / "build" / "item-acceptance-kit"
                target.mkdir(parents=True)
                (target / "evidence").write_text("preserve")
                with self.assertRaises(ValueError):
                    kit.build(root, core, helper, target, "a" * 40)
                self.assertEqual("preserve", (target / "evidence").read_text())

    def test_output_elsewhere_rejected_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "retained"
            target.mkdir()
            (target / "evidence").write_text("preserve")
            with self.assertRaises(ValueError):
                kit.build(root, root / "absent-core", root / "absent-helper", target, "a" * 40)
            self.assertEqual("preserve", (target / "evidence").read_text())
