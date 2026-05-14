# Task 302 HTTP Metrics Cleanup Outcome

## Status

- Task: `task-302`
- Date: `2026-05-14`
- Outcome: `implemented`

## Durable Findings

- After `task-301`, the remaining HTTP-only node-metrics surface was limited to the dead `EkgService` Retrofit interface and the unused `model/ekg/` plus `model/ekg2/` DTO packages.
- Deleting those files is safe once dead-reference searches stay empty, because `NodeMonitor` now loads core-node stats exclusively through the direct `DataPoint` protocol path.
- Shared Retrofit wiring must remain in place after this cleanup because it still serves non-EKG consumers, including `PooltoolService` and `NodeController.uploadMetadata(...)` via `MetadataService`.
- Persisted `Node.ekgPort` and `Node.promPort` remain intentionally out of scope for this task because `NodeController.allocateMetricsPorts(...)` and `allocateTracingPort(...)` still depend on them during node creation.

## Verification Evidence

- Static Kotlin search for `EkgService`, `EkgMetrics`, `EkgMetrics2`, `com.swiftmako.jormanager.model.ekg`, and `com.swiftmako.jormanager.model.ekg2` returned no remaining matches after deletion.
- Focused backend verification passed with:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
- The repo's current Gradle wiring also ran Vue unit tests and frontend production build during that verification target; both passed.

## Follow-On Relevance

- `task-103` is now the next rollout-alignment task on the remaining critical path.
- A later schema/controller cleanup can retire persisted `ekgPort` and `promPort` once tracing-port sequencing stops deriving from those values.
