# Task 530 Plan

## Summary

- Task ID: `task-530`
- Title: `Refactor NodeMonitor to consume the unified tracing signal inventory`
- Why now: `task-520`, `task-521`, and `task-522` already promoted the production protocol `1`/`2`/`3` typed extraction seams, and the live repo shows `NodeMonitor` is already downstream of unified raw capture. The remaining truthful gap is not transport or protocol discovery; it is that `NodeMonitor` still owns protocol-family-specific snapshot assembly and precedence logic directly instead of consuming one tracing-side dashboard signal seam.
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Converge `NodeMonitor` into a thin lifecycle and publishing monitor that reads one shared tracing-side dashboard snapshot seam, while keeping the existing one-connection raw-capture architecture and current `NodeStats` contract intact.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md` as the canonical task plan
- add the minimum shared tracing-side dashboard signal service or equivalent seam under `src/main/kotlin/com/swiftmako/jormanager/tracing/` that assembles current node-state metrics from fresh protocol `1`/`2`/`3` capture
- move the current protocol-family-specific decode and precedence logic out of `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- preserve the currently proven precedence and fallback behavior already encoded in the live repo:
- protocol `1` wins for the overlapping dashboard subset only when the fresh metric snapshot is complete
- protocol `2` remains the authoritative peer-counter source when fresh and still supplies the current `AddedToCurrentChain` chain fallback
- protocol `3` remains the node-state fallback contributor and keeps startup metadata available through shared tracing code without widening the dashboard payload in this task
- keep `NodeMonitor` responsible for node eligibility, sampling cadence, websocket batching, and `latestNodeStats` updates
- add focused automated coverage for the new shared dashboard signal seam and adapt `NodeMonitorTest` only as much as needed to pin the narrower monitor boundary

Out of scope:
- changing `TracingConnectionManager`, `TraceForwardSessionClient`, mux topology, or any transport ownership
- changing `BlockMonitor`, tracing block persistence, or block validation flow
- changing `NodeStats` fields, websocket payload shape, or frontend consumers
- widening startup metadata into the dashboard payload or replacing genesis-file-derived `epochLength` just because protocol `3` startup info exists
- removing legacy tracing backfill or pool cleanup behavior from `NodeMonitor` unless a tiny relocation is required to complete the refactor safely
- database, Liquibase, node-creation, config-generation, or remote-host changes

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
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-300-blockmonitor-boundary.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md` did not exist before this planning pass

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Rebuilt the deep index before planning and used code-index first to locate `NodeMonitor`, `BlockMonitor`, the tracing package, and the tracing or monitor test surfaces
- Re-verified all material findings against live files before writing the plan
- Code-index materially confirmed that `NodeMonitor` already consumes unified tracing runtime data indirectly; the remaining task is consumer convergence and seam cleanup, not new tracing transport or protocol implementation

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this task is a repo-local backend refactor plus focused tests
  - the governing protocol ownership and runtime topology are already pinned by completed tasks and repo-local research
  - no live cluster, remote host, journald, or operator-owned validation step is required to implement the narrower `NodeMonitor` convergence truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-520`
  - `task-521`
  - `task-522`
- Important already-landed adjacent work:
  - `task-510`
  - `task-511`
- Important downstream alignment:
  - `task-531`
  - `task-540`
  - `task-541`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/utils/BlockUtils.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3ExtractorTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoderTest.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already depends on shared raw capture and the promoted protocol `1`/`2`/`3` extractors instead of opening its own tracing sessions
  - still directly owns the protocol-family merge and precedence logic inside `loadNodeStateMetrics(...)`
  - still directly depends on `TracingRawCaptureService`, `TracingMetricDecoder`, `TraceForwardProtocol2Extractor`, and `TraceForwardProtocol3Extractor`, which is the truthful remaining convergence gap for task-530
  - still owns websocket batching, `latestNodeStats` updates, node filtering, and lifecycle behavior that should remain local to the monitor
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - already pins the current precedence rules and fallback behavior:
  - fresh and complete protocol `1` metrics win for overlapping dashboard fields
  - protocol `2` peer counters override protocol `3` peer values when fresh
  - stale protocol `2` batches do not keep feeding peer or chain fallback
  - legacy tracing backfill behavior is still tested separately inside `NodeMonitorTest`
  - this means task-530 should preserve current observable outcomes while narrowing the monitor boundary rather than changing dashboard semantics
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already owns freshness-gated access to protocol `1`, `2`, and `3` raw capture
  - must remain the only raw snapshot owner; task-530 should not add another cache or polling layer
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt`
  - already exposes the task-520 protocol `1` production decode truth and the current complete-subset requirement for `NodeStateMetrics`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
  - already owns the task-521 protocol `2` production decode truth for peer counters, `AddedToCurrentChain` fallback state, and block events
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt`
  - already owns the task-522 protocol `3` typed extraction seam for fallback node-state datapoints plus startup info
