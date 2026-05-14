# Task 300 Plan

## Summary

- Task ID: `task-300`
- Title: `Remove SSH and file scraping from BlockMonitor`
- Why now: `task-202` already bridged forwarded adopted-block events into the candidate-block persistence flow, so the next smallest truthful step on the critical path is to delete the now-redundant SSH and log-file discovery path from `BlockMonitor`
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Remove the legacy block-discovery transport from `BlockMonitor` so block discovery comes only from the tracing subsystem, while preserving the existing validation loop and downstream forged or missed or orphaned classification semantics.

In scope:
- delete local and remote log scraping from `BlockMonitor`
- remove block-discovery dependence on `logs/node.json`, `logs/node-*.json`, `tail`, `grep`, local shell commands, SSH sessions, and SSH connection-pool shutdown from this monitor
- keep `BlockMonitor` responsible only for lifecycle wiring around core-node eligibility and block validation, not raw discovery transport
- remove obsolete parsing and enrichment helpers that only existed for the legacy scraped-line path
- simplify any now-redundant duplicate-suppression or locking that existed only because tracing and legacy discovery coexisted during `task-202`
- add focused automated verification that `BlockMonitor` no longer shells out or opens SSH sessions for block discovery and that block validation behavior remains intact

Out of scope:
- changing tracing connection lifecycle ownership from `task-200`
- changing forwarded adopted-block decoding or tracing persistence behavior from `task-201` and `task-202`
- refactoring `NodeMonitor` or the node-state `DataPoint` path (`task-301`)
- retiring `EkgService` or HTTP-only metrics plumbing (`task-302`)
- node config generation, startup wiring, or deployed template updates
- journald replay, historical recovery, or fallback legacy discovery support

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-202-tracing-block-persistence-bridge.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-204-node-state-datapoint-contract.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built the deep code index and refreshed the live index for this task before planning
- Used code-index search first to locate `BlockMonitor` and its related seams, then verified the important findings against live file contents
- Code-index-synced findings confirmed live:
  - `BlockMonitor` still starts one monitor job per core node but the actual candidate-block discovery still comes entirely from `monitorBlocksLocal(...)`, `monitorBlocksRemote(...)`, and `saveBlocksFromRemoteNode(...)`
  - those legacy methods still depend on `FileRepository`, genesis adapters, `TraceAdoptedBlock` parsing, `HostConnection`, `QueryTip`, `ProcessBuilder`, SSHJ, and rotated-file plus tail-and-grep command pipelines
  - `BlockMonitor.start()` still seeds all persisted non-deleted nodes into the shared `nodesChannel`, and that startup fan-out is not replay-backed because `nodesChannel` is a plain `MutableSharedFlow()` with no replay buffer
  - `NodeMonitor.start()` explicitly relies on `BlockMonitor` for that cold-start seeding today, and `PooltoolMonitor` also consumes `nodesChannel` without its own startup seed path
  - `validateBlocks()` is already independent from the discovery transport and can stay as the remaining core responsibility of `BlockMonitor`
  - `task-202` introduced `TracingBlockMessageSink` plus `TracingBlockPersistenceService`, so candidate-block persistence no longer needs to flow through `BlockMonitor`
  - `TracingBlockPersistenceService` now owns duplicate suppression behind a shared persistence mutex, which means the extra legacy-path `blockFoundMutex` can likely be removed once the old scraper path is deleted
  - there is no existing `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`, so the smallest truthful verification is likely one new focused monitor test class plus any tiny updates to tracing tests only if needed

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning
- Truthful autonomy basis:
  - the tracing-side prerequisites are already landed, the remaining work is a contained deletion and simplification pass inside `BlockMonitor`, and no manual runtime operator step is needed to plan or implement that change

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-202`
- Practical live repo dependencies this task should reuse carefully:
  - `task-200`
  - `task-201`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/repositories/BlockRepository.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/repositories/NodeRepository.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-302`
  - `task-103`
  - `task-400`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `start()` currently does two distinct things that must be treated separately in this task: cold-start seeding of `nodesChannel`, and startup of block-monitor-specific work
  - `monitorBlocks()` currently respawns one job per eligible core node and then dispatches to `monitorBlocksLocal(...)` or `monitorBlocksRemote(...)` based on host type
  - `monitorBlocksLocal(...)` and `monitorBlocksRemote(...)` are pure legacy discovery paths and contain all remaining file-scrape and SSH behavior that this task is meant to delete
  - `saveBlocksFromRemoteNode(...)` is legacy-only now that tracing persistence is production-wired; it still parses scraped JSON lines and optionally enriches hashes via `cardano-cli query tip`
  - `validateBlocks()` is separate and should remain the monitor's primary behavior after legacy discovery removal
  - `stop(callback)` still shuts down `SSHClientPool`, which should be removed from `BlockMonitor` because `NodeMonitor` and `PooltoolMonitor` still use that shared pool independently
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `start()` contains an explicit comment that it does not self-seed because `BlockMonitor` already offers existing nodes onto `nodesChannel`
  - `monitorNodes()` consumes `nodesChannel` directly, so breaking `BlockMonitor` cold-start seeding would leave existing nodes unmonitored until some later node update is emitted
