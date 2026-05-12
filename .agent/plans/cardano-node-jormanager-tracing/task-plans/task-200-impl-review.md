Implementation: Iteration 1
Timestamp: 2026-05-12T23:38:10Z
Changes made:
- Added `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt` as a dedicated `SmartLifecycle` tracing manager that seeds eligible core nodes from `NodeRepository`, listens to `nodesChannel`, manages one per-node job, keeps duplicate refreshes idempotent, reconnects on session loss, and tears down jobs on node ineligibility or shutdown.
- Added `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt` with the smallest raw trace-forward session client needed for this task: direct outbound TCP connect to `host.hostname:tracingPort`, send the pinned trace-objects request, read `MsgTraceObjectsReply` or `MsgDone`, and expose an internal request-count constant without widening into decode logic.
- Added `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt` with a minimal raw-message model and single sink seam for later decoder or node-state tasks.
- Extended `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt` with accepted-session waiting and optional client-disconnect waiting so lifecycle teardown and reconnect tests are deterministic.
- Added `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt` covering seeded eligible-core startup, reconnect after disconnect, deletion-driven teardown, shutdown preventing reconnect churn, and ineligible nodes never opening a tracing session.
Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
- Note: due to current Gradle wiring, this test target also ran the existing Vue unit tests and Vue production build; both passed during the run.
Deviations from approved plan:
- Did not need `Configuration.kt` changes because constructor-default collaborators kept the new tracing manager self-contained while still Spring-instantiable.
- The raw-session seam stayed at one message sink interface and one small message model; no decoder, block persistence, or node-state mapping was added.
User interaction required now: no
Outcome: task-200 implementation now provides a dedicated production tracing lifecycle layer with explicit connect, reconnect, ineligibility teardown, and shutdown behavior, plus focused fixture-backed tests.

Implementation: Iteration 2
Timestamp: 2026-05-12T23:42:51Z
Changes made:
- Updated `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt` so read-side socket timeouts are treated as healthy idle periods for the blocking trace-forward session instead of transport failure, which prevents reconnect churn on quiet but healthy nodes.
- Updated `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt` so direct `stop()` now executes the same shutdown semantics as `stop(callback)`, rather than leaving tracing jobs running.
- Expanded `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt` with focused coverage for idle sessions not reconnecting while the session stays open and for direct `stop()` shutting down active connections cleanly.
Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
- Note: due to current Gradle wiring, this test target also ran the existing Vue unit tests and Vue production build; both passed again during the run.
Deviations from approved plan:
- None. The changes stay within lifecycle and transport scope and do not widen into decoder or node-state work.
User interaction required now: no
Outcome: the review-identified lifecycle regressions are fixed, and the tracing manager now keeps idle blocking sessions alive and honors both Spring stop paths truthfully.

Code Review: Iteration 2
Timestamp: 2026-05-12T23:44:12Z
- Reviewed the required docs, the canonical `task-200` plan, both review logs, the Hermod reference note, and the live tracing implementation and tests.
- Re-checked the two iteration-1 blockers against the code and the focused `TracingConnectionManagerTest` run.

Blocking findings:
- None. The prior blockers are resolved:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt:60-73` now treats `SocketTimeoutException` as an idle blocking-session heartbeat gap instead of a transport failure, so quiet but healthy sessions no longer force reconnect churn.
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:96-118` now routes direct `stop()` through the same shutdown path as `stop(callback)`, so active tracing jobs are actually torn down on the plain Spring stop path.
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt:147-213` adds direct coverage for both fixes, and the focused Gradle test target passed.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt:87-94` still uses `GlobalScope` for the callback-based stop path. It is functioning correctly here, but it leaves shutdown orchestration slightly less self-contained than the rest of the manager.
- The implementation still matches approved `task-200` scope: the production surface remains limited to lifecycle/session ownership plus a small sink seam, with no adopted-block decode, block persistence, `BlockMonitor` refactor, or node-state mapping pulled forward.

Approval bar:
- Keep the tracing layer scoped to connection lifecycle until `task-201` and `task-204`.
- Preserve the new idle-session behavior and both-stop-path shutdown coverage as later tracing work lands.
- Avoid broadening `TraceForwardSessionClient` into decode or monitor responsibilities.

Decision: approved

