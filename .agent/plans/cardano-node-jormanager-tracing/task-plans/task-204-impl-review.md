Implementation: Iteration 1
Timestamp: 2026-05-13T00:35:52Z

Changes made:
- Added a sibling DataPoint protocol surface in the tracing package: TraceForwardMessage now models DataPointsReply, SocketDataPointSessionClient pins the exact request or reply or done message ids and envelopes, and NodeStateDataPointDecoder codifies the exact 8-key node-state mapping into a reusable NodeStateMetrics to NodeStats adapter.
- Extended tracing fixtures with committed DataPoint request and reply bytes plus negative reply variants for missing keys, Nothing values, malformed scalar JSON, and overflow.
- Added focused tests covering fixture determinism, session round-trip behavior, exact node-state decoding, failure handling, and parity with the current NodeStats contract without refactoring NodeMonitor yet.

Files touched:
- src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt
- src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt
- src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt
- src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt

Verification run:
- ./gradlew test --tests com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest --tests com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest
- Result: passed; the Gradle test task also ran the repo's existing Vue build and unit-test tasks as part of the normal build graph.

Deviations from approved plan:
- None on scope. The implementation kept NodeMonitor untouched and limited production code to the pinned sibling DataPoint protocol and decoder surfaces.

User interaction required now: no
Outcome: Implementation ready for code review

Implementation: Iteration 2
Timestamp: 2026-05-14T21:07:59Z

Changes made:
- Fixed the DataPoint decoder to join all CBOR byte-string chunks before JSON scalar parsing so indefinite-length Haskell-serialised payloads decode correctly instead of truncating to the first segment.
- Reworked the pinned node-state fixtures to expose independent literal request and reply byte anchors, plus literal negative replies and a chunked slot value reply, so protocol drift cannot self-validate through the same builder on both sides.
- Updated focused tests to assert exact literal hex for the pinned 8-key request and full reply, exercise chunked-byte-string decoding, and use the independent literal fixtures for the session round-trip path.

Files touched:
- src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt

Verification run:
- ./gradlew test --tests com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest --tests com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest
- Result: passed after the review-driven fixes; the Gradle test task again ran the repo's existing Vue build and unit-test tasks as part of the normal build graph.

Deviations from approved plan:
- None on scope. The follow-up stayed within the approved DataPoint contract pinning work and still left NodeMonitor unchanged.

User interaction required now: no
Outcome: Review blockers addressed and implementation ready for code review

Code Review: Iteration 2
Timestamp: 2026-05-14T21:10:06Z

Iteration 2 re-review verified the required task docs, prior review context, the live tracing files, and the focused backend test run. The previous blockers are resolved.
The indefinite-length CBOR byte-string issue is fixed in src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt, which now joins all byte-string chunks before JSON scalar parsing, and the regression is covered by NodeStateDataPointDecoderTest.
The fixture self-validation concern is also resolved. TraceForwardFixtures now exposes independent literal request and reply anchors, and TraceForwardFixturesTest asserts exact literal wire bytes and uses those pinned literals in the fake-server round trip instead of only exercising the shared builders.
I also rechecked the surrounding live seams: NodeMonitor still owns the current NodeStats assembly path and remains untouched, NodeStats still matches the pinned eight-field mapping, and the task stayed within the approved pre-task-301 scope. Focused verification passed with ./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest".

Blocking findings:
- None.

Non-blocking observations:
- SocketDataPointSessionClient remains a minimal sibling protocol client and is not yet wired into TracingConnectionManager, which is consistent with task-204 scope and the deferral of the actual NodeMonitor migration to task-301.

Approval bar:
- The iteration 1 blockers are closed.
- The exact 8-key DataPoint contract is now independently anchored by literal fixtures and regression tests.
- No new issue introduced in iteration 2 rises to a hold-the-task level.
Decision: approved

