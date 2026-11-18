"""Package separate, default-disabled acceptance instrumentation. No server access."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def validate_artifacts(core: Path, helper: Path) -> None:
    with zipfile.ZipFile(core) as archive:
        names = set(archive.namelist())
        core_version = next(line.split(":", 1)[1].strip().strip("\"' ") for line in archive.read("plugin.yml").decode().splitlines() if line.startswith("version:"))
        if "com/ninja6/infernolantern/item/PickupValidationContext.class" not in names:
            raise ValueError("Core candidate lacks exact-event context")
        if any(name.startswith("com/ninja6/infernolantern/acceptance/") for name in names):
            raise ValueError("Helper classes leaked into core candidate")
    with zipfile.ZipFile(helper) as archive:
        names = set(archive.namelist())
        if "com/ninja6/infernolantern/acceptance/ItemAcceptancePlugin.class" not in names:
            raise ValueError("Helper entry point missing")
        if any(name.startswith(("org/sqlite/", "com/ninja6/infernolantern/item/", "com/ninja6/infernolantern/repository/")) for name in names):
            raise ValueError("Helper must not embed core or JDBC classes")
        descriptor = archive.read("plugin.yml").decode()
        config = archive.read("config.yml").decode()
        if archive.read("acceptance-build.properties").decode().strip() != "core-version=" + core_version:
            raise ValueError("Core/helper build versions differ")
        if "commands:" in descriptor or "permissions:" in descriptor or "enabled: false" not in config:
            raise ValueError("Helper must be default disabled without commands or permissions")


def build(root: Path, core: Path, helper: Path, destination: Path, revision: str | None = None) -> None:
    root, core, helper, destination = (path.resolve() for path in (root, core, helper, destination))
    if destination != root / "build" / "item-acceptance-kit":
        raise ValueError("Output must be ignored build/item-acceptance-kit")
    validate_artifacts(core, helper)
    revision = revision or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    for artifact in (core, helper):
        shutil.copyfile(artifact, destination / artifact.name)
    for name in ("ITEM_ACCEPTANCE_HELPER.md", "ITEM_LIFECYCLE.md"):
        shutil.copyfile(root / "docs" / name, destination / name)
    for scenario in ("control", "original", "pre-intent", "effect"):
        (destination / f"helper-{scenario}-example.yml").write_text(
            "# Install/arm only after explicit approval in a separate disposable test setup.\n"
            f"enabled: true\nscenario: {scenario}\nttl-seconds: 300\nmax-events: 4096\n", encoding="utf-8")
    (destination / "BUILD_INFO.json").write_text(json.dumps({
        "source_commit": revision, "core_jar": core.name, "helper_jar": helper.name,
        "purpose": "default-disabled pickup cancellation instrumentation; installation approval and native evidence pending",
        "paper_api": "26.3.build.249-stable", "java_target": 25,
        "helper_in_core_release": False, "commands": [], "permissions": [],
    }, indent=2) + "\n", encoding="utf-8")
    (destination / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
        for path in sorted(destination.iterdir()) if path.is_file()), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    build(root, args.core, args.helper, root / "build" / "item-acceptance-kit")
