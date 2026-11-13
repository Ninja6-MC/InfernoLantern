# Building Inferno Lantern

The build targets Java 25 and the exact Paper API `26.3.build.249-stable`, as selected in the [interface and test-target decision](INTERFACES_AND_TEST_TARGETS.md). Java 25 is a maintainer-approved repository baseline because Paper 26.3 requires it; the inherited contribution guidance's Java 21 baseline does not describe this repository. Gradle 9.9.1 is pinned through the checked-in wrapper with a distribution SHA-256. Java 25 must be installed locally; the build does not silently download a toolchain. Python 3 and Git are also required for build-contract verification and version selection. `PYTHON` can select another Python 3 executable.

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). The build compiles the minimal plugin entry point, expands `plugin.yml`, runs the build-tool contract tests and verifies the actual JAR in `build/libs`. `./gradlew test` runs the Python contract suite through its `buildToolTests` prerequisite; there is no gameplay code or Java unit-test suite yet. The plugin deliberately registers no commands, permissions, listeners or gameplay systems in this scaffold. The selected future interface remains in the decision record. A successful compile/package check does not establish server or crossplay compatibility.

## Source versions and artifacts

Every normal build, including CI at a release tag, computes a snapshot from the checked-out `HEAD`. CI checks out complete history and tags and builds the synthetic merge commit on pull requests/merge groups, rather than replacing it with the feature-branch tip. A shallow repository fails version selection; use a complete clone with tags.

The accepted release-tag format is `^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.(0|[1-9][0-9]*))?$`. Leading-zero numeric components, build metadata, other prerelease forms and suffix garbage are rejected. The future release workflow must reuse this contract rather than introducing a different tag selector.

`scripts/build_version.py` filters tags to complete valid names reachable from `HEAD`, then selects the nearest using `git describe --tags --long`. It strips the leading `v`, always retains Git distance and at least 12 commit characters, and produces forms such as `0.1.0-alpha.1-0-g0123456789ab` at an exact tag. Without a reachable valid tag it returns `0.0.0-SNAPSHOT-g0123456789ab`. Malformed or unreachable tags cannot select a base version. Both lightweight and annotated valid tags are supported.

For a local clean-version packaging check only, `./gradlew clean build -PreleaseTag=v0.1.0-alpha.1` requires that exact valid tag to exist and resolve to the checked-out `HEAD`; the resulting version is `0.1.0-alpha.1`. It fails on a malformed tag or a different commit. This is not a publication workflow or permission to create a tag/release. Release readiness, candidate promotion and deployment gates remain task #28.

The sole deployable artifact is `build/libs/InfernoLantern-<computed-version>.jar`. `verifyPluginJar` enumerates every JAR in that directory and rejects missing or extra files, a filename/version mismatch, duplicate descriptors, a missing compiled entry point, an unexpected API baseline, or bundled server API classes. It verifies that the embedded `plugin.yml` version equals the same computed version. Build artifacts have stable entry ordering and no preserved file timestamps. Use a clean build when changing version inputs; leftover JARs are rejected rather than silently uploaded.

## CI and checks

The `Build and Test` job runs on main pushes, pull requests and merge groups with no path filter. Checkout removes persistent credentials, workflow permissions are read-only, actions are pinned to immutable commits, and the Gradle setup validates the wrapper. The distribution checksum also protects wrapper downloads. The build uploads only the verified JAR set and records the built commit, computed version and artifact SHA-256 in its log. No CI job publishes a release or holds deployment credentials.

The build-context name must be observed on a real passing PR before task #32 adds it to protection. Task #32 also owns merge-queue adoption and its live verification; having a `merge_group` trigger alone is not evidence that a queue is active. DCO and Standards continue to use the existing shared workflow callers. Assigned-rule coverage is recorded in [STANDARDS_COVERAGE.md](STANDARDS_COVERAGE.md).

## Tool sources

- [Gradle 9.9.1 release](https://docs.gradle.org/9.9.1/release-notes.html) and [Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)
- [Official distribution and wrapper checksums](https://gradle.org/release-checksums/)
- [Paper plugin descriptor](https://docs.papermc.io/paper/dev/plugin-yml/)
- [Gradle setup action wrapper validation](https://github.com/gradle/actions/blob/main/docs/wrapper-validation.md)
