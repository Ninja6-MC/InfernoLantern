# Disposable item-drop recovery acceptance

The opt-in fixture runs one item-drop journal per case on Paper 26.3 build
249. It exercises the real `MatchedItemSaves`, `PaperRecoveryPort` and
`ReconciliationService` on a loopback server and disposable copies. The
fixture plugin compiles separately from `src/recoveryAcceptance` and never
enters the core JAR. Ordinary builds and tests start no Paper process.

## Run

Use Linux, Python 3.11 or later, and an explicit Java 25 JDK. Build the core
with `./gradlew clean check`. Provide the exact JAR and its SHA-256. Use the
pinned Paper 26.3 build 249 launcher and a directory where that launcher
has already booted and stopped in a disposable environment. The runner
checks the launcher, cached Minecraft server and generated Paper runtime
digests. It copies only the bootstrap `cache`, `libraries`, and `versions` directories. It makes
fresh test worlds and a single whitelisted offline player.

```sh
python3 scripts/run_item_recovery_acceptance.py \
  --core /absolute/path/to/InfernoLantern.jar --core-sha256 CORE_SHA256 \
  --paper /absolute/path/to/paper-26.3-249.jar \
  --bootstrap /absolute/path/to/disposable-paper-bootstrap \
  --java-home /absolute/path/to/jdk-25 --scratch /absolute/path/to/local-scratch
```

Choose an existing scratch directory outside the copied bootstrap folders
with at least 1 GiB free. The helper
JAR, server processes, raw files and logs go in a new directory there. The
fixture helper requires its local activation marker, offline mode, and
the loopback bind. Its fixture commands accept only the server console.
The runner starts and kills its own processes and keeps the capture and
logs local. Publish only generic results and artifact identities.

## Controls

| Saved source slot | Saved selected entity record | Expected result |
| --- | --- | --- |
| Original token | Exact target absent | Roll back with no drop |
| Empty | Exact staged target present | Complete and activate that existing item with its age preserved |
| Original token | Exact staged target present | Hold for administrative review with the stage and journal pending |

The runner seeds a standalone journal and saves selected chunk, player and
entity data independently before each crash. It seals the original compressed
player file, selected region file, world metadata and closed SQLite file
into one fixture capture. The helper derives actor, slot, world, chunk and
entity from the decoded journal. It reads the raw saved inputs, including
the addressed region record, and checks the manifest and file digests.
Paper's pinned parser and item codec interpret the physical bytes. The
test reasserts live mob pickup quarantine for its exact staged entity
before the real service runs. It checks the journal, authority row,
entity outcome and original file fingerprints afterward. The held drop
must retain its pickup restrictions and protection flags. At publication,
the finite age must equal the last observed staged age on the owner
thread. The final age can advance only by elapsed server ticks.

The capture's custody claim comes from the controlled single-player
fixture. Hashes identify its input artifacts. Coverage of other slots,
players, containers and worlds, a production capture source, other save
formats and automated startup recovery remain outside this proof. Issue
#11 remains open.
