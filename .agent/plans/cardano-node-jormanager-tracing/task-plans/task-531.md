# Task 531 Plan

## Summary

- Task ID: `task-531`
- Title: `Refactor block persistence flow to consume the unified tracing signal inventory`
- Why now: `task-530` finished the broader consumer migration work and exposed that the block path is already mostly converged: raw capture is owned by `TracingRawCaptureService`, shared block-event extraction is already owned by `TraceForwardProtocol2Extractor`, and `TracingBlockMessageSink` already persists normalized `ForwardedBlockEvent`s downstream of that extractor. The remaining truthful task-531 delta is therefore the smaller block-side cleanup and verification pass that makes this production boundary explicit, minimal, and well-covered instead of planning another new tracing seam.
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Converge the tracing-driven block persistence path around the production boundary that already exists in the live repo, while preserving the existing candidate-block persistence contract, duplicate suppression, websocket publication, and `BlockMonitor` validation boundary.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531.md` as the canonical task plan
- preserve and clarify the existing shared production block boundary:
- raw protocol `2` trace-object capture remains owned by `TracingRawCaptureService`
- block-event decoding remains owned by `TraceForwardProtocol2Extractor`
- `TracingBlockMessageSink` remains the thin tracing consumer that resolves node and host and hands normalized events to persistence
- `TracingBlockPersistenceService` remains the persistence seam that writes candidate blocks, enriches epoch and slot data, suppresses duplicates, and emits websocket updates
- make only the smallest production cleanup or delegation change needed if any live code still obscures that boundary, such as residual block-specific wrapper or naming ambiguity
- preserve the already accepted block-event semantics:
- `Forge.ForgedBlock` -> persist candidate block immediately with status `created`
- `Forge.AdoptedBlock` -> persist candidate block with status `completed`
- keep `TracingBlockMessageSink` responsible for node and host resolution before persistence, and keep `TracingBlockPersistenceService` responsible for writing `host.hostname`, epoch or slot enrichment, duplicate suppression, shared persistence serialization, and websocket publication
- keep `BlockMonitor` responsible only for startup seeding and later forged or missed or orphaned validation of persisted candidate blocks
- add focused automated coverage that proves the existing extractor -> sink -> persistence -> `BlockMonitor` boundary remains intact

Out of scope:
- changing trace-forward transport, mux ownership, or `TracingConnectionManager`
- changing `TraceForwardProtocol2Extractor` contracts except for a tiny cleanup that more clearly exposes the already-accepted block-event boundary
- widening `ForwardedBlockEvent`, `Block`, websocket payloads, or validation status semantics
- changing `BlockMonitor` validation behavior, node-channel startup seeding, or chain lookups
- database schema or Liquibase changes
- node creation, config generation, frontend work, or live cluster verification as a task requirement

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/database.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-impl-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-202-tracing-block-persistence-bridge.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-300-blockmonitor-boundary.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Built a fresh deep index before planning and used code-index first to locate the current block-persistence, protocol `2`, and monitor seams
- Re-verified all material findings against live files before writing the plan
- Code-index materially confirmed that `NodeMonitor` already consumes a shared tracing-side seam while the block path still enters through `TracingBlockMessageSink`; the truthful remaining work is consumer convergence on the block side, not new transport or new protocol decoding semantics
- Code-index materially confirmed that the shared protocol `2` extractor migration already landed for the block path in task-521, so task-531 must now stay on the smaller remaining cleanup and verification delta rather than introducing a new tracing-side block service by default

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this task is a repo-local backend refactor plus focused tests
  - protocol `2` block-event semantics and namespaces are already pinned by completed task research and current tests
  - no manual operator action, remote-host change, or live-node proof is required to complete the consumer convergence work truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-521`
- Important already-landed adjacent work:
  - `task-510`
  - `task-511`
  - `task-530`
- Important downstream alignment:
  - `task-540`
  - `task-541`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already owns protocol `2` trace-object retention and freshness boundaries and still forwards each fresh trace-object batch to `TracingBlockMessageSink`
  - should remain the raw-capture owner; task-531 should not add another queue, cache, or transport-owned block collector
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
  - already owns the accepted production protocol `2` block-event decode truth, including forged versus adopted status semantics and namespace compatibility
  - is already the shared production block-event extraction seam for the live block path
  - task-531 should preserve that ownership and only add a tiny helper or cleanup if it materially clarifies the production boundary without widening scope
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - currently performs node and host lookup, then directly calls `protocol2Extractor.decodeBlockEvents(batch)` before invoking persistence
  - already consumes the shared extractor and hands normalized events to persistence
  - the remaining task-531 question is whether any tiny delegation or cleanup is still useful to make that boundary explicit, not whether a new block signal service is required
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - already owns the durable candidate-block persistence contract after node and host have been resolved upstream: writing `host.hostname` into `Block.host`, epoch and slot enrichment via `BlockUtils`, duplicate suppression keyed by slot, shared serialization through `persistenceMutex`, and websocket publication
  - should stay persistence-focused and should not take on raw trace-batch decoding or tracing freshness logic
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - is now only a thin compatibility wrapper over `TraceForwardProtocol2Extractor`
  - is a likely place for any truly minimal task-531 cleanup if production code or tests still overstate it as the active architectural seam
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - already matches the tracing-era boundary pinned by task-300 research: startup node seeding plus persisted candidate-block validation only
  - should remain unchanged unless a tiny contract adjustment is unavoidable
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - already pins created and completed block persistence, duplicate suppression, missing-node safety, and raw-capture-before-persistence behavior
  - should be updated only enough to prove the accepted extractor -> sink -> persistence boundary remains intact after any cleanup