- `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - current dashboard contract remains compact and unchanged: peers, incomingPeers, blockHeight, remainingKESPeriods, epoch, slot, slotInEpoch, txsProcessed, and epochLength
  - task-530 should preserve this contract and not widen it to expose startup metadata
- `src/main/kotlin/com/swiftmako/jormanager/controllers/utils/BlockUtils.kt`
  - still depends on `latestNodeStats` carrying stable `epoch`, `slot`, and `slotInEpoch` semantics
  - this makes task-530 a behavior-preservation refactor, not a semantic rewrite
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - already has the narrowed tracing-era role pinned by task-300 research and should remain unchanged here
- Tracker versus live-repo truth:
  - the task graph still frames task-530 as a broad `NodeMonitor` migration, but the live repo shows most of that migration already landed under tasks `520` through `522`
  - the truthful remaining work is to centralize unified dashboard signal assembly behind one tracing-side seam and make `NodeMonitor` consume that seam directly

## Convergence Boundary

- Task-530 should establish one shared tracing-side dashboard signal seam downstream of `TracingRawCaptureService`.
- The new seam should be the only owner of protocol-family-specific dashboard assembly for the current `NodeStats` contract.
- `NodeMonitor` should no longer directly read fresh raw snapshots or call the protocol `1`/`2`/`3` extractors itself.
- The shared seam should preserve the current live precedence exactly unless a bug is found and fixed with tests:
  - use protocol `1` for `blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed` only when the fresh metric snapshot satisfies the full overlapping subset
  - use protocol `2` connection counters for `peers` and `incomingPeers` when fresh
  - fall back to protocol `3` node-state datapoints when protocol `1` is stale or incomplete
  - retain the current protocol `2` `AddedToCurrentChain` chain fallback when no fresh complete protocol `1` or protocol `3` snapshot can satisfy slot and block-height state
- Startup metadata from protocol `3` should remain available through shared tracing code, but task-530 should not widen `NodeStats` or replace genesis-file-derived `epochLength` unless a small correctness bug makes that unavoidable.
- The shared seam should return the narrowest useful model for `NodeMonitor`, ideally `NodeStateMetrics?` or an equivalent internal snapshot type, so `NodeMonitor` can keep null fallback creation and websocket publication logic without duplicating tracing assembly.
- Task-530 should not introduce a second transport owner, a second freshness policy, or another long-lived runtime topology.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - narrow the monitor so it consumes one shared tracing-side dashboard signal seam instead of assembling protocol `1`/`2`/`3` snapshots directly
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - add the smallest shared dashboard signal service or equivalent seam needed for unified node-state assembly
  - make only minimal extractor or helper adjustments if an existing seam needs a slightly different entry point
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - keep the current behavior coverage while adapting the test surface to the narrower monitor boundary
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - add focused tests for the new shared dashboard signal seam and its precedence rules

## Implementation Approach

- Prefer the smallest truthful convergence step:
  - add one tracing-side service or equivalent helper that reads fresh raw capture and applies the already-accepted precedence rules
  - inject that seam into `NodeMonitor`
  - keep `NodeMonitor` focused on lifecycle, sampling, node eligibility, null fallback generation, websocket publication, and `latestNodeStats`
- Reuse existing production extractors rather than creating a new abstraction layer per protocol family.
- Keep the service downstream of `TracingRawCaptureService` so freshness and invalidation continue to come from one place.
- Preserve current fallback semantics rather than designing a new per-field merge model.
- Keep `epochLength` handling stable:
  - continue resolving it from the genesis file unless a small clearly-correct fix is required
  - do not surface protocol `3` startup info into `NodeStats` in this task
- Avoid cleanup that belongs later:
  - do not rewrite transport code
  - do not change block-monitoring consumers
  - do not opportunistically remove the legacy tracing backfill tests or behavior unless the refactor naturally requires moving that code with equivalent coverage

## Acceptance Criteria

- `NodeMonitor` consumes one shared tracing-side dashboard signal seam instead of directly depending on `TracingRawCaptureService`, `TracingMetricDecoder`, `TraceForwardProtocol2Extractor`, and `TraceForwardProtocol3Extractor`.
- The new shared seam remains downstream of `TracingRawCaptureService` and does not own transport, reconnect behavior, or a second raw-cache lifecycle.
- Dashboard fields continue to come from the live-proven protocol family for each signal under the current precedence rules.
- Fresh and complete protocol `1` metrics still win for the overlapping dashboard subset.
- Fresh protocol `2` connection counters still provide `peers` and `incomingPeers`, and stale protocol `2` batches do not keep feeding dashboard state.
- Protocol `2` `AddedToCurrentChain` fallback remains available when fresher protocol `1` or protocol `3` snapshots cannot provide `slot` and `blockHeight`.
- Protocol `3` remains the node-state fallback contributor and startup metadata remains available in shared tracing code without widening `NodeStats`.
- `NodeStats` and websocket payload contracts remain unchanged.
- `latestNodeStats` semantics used by downstream code remain unchanged.
- No new polling loop, protocol-family-specific transport, or tracing cache owner is introduced.

## Verification Plan

- Static verification:
  - confirm `NodeMonitor` no longer directly depends on the raw capture service or the protocol `1`/`2`/`3` extractors for dashboard assembly
  - confirm the new shared dashboard signal seam lives under `tracing/` and remains downstream of `TracingRawCaptureService`
  - confirm `NodeStats.kt`, `BlockMonitor.kt`, and transport classes stay unchanged unless a small compatibility adjustment is truly required
  - confirm current `latestNodeStats` update behavior is preserved
- Automated verification:
  - add focused tests for the new tracing-side dashboard signal seam that prove:
  - fresh complete protocol `1` metrics produce the overlapping dashboard subset
  - protocol `2` connection counters override protocol `3` peer values when fresh
  - stale protocol `2` batches do not keep supplying peer counters or chain fallback
  - stale or incomplete protocol `1` metrics fall back to protocol `3`
  - protocol `2` `AddedToCurrentChain` still supplies chain fallback when fresher complete snapshots are absent
  - update `NodeMonitorTest` so the current websocket, null-fallback, default-node, and legacy tracing backfill behavior still pass after the seam extraction
  - run the narrowest relevant backend targets, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" -x buildVue -x testVue`
  - if implementation requires touching existing extractor entry points, extend the run to include only the affected extractor tests

