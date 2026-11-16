# Authoritative Repository and Operation Journal

Task #8 implements a server-independent storage foundation. It is **not wired into the current prototype entry point**: starting the existing plugin does not open this database, import `prototype-cleanup.bin`, persist prototype HP, or activate production gameplay. The rendering/combat experiments retain their existing cleanup mechanism. The Java APIs are internal implementation contracts, not a public acquisition or third-party integration API. Later item/transition/reconciliation tasks must connect the repository's readiness and pending-operation gates before enabling world mutations.

## Storage and Deployment Boundary

The initial implementation uses pinned `org.xerial:sqlite-jdbc:3.54.1.0` / SQLite 3.54.1 on a local filesystem. The deployable plugin JAR bundles the driver, native libraries, JDBC service entry and Xerial/SQLite notices. It excludes a second SLF4J API and all Paper/server API classes; Paper supplies logging. A separate JVM test opens a database through the actual plugin JAR with only the server logging API on its classpath, verifies the driver came from that JAR and queries the loaded native engine. No JDBC library is downloaded at server startup.

`SqliteRepository.open(path, queueCapacity)` returns a future. Directory creation, driver loading, schema validation/migration and all subsequent database work run on its dedicated daemon thread. An instance becomes available only after initialization and stored-record validation succeeds. The application ID, migration checksums and exact known schema structure reject unrelated, future or altered databases; malformed snapshots and broken references also reject startup. The repository never repairs or drops an unrecognized database. Preserve it and investigate outside the world mutation path.

WAL, `synchronous=FULL`, foreign keys and a five-second busy timeout are configured and read back. Write transactions use IMMEDIATE mode to acquire their writer position before reading CAS preconditions, avoiding a deferred read-to-write upgrade race. Multi-query reads use a DEFERRED transaction so a tower, ground representation and manifest share one SQLite snapshot. SQLite still has one writer. A lock/constraint/I/O failure fails the request; the repository does not silently retry a transaction or replay effects. A rollback failure stops admission and fails remaining queued work.

WAL is for a single host/local filesystem, not a network share. Its `.db`, `-wal` and `-shm` files are related: do not copy a running database file alone as a backup. Stop/close the repository cleanly before an offline file backup, or use a separately validated SQLite backup procedure. FULL durability relies on the operating system/filesystem honoring sync requests. Java 25 permits the bundled native library but emits its native-access warning in this test environment; future Java baselines require their own native-access verification.

## Versioned Records

Schema migration 1 creates variant, tower and operation records. Migration 2 adds ground representations, structure components, award metadata/identity links and repair escrow. Migration history has immutable SQL checksums; schema changes require a new migration rather than editing an existing production migration.

| Record | First-class fields and ownership |
| :--- | :--- |
| Variant | Stable identifier, definition revision, ACTIVE/RETIRED, last valid versioned definition document, row version. Retirement retains the definition; no dependent record is deleted. |
| Tower | UUID, variant, finite HP/maximum, stable form, generation, deployment revision, controller, world/base/orientation, independent switches, expansion/repair timestamps, row version and active operation. No permanent owner or inventory-holder tracking. |
| Ground representation | One entity UUID per tower, generation, world/chunk, delivery operation and status. It exists only with a GROUND tower snapshot. |
| Structure component | Tower/deployment revision, unique component identifier, BLOCK/ENTITY, world/position, original/expected block states or entity UUID/type. It exists only with COMPACT/EXPANDED snapshots. |
| Operation | Immutable identity and expected tower version/generation/revision, before/after state snapshots, kind, versioned effect-intent document, durable phase, operation row version and caller evidence. |
| Award | Unique source/recipient/variant key, quantity, status, row version and normalized unique tower-identity links. Identity allocation is paged; quantity is not limited to one page or a gameplay tower-count cap. |
| Repair escrow | Operation/tower/deployment revision, contributor, versioned exact resource bytes, payment phase, HP phase and row version. Resource bytes are defensively copied. |

The `ILS1` binary snapshot codec is versioned, length-bounded and rejects trailing or malformed data. It contains only immutable records, strings and bytes. Variant definitions and intended effects are opaque versioned documents at this layer; later catalog/effect services validate their semantics. Resource stacks must be serialized on their owning scheduler before storage, never by touching Bukkit inventories or ItemStacks on the database thread. No item lore is authoritative.

## CAS and Durable Intent Protocol

1. Capture source identity, expected row/generation/deployment revision and immutable before/after/effect data on the owning scheduler. Validate gameplay/protection there; the repository cannot perform those checks.
2. `beginIntent` checks all expected values, records the intent, claims `active_operation` and increments the tower row version in one database transaction. Only one nonterminal operation may own a tower. Reusing the same operation ID and identical intent returns its existing receipt; different intent data with that ID fails.
3. Return asynchronously to the owning world scheduler and revalidate the source/revision/authorization before applying named idempotent effects. The database performs **none** of those effects. Direct tower CAS is limited to an unchanged representation; it cannot change form, generation, deployment revision, controller, anchor or manifest outside the journal.
4. The caller records verified effect progress using operation-version/phase CAS. `EFFECTS_APPLIED` means the caller asserted that phase; it is not database inspection of Minecraft saves. `completeOperation` requires the matching active operation and frozen tower version, commits the intended tower/ground/manifest together, clears ownership and records completion atomically. Repeated completion returns the stored operation receipt without replaying anything or changing a newer tower.
5. Ambiguous evidence moves the operation to RECONCILING and leaves the tower frozen. `resolveRollback` is available only from that phase, with explicit caller-supplied evidence that the original representation was restored. It restores the database snapshot; it neither refunds resources nor proves an inventory/chunk save was restored. No startup path automatically completes or compensates pending operations.