- `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`
  - already pins the downstream tracing-era `BlockMonitor` boundary: startup seeding plus forged or missed or orphaned validation on persisted candidate blocks
  - should be included in task-531 verification so the block-persistence cleanup does not accidentally blur discovery versus validation responsibilities
- Tracker versus live-repo truth:
  - the task graph correctly says task-531 is still pending, but the live repo already has the accepted protocol `2` extraction logic in production
  - the truthful remaining work is not “teach block persistence protocol `2`” and not automatically “add a new block seam”; it is to finish the smaller cleanup, tests, and boundary clarification around the already-landed shared extractor path

## Convergence Boundary

- Task-531 should preserve the current production block boundary downstream of unified tracing capture:
  - `TracingRawCaptureService` records trace-object batches first
  - `TraceForwardProtocol2Extractor` decodes block events from those batches
  - `TracingBlockMessageSink` resolves node and host and invokes persistence per normalized event
  - `TracingBlockPersistenceService` persists and publishes the candidate block
- If implementation introduces any new helper or named block seam, it must be justified as a strictly smaller cleanup or delegation convenience around that existing boundary, not as a replacement architecture.
- `TracingBlockPersistenceService` should continue to consume normalized `ForwardedBlockEvent` values and should not start reading raw capture or trace-object batches itself.
- The preserved boundary should keep current behavior exactly unless a bug is found and fixed with tests:
  - forged events persist immediately as status `created`
  - adopted events persist as status `completed`
  - managed repository hostnames remain the persisted `Block.host` value rather than forwarded wrapper hostnames
  - duplicate suppression remains centralized in `TracingBlockPersistenceService`
- Task-531 should not relocate the persistence bridge into `BlockMonitor`; tracing remains the discovery owner and `BlockMonitor` remains downstream validation only.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - only if needed, make the smallest cleanup to clarify the existing production block boundary around `TraceForwardProtocol2Extractor`, `TracingBlockMessageSink`, or the thin `TraceForwardAdoptedBlockDecoder` compatibility wrapper
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - only if needed, narrow or clarify sink delegation without changing its role as node or host resolution plus persistence invocation
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - adapt focused sink tests to whichever minimal cleanup lands while preserving persistence and raw-capture ordering coverage
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - keep or retarget block-decoder coverage so test intent matches the actual production boundary, especially if `TraceForwardAdoptedBlockDecoderTest` remains compatibility-wrapper coverage only
- `src/test/kotlin/com/swiftmako/jormanager/monitors/BlockMonitorTest.kt`
  - include downstream validation-boundary preservation coverage if any cleanup touches observable block-flow assumptions

## Implementation Approach

- Prefer the smallest truthful convergence step:
  - start from the live repo truth that `TraceForwardProtocol2Extractor` is already the shared production block-event decoder
  - only introduce a helper or delegation cleanup if it removes residual ambiguity or reduces direct feature-shaped wiring without inventing a second architectural seam
  - otherwise keep production code changes smaller and let task-531 land as boundary clarification plus focused regression coverage
- Keep responsibilities narrow:
  - raw capture stays in `TracingRawCaptureService`
  - typed protocol `2` decode truth stays in `TraceForwardProtocol2Extractor`
  - node and host resolution stay in `TracingBlockMessageSink`
  - persistence semantics stay in `TracingBlockPersistenceService`
- Reuse existing DTOs and contracts:
  - keep `ForwardedBlockEvent` unchanged unless a tiny constructor or helper change is truly required
  - keep persisted status strings and websocket publication unchanged
- Avoid over-cleanup in this task:
  - do not add a new snapshot-style block service just to mirror `TracingDashboardSignalService`; the live block path does not need that abstraction by default
  - do not move persistence into the extractor or any new helper
  - do not expand the cleanup into a generic tracing event framework or broad task-540 deletion pass

## Acceptance Criteria

