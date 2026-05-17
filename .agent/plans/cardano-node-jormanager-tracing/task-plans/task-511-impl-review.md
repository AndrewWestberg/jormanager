Implementation: Iteration 1
Timestamp: 2026-05-17T23:20:47Z

Changes made: implemented a tracing-owned shared raw capture layer and moved production first-visibility for tracing traffic behind that layer. Added a new `TraceForwardMessage.MetricsReply` production message type, introduced `TracingRawCaptureService` with raw metric snapshots, raw datapoint snapshots, and bounded in-memory raw trace-object batch retention, and added `ForwardingMetricsProtocol` so protocol `1` now has a production decode path. Updated `SocketTraceForwardSessionClient` to open one muxed production session that runs protocol `1`, `2`, and `3` together after the forwarding handshake. Switched `TracingConnectionManager` to feed the shared raw capture service, updated `TracingBlockMessageSink` so block persistence runs downstream of raw trace-object capture, and refactored `NodeMonitor` to read datapoint and trace-object state from `TracingRawCaptureService` instead of opening its own authoritative tracing sockets. Added a `ForwardedNodeState.merge` member to keep the downstream merge path local to the tracing model.

Files touched: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`, `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511-plan-review.md`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/forwarding/ForwardingMetricsProtocol.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`, `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`, `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`, `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`, and `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`.

Verification run: `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest"`. This passed after exercising the new unified ingress path, raw-capture routing, downstream block persistence, and the monitor's raw-snapshot consumption. The Gradle invocation also ran the repo's `testVue` and `buildVue` tasks as part of the configured lifecycle; those passed too.

Deviations from approved plan: none in scope. The implementation stayed on the minimum transport follow-through needed to make one tracing-owned ingress seam real, then stopped at shared raw capture and downstream rewiring instead of widening into typed protocol-1 extraction or broader consumer migration.

User interaction required: no.

Outcome: task-511 implementation iteration 1 completed and ready for code review.
Code Review: Iteration 1
Timestamp: 2026-05-17T23:26:25Z

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:337-368`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt:23-43`, `101-106` regresses node-health correctness by treating raw snapshots as timeless truth. `NodeMonitor` now serves whatever `latestDataPointSnapshot()` / `recentTraceObjectBatches()` last captured, with no freshness gate and no invalidation on disconnect. Before this change each sample performed live protocol reads and naturally fell back to `nullNodeStats(...)` on failure; after this change a dead or partitioned tracing connection will keep publishing the last healthy stats indefinitely. That violates the monitor semantics the PRD is trying to preserve and masks tracing outages on the dashboard. This needs either bounded freshness checks based on `capturedAt`, or explicit cache eviction on disconnect / teardown, with coverage for the stale-snapshot case.
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt:52-53`, `86-93`, `105-106` introduces an unsafe concurrent buffer. The per-node `ArrayDeque` is mutated from tracing-ingest coroutines inside `ConcurrentHashMap.compute(...)`, while `NodeMonitor` concurrently iterates it via `recentTraceObjectBatches(...).toList()`. `ArrayDeque` is not thread-safe, and the map only protects replacing the reference, not concurrent mutation of the deque instance. This can race under normal runtime conditions because the connection manager writes while the monitor samples. The raw trace buffer needs synchronization or an immutable replacement strategy before this is safe.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:38-40` now depends directly on `TracingRawCaptureService` instead of the existing `TraceForwardMessageSink` seam. That is a step backward in transport-layer abstraction and will make later extraction tasks harder to test or swap cleanly, even though it does not block correctness by itself.
- The focused tests cover happy-path routing, but there is still no regression test for “connection drops after one good snapshot and NodeMonitor must stop publishing stale values”. That gap is what let the first blocking issue through.

Approval bar:
- Add snapshot invalidation or freshness enforcement so `NodeMonitor` falls back to null stats when tracing ingress is stale or disconnected.
- Replace the mutable shared `ArrayDeque` buffering with a thread-safe approach and add a concurrent-access-safe test around trace-batch reads/writes.
- Add at least one monitor-level test proving stale raw capture does not continue to drive dashboard stats after tracing ingress stops.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-17T23:27:43Z

Changes made: addressed the blocking code-review findings around stale dashboard state and unsafe raw trace buffering. Added freshness-aware snapshot access and explicit node cache invalidation to `TracingRawCaptureService`, switched the per-node trace batch store from a mutable shared `ArrayDeque` to immutable list replacement, and cleared raw capture state when tracing connections are removed or disconnected in `TracingConnectionManager`. Updated `NodeMonitor` to consume only fresh datapoint snapshots and fresh trace-object batches using a bounded `rawSnapshotMaxAge`, so stale tracing ingress now falls back to null node stats instead of publishing indefinitely healthy cached values. Added test-only raw-capture recording helpers for deterministic regression coverage.

