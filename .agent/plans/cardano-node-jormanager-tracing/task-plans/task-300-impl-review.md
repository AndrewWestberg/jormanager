Implementation: Iteration 1
Timestamp: 2026-05-14T21:48:43Z

Changes made:
- Reduced `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt` to the approved tracing-era boundary: startup still seeds persisted non-deleted nodes onto `nodesChannel`, but all legacy block-discovery transport code was removed.
- Deleted the legacy local and remote scraping paths, scraped-line parsing, shell command execution, SSH session usage, SSH pool shutdown coupling, and the obsolete constructor dependencies that only supported that path.
- Kept the existing validation loop behavior intact for forged, missed, and orphaned block classification, with only minimal control-flow simplification inside `validateBlocks()`.
- Added `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt` with focused tests for cold-start node seeding and the retained forged/missed/orphaned validation outcomes.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest"` ✅
- Note: this Gradle target still triggers the repo's frontend build and Vue tests as part of the configured task graph; those also passed during the run.

Deviations from approved plan:
- None in behavior or scope.
- The focused cold-start test uses a replay-enabled test `MutableSharedFlow` fixture so emitted startup nodes can be asserted deterministically without changing production wiring.

User interaction required now:
- No

Outcome: Implementation ready for code review

Code Review: Iteration 1
Timestamp: 2026-05-14T21:50:34Z

Blocking findings:
- None. I did not find a correctness or regression issue in the live `task-300` implementation that should block approval.
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:66-156` now cleanly limits `BlockMonitor` to startup node seeding plus validation, and the removed SSH/file-scrape transport is already independently covered by the tracing path in `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:66-85` and `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt:18-41`.
- The cold-start seeding contract for adjacent monitors is preserved: `BlockMonitor.kt:69-72` still emits persisted non-deleted nodes, which matches the current dependency called out in `NodeMonitor.kt:105-110` and `PooltoolMonitor.kt:105-141`.
- Validation behavior is still intact for forged/missed/orphaned classification in `BlockMonitor.kt:78-142`, and the new focused coverage in `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt:19-107` exercises those retained paths.

Non-blocking observations:
- `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt:25` uses a replay-enabled test `MutableSharedFlow`, while production wiring remains unreplayed in `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt:204-209`. That is fine for deterministic assertions here, but it does not reduce the pre-existing startup timing sensitivity of the real shared flow.
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json:398-423` still shows `task-300` as `pending`, so there is implementation-tracking drift in plan metadata. That is documentation/process drift, not a code blocker.

Approval bar:
- Met for `task-300`: block discovery is tracing-only, legacy SSH/log scraping code is actually deleted rather than left dormant, `BlockMonitor` still preserves the shared startup seed contract, and validation semantics remain in place with focused regression coverage.

Decision: approved

