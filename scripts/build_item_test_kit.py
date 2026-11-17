"""Package an already-verified disposable item harness; never contact a server."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def build(root: Path, jar: Path, destination: Path, revision: str | None = None) -> None:
    root = root.resolve()
    jar = jar.resolve()
    destination = destination.resolve()
    if not jar.is_file() or jar.suffix != ".jar":
        raise ValueError("One verified plugin JAR is required")
    if destination != root / "build" / "item-test-kit":
        raise ValueError("Output must be the repository's ignored build/item-test-kit")
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    shutil.copyfile(jar, destination / jar.name)
    for name in ("ITEM_LIFECYCLE.md", "ITEM_LIVE_TEST_BRIEF.md"):
        shutil.copyfile(root / "docs" / name, destination / name)
    (destination / "fixture-config-example.yml").write_text(
        "# Merge only these authorized test settings; preserve unrelated configuration.\n"
        "prototype:\n  enabled: true\n  item-lifecycle:\n    enabled: true\n"
        "    survives-fire-lava: true\n    despawns: true\n", encoding="utf-8")
    revision = revision or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    (destination / "BUILD_INFO.json").write_text(json.dumps({
        "source_commit": revision, "plugin_jar": jar.name,
        "purpose": "disposable item lifecycle harness; native acceptance pending",
        "paper_api": "26.3.build.249-stable", "java_target": 25,
        "resource_pack_required": False,
    }, indent=2) + "\n", encoding="utf-8")
    lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
             for path in sorted(destination.iterdir()) if path.is_file()]
    (destination / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parent.parent
    build(repository, args.jar, repository / "build" / "item-test-kit")
