"""Build contracts, tested against independent temporary Git histories and archives."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_version import build_version, git
from verify_plugin_jar import MAIN, verify


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-b", "main")
        git(self.repo, "config", "user.email", "test@example.invalid")
        git(self.repo, "config", "user.name", "Build Test")
        self.commit("initial")

    def commit(self, message):
        git(self.repo, "commit", "--allow-empty", "-m", message)
        return git(self.repo, "rev-parse", "--short=12", "HEAD")

    def test_no_tag_uses_built_commit(self):
        self.assertEqual(build_version(self.repo), f"0.0.0-SNAPSHOT-g{git(self.repo, 'rev-parse', '--short=12', 'HEAD')}")

    def test_exact_tag_preserves_zero_distance(self):
        git(self.repo, "tag", "v0.1.0-alpha.1")
        short = git(self.repo, "rev-parse", "--short=12", "HEAD")
        self.assertEqual(build_version(self.repo), f"0.1.0-alpha.1-0-g{short}")
        self.assertEqual(build_version(self.repo, "v0.1.0-alpha.1"), "0.1.0-alpha.1")

    def test_distance_from_annotated_tag(self):
        git(self.repo, "tag", "-a", "v1.0.0", "-m", "release")
        short = self.commit("next")
        self.assertEqual(build_version(self.repo), f"1.0.0-1-g{short}")

    def test_malformed_and_unreachable_tags_are_ignored(self):
        git(self.repo, "tag", "v0.1.0")
        git(self.repo, "switch", "-c", "unrelated")
        self.commit("unreachable release")
        git(self.repo, "tag", "v9.0.0")
        git(self.repo, "switch", "main")
        short = self.commit("next")
        for tag in ["v2.0.0-bogus", "v2.0.0junk", "v02.0.0", "v2.0.0-alpha.01", "v2.0.0+build"]:
            git(self.repo, "tag", tag)
        self.assertEqual(build_version(self.repo), f"0.1.0-1-g{short}")

    def test_nearest_valid_tag_wins(self):
        git(self.repo, "tag", "v0.1.0")
        self.commit("second")
        git(self.repo, "tag", "v0.2.0-rc.1")
        short = self.commit("third")
        self.assertEqual(build_version(self.repo), f"0.2.0-rc.1-1-g{short}")

    def test_merge_commit_identity_is_not_branch_tip(self):
        git(self.repo, "tag", "v0.1.0")
        git(self.repo, "switch", "-c", "feature")
        branch_tip = self.commit("feature")
        git(self.repo, "switch", "main")
        self.commit("main update")
        git(self.repo, "merge", "--no-ff", "feature", "-m", "synthetic PR merge")
        merged = git(self.repo, "rev-parse", "--short=12", "HEAD")
        self.assertNotEqual(branch_tip, merged)
        self.assertEqual(build_version(self.repo), f"0.1.0-3-g{merged}")

    def test_release_rejects_wrong_commit_and_malformed_tag(self):
        git(self.repo, "tag", "v1.0.0")
        self.commit("after release")
        with self.assertRaises(ValueError):
            build_version(self.repo, "v1.0.0")
        with self.assertRaises(ValueError):
            build_version(self.repo, "v1.0.0oops")

    def test_shallow_history_fails_instead_of_inventing_version(self):
        self.commit("next")
        clone = Path(self.temp.name) / "shallow"
        subprocess.run(["git", "clone", "--depth=1", self.repo.as_uri(), str(clone)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, "complete Git history"):
            build_version(clone)


class ArtifactTests(unittest.TestCase):
    VERSION = "0.1.0-alpha.1-2-g123456789abc"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.jar = self.directory / f"InfernoLantern-{self.VERSION}.jar"

    def write_jar(self, version=None, main_class=True, duplicate=False, bundled_api=False):
        descriptor = f"name: InfernoLantern\nversion: '{version or self.VERSION}'\nmain: {MAIN}\napi-version: '26.3'\n"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", descriptor)
            if main_class:
                archive.writestr(MAIN.replace(".", "/") + ".class", b"\xca\xfe\xba\xbe")
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr("plugin.yml", descriptor)
            if bundled_api:
                archive.writestr("org/bukkit/plugin/java/JavaPlugin.class", b"bad")

    def test_expected_artifact_passes(self):
        self.write_jar()
        self.assertEqual(verify(self.directory, self.VERSION), self.jar)

    def test_missing_jar_fails(self):
        with self.assertRaisesRegex(ValueError, "Expected only"):
            verify(self.directory, self.VERSION)

    def test_extra_or_duplicate_deployable_jar_fails(self):
        self.write_jar()
        shutil.copy(self.jar, self.directory / "unexpected.jar")
        with self.assertRaisesRegex(ValueError, "Expected only"):
            verify(self.directory, self.VERSION)

    def test_wrong_filename_fails(self):
        self.write_jar()
        self.jar.rename(self.directory / "InfernoLantern-wrong.jar")
        with self.assertRaisesRegex(ValueError, "Expected only"):
            verify(self.directory, self.VERSION)

    def test_wrong_embedded_version_fails(self):
        self.write_jar(version="0.1.0")
        with self.assertRaisesRegex(ValueError, "Embedded plugin version"):
            verify(self.directory, self.VERSION)

    def test_duplicate_descriptor_fails(self):
        self.write_jar(duplicate=True)
        with self.assertRaisesRegex(ValueError, "one plugin.yml"):
            verify(self.directory, self.VERSION)

    def test_missing_compiled_entry_point_fails(self):
        self.write_jar(main_class=False)
        with self.assertRaisesRegex(ValueError, "Missing compiled"):
            verify(self.directory, self.VERSION)

    def test_bundled_server_api_fails(self):
        self.write_jar(bundled_api=True)
        with self.assertRaisesRegex(ValueError, "must not be bundled"):
            verify(self.directory, self.VERSION)


if __name__ == "__main__":
    unittest.main()
