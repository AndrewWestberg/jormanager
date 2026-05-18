Implementation: Iteration 1
Timestamp: 2026-05-18T00:00:14Z

Changes made:
- added production protocol `1` typed decoding in `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt` for chain, forge, KES, and mempool metric families using the existing `TracingRawMetricValue` transport types
- added `TracingRawCaptureService.latestFreshMetricSnapshot(...)` so protocol `1` reads follow the same freshness boundary already used for datapoints and trace batches
- updated `NodeMonitor` to prefer fresh protocol `1` metrics only when the complete overlapping dashboard subset is present, while still sourcing peer counters from protocol `2` trace objects and falling back to the existing datapoint or trace paths when protocol `1` is stale or incomplete
- updated the canonical task plan to match the approved plan boundary: fresh-only metric accessor, complete-subset gate, and focused verification targets
- added focused tests in `TracingMetricDecoderTest` and `NodeMonitorTest` for typed protocol `1` decoding, wrong-tag safety, protocol `1` precedence, stale or incomplete metric fallback, and continued protocol `2` peer-counter merge behavior

Files touched:
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue` ✅

Deviations from approved plan:
- none; the implementation follows the approved fresh-only accessor and complete-subset gate

User interaction required:
- no

Outcome: implementation iteration 1 complete and ready for code review.

Code Review: Iteration 1
Timestamp: 2026-05-18T00:03:06Z

Reviewed the required docs and plans, the live `task-520` diff, and the focused implementation/tests. `TracingMetricDecoder` matches the task-500 live metric inventory, and `TracingRawCaptureService.latestFreshMetricSnapshot(...)` keeps protocol `1` reads inside the existing freshness boundary. The remaining issue is in the `NodeMonitor` precedence wiring.

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:343-350` regresses peer counters whenever protocol `1` metrics are fresh and complete but protocol `2` connection-counter trace objects are absent. The new protocol-1-first branch creates `NodeStateMetrics` with `peers = 0` and `incomingPeers = 0`, then only overwrites those from `loadForwardedNodeState(...)`; it never falls back to the still-available protocol `3` datapoint counters. Before this change, the datapoint path preserved the dashboard current peer values, and the pinned fixture still carries those datapoints. In production this will turn truthful peer counts into `0` for any node where protocol `1` is fresh before protocol `2` has delivered counters, which is a user-visible dashboard regression and outside the task overlapping-fields boundary.

Non-blocking observations:
- `NodeMonitorTest` proves protocol `1` wins for block or slot or KES or tx fields, but it does not assert peer values in that mixed-source case, so the regression above is currently untested.
- `NodeMonitor.loadNodeStateMetrics(...)` still accepts `host: Host`, but that parameter is now unused.

Approval bar:
- Preserve existing peer-counter behavior in the protocol-1-first path: either retain datapoint-derived `peers` and `incomingPeers` when protocol `2` counters are absent, or otherwise gate protocol `1` promotion so it cannot replace non-overlapping dashboard fields with synthetic zeroes.
- Add a focused `NodeMonitorTest` covering fresh protocol `1` metrics plus datapoints but no protocol `2` connection-counter trace object, and assert peer counters remain truthful.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-18T00:03:36Z

Changes made:
- fixed the protocol-1-first `NodeMonitor` branch so non-overlapping peer counters remain truthful: when fresh protocol `1` metrics are complete but protocol `2` connection counters are absent, the monitor now preserves `peers` and `incomingPeers` from the existing fresh datapoint snapshot instead of forcing them to `0`

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue` ✅

Deviations from approved plan:
- none; this fix restores the approved overlapping-fields-only precedence boundary

User interaction required:
- no

Outcome: implementation iteration 2 resolves the peer-counter regression and is ready for follow-up code review.

Code Review: Iteration 2
Timestamp: 2026-05-18T00:05:22Z

Reviewed the required docs and plans, re-checked the live `task-520` diff with code-index plus direct file reads, and reran the focused verification command: `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue` ✅. The iteration 2 change in `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` now preserves `peers` and `incomingPeers` from fresh datapoints when protocol `1` metrics are promoted but protocol `2` counters are absent, which closes the prior dashboard regression. The focused tests in `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt` now cover both mixed-source cases: datapoint peer preservation without protocol `2`, and protocol `2` override when connection-counter trace objects are present. I did not find any remaining correctness or regression issues in the iteration 2 follow-up scope.

Blocking findings:
- none

Non-blocking observations:
- none

Approval bar:
- none

Decision: approved