- Production block persistence remains downstream of unified raw capture and the shared `TraceForwardProtocol2Extractor` block-event decode path.
- If task-531 introduces any new helper or named block seam, it remains only a thin cleanup or delegation layer around that existing boundary and does not own transport, reconnects, or another trace-object buffer.
- Forged block events still persist immediately with status `created`.
- Adopted block events still persist with status `completed`.
- Managed repository hostnames remain the persisted `Block.host` value for tracing-driven candidate blocks.
- `TracingBlockMessageSink` remains the node or host resolution boundary and `TracingBlockPersistenceService` remains the candidate-block persistence boundary.
- Duplicate suppression and serialization remain centralized in `TracingBlockPersistenceService`.
- `BlockMonitor` remains downstream validation only and does not reacquire block-discovery responsibilities.
- No isolated per-feature tracing transport path or decoder ownership is reintroduced.

## Verification Plan

- Static verification:
  - confirm the live production boundary still reads as raw capture -> shared protocol `2` extraction -> sink -> persistence
  - if a new helper or named block seam is added, confirm it is only a thin delegation layer around `TraceForwardProtocol2Extractor` and not a new cache or transport owner
  - confirm `TracingBlockPersistenceService` and `BlockMonitor` responsibilities remain unchanged apart from any tiny constructor-wiring adjustments
  - confirm no new cache, queue, or transport owner appears in the block flow
- Automated verification:
  - keep focused block-event decode coverage proving:
  - `Forge.ForgedBlock` and `Forge.Loop.ForgedBlock` decode to `ForwardedBlockEvent(status = "created")`
  - `Forge.AdoptedBlock` and `Forge.Loop.AdoptedBlock` decode to `ForwardedBlockEvent(status = "completed")`
  - malformed or irrelevant trace objects are ignored safely
  - if `TraceForwardAdoptedBlockDecoderTest` remains in the suite, treat it explicitly as compatibility-wrapper coverage unless implementation retargets that test surface
  - update `TracingBlockMessageSinkTest` so it still proves:
  - candidate blocks are persisted through the shared extractor -> sink -> persistence path
  - raw capture records the trace batch before persistence runs
  - missing nodes or hosts are ignored safely
  - duplicate suppression and sentinel epoch behavior in `TracingBlockPersistenceService` remain intact
  - include `BlockMonitorTest` or an equally explicit preservation check so task-531 still proves the downstream block-validation boundary remains startup seeding plus candidate-block validation only
  - run the narrowest relevant backend targets, expected shape:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest" -x buildVue -x testVue`
- Truthful validation boundary:
  - fixture-backed and unit-level verification is sufficient for this task
  - if implementation changes the durable production block boundary or leaves a new named delegation seam in place, add or update a research note so later tasks can reference that boundary explicitly

## Risks And Open Questions

- Main risk is scope creep into `task-540` by trying to remove all thin compatibility decoders or do broad tracing cleanup instead of only converging the block consumer boundary.
- Another risk is accidental ownership drift if cleanup moves node or host resolution out of `TracingBlockMessageSink` or moves candidate-block persistence concerns out of `TracingBlockPersistenceService`.
- Another risk is unnecessary abstraction: the truthful target is the smallest cleanup around the existing shared extractor path, not a new generalized block-signal service.
- Open implementation choice to resolve minimally during coding:
  - whether the smallest truthful implementation is a tiny production code cleanup at all, or mostly test and doc clarification around the already-correct shared extractor boundary

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-plan-review.md` during this planning pass.
- Planning-only work should not update the PRD or tasks JSON.
- If implementation changes the durable production boundary between shared extractor, sink, persistence, and downstream `BlockMonitor`, add or update a research note for that block boundary instead of leaving the change only in code and tests.
- If implementation does not change that durable boundary and only tightens tests or naming, `no new research` remains appropriate.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-impl-review.md`

## Self-Review

- Scope creep check: the revised plan narrows task-531 to the actual remaining live-repo delta and no longer assumes a new block seam is needed.
- Workflow check: backend, test, and database workflows were consulted because this task touches a persistence-facing backend seam but does not require schema migration work.
- Missing tests or docs check: the plan now includes explicit downstream `BlockMonitorTest` preservation coverage and requires research updates if implementation changes the durable block boundary.
- Consistency check: the plan now matches the live repo, the PRD, task-202 and task-300 block notes, and task-521 research by treating `TraceForwardProtocol2Extractor` as the existing shared production block-event seam and task-531 as the final minimal cleanup around that path.

## Final Outcome

- Result: completed and implementation-review approved.
- Final implementation summary:
  - registered `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt` as a Spring component so production block and dashboard consumers share the same injected protocol `2` extractor boundary instead of only ad hoc construction
  - added focused `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt` coverage proving the sink delegates decode to the shared extractor before invoking `TracingBlockPersistenceService`
  - preserved the accepted production block boundary as raw capture -> shared protocol `2` extraction -> sink node or host resolution -> persistence -> downstream `BlockMonitor` validation
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest" -x buildVue -x testVue`
- Review results:
  - planning review completed in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-plan-review.md`
  - implementation review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531-impl-review.md`
- Research outcome:
  - no new research; implementation only clarified and regression-tested the already accepted production block boundary without changing its durable architecture
