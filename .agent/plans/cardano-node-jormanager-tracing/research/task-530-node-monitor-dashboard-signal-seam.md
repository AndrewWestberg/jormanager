# Task 530 NodeMonitor Dashboard Signal Seam

## Status

- Verified during task-530 implementation on 2026-05-18.

## Durable Findings

- The accepted production seam for dashboard node-state assembly is now `TracingDashboardSignalService`, which stays downstream of `TracingRawCaptureService` and owns only signal assembly plus precedence, not transport, reconnects, or snapshot retention.
- `NodeMonitor` should consume `TracingDashboardSignalService` rather than directly depending on `TracingRawCaptureService`, `TracingMetricDecoder`, `TraceForwardProtocol2Extractor`, or `TraceForwardProtocol3Extractor`.
- Fresh protocol `2` connection counters are authoritative for dashboard peer values whenever they are available, even if protocol `1` metrics are absent and protocol `3` datapoints are supplying the rest of the node-state fallback. This precedence was already intended by the plan and tests, but task-530 verification exposed that the live shared-seam refactor needed an explicit implementation fix for the protocol `3` fallback path.
- Protocol `2` `AddedToCurrentChain` remains only a chain fallback when fresher complete protocol `1` or protocol `3` snapshots cannot provide `slot` and `blockHeight`.
- Protocol `3` startup metadata remains available independently of dashboard metrics through `TracingDashboardSignals.startupInfo`; task-530 deliberately does not widen `NodeStats` to expose it.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" -x buildVue -x testVue`
- Result: passed.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalServiceTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
