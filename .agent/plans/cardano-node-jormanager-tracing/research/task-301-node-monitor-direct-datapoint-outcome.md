# Task 301 NodeMonitor Direct DataPoint Outcome

## Status

- Task: `task-301`
- Date: `2026-05-14`
- Outcome: `implemented`

## Durable Findings

- `NodeMonitor` can own the first node-state migration end to end without introducing a second standalone connection manager. Reusing its existing per-node job map plus a small `DataPointSessionClientFactory` seam was sufficient.
- Startup self-seeding is required for node-state monitoring because `nodesChannel` remains an unreplayed `MutableSharedFlow`, and relying on `BlockMonitor` startup ordering would leave existing nodes unmonitored.
- The first node-state migration remains core-only in production. Relay and pool nodes now produce no replacement `nodestats` events, and there is no HTTP fallback path.
- The default-node cache tradeoff is real and intentional in this phase: if the configured default node is not an eligible core node, `latestNodeStats` can remain stale or `null` until an eligible core default exists.
- The pinned task-204 `DataPoint` mini-protocol requires JorManager to send client-side `MsgDone` after receiving the first `DataPointsReply`. Waiting for a server-sent `Done` is incorrect and causes fallback/null regressions against a conforming server.

## Verification Evidence

- Focused backend verification passed with:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
- The socket-level regression in `NodeStateDataPointDecoderTest` now asserts the exact request -> reply -> client `MsgDone` sequence for the `DataPoint` client.

## Follow-On Relevance

- `task-302` can now retire `EkgService` and any remaining HTTP-only metrics plumbing without needing additional `NodeMonitor` transport changes.
- Future relay/pool parity work should be treated as a separate design decision rather than widening the current core-only direct node-state path.
