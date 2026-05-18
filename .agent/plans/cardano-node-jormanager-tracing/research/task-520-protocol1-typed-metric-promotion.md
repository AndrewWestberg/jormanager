# Task 520 Protocol 1 Typed Metric Promotion

## Status

- Completed on 2026-05-18.

## Durable Findings

- Production protocol `1` typed extraction now lives in `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt` and covers the live-proven chain, forge, KES, and mempool metric families.
- Production decoding consumes `TracingRawMetricValue` directly from raw capture; it does not round-trip through the JSON-string bridge used in `LiveTraceForwardIntegrationTest` helpers.
- `TracingRawCaptureService` now exposes `latestFreshMetricSnapshot(...)`, which keeps protocol `1` dashboard reads inside the same freshness boundary already enforced for datapoints and trace-object batches.

## Promotion Boundary

- `NodeMonitor` now promotes protocol `1` only when a fresh metric snapshot can satisfy the full task-520 overlapping dashboard subset:
  - `blockHeight`
  - `remainingKESPeriods`
  - `epoch`
  - `slot`
  - `slotInEpoch`
  - `txsProcessed`
- Task-520 intentionally does not introduce a nullable per-field merge model for dashboard assembly.
- If the protocol `1` subset is stale or incomplete, `NodeMonitor` falls back to the existing datapoint or trace-object path for the whole snapshot.

## Mixed-Source Rule

- Peer counters remain outside the protocol `1` promotion scope.
- When protocol `1` metrics are promoted and protocol `2` connection counters are absent, `NodeMonitor` must preserve peer counters from fresh datapoints instead of replacing them with synthetic zeroes.
- When protocol `2` connection counters are present, they continue to override datapoint peer values.

## Verification Evidence

- Focused verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingMetricDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
