# Task 522 Plan

## Summary

- Task ID: `task-522`
- Title: `Promote protocol 3 typed extraction for startup and structured node-state datapoints into production`
- Why now: `task-510` and `task-511` already landed the shared protocol `3` raw-capture path, while `task-520` and `task-521` already promoted protocol `1` and `2` typed extraction into production. The remaining truthful protocol `3` gap is that startup metadata and structured datapoints are still consumed through the older raw decoder path in `NodeMonitor` instead of one explicit production typed extraction seam aligned to the unified tracing architecture.
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Promote the proven protocol `3` datapoint decoding for startup metadata and structured node-state datapoints into an explicit production typed extraction layer downstream of shared raw capture, then make only the smallest consumer wiring change needed so `NodeMonitor` reads protocol `3` through that shared typed seam instead of calling the raw datapoint decoder directly.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md` as the canonical task plan
- add the minimum production protocol `3` typed extraction seam under `src/main/kotlin/com/swiftmako/jormanager/tracing/` for:
- `NodeStartupInfo` startup metadata needed by the tracing migration architecture
- `NodeAddBlock` structured datapoint semantics pinned to epoch, slot-in-epoch, and sync percentage rather than block height
- the existing scalar datapoint subset already used for current `NodeStats` fallback fields
- widen the production protocol `3` request manifest at its live owner so `NodeStartupInfo` is actually requested by runtime capture, currently through `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt` and `NodeStateDataPointDecoder.REQUESTED_NAMES`
- keep extraction downstream of `TracingRawCaptureService` and reuse the current freshness-gated datapoint snapshot accessor instead of adding transport, cache, or polling ownership
- make the smallest `NodeMonitor` change needed so protocol `3` reads flow through the promoted shared typed extractor while preserving the current precedence introduced by `task-520` and `task-521`
- add focused automated coverage for the promoted protocol `3` typed layer and the resulting `NodeMonitor` fallback behavior

Out of scope:
- widening `NodeStats` or websocket payloads with startup metadata such as era, slot length, or slots per KES period
- changing `BlockMonitor` behavior or block persistence flow
- changing protocol `1` or protocol `2` typed extraction behavior beyond minimal compatibility adjustments
- redesigning `NodeMonitor` into the larger unified consumer owned by `task-530`
- schema, Liquibase, or entity changes
- frontend changes, rollout docs, or live operator validation

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Database workflow consulted:
  - not needed; this task does not truthfully change entities, persistence, or Liquibase
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-204-node-state-datapoint-contract.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-301-node-monitor-direct-datapoint-outcome.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Rebuilt the deep index before planning and used code-index first to locate the current tracing package, `NodeMonitor`, `BlockMonitor`, and datapoint-related test surfaces
- Re-verified all material findings against live files before writing the plan
- Code-index materially confirmed that protocol `3` transport and raw capture already exist in production; the missing work is typed extraction promotion plus minimal consumer rewiring, not new mux or session architecture

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this is a repo-local backend refactor plus focused tests
  - the protocol `3` datapoint manifest, startup-info presence, and corrected `NodeAddBlock` semantics are already pinned by repo-local research, fixtures, and live-test evidence
  - no manual operator action or persistence migration is required to implement the production typed layer truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-510`
  - `task-511`
- Important already-landed adjacent work:
  - `task-520`
  - `task-521`
