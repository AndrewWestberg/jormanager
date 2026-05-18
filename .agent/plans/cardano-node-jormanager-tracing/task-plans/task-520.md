# Task 520 Plan

## Summary

- Task ID: `task-520`
- Title: `Promote protocol 1 typed extraction for chain, forge, KES, and mempool metrics into production`
- Why now: `task-511` already landed shared protocol `1` raw metric capture and `NodeMonitor` already consumes shared tracing raw state, but production still reconstructs dashboard values by preferring protocol `3` datapoints and protocol `2` trace fallback while the tested protocol `1` typed extraction remains stranded in `LiveTraceForwardIntegrationTest`
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Promote the live-test-proven protocol `1` typed metric extraction for chain, forge, KES, and mempool data into production code, then apply only the smallest `NodeMonitor` wiring change needed so the current dashboard contract prefers protocol `1` for the fields it already carries, while keeping protocol `2` peer counters and protocol `3` startup or structured datapoint work in their later tasks.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md` as the canonical task plan
- lift the typed protocol `1` decoding logic now living in `LiveTraceForwardIntegrationTest.kt` into production code under `src/main/kotlin/com/swiftmako/jormanager/tracing/`
- keep the production typed model limited to the metric families already proven in task-500 research and live tests: chain, forge, KES, and mempool
- reuse the existing `TracingRawCaptureService` seam by adding a freshness-gated metric accessor rather than adding a new transport or cache owner
- update `NodeMonitor` so the fields already present in `NodeStats` prefer protocol `1` typed metrics only when a fresh metric snapshot supplies the full overlapping dashboard subset for this task
- preserve protocol `2` trace-object use for peer and connection counters, because task-500 research explicitly says those counters are still not available from protocol `1`
- preserve protocol `3` as the source for startup metadata and any later structured datapoint extraction owned by `task-522`
- add focused automated coverage for production typed protocol `1` extraction and for the new `NodeMonitor` precedence order

Out of scope:
- widening `NodeStats` with new dashboard fields such as density, tip hash, or forging counters
- moving peer counters from protocol `2` to protocol `1`
- replacing `NodeStartupInfo` or other protocol `3` structured datapoints
- broad `NodeMonitor` consumer migration beyond the minimal precedence change for existing `NodeStats` fields
- block-event decoding, block persistence, config generation, rollout docs, or live operator validation

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
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Rebuilt the deep index before planning and used code-index first to locate the tracing package, `NodeMonitor`, `NodeStats`, and the test-only protocol `1` typed helpers
- Re-verified all material findings against live files because tracker state is slightly stale relative to the codebase
- Code-index materially helped confirm the current task boundary is narrow: production already has protocol `1` raw capture, while the missing work is typed extraction plus a `NodeMonitor` precedence change rather than new transport work

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this is a repo-local backend refactor plus focused tests
  - the metric inventory and decoder shapes are already proven by repo-local live tests and raw-capture production seams
  - no remote-host or live-network validation is required to implement and verify the production typed layer truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-511`
- Important downstream alignment:
  - `task-521`
  - `task-522`
  - `task-530`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/forwarding/ForwardingMetricsProtocol.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already stores the latest protocol `1` metric snapshot per node as typed raw values, so task-520 should build on that seam instead of adding another cache or service
  - unlike datapoints and trace objects, metrics did not yet have a freshness-gated read helper at planning time, so task-520 must add that helper before `NodeMonitor` can prefer protocol `1`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/forwarding/ForwardingMetricsProtocol.kt`
  - already decodes protocol `1` replies into `TracingRawMetricValue.Counter`, `IntGauge`, and `Label`, so the missing production work is family-specific typed extraction on top of those raw values
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - already contains the concrete metric-name inventory and typed helper logic for `ChainMetrics`, `ForgeMetrics`, `KesMetrics`, and `MempoolMetrics`
  - those helpers are currently test-only and use a stringified JSON bridge that production code no longer needs because raw capture is already type-preserving
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already reads shared raw tracing state and no longer owns tracing transport
  - `loadNodeStateMetrics(...)` still prefers protocol `3` datapoints, then falls back to merged protocol `2` trace objects, with no production protocol `1` typed decode path yet
  - the current `NodeStats` fields that protocol `1` can improve immediately are `blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - already owns the current `NodeStateMetrics` container and `toNodeStats(...)` mapper, so the smallest implementation is likely to reuse `NodeStateMetrics` as the consumer-facing aggregate rather than introducing a second dashboard DTO for this task alone
