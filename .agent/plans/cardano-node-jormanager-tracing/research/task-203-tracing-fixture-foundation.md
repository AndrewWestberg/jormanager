# Task 203 Tracing Fixture Foundation

## Status

- Implemented and review-approved on 2026-05-12.

## Durable Findings

- The reusable automated fixture layer should stay split across 2 boundaries:
  - session-level fixtures for the minimum evidence-backed trace-forward exchange (`MsgTraceObjectsRequest`, empty `MsgTraceObjectsReply`, `MsgDone`)
  - forwarded payload fixtures that preserve the outer `TraceObject`-style wrapper with `toNamespace` and `toMachine`
- Do not collapse forwarded payload fixtures into pre-normalized helpers containing only namespace plus machine JSON. Later decoder work needs the wrapper boundary preserved so tests match the accepted PRD contract.
- For the minimum request/reply/done session contract currently pinned by task-203 tests, the independent literal CBOR hex anchors are:
  - blocking request count 25: `8301f582001819`
  - blocking request count 10: `8301f582000a`
  - blocking request count 1: `8301f5820001`
  - non-blocking request count 2: `8301f4820002`
  - empty reply: `820380`
  - done: `8102`
- Keeping those byte anchors in tests prevents the fake server and builders from only self-validating against the same shared helper output.
- Reuse for later node-state work should remain at the transport/session harness and fixture-layout level until task-204 pins the actual node-state protocol family.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
- Current repo Gradle wiring also runs Vue unit tests and frontend production build during that command; both passed during task-203 verification.

## Files

- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
