Planner: Iteration 1
Timestamp: 2026-05-14T21:38:16Z

Summary:
- Planned `task-300` after syncing code-index for `/home/westbam/Development/jormanager` with both deep build and refresh, then verifying code-index findings against live files.
- Required reads completed: `.agent/readme.md`, `.agent/system/architecture.md`, `.agent/workflows/backend.md`, `.agent/workflows/test.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`, and relevant tracing/block-monitor research (`task-201`, `task-202`, `task-204`, plus Hermod reference note).
- No existing canonical `task-300` plan was present, so `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md` was created.

Plan:
- Interaction mode: `autonomous`.
- Scope: remove legacy SSH and log-file scraping from `BlockMonitor` so block discovery is tracing-only, while preserving the existing block validation loop and forged/missed/orphaned classification behavior.
- Non-goals: no tracing protocol redesign, no `NodeMonitor` migration, no `EkgService` retirement, no config/startup/template work, no fallback legacy discovery path.
- Expected file changes: primarily `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`, new focused `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`, and only minimal adjacent test updates if bean/constructor changes require them.
- Implementation approach: delete the legacy local/remote scrape methods and obsolete parsing/enrichment helpers, remove now-unused constructor dependencies/imports/shutdown logic/mutexes, keep `validateBlocks()` intact, and simplify `monitorBlocks()` or remove it if it has no remaining live purpose after discovery transport deletion.
- Acceptance criteria: no runtime dependence remains on `logs/node.json`, `logs/node-*.json`, `tail`, `grep`, shell commands, SSH sessions, or SSH pool shutdown for block discovery; tracing is the only discovery writer; legacy discovery helpers are removed rather than left dormant; validation still classifies candidate blocks correctly.
- Verification plan: static confirmation that legacy discovery symbols and dependencies are gone; focused backend tests covering retained validation outcomes for forged, missed, and orphaned cases; run the narrowest relevant Gradle test target around the new `BlockMonitorTest`.
- Risks/open questions: main risk is leaving stale lifecycle scaffolding or unintentionally changing validation semantics during simplification; if `monitorBlocks()` has no remaining effect once scraping is removed, the smallest truthful implementation may delete more lifecycle code than originally expected.

Outcome: Plan drafted and ready for critique

Critiquer: Iteration 1
Timestamp: 2026-05-14T21:40:30Z

Summary:
- The plan is close, but it leaves one startup-boundary regression unpinned: `task-300` is framed as a local `BlockMonitor` deletion pass, yet `BlockMonitor.start()` still seeds persisted nodes into the shared `nodesChannel` (`src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:101-110`), and both `NodeMonitor` and `PooltoolMonitor` still rely on that shared stream for cold-start monitoring (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:105-114`, `src/main/kotlin/com/swiftmako/jormanager/monitors/PooltoolMonitor.kt:99-107`).

Blocking findings:
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md:125-145` leaves open removing `monitorBlocks()` or deleting more lifecycle code if it no longer serves a purpose. That is not safe to leave implicit. Today the same area still owns the initial `nodesChannel.emit(...)` fan-out in `BlockMonitor.start()` (`src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:101-110`). `NodeMonitor` explicitly documents that it is not self-seeding because `BlockMonitor` already does it (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:105-113`), and `PooltoolMonitor` also has no startup seed of its own. If task-300 removes or collapses that lifecycle without an explicit replacement, existing nodes will stop getting monitored after a cold start until some later node update flows through `NodeController`. The plan needs to pin the boundary: preserve the startup seed as-is for this task, or deliberately move it to a shared owner with matching verification.
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md:163-169` only verifies validation outcomes and the absence of legacy shell/SSH collaborators. That misses the cold-start regression above, and the risk is amplified because `nodesChannel` is a plain `MutableSharedFlow()` with no replay buffer (`src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt:204-209`). As written, the task could pass all proposed tests while silently breaking startup wiring for `NodeMonitor` and `PooltoolMonitor`. The plan must either add an explicit startup-broadcast verification or narrow itself to "delete scrape methods while preserving current seeding behavior exactly."

Non-blocking observations:
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md:150-151` should scope its acceptance wording to `BlockMonitor` specifically. The repository still intentionally uses SSH-based transport elsewhere today, notably in `NodeMonitor` and `PooltoolMonitor`, so broad phrasing like "no runtime dependency remains" is only accurate when limited to block discovery in `BlockMonitor`.
- `TracingConnectionManager` already self-seeds from `nodeRepository` on startup (`src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:79-84`), so task-300 does not need any extra tracing bootstrap work. The smallest truthful plan is simpler: delete the legacy scrape path, remove `TracingBlockPersistenceService`/SSH/file parsing leftovers from `BlockMonitor`, and leave shared startup seeding untouched unless this task explicitly owns moving it.
- Removing `SSHClientPool.shutdown()` from `BlockMonitor.stop(callback)` is the right direction, because `NodeMonitor` and `PooltoolMonitor` still use that shared pool. That cleanup is within task-300 scope and lowers cross-monitor shutdown coupling.

Approval bar:
- Lock the startup ownership boundary in the plan: either preserve `BlockMonitor`'s current `nodesChannel` seeding unchanged for task-300, or move it explicitly and say where.
- Add one automated verification for cold-start node seeding/broadcast behavior, not just validation outcomes and shell/SSH deletion.
- Tighten acceptance wording so SSH/log-file removal claims are clearly limited to `BlockMonitor` block discovery.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-14T21:42:36Z

Summary:
- Re-read the required docs, the current `task-300` plan, the critique log, and verified the critique directly against live code in `BlockMonitor`, `NodeMonitor`, `PooltoolMonitor`, `TracingConnectionManager`, and `Configuration`.
- Revised `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md` to address the critique blockers with the smallest truthful change.

Plan revisions made:
- Locked the startup ownership boundary explicitly:
  - `task-300` now preserves `BlockMonitor.start()` cold-start seeding of persisted non-deleted nodes onto `nodesChannel`.
  - The plan now explicitly forbids moving that startup ownership in this task.
  - The plan distinguishes between `BlockMonitor`'s startup seeding responsibility and its legacy block-discovery transport, so only the latter is deleted.
- Added verified live-code evidence for the critique:
  - `BlockMonitor.start()` still seeds `nodesChannel`.
  - `NodeMonitor.start()` explicitly relies on `BlockMonitor` for that seed.
  - `PooltoolMonitor` also relies on `nodesChannel` and does not self-seed.
  - `nodesChannel` is a plain `MutableSharedFlow()` with no replay buffer, so cold-start seeding is a real contract.
- Tightened the implementation approach:
  - `monitorBlocks()` may be removed only if it is redundant after legacy discovery deletion, but the `start()` node broadcast must remain intact.
  - `SSHClientPool.shutdown()` removal is now scoped specifically to `BlockMonitor.stop(...)`, not the wider app.
- Tightened acceptance wording:
  - SSH/log-file removal claims are now explicitly scoped to `BlockMonitor` block discovery.
  - Acceptance now also requires preserving cold-start `nodesChannel` seeding and removing only `BlockMonitor`'s shutdown coupling to `SSHClientPool`.
- Added the missing verification:
  - New automated verification now requires a focused cold-start broadcast test proving `BlockMonitor.start()` still emits persisted non-deleted nodes onto `nodesChannel`.
  - Verification explicitly prefers a direct `BlockMonitor` plus shared-channel test over a broader `NodeMonitor` or `PooltoolMonitor` integration harness.

Outcome:
- `task-300` canonical plan revised and now addresses the critique’s startup-boundary, cold-start verification, and acceptance-scope blockers.
- No planning review log was edited.

