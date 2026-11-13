#!/usr/bin/env python3
"""Derive packaged versions from the checked-out Git commit."""

import argparse
from pathlib import Path
import re
import subprocess

RELEASE_TAG = re.compile(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.(?:0|[1-9][0-9]*))?")


def git(repo, *args):
    return subprocess.check_output(
        ["git", "-C", str(repo), *args], text=True, stderr=subprocess.PIPE
    ).strip()


def build_version(repo, release_tag=None):
    if git(repo, "rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Version selection requires complete Git history and tags.")
    head = git(repo, "rev-parse", "HEAD")
    short_head = git(repo, "rev-parse", "--short=12", "HEAD")
    if release_tag is not None:
        if not RELEASE_TAG.fullmatch(release_tag):
            raise ValueError("Release tag does not match the accepted format.")
        if git(repo, "rev-parse", f"refs/tags/{release_tag}^{{commit}}") != head:
            raise ValueError("Release tag must identify the checked-out HEAD.")
        return release_tag[1:]
    tags = [
        tag for tag in git(repo, "tag", "--merged", "HEAD").splitlines()
        if RELEASE_TAG.fullmatch(tag)
    ]
    if not tags:
        return f"0.0.0-SNAPSHOT-g{short_head}"
    args = ["describe", "--tags", "--long", "--abbrev=12"]
    for tag in sorted(tags):
        args.extend(["--match", tag])
    return git(repo, *args, "HEAD")[1:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--release-tag")
    args = parser.parse_args()
    try:
        print(build_version(args.repo, args.release_tag))
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Version selection failed: {error}\n")


if __name__ == "__main__":
    main()
