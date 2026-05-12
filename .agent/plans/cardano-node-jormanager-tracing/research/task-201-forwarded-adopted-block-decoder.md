# Task 201 Forwarded Adopted-Block Decoder

## Status

- Implemented and review-approved on 2026-05-12.

## Durable Findings

- The minimal truthful production seam for `task-201` is local reply traversal inside the decoder: `TraceForwardMessage.TraceObjectsReply(traceObjects: CborArray)` can stay as the session client's public boundary while the adopted-block decoder extracts individual forwarded wrapper payloads for its own use.
- The normalized adopted-block event contract for downstream `task-202` should already preserve forwarded `toTimestamp` and `toHostname` alongside machine-owned `slot` and `blockHash`; this avoids another decoder rewrite when `BlockMonitor` persistence migration starts.
- The decoder must enforce the accepted PRD boundary strictly:
  - namespace `Forge.AdoptedBlock`
  - machine `kind: "TraceAdoptedBlock"`
  - numeric machine `slot`
  - machine-owned `blockHash`
- `JSONObject.optLong(...)` is too permissive for this boundary because malformed non-numeric values coerce to `0`; the decoder must inspect the raw JSON value type instead of relying on `optLong`.
- Accepting `blockHash` from the outer wrapper hides schema drift and is not truthful to the pinned `toMachine` contract; require `blockHash` from `toMachine` only.
- Fixture-backed non-empty `TraceObjectsReply` coverage is sufficient for this task's automated proof; live node interoperability is still deferred to later tracing-consumer integration work.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
- Current repo Gradle wiring also runs Vue unit tests and frontend production build during that command; both passed during task-201 verification.
- A recurring KSP/AWT `NullPointerException` was printed during the successful Gradle run but did not fail the test task and does not appear task-specific.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