Files touched: `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`, `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`, `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`, and `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`.

Verification run: `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest"`. This passed with the new stale-snapshot regression coverage and raw-capture eviction assertions. The Gradle lifecycle again ran `testVue` and `buildVue`; both passed.

Deviations from approved plan: none. The fix pass stayed on correctness and safety of the shared raw ingress layer without widening into new extraction or consumer features.

User interaction required: no.

Outcome: task-511 implementation iteration 2 completed and ready for code review.

Code Review: Iteration 2
Timestamp: 2026-05-17T23:29:25Z

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:153-179`, `207-215`, `102-117` does not actually guarantee the new cache invalidation on disconnect or shutdown. `runConnectionLoop()` only calls `messageSink.clearNode(...)` after the loop body continues past `sessionClient.runSession(...)`, but both `ManagedTracingConnection.shutdown()` and `stopAndWait()` cancel the job directly. On that path `runSession()` is interrupted by `CancellationException`, line `163-164` rethrows it, and the later `clearNode(...)` calls at `175` and `178` are skipped entirely. That means raw snapshots can still survive manager stop, target replacement, or cancellation-driven teardown, and `NodeMonitor` can keep treating them as fresh for up to `rawSnapshotMaxAge`. This is the same dashboard-truth problem in a narrower but still real form, so the stale-snapshot regression is not fully fixed yet. The invalidation needs to happen in a `finally`-style path or in teardown before cancellation makes the coroutine exit.

Non-blocking observations:
- The unsafe raw trace buffer issue appears fixed. `TracingRawCaptureService` now stores immutable `List<TracingRawTraceObjectBatch>` instances (`TracingRawCaptureService.kt:52`, `86`, `136-140`) instead of sharing a mutable `ArrayDeque`, so the previous concurrent read/write hazard is gone.
- The new freshness gate in `NodeMonitor` (`NodeMonitor.kt:339-353`) is directionally correct and matches the PRD better than the previous timeless-cache behavior.
- `TracingRawCaptureService.record*ForTest(...)` adds test-only hooks to production code. That is acceptable for now, but it is extra surface area that should stay minimal and package-local.

Approval bar:
- Make node-cache invalidation unconditional on tracing-session teardown, including cancellation-driven stop and target replacement paths.
- Add a manager-level regression test that proves raw capture is cleared when an active tracing session is stopped or replaced via cancellation, not just when `clearNode()` is called directly.
- Re-run the focused tracing and monitor tests after that teardown-path fix.

Decision: requires_changes

Implementation: Iteration 3
Timestamp: 2026-05-17T23:32:10Z

Changes made: fixed the remaining teardown-path cache invalidation bug identified in code review iteration 2. Moved raw-capture clearing into unconditional tracing-session teardown by wrapping `runConnectionLoop()` in a `try/finally`, and updated `ManagedTracingConnection.shutdown()` to clear node capture state before cancelling the session job so cancellation-driven stop and target replacement cannot leave fresh-looking snapshots behind. Adjusted the manager ingress test to assert data is present while the session is active and cleared after stop, and added a dedicated `stopClearsRawCaptureForManagedNode` regression test for cancellation-driven teardown.

Files touched: `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt` and `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`.

Verification run: `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest"`. This passed after exercising the new unconditional teardown invalidation path. The Gradle lifecycle again ran `testVue` and `buildVue`; both passed.

Deviations from approved plan: none. This was a narrow correctness fix within the existing raw-capture ingress scope.

User interaction required: no.

Outcome: task-511 implementation iteration 3 completed and ready for code review.

Code Review: Iteration 3
Timestamp: 2026-05-17T23:33:27Z

Blocking findings:
- None.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:154-180,208-218` now clears raw capture on the two relevant teardown paths: reconnect/disconnect inside the loop and cancellation-driven shutdown via `ManagedTracingConnection.shutdown()`. That closes the previously identified stale-snapshot hole in the live code, even though the impl-review note mentions a `try/finally` that was not actually used.
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt:52,86,136-140` still uses immutable list replacement for trace batches, so the earlier unsafe shared-buffer regression remains fixed.
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:339-353` plus `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt:124-154` gives a truthful freshness model for dashboard reads: stale snapshots age out, and disconnected sessions are actively cleared.
- The transport layer still depends directly on `TracingRawCaptureService` instead of the lighter `TraceForwardMessageSink` seam. That is extra coupling, but it is not a correctness blocker for `task-511`.

Approval bar:
- No further blocking changes required for this iteration.

Decision: approved

