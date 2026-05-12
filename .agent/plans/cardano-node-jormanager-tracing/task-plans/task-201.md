# Task 201 Plan

## Summary

- Task ID: `task-201`
- Title: `Decode forwarded adopted-block trace objects`
- Why now: `task-200` and `task-203` are complete, so the next smallest unblocked step is to turn raw forwarded trace-object replies into a normalized adopted-block event that later block-persistence work can consume
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Implement the smallest truthful backend decoder for forwarded adopted-block trace objects so JorManager can recognize the `Forge.AdoptedBlock` trace family from direct trace-forward replies, traverse the live `TraceObjectsReply` CBOR payload boundary, and extract the `TraceAdoptedBlock` machine payload fields Phase 1 actually needs.

In scope:
- add a production decoder under the tracing subsystem for forwarded `TraceObject` wrappers
- add the smallest production seam that traverses `TraceForwardMessage.TraceObjectsReply(traceObjects: CborArray)` into individual forwarded trace-object wrapper payloads before applying adopted-block matching
- match only the PRD-pinned adopted-block identity: namespace `Forge.AdoptedBlock` plus machine payload `kind: "TraceAdoptedBlock"`
- normalize the forwarded payload into a small internal block-event model carrying `slot`, `blockHash`, forwarded `toTimestamp`, and forwarded `toHostname`
- tolerate optional forwarded machine fields such as `blockSize` or `txIds` without depending on them
- fail safely on malformed payloads, unknown namespaces, and missing required fields
- add focused automated tests reusing the `task-203` forwarded wrapper fixtures, including one non-empty `TraceObjectsReply` traversal test

Out of scope:
- persisting decoded events into the block repository (`task-202`)
- removing SSH or file scraping from `BlockMonitor` (`task-300`)
- refactoring `BlockMonitor` to consume tracing events in production yet
- selecting or decoding node-state payload families (`task-204`)
- changing node config generation, startup wiring, or tracing connection lifecycle (`task-100`, `task-101`, `task-102`, `task-200`)
- any frontend, database, or API surface changes unless live code proves a tiny backend seam update is unavoidable
- changing the tracing connection manager lifecycle or session framing beyond the minimal reply-traversal seam needed to decode a real `TraceObjectsReply`

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-200-connection-lifecycle.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-203-tracing-fixture-foundation.md`
  - additional research inventory checked under `.agent/plans/cardano-node-jormanager-tracing/research/`

## Code-Index Sync

- Confirmed project path is `/home/westbam/Development/jormanager`
- Checked code-index settings info and file-watcher status before deeper planning reads
- Refreshed the shallow index and rebuilt the deep index during planning
- Used code-index file search and content search first, then verified important findings against live files directly
- Code-index-synced findings confirmed live:
  - `TracingConnectionManager` currently owns only lifecycle and forwards raw `TraceForwardMessage` values to a single `TraceForwardMessageSink`
  - `TraceForwardSessionClient` currently parses session-level CBOR replies but stops at `TraceForwardMessage.TraceObjectsReply(traceObjects: CborArray)` and does not decode forwarded `TraceObject` payloads yet
  - `BlockMonitor` still parses the legacy scraped log-line shape into `TraceAdoptedBlock` and still shells out to `cat`, `tail`, and `grep`
  - `TraceAdoptedBlock` is tied to the old outer `{at, env, data, host}` log format, so it cannot be reused directly for forwarded trace-object wrappers
  - `task-203` already added forwarded adopted-block wrapper fixtures with `toNamespace`, `toMachine`, `toHostname`, and `toTimestamp`, including malformed JSON, unknown namespace, and missing-field cases
  - current automated protocol coverage proves empty `TraceObjectsReply` handling only, so this task must add one non-empty reply traversal proof instead of treating that seam as optional

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for the planning pass
- Truthful autonomy basis:
  - the accepted PRD identity for Phase 1 is already pinned, the current tracing subsystem exposes a single raw-message sink seam, and the repo already contains reusable forwarded wrapper fixtures for local decoder implementation and tests

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-203`
  - `task-200`
