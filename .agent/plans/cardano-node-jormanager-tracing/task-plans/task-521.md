# Task 521 Plan

## Summary

- Task ID: `task-521`
- Title: `Promote protocol 2 typed extraction for block and connection-counter trace objects into production`
- Why now: `task-510` and `task-511` already landed the unified protocol `2` raw-capture path, `task-520` already promoted protocol `1` typed metrics into production, and the remaining truthful gap is that production protocol `2` consumption is still split across older feature-shaped decoders instead of one explicit typed extraction layer aligned to the final shared-capture architecture for block events, peer counters, and chain fallback
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Promote the live-proven protocol `2` trace-object extraction for block events, connection counters, and the existing chain fallback into an explicit production typed signal layer downstream of shared raw capture, then make only the smallest consumer wiring changes needed so current production `NodeMonitor` and block-persistence flows read through that shared typed layer instead of treating the older ad hoc decoders as the architectural endpoint.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md` as the canonical task plan
- add the minimum production protocol `2` typed extraction seam under `src/main/kotlin/com/swiftmako/jormanager/tracing/` for:
- forged and adopted block trace objects
- connection-manager counter trace objects
- `ChainDB.AddBlockEvent.AddedToCurrentChain` trace objects used by the current protocol `2` chain fallback path
- keep the already-correct block semantics intact:
- `Forge.ForgedBlock` -> status `created`
- `Forge.AdoptedBlock` -> status `completed`
- keep connection counters sourced from protocol `2` trace objects rather than protocol `1`
- keep extraction downstream of `TracingRawCaptureService` and reuse the current bounded fresh-batch accessors instead of adding new transport or cache ownership
- make the smallest production consumer changes needed so `NodeMonitor` and tracing block persistence consume a shared protocol `2` typed extraction layer rather than each acting like an independent long-term decoder boundary
- add focused automated coverage for the production protocol `2` typed layer and the resulting consumer wiring

Out of scope:
- widening `NodeStats` or websocket payloads with new trace-derived fields
- changing block validation semantics in `BlockMonitor`
- replacing protocol `3` startup or structured datapoint extraction
- broad `NodeMonitor` unification work that belongs to `task-530`
- broad block-flow cleanup that belongs to `task-531`
- live cluster validation, frontend work, persistence schema changes, or config generation

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-202-tracing-block-persistence-bridge.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Ran `code-index_refresh_index` before planning and then built a fresh deep index with `code-index_build_deep_index`
- Used code-index first to locate the current protocol `2` seams in `NodeMonitor`, `TracingRawCaptureService`, `TraceForwardNodeStateDecoder`, `TraceForwardAdoptedBlockDecoder`, `TracingBlockMessageSink`, and tracing tests
- Re-verified all material findings against live files before making planning claims
- Code-index materially confirmed that transport and raw capture are already production-owned by the unified tracing runtime; the missing work is an explicit shared typed protocol `2` layer plus minimal consumer rewiring, not new socket or mux work

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this is a repo-local backend refactor plus focused tests
  - the protocol `2` payload families and semantics are already pinned by repo-local research, fixtures, and current production code
  - no manual operator action or live-node step is required to implement the production typed layer truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-510`
  - `task-511`
- Important already-landed adjacent work:
  - `task-520`