- Important downstream alignment:
  - `task-530`
  - `task-541`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already records the latest protocol `3` datapoint snapshot per node and already exposes `latestFreshDataPointSnapshot(...)`
  - this means task-522 should stay downstream of the raw-capture seam and must not add another datapoint cache owner
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - currently owns the production protocol `3` request set by passing `NodeStateDataPointDecoder.REQUESTED_NAMES` into `ForwardingDataPointsProtocol`
  - because protocol `3` is manifest-driven and non-enumerable, task-522 must include this runtime request boundary so `NodeStartupInfo` can actually enter shared raw capture instead of remaining test-only
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - already contains the repo's current protocol `3` decoding truth for scalar datapoint fallback and the corrected `NodeAddBlock` interpretation
  - currently mixes several concerns in one raw decoder: CBOR datapoint-map decoding, scalar node-state assembly, and `NodeAddBlock` parsing
  - currently owns the shared requested-name manifest used by production runtime, but that manifest stops at the scalar node-state keys plus `NodeAddBlock` and does not yet request `NodeStartupInfo`
  - does not expose an explicit startup-info model or one named production protocol `3` typed extraction boundary
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - currently calls `NodeStateDataPointDecoder` directly on fresh raw snapshots inside `loadNodeStateMetrics(...)`
  - after `task-520`, protocol `1` metrics are preferred for overlapping dashboard fields when fresh and complete
  - after `task-521`, protocol `2` still supplies peer counters and `AddedToCurrentChain` fallback state
  - protocol `3` therefore remains a fallback contributor plus the future owner of startup metadata, which is exactly the seam this task should formalize without broadening `NodeMonitor`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoderTest.kt`
  - already proves the current scalar datapoint decode path, chunked bytestring handling, legacy-prefixed compatibility, and the corrected `NodeAddBlock` non-block-height boundary
  - these tests are the best minimal base for promoting production protocol `3` typed extraction without needing live-network work
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - live test helpers already show `NodeStartupInfo` as the best startup metadata source and expose the full protocol `3` datapoint manifest from task-500 research
  - startup metadata currently remains stranded in test-only helper usage rather than a production extractor
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - already pins datapoint fallback, protocol `1` precedence, and protocol `2` peer-counter behavior
  - does not yet pin a dedicated production protocol `3` extraction seam or startup-info decode path
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - now only validates persisted candidate blocks and does not directly consume protocol `3`
  - should remain unchanged for task-522
- Tracker versus live-repo truth:
  - the task graph says protocol `3` typed extraction is still pending, and the live repo agrees
  - the truthful gap is not datapoint transport or raw capture; it is the missing explicit production typed protocol `3` layer for startup and structured datapoints plus a narrow consumer swap in `NodeMonitor`

## Promotion Boundary

- Task-522 should establish one explicit shared protocol `3` typed extraction boundary downstream of `TracingRawCaptureService`.
- Task-522 must also promote the production request-manifest boundary that feeds protocol `3` raw capture so startup datapoints are requested before any typed extraction claims are considered complete.
- That shared layer should cover the currently proven datapoint families needed by production consumers and near-term tracing architecture:
  - scalar node-state datapoints already mapped into current `NodeStats` fallback fields
  - `NodeAddBlock` as a structured datapoint carrying epoch, slot-in-epoch, and sync percentage only
  - `NodeStartupInfo` as the production source for startup metadata such as era, epoch length, slot length, and slots per KES period
- The promoted typed layer should remain transport-agnostic and should consume fresh raw datapoint snapshots rather than owning session lifecycle, reconnect behavior, or snapshot retention.
- The runtime request manifest should stay minimal and source-derived: add `NodeStartupInfo`, keep the existing node-state fallback names, and do not widen into unrelated protocol `3` datapoints from the broader task-500 inventory.
- `NodeMonitor` should continue to prefer protocol `1` for the overlapping dashboard subset introduced in `task-520`.
- `NodeMonitor` should continue to prefer protocol `2` for peer counters and `AddedToCurrentChain` chain fallback introduced in `task-521`.
- `NodeMonitor` should use protocol `3` through the promoted typed extractor as its existing datapoint fallback path, with no per-field cross-family merge expansion beyond the current minimal behavior.
- Startup metadata should become available through production tracing code in this task, but task-522 should not widen `NodeStats` or change websocket payload contracts just to surface it immediately.
- `NodeAddBlock` must remain explicitly non-authoritative for block height and full slot, and the production typed layer should make that contract harder to misuse than the current raw decoder shape.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - add the smallest shared production protocol `3` typed extraction file or files for startup info and structured datapoints
  - refactor `NodeStateDataPointDecoder.kt` only as needed to keep decode logic centralized without widening scope
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - update the production protocol `3` request-manifest path so shared raw capture requests `NodeStartupInfo`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - minimal swap from direct raw datapoint decoder usage to the promoted shared protocol `3` typed extractor
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - add focused protocol `3` typed extraction tests or adapt current datapoint decoder tests to the promoted production seam
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
  - update or add the smallest manifest-level assertion needed to prove `NodeStartupInfo` is in the production request set
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - smallest updates needed to pin protocol `3` fallback behavior after the shared extractor swap
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
  - only if startup-info fixture coverage is needed and can be added narrowly

## Implementation Approach

- Prefer the smallest truthful production promotion:
  - introduce one shared protocol `3` typed extraction component under `tracing/`
  - reuse the current datapoint decode logic and fixtures rather than rewriting the protocol contract for style
  - keep current consumer-facing DTOs where they still fit, and add a small startup-info DTO only if needed to make the production seam explicit
  - update the existing production request-manifest owner instead of inventing a second source of truth for requested protocol `3` names
- Split responsibilities only where it reduces ambiguity:
  - keep low-level datapoint-map decoding and chunk-joining logic reusable
  - make startup-info extraction explicit instead of leaving it stranded in live-test helpers
  - keep `NodeAddBlock` parsing explicit and structured so it cannot drift back toward the old block-height misinterpretation
- Keep consumer changes minimal:
  - in `NodeMonitor`, replace the direct `NodeStateDataPointDecoder` call with the promoted protocol `3` extractor while preserving the current precedence order of protocol `1` first, protocol `3` fallback, then protocol `2` chain fallback where that already exists
  - do not expand `NodeMonitor` into a broader multi-signal assembler here; that remains `task-530`
- Keep startup metadata production-owned but not overconsumed:
  - ensure production runtime explicitly requests `NodeStartupInfo` so the shared raw-capture layer can retain it
  - decode `NodeStartupInfo` into a small typed model in production code
  - make that model available for current or next-task consumers without forcing `NodeMonitor` or `NodeStats` contract changes now
  - continue using genesis-file-derived epoch length in `NodeMonitor` unless implementation uncovers a small, clearly beneficial startup-info substitution that does not widen scope
- Preserve compatibility and failure boundaries already pinned in repo truth:
  - missing datapoints must fail safely without corrupting dashboard state
  - chunked `CborByteString` datapoint values must keep decoding correctly
  - legacy-prefixed scalar datapoints may remain supported if retaining that compatibility is cheaper than removing it and does not conflict with the PRD's no-legacy-tracing rule, since this is a decoder-side fixture boundary rather than a runtime transport mode
- Avoid premature cleanup:
  - do not rewrite or delete the existing decoder entirely if a small wrapper or delegation step is the safer promotion path
  - do not change `BlockMonitor`, persistence, or websocket contracts in this task

## Acceptance Criteria

- Production code has an explicit shared protocol `3` typed extraction layer for startup metadata and structured datapoints.
- The promoted protocol `3` layer is downstream of `TracingRawCaptureService` and does not own transport, reconnect, or raw-cache lifecycle.
- Production runtime requests `NodeStartupInfo` through the live protocol `3` request-manifest owner and shared raw capture can retain that datapoint for the promoted extractor.
- Production code can decode `NodeStartupInfo` into a typed startup model suitable for later runtime consumers.
- Production code continues to decode the existing scalar node-state datapoint subset needed for current `NodeStats` fallback behavior.
- `NodeAddBlock` remains explicitly interpreted as epoch, slot-in-epoch, and sync-percentage metadata, not as block height.
- `NodeMonitor` consumes protocol `3` through the promoted typed extraction seam rather than calling the raw decoder directly.
- `NodeMonitor` still preserves the current precedence model where fresh and complete protocol `1` metrics win for overlapping dashboard fields.
- Missing or malformed datapoints fail safely without making stale or corrupted dashboard state look healthy.
- Chunked-byte-string datapoint decoding remains correct.
- `NodeStats` and websocket payload contracts remain unchanged in this task.
- No new transport owner, polling loop, or protocol-family-specific socket topology is introduced.

## Verification Plan

- Static verification:
  - confirm production tracing code now has an explicit shared protocol `3` typed extraction seam rather than only `NodeMonitor` calling `NodeStateDataPointDecoder` directly
  - confirm the live production request-manifest owner now includes `NodeStartupInfo` in the runtime protocol `3` request set
  - confirm startup-info extraction lives in production code, not only in `LiveTraceForwardIntegrationTest.kt`
  - confirm shared raw capture can still receive that requested datapoint without adding another transport owner
  - confirm `NodeAddBlock` parsing remains pinned away from block-height semantics
  - confirm `NodeMonitor` still preserves task-520 and task-521 precedence after the protocol `3` seam swap
  - confirm `NodeStats.kt` remains unchanged unless a truly unavoidable contract fix appears
- Automated verification:
  - add focused tracing tests that prove the promoted protocol `3` extractor decodes:
  - the current scalar node-state datapoint subset into the same fallback `NodeStateMetrics` shape
  - `NodeStartupInfo` into a typed startup model with the expected fields when fixture data is present
  - `NodeAddBlock` into a structured value that excludes block-height semantics
  - malformed, missing, or incomplete datapoints fail safely
  - chunked bytestring datapoint values still decode correctly
  - add one focused manifest-boundary assertion, such as a unit test on the production requested datapoint name list or equivalent protocol-level fixture proof, that fails if `NodeStartupInfo` is not requested by runtime capture
  - update `NodeMonitorTest` so datapoint fallback still works after the shared extractor swap
  - update `NodeMonitorTest` so fresh protocol `1` metrics still win over protocol `3` fallback after the promotion
  - run the narrowest relevant backend targets, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Truthful validation boundary:
  - fixture-backed and unit-level verification is sufficient for this task
  - optional live tracing verification remains useful evidence for startup-info shape but is not required to claim task-522 complete

## Risks And Open Questions

- Main risk is scope creep into `task-530` by trying to fully redesign dashboard assembly instead of only promoting protocol `3` extraction and swapping the consumer seam.
- Another risk is conflating startup metadata availability with an immediate need to change `NodeStats`; this task should keep startup info production-owned without widening dashboard contracts.
- Another risk is accidentally reintroducing the old misconception that `NodeAddBlock` is block height.
- Another risk is over-refactoring the existing datapoint decoder when most repo truth is already encoded there.
- Open implementation choice to resolve minimally during coding:
  - whether the smallest truthful promotion is one new protocol `3` extractor with thin delegation to `NodeStateDataPointDecoder`, or a small restructuring inside that file that makes startup and structured datapoints explicit without adding extra wrappers

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522-plan-review.md` during this planning pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add or update a narrow research note during implementation to record the accepted production protocol `3` typed extraction boundary, especially the startup-info model and the explicit `NodeAddBlock` contract carried into production.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on protocol `3` typed extraction promotion plus the smallest `NodeMonitor` seam swap and explicitly defers the broader consumer unification to `task-530`.
- Workflow check: backend, test, and update-doc guidance are sufficient because this task is backend-only and this planning pass creates a canonical task-plan artifact.
- Missing tests or docs check: the plan now includes a focused manifest-boundary assertion so `NodeStartupInfo` capture is proven at the runtime request layer, plus the existing datapoint and `NodeMonitor` regression coverage and required research-note follow-up during implementation.
- Consistency check: the plan matches the PRD, task graph, task-500 research, and live code by preserving one muxed connection, raw capture before typed extraction, the manifest-driven protocol `3` request boundary in `TraceForwardSessionClient`, protocol-family-specific signal ownership, and the corrected `NodeAddBlock` semantics.

## Final Outcome

- Review-approved implementation promoted protocol `3` typed extraction into production with the smallest truthful seam: `TraceForwardProtocol3Extractor` now decodes shared raw datapoint snapshots into typed startup info plus the existing node-state fallback metrics.
- Production runtime now requests `NodeStartupInfo` through the live manifest path (`NodeStateDataPointDecoder.REQUESTED_NAMES` -> `TraceForwardSessionClient`) so startup metadata can actually reach shared raw capture.
- `NodeMonitor` now reads protocol `3` fallback datapoints through the promoted extractor seam rather than calling the raw decoder directly, while preserving the protocol `1` and protocol `2` precedence introduced by tasks `520` and `521`.
- `NodeStats` and websocket payload contracts remained unchanged.
- Focused verification passed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Durable research update recorded at `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`.
