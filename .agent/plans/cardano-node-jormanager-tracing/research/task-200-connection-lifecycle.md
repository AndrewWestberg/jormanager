# Task 200 Connection Lifecycle

## Status

- Implemented and review-approved on 2026-05-12.

## Durable Findings

- The first production tracing layer can stay small and still satisfy the PRD if it is split into 3 pieces only:
  - `TracingConnectionManager` for per-core-node lifecycle ownership
  - a raw `TraceForwardSessionClient` for the minimum TCP request/reply/done contract
  - a single sink seam for later decoder or node-state consumers
- Duplicate `nodesChannel` refresh events are normal in the current app shape, so tracing lifecycle management must treat same-target core updates idempotently instead of canceling and reconnecting on every repeat event.
- For the blocking trace-forward session shape pinned by the current fixture foundation, socket read timeouts on an otherwise-open connection should be treated as idle periods, not transport failure. Quiet but healthy nodes may legitimately produce no trace objects for longer than a read timeout window.
- `SmartLifecycle.stop()` and `SmartLifecycle.stop(callback)` both need to route through the same shutdown path; leaving one path as a no-op creates real connection leaks in this subsystem.
- The current internal trace-objects request count remains pinned to `25`, matching the independent task-203 request-byte anchor `8301f582001819` used by the focused lifecycle tests.
- The callback-based stop path still uses `GlobalScope` today. That is acceptable for the current minimal lifecycle task, but later tracing work should avoid broadening that pattern further.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
- Current repo Gradle wiring also runs Vue unit tests and frontend production build during that command; both passed on the final verification run.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