A stable tower snapshot may retain its pre-operation form while `active_operation` is set. `mutationReady()` is false; callers must treat it as unavailable, not as a second usable representation. Every pending journal entry survives reopening and is exposed through bounded pages for the later reconciliation service. A database transaction never atomically commits Minecraft world, entity, player or inventory saves. Task #11 must handle those independent save boundaries and ambiguous physical outcomes.

Award metadata cannot mutate tower state. Appending identities requires current unfrozen tower versions, matching variants and award-row CAS; source provenance and existing identity links are immutable. Repair escrow writes require the current pending operation version plus tower/deployment ownership. Operation completion/rollback rejects unsettled escrow phases. These are storage guards, not administrative issuance, recipient eligibility, delivery, resource payment or repair-rounding implementations.

## Threading, Bounds and Shutdown

All reads and writes are admitted through one bounded queue. Capacity is an explicit technical setting (1..100,000 requests); a full queue returns an exceptional future with `RejectedExecutionException` before admission. Callers must retain their source state and retry/reject privately as appropriate. This does not limit how many towers may exist. Reads return immutable snapshots and at most 256 rows per request, using an exclusive lexical UUID cursor. Pagination across different requests is not one global database snapshot. Per-state manifests have a technical bound of 4,096 components and 1 MiB encoded state; resource bytes are bounded to 1 MiB. Oversized requests fail before their world effects may begin.

Completion callbacks may run on the database thread or immediately on the attaching caller if a future already completed. Never use `join()`/`get()` from a world thread or access live world/player objects in a database callback. `WorldScheduler` accepts an immutable world/chunk anchor; `PaperWorldScheduler` marshals an action through Paper's task scheduler and rejects missing/unloaded owner chunks without loading them. Its adapter is compiled here; actual gameplay scheduling integration is later work. It is not a Folia compatibility claim.

`closeAsync()` immediately stops admission and returns a future; it does not wait or perform I/O on its caller. Accepted requests drain in order, then the worker closes its connection and completes that future. Requests arriving after close fail. A caller coordinating process shutdown must await that future outside world threads; process termination can discard requests that never committed. Completed database commits and uncommitted queued requests must not be conflated. Close the world-scheduler adapter during owner-thread plugin disable so abandoned callbacks fail rather than waiting forever. No current prototype lifecycle is changed by this unwired service.

## Repository Verification

The existing suite plus repository tests cover:

- Fresh schema and v1-to-v2 migration preserving versions, health, timers and retirement data.
- Unknown/future/unrelated or altered schema rejection without overwriting database contents; malformed journal decoding fails closed.
- Concurrent CAS with one winner, frozen intent ownership, identity/generation checks and prevention of direct representation mutation.
- Pending intent reopen, guarded phase progress, idempotent completion, explicit reconciliation/rollback and real SQL-triggered mid-completion failure with full rollback.
- Ground/entity-manifest round trips, award uniqueness and paged identity claims, immutable escrow bytes and settlement guards.
- Real SQLite writer-lock backpressure, accepted-request draining/close rejection and off-caller execution.
- Immutable pages/components, snapshot codec bounds and an isolated native-JDBC probe of the actual deployable JAR.

These tests do not prove world-save atomicity, live player/item recovery, all production crash scenarios, Paper runtime integration or server load. Those remain later lifecycle, recovery and G1-G5 work.

## Local SQLite Benchmark

Run `./gradlew repositoryBenchmark` to reproduce a synthetic temporary-local-database workload. It writes only generic results to ignored `build/benchmarks/repository.json` and removes its synthetic database. The measured implementation uses WAL/FULL and the same async/CAS/journal paths as the service; the workload performs no world effects and runs no Minecraft process.

Measured 2026-11-17: SQLite 3.54.1, JDBC 3.54.1.0, Java 25.0.5.0.3, Linux amd64, 12 available processors, 4,037,017,600-byte maximum heap. The operating system's temporary local filesystem was used; physical disk/fsync behavior was not independently characterized. No hostname, private path or real tower/player/world data is included.

| Workload | Count | Mean ms | p50 ms | p95 ms | p99 ms |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Insert portable synthetic tower | 1,000 | 0.1859 | 0.1623 | 0.3051 | 0.3940 |
| HP CAS, one durable transaction | 1,000 | 0.2554 | 0.2368 | 0.3373 | 0.3973 |
| Immutable page of 256 towers | 100 | 20.1400 | 19.1337 | 23.1153 | 27.2105 |
| Intent + applied phase + completion, three commits | 100 | 1.2611 | 1.0972 | 1.8906 | 2.3126 |

SQLite is selected as the initial local backend for this bounded foundation. These results justify continuing implementation for the documented synthetic workload; they establish neither a gameplay tower-count maximum nor production latency/capacity on another machine. Larger volumes, overlapping operations, storage contention, backups, real server tick impact and recovery backlog must be measured under Task #27/G4 before production tuning/readiness claims.

## Sources

- [Xerial JDBC releases and native packaging](https://github.com/xerial/sqlite-jdbc)
- [SQLite WAL constraints and concurrency](https://sqlite.org/wal.html)
- [SQLite synchronous durability modes](https://sqlite.org/pragma.html#pragma_synchronous)
- [SQLite transaction modes](https://sqlite.org/lang_transaction.html)
- [SQLite online backup API](https://sqlite.org/backup.html)
