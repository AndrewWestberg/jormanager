# Task 511 Raw Capture Freshness And Invalidation

## Status

- Recorded during `task-511` implementation on 2026-05-17.

## Durable Findings

- Shared raw capture for tracing data must not be treated as timeless truth for dashboard consumers.
- `NodeMonitor` can consume raw datapoint snapshots and raw trace-object batches from the shared capture layer, but only if those records are freshness-gated.
- Cancellation-driven tracing teardown must clear per-node raw capture immediately; waiting only for age-out leaves a window where disconnected nodes still appear healthy.

## Accepted Runtime Boundary

- Raw capture remains in-memory only.
- Protocol `1` metric snapshots and protocol `3` datapoint snapshots are kept as latest-per-node values.
- Protocol `2` trace-object capture uses bounded immutable per-node batch replacement instead of a mutable shared queue.
- Dashboard readers must use freshness-checked accessors rather than unconditional latest-value access.
- Tracing teardown paths must clear per-node capture state both on reconnect churn and on cancellation-driven shutdown.

## Implementation Evidence

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

## Verification

- Verified by focused tests covering:
  - unified ingress raw capture
  - stale raw snapshot fallback to null dashboard stats
  - cancellation-driven raw capture invalidation on tracing stop