- Important downstream alignment:
  - `task-522`
  - `task-530`
  - `task-531`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/mux/Mux.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already records protocol `2` trace-object batches as raw first-visibility capture and already exposes freshness-gated recent-batch reads, so task-521 should build typed extraction directly on that seam instead of adding another queue or cache
  - still fans trace batches directly into `TracingBlockMessageSink`, which is acceptable operationally but leaves block persistence coupled to the older block-specific decoder instead of a shared typed protocol `2` layer
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - already decodes both adopted and forged trace objects into `ForwardedBlockEvent`
  - already preserves the corrected semantics from task-500 research: forged means `created`, adopted means `completed`
  - still represents a narrow block-specific decoder rather than an explicit shared protocol `2` typed extraction component
  - current fixture-backed repo truth for forged events is `toMachine.block`, not `toMachine.blockHash`
  - task-201 research pinned strict `toMachine.blockHash` only for adopted-block decoding and does not yet pin the forged payload field name
  - task-521 therefore must preserve strict machine-owned `blockHash` for adopted events, treat forged `toMachine.block` as the accepted implementation contract unless new upstream or live evidence in this task proves otherwise, and record that forged-contract decision in research so repo truth and durable notes no longer drift
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - already merges `ChainDB.AddBlockEvent.AddedToCurrentChain` and `Net.ConnectionManager.Remote.ConnectionManagerCounters` into `ForwardedNodeState`
  - current production `NodeMonitor` depends on it for protocol `2` peer counters and chain fallback
  - this decoder is functionally the right payload knowledge, but it is still named and shaped as an older feature-oriented decoder rather than a shared protocol `2` typed signal layer
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already reads protocol `2` only through shared raw capture and no longer owns tracing transport
  - after task-520, protocol `1` supplies the preferred overlapping dashboard subset, while protocol `2` remains responsible for peer counters and the current `AddedToCurrentChain` chain fallback when datapoints or metrics do not cover the snapshot
  - current `loadForwardedNodeState(...)` folds fresh trace batches through `TraceForwardNodeStateDecoder`, so task-521 should keep the same freshness rule and minimal consumer shape rather than redesigning dashboard assembly
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - currently decodes raw trace batches directly with `TraceForwardAdoptedBlockDecoder` and persists them through `TracingBlockPersistenceService`
  - this is the clearest place where task-521 can replace a feature-specific protocol `2` decoder with a shared typed extractor without touching transport or downstream persistence semantics
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - already owns duplicate suppression, host normalization, epoch or slot enrichment, websocket emission, and the candidate-block persistence contract
  - task-521 should not widen beyond feeding it the same `ForwardedBlockEvent` semantics through a shared extraction layer
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - no longer performs log scraping and now only validates persisted candidate blocks, so this task should leave it unchanged except to note that block discovery remains downstream of tracing persistence
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - already proves forged and adopted decode paths, malformed payload rejection, and non-empty trace-objects reply handling
  - can be repurposed or supplemented to pin the new shared protocol `2` typed block extractor rather than leaving the tests centered on a legacy-named decoder
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoderTest.kt`
  - currently proves connection-counter and chain-state merge for one trace reply, which is the exact minimum behavior task-521 needs to retain under a shared protocol `2` typed layer
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - already contains task-520 assertions that fresh protocol `1` metrics still preserve protocol `2` peer counters when available
  - this means task-521 can keep the consumer change small by swapping in the shared protocol `2` extractor while preserving the current precedence tests
- Tracker versus live-repo truth:
  - the task graph says protocol `2` typed extraction is still pending, and that is directionally true, but the live repo already has most protocol knowledge embedded in production decoders
  - the truthful task-521 gap is therefore architectural promotion and seam cleanup, not inventing protocol `2` semantics from scratch

## Promotion Boundary

- Task-521 should establish one explicit shared protocol `2` typed extraction boundary downstream of `TracingRawCaptureService`.
- That shared layer should cover the currently proven trace-object families needed by production consumers:
  - forged and adopted block trace objects
  - connection-manager counters
  - the existing `ChainDB.AddBlockEvent.AddedToCurrentChain` signal because it is still part of the live `NodeMonitor` protocol `2` fallback contract today
- The typed layer should remain transport-agnostic and should consume fresh raw trace-object batches rather than owning session lifecycle, reconnect logic, or its own cache.
- `NodeMonitor` should continue to prefer protocol `1` for the overlapping dashboard subset introduced in task-520.
- `NodeMonitor` should continue to source `peers` and `incomingPeers` from protocol `2`, and should continue to derive its protocol `2` chain fallback from `AddedToCurrentChain`, with the smallest possible code change being a swap from `TraceForwardNodeStateDecoder` calls to the shared protocol `2` typed extractor.
- Block persistence should continue to receive normalized `ForwardedBlockEvent` values with unchanged `created` or `completed` semantics.
- Adopted block extraction remains pinned to strict machine-owned `toMachine.blockHash`.
- For forged blocks, the planning-time resolved repo boundary is to preserve the current fixture-backed `toMachine.block` contract unless implementation gathers stronger upstream or live evidence for `toMachine.blockHash`; whichever contract is confirmed must be pinned in tests and recorded in research during the task.
- Task-521 should not yet perform the larger consumer simplifications described in `task-530` and `task-531`; it only needs to make those later tasks easier by centralizing protocol `2` decoding truth now.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - add the smallest shared production protocol `2` typed extraction file or files for block events and connection counters
  - keep or rename existing decoder code only if that truthfully improves the architectural seam without widening scope
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - minimal swap from the older direct trace-object decoder usage to the shared protocol `2` typed extractor
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - minimal swap from the older direct block decoder usage to the shared protocol `2` typed extractor
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - add focused shared protocol `2` extraction tests or adapt existing block and node-state decoder tests to the promoted production seam
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - only the smallest updates needed to keep peer-counter precedence pinned after the shared extractor swap
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - only the smallest updates needed to prove block persistence still consumes normalized `created` and `completed` events through the shared protocol `2` extractor

## Implementation Approach

- Prefer the smallest truthful production promotion:
  - introduce one shared protocol `2` typed extraction component under `tracing/`
  - reuse the current decode logic rather than rewriting it for stylistic reasons
  - keep existing DTOs such as `ForwardedBlockEvent` and `ForwardedNodeState` if they still fit the consumer boundary cleanly
- Keep the shared extractor downstream of raw capture:
  - it should accept `TraceForwardMessage.TraceObjectsReply` or `TracingRawTraceObjectBatch` inputs
  - it should rely on `TracingRawCaptureService.recentFreshTraceObjectBatches(...)` for freshness rather than inventing its own TTL rules
  - it should not own socket lifecycle, reconnect behavior, or batch retention
- Keep consumer changes minimal:
  - in `NodeMonitor`, replace the direct `TraceForwardNodeStateDecoder` folding call with the shared protocol `2` extractor while preserving the task-520 precedence and the current `AddedToCurrentChain` plus connection-counter fallback behavior
  - in `TracingBlockMessageSink`, replace the direct `TraceForwardAdoptedBlockDecoder` use with the shared protocol `2` extractor while preserving the same persistence call path
  - leave `TracingBlockPersistenceService` and `BlockMonitor` behavior unchanged unless a small naming cleanup becomes unavoidable
- Preserve corrected semantics explicitly:
  - forged block events must still persist immediately as `created`
  - adopted block events must still persist as `completed`
  - connection counters must still override datapoint peer counts where fresh protocol `2` data is available
- Preserve freshness and fallback boundaries explicitly:
  - `AddedToCurrentChain` remains a required protocol `2` fallback source for `slot` and `blockHeight` when task-520 protocol `1` metrics are absent or stale
  - the promoted shared extractor must continue to operate only on fresh trace-object batches exposed through `recentFreshTraceObjectBatches(...)`
  - task-521 must add a regression test proving stale trace-object batches no longer drive peer counters or chain fallback after rewiring
- Resolve the forged-block hash contract explicitly during implementation:
  - adopted events stay strict on machine-owned `toMachine.blockHash`
  - forged events keep the current fixture-backed `toMachine.block` decoding path unless task-521 proves a stronger upstream or live `blockHash` contract
  - if implementation changes or newly confirms the forged payload shape, update or add research so task-201's adopted-only rule and task-521's forged rule are both durable and non-conflicting
- Avoid premature cleanup:
  - do not force-delete `TraceForwardAdoptedBlockDecoder` or `TraceForwardNodeStateDecoder` if a small wrapper or delegation step is the least risky path
  - if the promoted shared extractor simply becomes the new owner and the old classes become thin delegates, that is acceptable for task-521 if it keeps the implementation smaller and truthful

## Acceptance Criteria

- Production code has an explicit shared protocol `2` typed extraction layer for block events and connection counters.
- Production code has an explicit shared protocol `2` typed extraction layer for block events, connection counters, and the existing `AddedToCurrentChain` chain fallback.
- The shared protocol `2` layer is downstream of `TracingRawCaptureService` and does not own transport, reconnect, or raw-cache lifecycle.
- Production block-event extraction still recognizes forged and adopted trace objects and preserves the corrected `created` versus `completed` semantics.
- Production `NodeMonitor` fallback still depends on `AddedToCurrentChain` for `slot` and `blockHeight` when protocol `1` metrics are unavailable.
- Production peer-counter extraction still comes from protocol `2` trace objects and does not depend on protocol `1`.
- `NodeMonitor` continues to preserve the task-520 precedence model while sourcing peer counters through the promoted shared protocol `2` layer.
- Adopted-block extraction remains strict on machine-owned `toMachine.blockHash`, and the forged-block hash contract is explicitly pinned in code, tests, and research during the task rather than left implicit.
- Block persistence continues to receive normalized `ForwardedBlockEvent` values through the promoted shared protocol `2` layer.
- Stale protocol `2` trace-object batches do not continue driving peer counters or chain fallback after age-out.
- No runtime dependence is reintroduced on legacy log scraping, HTTP metrics, or consumer-owned protocol transport.
- The promotion remains narrow and does not widen `NodeStats`, block-validation behavior, or websocket payload contracts.

## Verification Plan

- Static verification:
  - confirm production tracing code now has a shared protocol `2` typed extraction seam rather than only feature-specific decoders
  - confirm `NodeMonitor` no longer directly depends on the old protocol `2` feature decoder boundary
  - confirm `NodeMonitor` still preserves the current `AddedToCurrentChain` protocol `2` fallback for `slot` and `blockHeight`
  - confirm `TracingBlockMessageSink` no longer directly depends on the old block-only decoder boundary
  - confirm shared protocol `2` extraction still consumes fresh raw trace-object batches and does not introduce another cache or transport owner
  - confirm the forged-block hash contract is explicit in production code rather than an undocumented fallback
  - confirm `BlockMonitor.kt` remains unchanged unless a truly unavoidable contract adjustment appears
- Automated verification:
  - add focused tracing tests that prove the promoted shared protocol `2` extractor decodes:
  - `Forge.ForgedBlock` -> `ForwardedBlockEvent(status = "created")`
  - `Forge.AdoptedBlock` -> `ForwardedBlockEvent(status = "completed")`
  - `Net.ConnectionManager.Remote.ConnectionManagerCounters` -> peer counters
  - `ChainDB.AddBlockEvent.AddedToCurrentChain` -> chain fallback fields used by `NodeMonitor`
  - whichever forged-block hash contract is accepted for this task (`toMachine.block` if current fixture-backed truth is retained, or `toMachine.blockHash` if new evidence justifies changing it)
  - keep or extend negative tests for malformed machine JSON, wrong kinds, and missing required fields
  - update `TracingBlockMessageSinkTest` so block persistence still passes through the promoted shared extractor with both `created` and `completed` statuses
  - update `NodeMonitorTest` so protocol `1` plus protocol `2` mixed-source peer-counter behavior still passes after the shared extractor swap
  - update `NodeMonitorTest` so stale protocol `2` trace-object batches no longer drive peer counters or `AddedToCurrentChain` chain fallback after age-out
  - run the narrowest relevant backend targets, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Truthful validation boundary:
  - fixture-backed and unit-level verification is sufficient for this task
  - optional live tracing tests remain useful regression evidence but are not required to claim task-521 complete

## Risks And Open Questions

- Main risk is scope creep into `task-530` or `task-531` by trying to fully redesign `NodeMonitor` or block-persistence consumers instead of only centralizing protocol `2` decoding truth.
- Another risk is needless churn: most protocol `2` knowledge already exists in production decoders, so rewriting everything instead of promoting those seams would be larger than necessary.
- Another risk is unintentionally regressing forged-block hash handling while resolving the current repo-vs-research split; the plan now constrains that by keeping adopted strictness and requiring the forged contract to be pinned explicitly in tests and research.
- Another risk is collapsing chain fallback and connection-counter extraction too aggressively if `NodeMonitor` still needs both during the task-520 precedence model.
- Open implementation choice to resolve minimally during coding:
  - whether the smallest truthful promotion is one new shared extractor with thin delegating wrappers, or a rename or consolidation of the existing decoders into that shared role

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md` during this planning pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add or update a narrow research note during implementation to record the accepted forged-block hash contract if task-521 keeps the current `toMachine.block` rule or proves a different upstream or live `blockHash` rule, so adopted-block and forged-block boundaries are both durable and non-conflicting.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on protocol `2` typed extraction promotion plus the smallest `NodeMonitor` and block-sink rewiring, and explicitly defers the larger consumer migrations.
- Workflow check: backend, test, and update-doc guidance are sufficient because this task is backend-only and includes a canonical task-plan update.
- Missing tests or docs check: the plan includes focused tracing and consumer regression tests and records all consulted docs, workflows, and research.
- Consistency check: the plan now matches the PRD and live code by preserving protocol `2` as the truthful source for peer counters, block-event transitions, and the current `AddedToCurrentChain` chain fallback, while preserving task-520's protocol `1` precedence for overlapping dashboard fields.

## Final Outcome

- Final result: review-approved and completed on 2026-05-18.
- Final implementation summary:
  - added `TraceForwardProtocol2Extractor` as the shared production protocol `2` typed extraction seam
  - rewired `NodeMonitor` and `TracingBlockMessageSink` to consume the shared extractor downstream of `TracingRawCaptureService`
  - preserved protocol `2` ownership of connection counters and `AddedToCurrentChain` fallback state
  - preserved block semantics of forged=`created` and adopted=`completed`
  - accepted both short fixture namespaces and live-proven `Forge.Loop.*` namespaces for forged/adopted block events
  - pinned adopted hash extraction to `toMachine.blockHash` and forged hash extraction to `toMachine.block`
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
  - result: passed
- Research outcome:
  - added `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
- Review outcome:
  - planning review approved through `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md`
  - implementation review approved through `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-impl-review.md`