- Live repo dependencies this task should reuse carefully:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/TraceAdoptedBlock.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-202`
  - `task-300`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - confirms the smallest truthful production seam is still the injected `TraceForwardMessageSink`
  - this task should attach decode logic at or just behind that seam instead of widening the lifecycle layer
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - confirms session decoding currently stops at `TraceForwardMessage.TraceObjectsReply(traceObjects: CborArray)`
  - this task should not rewrite transport framing or reconnect behavior, but it does need the smallest truthful `CborArray` traversal or extraction seam so a real reply can reach the adopted-block wrapper decoder
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - confirms there is no production normalized trace-event model yet
- `src/main/kotlin/com/swiftmako/jormanager/model/TraceAdoptedBlock.kt`
  - confirms existing parsing assumes the old scraped log-line envelope with `at`, `env`, `data`, and `host`
  - useful as a semantic reference for the fields downstream block flow cares about, but not as the direct forwarded wrapper model
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - confirms current downstream block discovery uses slot/hash plus timestamp and host context when creating candidate `Block` rows
  - also confirms this task should not yet replace log scraping or repository writes
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
  - already pins the exact forwarded wrapper boundary this decoder must handle: outer `toNamespace` plus `toMachine`
  - already includes the forwarded `toHostname` and `toTimestamp` context that downstream block persistence will need preserved in the normalized event
  - already includes the core negative cases required by the task acceptance criteria

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/...`
  - new small production decoder and normalized event model, plus the smallest truthful `TraceObjectsReply` traversal helper or sink-adjacent extractor if that is smaller and clearer
- `src/test/kotlin/com/swiftmako/jormanager/tracing/...`
  - focused decoder tests reusing `TraceForwardFixtures`, including one non-empty reply traversal test and any tiny fixture additions needed to build that reply
- `.agent/plans/cardano-node-jormanager-tracing/research/task-201-*.md` or a similarly narrow research note only if implementation produces durable decoder constraints worth recording

Expected production touchpoints should stay backend-only unless live implementation proves otherwise.

## Implementation Approach

- Keep the solution inside the existing tracing package and reuse the raw-message sink seam introduced by `task-200`.
- Preferred minimal production shape:
  - one small normalized event type for adopted-block events, carrying only the fields Phase 1 needs now: `slot`, `blockHash`, forwarded `timestamp`, and forwarded `hostname`
  - one decoder component that accepts forwarded trace-object JSON from trace-forward replies and returns either a normalized adopted-block event or `null`
  - one small helper that truthfully traverses `TraceForwardMessage.TraceObjectsReply` and extracts each forwarded trace object from the reply `CborArray`; this seam is required, not optional
- Preserve the PRD-pinned identity checks exactly:
  - namespace must normalize to `Forge.AdoptedBlock`
  - machine payload must contain `kind: "TraceAdoptedBlock"`
  - required machine fields are `slot` and `blockHash`
- Do not route forwarded payloads through the old `TraceAdoptedBlock` Moshi model:
  - that model expects the scraped log-line wrapper and `data.val` nesting
  - forwarded payloads are `TraceObject` wrappers with machine JSON under `toMachine`
- Keep parsing permissive where the PRD allows and strict where identity matters:
  - ignore optional `blockSize` and `txIds`
  - ignore unknown namespaces and wrong `kind` values without throwing
  - reject missing `slot` or `blockHash`
  - reject malformed `toMachine` JSON safely
- Prefer existing JSON tooling already used in the backend over introducing a new dependency or a large protocol model.
- If the smallest implementation needs a wrapper model, keep it shallow:
  - only the forwarded fields needed for this task such as `toNamespace`, `toMachine`, `toHostname`, and `toTimestamp`
  - do not model the full upstream trace-forward schema if the repo does not need it yet
- Pin the downstream normalization contract now so `task-202` can reuse it without another decoder rewrite:
  - preserve forwarded `toTimestamp` and `toHostname` on the normalized adopted-block event now rather than deriving them later
  - keep `toTimestamp` as the forwarded string value unless live code proves parsing it earlier is necessary
  - keep `toHostname` as the forwarded string value from the wrapper rather than substituting the connection target hostname inside this task
- Keep integration with the lifecycle layer minimal:
  - add only a tiny sink-adjacent helper or equivalent extractor needed to prove `TraceObjectsReply` messages can be traversed and decoded from a non-empty reply
  - do not widen into persistence, websocket publishing, or `BlockMonitor` ownership during this task

## Acceptance Criteria