- `src/main/kotlin/com/swiftmako/jormanager/monitors/PooltoolMonitor.kt`
  - `start()` does not seed persisted nodes and relies on `nodesChannel.collect { ... }` for core-node startup work, so it shares the same cold-start dependency on `BlockMonitor` today
- `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - `nodesChannel` is configured as `MutableSharedFlow()` with no replay or extra buffer settings, so startup seed timing is a real behavioral contract rather than an implementation detail
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - already resolves node and host context, decodes forwarded trace replies, and hands normalized events into persistence without any `BlockMonitor` dependency
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - already owns candidate-block save behavior and websocket publication for tracing-driven discovery
  - shared duplicate suppression now lives here, so `BlockMonitor` no longer needs a second discovery-side mutex once the old path is removed

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - remove legacy local and remote scraping, legacy parse and enrichment helpers, obsolete dependencies, and any now-unused shutdown or mutex logic
- `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`
  - new focused tests for the simplified monitor lifecycle and retained validation behavior
- `src/test/kotlin/com/swiftmako/jormanager/tracing/...`
  - only if a tiny assertion adjustment is needed after removing the old coexistence assumptions
- `.agent/plans/cardano-node-jormanager-tracing/research/task-300-*.md`
  - only if implementation reveals durable findings worth capturing for later cleanup tasks

## Implementation Approach

- Preferred smallest truthful production shape:
  - preserve `BlockMonitor.start()` cold-start seeding of persisted non-deleted nodes onto `nodesChannel` unchanged for this task
  - do not move `nodesChannel` startup ownership to another component in `task-300`; `NodeMonitor` and `PooltoolMonitor` still depend on the current owner and `TracingConnectionManager` already self-seeds independently
  - keep `validateBlocks()` startup intact
  - keep `monitorBlocks()` only if it still serves a real purpose after transport deletion; if it becomes empty or redundant, remove only the block-monitor-specific collection or job-management path while leaving the startup node broadcast in `start()` intact
  - remove `monitorBlocksLocal(...)`, `monitorBlocksRemote(...)`, and `saveBlocksFromRemoteNode(...)` entirely if no remaining call sites depend on them
- Treat tracing as the only discovery writer after this task:
  - do not add a new fallback path
  - do not preserve dormant shell or SSH helper code behind flags
  - do not move legacy parsing logic into the tracing package
- Simplify `BlockMonitor` to its real remaining responsibility:
  - periodic validation of already-persisted candidate blocks
  - preserving the existing cold-start `nodesChannel` broadcast contract for other monitors until a later task deliberately re-homes that ownership
- Remove obsolete dependencies aggressively but narrowly:
  - constructor injections for `FileRepository`, `TracingBlockPersistenceService`, genesis adapters, `TraceAdoptedBlock` adapter, and `QueryTip` adapter should be removed if they become unused after deleting the legacy path
  - imports and shutdown behavior for `HostConnection`, `SSHClientPool`, SSHJ, `ProcessBuilder`, `TimeUnit`, `okio`, `DataIntegrityViolationException`, and related legacy-only code should be removed from `BlockMonitor` when no longer referenced
  - if host lookups are no longer needed for block monitoring, reduce `HostRepository` usage too; if still needed for minimal eligibility or bookkeeping, keep only that seam
- Keep validation semantics intact:
  - leave `validateBlocks()` logic and repository contract behavior unchanged unless a tiny refactor is required to keep the class coherent
  - do not change forged, missed, or orphaned classification rules in this task
- Testing approach should stay focused:
  - add one new `BlockMonitorTest` class rather than broad integration coverage
  - verify the simplified monitor preserves the current cold-start `nodesChannel` broadcast behavior for persisted non-deleted nodes
  - verify the simplified monitor starts without spawning legacy BlockMonitor discovery behavior
  - verify `validateBlocks()` still updates old candidate blocks correctly for at least one forged or missed or orphaned path
  - use mocks to prove no shell, SSH, or legacy parse collaborator is needed anymore, primarily by the absence of those dependencies from the constructor and class surface rather than by brittle command-string assertions

## Acceptance Criteria

- `BlockMonitor` no longer contains runtime block-discovery dependence on `logs/node.json`, `logs/node-*.json`, `tail`, `grep`, local shell commands, or SSH sessions.
- Candidate-block discovery comes only from the tracing subsystem already wired by `task-202`.
- Legacy block-discovery-only helpers are removed rather than left dormant.
- `BlockMonitor` preserves its current cold-start `nodesChannel` seeding behavior for persisted non-deleted nodes in this task; startup ownership is not moved here.
- `BlockMonitor` still runs the downstream validation loop so previously discovered candidate blocks can still be classified as forged, missed, or orphaned.
- `BlockMonitor.stop(...)` no longer shuts down the shared `SSHClientPool`, because that pool is still used by other monitors outside this task.
- No new fallback or backward-compatibility path is introduced.

## Verification Plan

- Static verification:
  - confirm `BlockMonitor.kt` no longer contains `monitorBlocksLocal`, `monitorBlocksRemote`, `saveBlocksFromRemoteNode`, `ProcessBuilder`, `tail -Fn0`, `grep`, `SSHClient`, `SSHClientPool`, `HostConnection`, or `QueryTip`-driven hash enrichment code
  - confirm tracing-driven persistence remains owned by `TracingBlockMessageSink` plus `TracingBlockPersistenceService`, not moved back into `BlockMonitor`
  - confirm `BlockMonitor.start()` still seeds persisted non-deleted nodes into `nodesChannel` and that this task does not move startup ownership to `NodeMonitor`, `PooltoolMonitor`, or another component
  - confirm `validateBlocks()` behavior is unchanged except for any minimal refactor required by deleted constructor dependencies
  - confirm no dormant legacy discovery code path remains behind conditionals or dead injections
- Automated verification:
  - add focused backend tests covering:
    - `BlockMonitor.start()` still broadcasts persisted non-deleted nodes onto `nodesChannel` on cold start
    - `BlockMonitor` startup still launches the validation loop without requiring legacy discovery collaborators
    - validation still marks an old candidate block as forged when chain data matches
    - validation still marks a candidate block as missed when no hash is available
    - validation still marks a candidate block as orphaned when the chain hash does not match
  - prefer a direct startup-broadcast test against `BlockMonitor` and the shared `nodesChannel` rather than a broader `NodeMonitor` or `PooltoolMonitor` integration harness
  - run the narrowest relevant backend target, preferably the new `BlockMonitorTest` plus any affected tracing tests if constructor or bean wiring changes require it
- Truthful validation boundary:
  - this task does not need live node or SSH experimentation because its goal is removing the old transport path, not proving the already-landed tracing transport again

## Risks And Open Questions

- Main risk is leaving `BlockMonitor` half-migrated with dead monitor-job scaffolding or stale constructor dependencies after deleting the scraper path; implementation should prefer deleting whole unused seams rather than preserving placeholders.
- Another risk is accidentally breaking the current cold-start `nodesChannel` seeding contract that `NodeMonitor` and `PooltoolMonitor` still rely on; this plan now locks that ownership boundary to stay in `BlockMonitor` for `task-300`.
- Another risk is accidentally changing validation semantics while simplifying the class; tests should pin at least one forged and one non-forged classification path.
- If `monitorBlocks()` turns out to have no remaining live effect once discovery is tracing-only, the smallest truthful implementation may remove that block-monitor-specific lifecycle code, but not the startup node broadcast owned by `BlockMonitor.start()`.
- No blocking user input or manual execution step is currently required.

## Required Docs, Tracking, And Research Updates

- Create this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`.
- Do not write the planning review log during this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation reveals durable findings about:
  - the smallest truthful final responsibility boundary for `BlockMonitor` after discovery removal
  - any validation-loop assumptions that were previously hidden by legacy discovery coexistence

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300-impl-review.md`

## Final Outcome

- Result: completed
- Approved plan summary:
  - preserve `BlockMonitor.start()` cold-start seeding of persisted non-deleted nodes onto `nodesChannel`
  - remove all legacy SSH, shell, and log-file block-discovery transport from `BlockMonitor`
  - retain only the validation loop for forged, missed, and orphaned classification
  - remove `BlockMonitor` shutdown coupling to the shared `SSHClientPool`
- Implementation outcome:
  - `BlockMonitor` now owns only the retained startup seeding contract plus candidate-block validation
  - legacy `monitorBlocksLocal(...)`, `monitorBlocksRemote(...)`, `saveBlocksFromRemoteNode(...)`, and their SSH or shell collaborators were deleted rather than left dormant
  - focused `BlockMonitorTest` coverage now pins cold-start seeding and forged, missed, and orphaned validation outcomes
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest"`
  - note: this repo's Gradle test graph also ran Vue unit tests and frontend production build during that target, and those passed
- Final review result:
  - `Code Review: Iteration 1` approved with no blocking findings
- Research outcome:
  - added `.agent/plans/cardano-node-jormanager-tracing/research/task-300-blockmonitor-boundary.md`

## Self-Review

- Scope creep check: the plan removes only legacy `BlockMonitor` discovery and does not absorb `NodeMonitor`, `EkgService`, config, or rollout-template work.
- Workflow check: verification is aligned with the repo's backend and test workflows and does not rely on stale live-node-only instructions.
- Missing tests/docs check: the plan explicitly calls for a new focused `BlockMonitor` test surface and optional durable research only if implementation finds something worth preserving.
- Complexity check: the preferred path is deletion and simplification inside `BlockMonitor`, not a new abstraction layer or another compatibility switch.
