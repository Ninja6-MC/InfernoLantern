#!/usr/bin/env python3
"""Reject missing, additional or incorrectly versioned plugin artifacts."""

import argparse
from pathlib import Path
import re
import zipfile

MAIN = "com.ninja6.infernolantern.InfernoLanternPlugin"


def scalar(descriptor, key):
    values = re.findall(rf"^{re.escape(key)}: (.+)$", descriptor, re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {key} field in plugin.yml.")
    return values[0].strip().strip("'\"")


def verify(directory, version):
    expected = f"InfernoLantern-{version}.jar"
    artifacts = sorted(path.name for path in directory.glob("*.jar"))
    if artifacts != [expected]:
        raise ValueError(f"Expected only {expected}; found {artifacts}.")
    with zipfile.ZipFile(directory / expected) as artifact:
        names = artifact.namelist()
        if names.count("plugin.yml") != 1:
            raise ValueError("Expected one plugin.yml entry.")
        descriptor = artifact.read("plugin.yml").decode("utf-8")
        if scalar(descriptor, "version") != version:
            raise ValueError("Embedded plugin version differs from artifact version.")
        if scalar(descriptor, "name") != "InfernoLantern":
            raise ValueError("Unexpected plugin name.")
        if scalar(descriptor, "main") != MAIN or scalar(descriptor, "api-version") != "26.3":
            raise ValueError("Unexpected plugin entry point or API baseline.")
        entry = MAIN.replace(".", "/") + ".class"
        if entry not in names or artifact.read(entry)[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("Missing compiled plugin entry point.")
        if any(name.startswith("org/bukkit/") for name in names):
            raise ValueError("The server API must not be bundled in the plugin.")
    return directory / expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        print(f"Verified {verify(args.directory, args.version).name}")
    except (ValueError, OSError, zipfile.BadZipFile, UnicodeError) as error:
        parser.exit(1, f"Artifact verification failed: {error}\n")


if __name__ == "__main__":
    main()