- Forwarded adopted-block trace objects decode without relying on the old scraped log-line model.
- The production decoder truthfully handles the live reply boundary by traversing at least one non-empty `TraceForwardMessage.TraceObjectsReply` payload into forwarded wrapper decoding.
- Namespace and machine-kind checks are both enforced exactly as pinned by the PRD.
- Valid forwarded `Forge.AdoptedBlock` payloads produce a normalized internal block event carrying `slot`, `blockHash`, forwarded `toTimestamp`, and forwarded `toHostname`.
- Malformed `toMachine` JSON, unexpected namespaces, wrong kinds, and missing required fields fail safely without crashing the tracing subsystem.
- Decoder output is suitable for reuse by later block-persistence and `BlockMonitor` migration tasks without forcing another decode rewrite.

## Verification Plan

- Static verification:
  - confirm the decoder lives in the tracing subsystem and does not alter transport lifecycle responsibilities
  - confirm no code path depends on the legacy `TraceAdoptedBlock` outer `{at, env, data, host}` shape for forwarded payloads
  - confirm the normalized event model stays limited to the Phase 1 fields needed now for downstream reuse: `slot`, `blockHash`, forwarded `toTimestamp`, and forwarded `toHostname`
  - confirm `BlockMonitor` remains unchanged aside from any minimal imports avoided by keeping this task decoder-only
- Automated verification:
  - add focused decoder tests covering:
    - valid `Forge.AdoptedBlock` wrapper decodes to normalized event
    - unknown namespace returns no event
    - malformed `toMachine` JSON returns no event and does not throw
    - missing `blockHash` returns no event
    - wrong `kind` returns no event if a fixture or inline sample is added for that case
    - optional fields like `blockSize` do not affect success
  - add one focused test proving a non-empty `TraceObjectsReply` containing the forwarded wrapper reaches the decoder cleanly
  - run the narrowest relevant backend target, preferably a concrete tracing decoder test class once it exists
- Truthful validation boundary:
  - live node interoperability is not required to complete this task because the accepted wrapper boundary and negative cases are already pinned by the task-203 fixture foundation

## Risks And Open Questions

- Main risk is accidental scope creep into task-202 by wiring repository persistence or block-monitor updates too early.
- A second risk is over-modeling the entire upstream forwarded trace schema instead of only the fields needed to decode adopted-block events truthfully.
- The exact JSON representation inside forwarded CBOR trace objects in production code still needs to be matched by the chosen implementation, so the reply-traversal seam must stay as small and local as possible.
- Forwarded timestamp and hostname are now intentionally part of the normalized event contract for downstream block persistence reuse; the remaining open question is only whether the minimal implementation can preserve them as raw strings without early parsing.

## Required Docs, Tracking, And Research Updates

- Create this canonical plan doc at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201.md`.
- Do not write the planning review log from this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation reveals durable decoder constraints, such as the exact forwarded `toMachine` representation encountered in production parsing or any gotchas in traversing trace-object reply payloads.

## Final Outcome

- Status: `completed`
- Final implementation summary:
  - added `TraceForwardAdoptedBlockDecoder` and `ForwardedAdoptedBlockEvent` in the tracing package
  - kept the production reply-traversal seam local to `decode(reply)` so non-empty `TraceObjectsReply` payloads can be decoded without widening `TracingConnectionManager` or `TraceForwardSessionClient`
  - enforced the PRD-pinned decoder boundary: namespace `Forge.AdoptedBlock`, machine `kind: "TraceAdoptedBlock"`, numeric machine `slot`, machine-owned `blockHash`, forwarded `toTimestamp`, and forwarded `toHostname`
  - added focused fixture-backed coverage for valid decode, malformed machine JSON, unknown namespace, wrong kind, missing required fields, non-numeric `slot`, wrapper-only `blockHash`, and non-empty reply traversal
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
  - repo-wide Gradle wiring also ran Vue unit tests and frontend build successfully during that command
  - a recurring KSP/AWT `NullPointerException` was printed during successful Gradle execution; it did not fail the build and appears unrelated to task-201 logic
- Durable research update:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
- Review outcome:
  - planning review completed via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201-plan-review.md`
  - implementation review approved via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201-impl-review.md`

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201-impl-review.md`

## Self-Review

- Scope is intentionally tight: decode and normalization only, with no block persistence, no `BlockMonitor` migration, and no node-state expansion.
- Workflow text is current with live repo reality: tracing lifecycle exists, forwarded wrapper fixtures exist, and production block discovery still depends on legacy log scraping.
- Missing tests are addressed directly with fixture-backed decoder coverage.
- The plan avoids stale wording by keeping verification and review references generic rather than tied to a future iteration number.