## Risks And Open Questions

- Main risk is scope creep into `task-540` by trying to do broader transitional cleanup instead of only narrowing `NodeMonitor` around one shared dashboard seam.
- Another risk is accidentally changing precedence behavior while moving logic out of `NodeMonitor`; the existing test inventory should be preserved and supplemented rather than replaced.
- Another risk is widening the dashboard contract just because startup metadata is now available through protocol `3`; this task should not do that.
- Another risk is inventing a new cache or partial-merge model that conflicts with the freshness and invalidation behavior already pinned in task-511 research.
- Open implementation choice to resolve minimally during coding:
  - whether the smallest truthful seam returns `NodeStateMetrics?` directly or a slightly richer internal snapshot model that `NodeMonitor` maps to `NodeStats` without changing the external contract

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-plan-review.md` during this planning pass.
- Planning-only work should not update the PRD or tasks JSON; implementation completion must sync tracker and PRD state.
- Add or update a narrow research note during implementation if task-530 changes the accepted dashboard signal assembly boundary in a way that future tasks need to reference explicitly.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-impl-review.md`

## Self-Review

- Scope creep check: the plan keeps task-530 on `NodeMonitor` consumer convergence and explicitly avoids transport, block persistence, config generation, or dashboard contract expansion.
- Workflow check: backend and test workflows are the only truthful workflow inputs for this backend-only refactor plan.
- Missing tests or docs check: the plan adds focused service-level precedence tests while retaining `NodeMonitorTest` coverage for observable behavior and existing legacy backfill expectations.
- Consistency check: the plan matches the PRD, task-500 research, task-520 through task-522 durable findings, and the live repo by treating task-530 as a seam-convergence refactor rather than a first-time migration away from protocol-family-specific sessions.

## Final Outcome

- Result: completed and implementation-review approved.
- Final implementation summary:
  - added `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt` as the tracing-side seam that assembles dashboard node-state signals from fresh protocol `1`/`2`/`3` capture without owning transport or caching
  - narrowed `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` so it consumes that shared seam instead of directly reading raw capture and protocol extractors
  - preserved startup metadata availability through the tracing seam without widening `NodeStats`
  - fixed a live precedence gap discovered during verification so fresh protocol `2` counters override protocol `3` peer values even when protocol `1` metrics are absent
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" -x buildVue -x testVue`
- Review results:
  - planning review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-plan-review.md`
  - implementation review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-impl-review.md`
- Research outcome:
  - recorded durable dashboard-signal seam and precedence findings in `.agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md`
