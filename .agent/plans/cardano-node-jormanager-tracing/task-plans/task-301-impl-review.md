Implementation: Iteration 1
Timestamp: 2026-05-14T22:08:29Z

Changes made:
- Refactored `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` off Retrofit, `EkgService`, SSH port-forwarding, and `node.ekgPort`, replacing the per-node HTTP fetch with direct short-lived `DataPoint` sessions against `host.hostname:node.tracingPort` decoded through the existing `NodeStateDataPointDecoder`.
- Kept lifecycle ownership inside `NodeMonitor` by reusing its per-node job map, adding explicit startup self-seeding from `nodeRepository.findAll()`, and enforcing the approved core-only eligibility rule so relay and pool nodes no longer start node-state monitoring jobs or emit replacement `nodestats` events.
- Preserved the current `NodeStats` contract, null-on-failure fallback behavior, default-node cache updates only on successful eligible-core results, and grouped `/topic/messages` `SocketResponse.Success(type = "nodestats", ...)` publication.
- Added a small `DataPointSessionClientFactory` seam in `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt` so `NodeMonitor` tests can inject deterministic session behavior without widening the production architecture.
- Added focused backend coverage in `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt` for startup self-seeding, successful direct node-state mapping, null fallback on failure, non-core silence, default-relay cache non-refresh, and unchanged websocket payload shape.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"` (passed; repo task graph also ran Vue unit tests and frontend production build successfully)

Deviations from approved plan:
- None. The implementation kept lifecycle ownership in `NodeMonitor`, used repeated short-lived single-request `DataPoint` sessions, and did not introduce a separate node-state manager.

User interaction required:
- No

Outcome: implementation pass completed and ready for code review

Code Review: Iteration 1
Timestamp: 2026-05-14T22:10:52Z

Blocking findings:
1. `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt:58-74,98-123` implements the `DataPoint` session with the wrong `MsgDone` direction. The pinned task-204 contract requires `MsgDone` to be sent by JorManager to the node (`array(1)[2]`), but the new client instead waits for `Done` from the server and never writes one itself. Against a conforming server that waits for the client’s `MsgDone` or disconnect before completing the mini-protocol, this session can hang until timeout/reconnect and `NodeMonitor` will regress from “successful poll every 5s” to repeated fallback/null stats. The current tests do not catch this because `NodeMonitorTest` stubs an inbound `Done` (`src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt:259-262`) and `NodeStateDataPointDecoderTest` only verifies request plus server replies, not client-side `MsgDone` emission (`src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt:100-124`).

Non-blocking observations:
1. `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:129-130` still has a no-op `stop()` while `stop(callback)` performs the real shutdown. Spring usually prefers the callback path for `SmartLifecycle`, so this is not a task-301 blocker, but it is lifecycle drift compared with `TracingConnectionManager`, which implements both stop paths consistently.
2. The implementation otherwise matches the intended scope well: `NodeMonitor` no longer depends on HTTP polling or SSH port forwarding, startup self-seeding is present, websocket payload shape stays `SocketResponse.Success(type = "nodestats", ...)`, and the default-node cache remains core-only success-driven.

Approval bar:
1. Fix `SocketDataPointSessionClient` so the client sends `MsgDone` in the correct direction and does not model server-sent `Done` as the normal completion path for this mini-protocol.
2. Add a socket-level regression test that fails without that fix: the fake server should require client `MsgDone` or explicit client disconnect after the reply, so the production client contract is verified rather than the current permissive stub behavior.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-14T22:12:16Z

Changes made:
- Fixed `SocketDataPointSessionClient` to follow the pinned task-204 mini-protocol correctly: after the first `DataPointsReply`, the client now sends client-side `MsgDone` and returns instead of waiting for a server-sent `Done`.
- Tightened `NodeStateDataPointDecoderTest` with a socket-level regression test that asserts the exact request -> reply -> client `MsgDone` sequence against a manual server, so the contract fails if the client ever regresses back to the wrong direction.
- Updated the lightweight `NodeMonitorTest` helper to stop simulating an inbound `Done`, keeping those tests aligned with the corrected production protocol.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"` (passed; repo task graph also ran Vue unit tests and frontend production build successfully)

Deviations from approved plan:
- None. This iteration only corrected the pinned DataPoint protocol behavior and added the missing regression coverage requested by code review.

User interaction required:
- No

Outcome: review blockers resolved and ready for follow-up code review

Code Review: Iteration 2
Timestamp: 2026-05-14T22:13:48Z

Blocking findings:
1. None. The iteration-1 blocker is resolved in the live code: `SocketDataPointSessionClient` now sends client-side `MsgDone` immediately after the first `DataPointsReply` and treats inbound `Done` as unexpected (`src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt:63-72`), and the socket-level regression test now verifies the exact request -> reply -> client `MsgDone` sequence against a manual server (`src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt:103-158`).

Non-blocking observations:
1. No newly introduced issues were identified in the reviewed fix set.
2. Focused verification passed with `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`.

Approval bar:
1. No further changes required for task-301 follow-up review.

Decision: approved