- `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - does not currently expose density, tip hash, or forge counters, so task-520 should not widen the dashboard contract just because protocol `1` has those values available
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - currently covers datapoint-first decoding and protocol `2` connection-counter fallback, but does not yet pin the new protocol `1` precedence for shared fields
- Tracker versus live-repo truth:
  - the task graph says typed protocol `1` extraction is still pending, and the live repo agrees on the consumer side even though some task-511 completion notes overstate protocol `1` promotion
  - the truthful gap is not raw protocol `1` capture; it is the missing production typed extraction layer and the missing `NodeMonitor` consumption path that uses it

## Consumption Boundary

- Task-520 should add one production typed protocol `1` extraction seam downstream of `TracingRawCaptureService`, not a new transport or monitor-owned cache.
- `NodeMonitor` should prefer that protocol `1` typed seam only for the existing `NodeStats` fields protocol `1` can supply truthfully.
- `NodeMonitor` should continue to source `peers` and `incomingPeers` from protocol `2` trace objects until `task-521` formalizes that extraction layer.
- `NodeMonitor` may continue to use protocol `3` datapoints only where protocol `1` does not cover the current `NodeStats` contract or where freshness fallback is still needed, but task-520 should reverse the current precedence for overlapping fields only when protocol `1` can provide the full overlapping subset for `blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed`.
- Task-520 does not introduce a nullable per-field merge model. If the protocol `1` overlapping subset is incomplete or stale, `NodeMonitor` stays on the existing datapoint or trace-object fallback path for the whole snapshot.
- Startup metadata such as era or slot-length stays with protocol `3` and later tasks; task-520 should not couple its typed metric extractor to startup-info concerns.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - add the smallest production typed protocol `1` extraction file or files for chain, forge, KES, and mempool metrics
  - add a freshness-gated metric snapshot accessor in `TracingRawCaptureService`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - change node-state assembly so fresh protocol `1` typed metrics are preferred for overlapping `NodeStats` fields while protocol `2` still supplies peer counters
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - add focused production tests for typed protocol `1` extraction using fixture-level raw metric snapshots or representative raw maps
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - add or update tests to pin protocol `1` precedence, safe missing-metric handling, and continued protocol `2` peer-counter fallback
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - only if extracting shared fixtures or constants out of the live test materially reduces duplication without widening scope

## Implementation Approach

- Keep the extraction layer minimal and downstream of raw capture:
  - prefer one small production decoder module in `tracing/` that converts `Map<String, TracingRawMetricValue>` into typed protocol `1` families
  - reuse the exact live-proven metric names from task-500 research and `LiveTraceForwardIntegrationTest`
  - use the existing raw value types directly instead of stringifying back to JSON as the live test helpers currently do
- Reuse existing consumer shapes where practical:
  - do not invent a new dashboard aggregate if `NodeStateMetrics` can remain the bridge into `NodeStats`
  - add one small mapper from typed protocol `1` metrics into `NodeStateMetrics` for the complete overlapping dashboard subset this task owns
  - merge only protocol `2` peer counters into that otherwise-complete `NodeStateMetrics` result
  - keep protocol `3` handling intact for the fallback path rather than partial field-level merge behavior
- Prefer the smallest truthful precedence change in `NodeMonitor`:
  - first check for a fresh protocol `1` metric snapshot and decode typed metrics from it
  - only promote protocol `1` when the decoded result can satisfy the full overlapping dashboard subset for this task
  - merge in protocol `2` peer counters if available
  - if protocol `1` is stale or incomplete, fall back to the existing datapoint path and then to the existing protocol `2` trace-object path
  - do not remove protocol `2` or protocol `3` decoders in this task unless they become fully unreachable through the smaller change
- Keep task boundaries explicit:
  - protocol `1` forge counters may be decoded and tested in production now, but they do not need to be surfaced through `NodeStats` yet
  - do not use task-520 to widen websocket payloads or to fold startup metadata into the protocol `1` extractor
  - do not broaden stale-tracker cleanup beyond noting that task-520 closes the real production-consumption gap that still exists despite earlier raw-capture progress notes

## Acceptance Criteria

- Production code can decode fresh protocol `1` raw metric snapshots into typed chain, forge, KES, and mempool metric families using the live-proven metric-name set.
- The production typed extraction layer is downstream of `TracingRawCaptureService` and does not own transport, reconnect, or cache lifecycle.
- Missing or partially absent protocol `1` metrics fail safely without breaking tracing runtime consumption.
- `NodeMonitor` only switches to protocol `1` for the overlapping dashboard fields when the typed result is both fresh and complete for that subset.
- `NodeMonitor` prefers protocol `1` typed metrics for overlapping `NodeStats` fields when a fresh metric snapshot exists.
- `NodeMonitor` still sources `peers` and `incomingPeers` from protocol `2` trace-object state where available.
- `NodeMonitor` still has a safe fallback when protocol `1` snapshots are absent or stale.
- `NodeStats` contract remains unchanged in this task.
- The implementation reuses existing seams and does not introduce a new per-feature tracing transport or a second monitor-owned raw cache.

## Verification Plan

- Static verification:
  - confirm production tracing code contains a typed protocol `1` extraction layer rather than leaving chain or KES or mempool helpers stranded in `LiveTraceForwardIntegrationTest.kt`
  - confirm the production extractor consumes `TracingRawMetricValue` directly instead of round-tripping through the live test's JSON-string helper shape
- confirm `NodeMonitor.loadNodeStateMetrics(...)` now checks fresh protocol `1` metric snapshots before protocol `3` datapoints for overlapping fields
- confirm `TracingRawCaptureService` exposes a freshness-gated metric accessor parallel to the existing datapoint and trace freshness helpers
  - confirm `NodeStats.kt` is unchanged unless a truly unavoidable contract fix appears during implementation
  - confirm no new transport owner, polling loop, or cache abstraction was introduced
- Automated verification:
- add focused tracing tests in `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoderTest.kt` that decode representative raw metric snapshots into typed `ChainMetrics`, `ForgeMetrics`, `KesMetrics`, and `MempoolMetrics`
- add focused tracing tests in `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoderTest.kt` that prove missing metric names or wrong raw value tags fail safely
- update `NodeMonitorTest` so one case proves protocol `1` metrics win over protocol `3` datapoint values for block height, slot, epoch, remaining KES periods, and tx count when both are present
- update `NodeMonitorTest` so additional cases prove stale or incomplete protocol `1` snapshots fall back to the existing datapoint path
- update `NodeMonitorTest` so another case proves protocol `2` counters still supply `peers` and `incomingPeers` alongside protocol `1` typed metrics
- run the narrowest relevant backend targets, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Truthful validation boundary:
  - fixture-backed and unit-level verification is sufficient for this task
  - optional live tracing tests remain useful regression evidence but are not required to claim task-520 complete

## Risks And Open Questions

- Main risk is scope creep into `task-521`, `task-522`, or `task-530` by turning protocol `1` extraction into a full unified dashboard rewrite.
- Another risk is over-rotating away from protocol `2` and accidentally breaking peer counters even though task-500 research says protocol `1` still does not expose them.
- Another risk is widening `NodeStats` just because protocol `1` exposes extra values not currently consumed by the dashboard contract.
- Another risk is copying the live-test JSON bridge into production instead of decoding from `TracingRawMetricValue` directly.
- Open implementation choice to resolve minimally during coding:
- whether any later task wants a broader nullable cross-family dashboard aggregate after `task-530`; task-520 stays with the smaller complete-subset gate

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`.
- Review logs remain append-only and are captured at:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520-impl-review.md`
- The completed task must update the tasks graph and research brain with the final protocol `1` promotion boundary.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on production protocol `1` typed extraction plus the smallest `NodeMonitor` precedence change and does not widen into protocol `2` or protocol `3` task ownership.
- Stale-tracker check: the plan explicitly distinguishes already-landed protocol `1` raw capture from the still-missing production typed-consumption path, which is the real task-520 gap.
- Workflow check: only backend and test workflows are needed because this task is backend-only and does not require doc-system or database changes.
- Test and docs check: the plan includes focused tracing and `NodeMonitor` test updates and does not require broader documentation churn.
- Consistency check: the plan reuses existing seams, keeps `NodeStats` stable, and aligns with task-500 findings that peer counters remain protocol `2` work.

## Final Outcome

- Outcome: completed
- Implementation summary:
  - added production protocol `1` typed decoding in `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt` for chain, forge, KES, and mempool metric families
  - added `TracingRawCaptureService.latestFreshMetricSnapshot(...)` so protocol `1` dashboard reads follow the same freshness rule already used for datapoints and trace batches
  - updated `NodeMonitor` to prefer protocol `1` only when the full overlapping dashboard subset is present and fresh, while preserving peer counters from protocol `2` trace objects or existing datapoints
  - added focused regression coverage in `TracingMetricDecoderTest` and `NodeMonitorTest`
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Final review result:
  - implementation review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520-impl-review.md`
- Research outcome:
  - added `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
