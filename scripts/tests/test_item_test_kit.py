import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "build_item_test_kit.py"
spec = importlib.util.spec_from_file_location("item_kit", MODULE)
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


class ItemKitTests(unittest.TestCase):
    def test_artifact_and_evidence_hashes_are_reproducible_and_complete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "docs").mkdir()
            for name in ("ITEM_LIFECYCLE.md", "ITEM_LIVE_TEST_BRIEF.md"):
                (root / "docs" / name).write_text("Generic disposable evidence\n", encoding="utf-8")
            jar = root / "candidate.jar"
            jar.write_bytes(b"verified candidate fixture")
            destination = root / "build" / "item-test-kit"
            kit.build(root, jar, destination, "a" * 40)
            original = {p.name: p.read_bytes() for p in destination.iterdir()}
            for line in (destination / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ")
                self.assertEqual(digest, hashlib.sha256((destination / name).read_bytes()).hexdigest())
            self.assertEqual(len(original) - 1, len((destination / "SHA256SUMS").read_text().splitlines()))
            self.assertEqual(jar.read_bytes(), (destination / jar.name).read_bytes())
            kit.build(root, jar, destination, "a" * 40)
            self.assertEqual(original, {p.name: p.read_bytes() for p in destination.iterdir()})

    def test_output_outside_ignored_directory_is_rejected_without_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "candidate.jar"
            jar.write_bytes(b"candidate")
            protected = root / "retained"
            protected.mkdir()
            (protected / "evidence").write_text("keep", encoding="utf-8")
            with self.assertRaises(ValueError):
                kit.build(root, jar, protected, "a" * 40)
            self.assertEqual("keep", (protected / "evidence").read_text())
