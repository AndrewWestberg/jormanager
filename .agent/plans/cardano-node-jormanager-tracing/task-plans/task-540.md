# Task 540 Plan

## Summary

- Task ID: `task-540`
- Title: `Remove transitional session clients and stale test assumptions`
- Why now: `task-530` and `task-531` completed the real consumer migration onto shared raw capture plus typed extraction, so the remaining truthful gap is cleanup: the repo still carries a `TraceForwardSessionClient` transport seam and several tests or live probes whose naming and setup continue to frame the unified transport as session-centric rather than the accepted single-connection architecture from `task-500`.
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Remove or simplify the remaining runtime and test seams that existed to make the earlier transition easier, while preserving the already accepted one-connection-per-core-node production behavior.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md` as the canonical task plan
- verify whether `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt` is still a meaningful production boundary or now only a stale naming and test seam
- apply the smallest truthful cleanup to production tracing transport ownership in `TracingConnectionManager` and the tracing package so the live architecture reads as one connection manager owning one muxed trace-forward connection per node, not a generic session-client topology
- update focused backend tests that currently stub or describe the old session-client seam directly so they assert unified connection lifecycle, reconnect, teardown, and shared ingress behavior without implying sibling or per-family sessions
- keep or add one explicit transport-truth proof for the surviving seam so any rename or removal still proves one socket, one forwarding handshake, and concurrent protocol `1`/`2`/`3` capture
- clean up or retitle stale live or fixture-backed tests whose names still advertise separate trace-object or datapoint sessions as if they were an accepted runtime target
- keep the single-connection live harness as the architectural truth and preserve all current protocol `1`/`2`/`3` capture behavior
- update active task-tracking and PRD status notes if implementation changes the current implementation-baseline wording

Out of scope:
- changing protocol semantics, raw-capture freshness policy, dashboard precedence, or block-persistence behavior already pinned by `task-511`, `task-521`, `task-522`, `task-530`, and `task-531`
- introducing a second tracing transport abstraction or a larger tracing-package redesign just to remove one stale name
- rewriting historical completed task plans purely for wording cleanup unless a still-active artifact would otherwise mislead future work
- live cluster experimentation beyond the focused verification already required by the task graph
- frontend, database, node-creation, or config-generation work

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
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-531.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Built a fresh deep index before planning and used code-index first to locate the current tracing transport, monitor, and tracing-test seams
- Re-verified all material findings against live files before writing the plan
- Code-index materially confirmed that the only obvious remaining transport-shaped abstraction in production is `TraceForwardSessionClient` and that its factory seam is used mainly by `TracingConnectionManager` tests plus one sink test, which makes task-540 a narrow convergence and test-truth task rather than another broad runtime migration

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - this task is a repo-local backend cleanup plus focused tests and tracking updates
  - the accepted single-connection architecture is already pinned by task-500 research and completed implementation work
  - no operator action, remote system change, or architecture choice from the user is needed to complete the cleanup truthfully

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-530`
  - `task-531`
- Important already-landed adjacent work:
  - `task-500`
  - `task-510`
  - `task-511`
  - `task-520`
  - `task-521`
  - `task-522`
- Important downstream alignment:
  - `task-541`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - already owns the accepted production lifecycle: one managed tracing connection per eligible core node, reconnect loop, teardown, and raw-capture invalidation
  - still depends on `TraceForwardSessionClientFactory` and `TraceForwardSessionClient`, which is now the clearest remaining transport seam whose name and test shape still reflect the transitional era more than the final architecture
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - no longer represents sibling per-family sessions; it already opens one socket, performs one forwarding handshake, and runs protocol `1`, `2`, and `3` concurrently on one mux
  - the stale part is therefore primarily the abstraction and naming contract, not the core transport behavior itself
  - task-540 should either remove this seam or rename and narrow it so the code reads as a unified connection runner rather than a generic session client
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - strongly depends on inline `TraceForwardSessionClient` test doubles for reconnect and ingress behavior
  - should keep its behavioral coverage but stop presenting the transport boundary as a generic session-client contract if production cleanup removes or renames that seam
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - contains one manager-driven test that also uses the `TraceForwardSessionClient` test seam to inject an adopted-block reply
  - should be updated minimally to keep manager-to-raw-capture-to-persistence coverage aligned with the final transport boundary
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - includes two socket-backed tests that currently use `SocketTraceForwardSessionClient` to pull a trace-object reply from a scripted server
  - decoder coverage should remain, but the helper path should stop reinforcing that the endorsed runtime unit is an adopted-block-focused session client if a smaller shared connection helper can express the same proof
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - already contains the enduring single-connection harness (`captureAllProtocols` and the unified live tests)
  - still also contains `liveTraceObjectsSessionToGld` and `liveDataPointSessionToGld`, which are useful protocol diagnostics but read like sanctioned runtime topology; task-540 should either remove them as redundant or rename and clearly demote them to protocol probes rather than architectural targets
- Tracker versus live-repo truth:
  - the PRD and tasks graph already describe task-540 as cleanup of transitional session clients and stale assumptions
  - the live repo confirms that this is now mostly naming, seam, and test-truth cleanup around the final architecture, not a new functional migration

## Convergence Boundary

- Production tracing transport should continue to mean exactly one muxed connection per eligible core node.
- After task-540, no production class or active test should suggest that protocol `2` trace objects, protocol `3` datapoints, or any tracing consumer own a separate authoritative runtime session.
- The cleanup should prefer the smallest truthful result:
  - if the current `TraceForwardSessionClient` abstraction is still useful after a rename that matches its actual role, keep the behavior and narrow the seam
  - if it is only test scaffolding plus stale naming, remove it and inject a smaller connection-opening seam directly where needed
- Keep the raw-capture boundary unchanged:
  - connection ownership in `TracingConnectionManager`
  - raw ingress in `TracingRawCaptureService`
  - downstream typed extraction and consumers unchanged
- Historical diagnostic tests may remain only if their names and comments no longer imply that per-family sessions are an accepted production architecture.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - smallest cleanup needed to remove or narrow the stale session-client abstraction while preserving lifecycle behavior
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - likely rename, simplification, inlining, or deletion depending on the smallest truthful implementation
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - adapt lifecycle and ingress tests to the final transport seam without losing reconnect coverage
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - update the manager-driven ingress test if production constructor wiring changes
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - keep decoder-level proof but stop anchoring it to a misleading session-client abstraction if that seam is retired
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - remove, rename, or clearly demote stale partial-session probes so active test naming matches the accepted architecture
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - update current implementation notes only if implementation meaningfully removes the last remaining session-client seam
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - mark `task-540` complete and record the actual cleanup outcome when implementation finishes

## Implementation Approach

- Start from live truth, not from historical naming:
  - the transport behavior is already correct
  - the remaining problem is that one abstraction and several tests still describe it with transitional session language
- Prefer the smallest production edit that improves architectural truth:
  - if a rename plus constructor cleanup is enough, do that instead of redesigning transport ownership
  - if the abstraction has become test-only indirection, inline or delete it rather than preserving dead terminology
- Preserve all current runtime invariants:
  - one socket per core node
  - one forwarding handshake
  - concurrent protocol `1`/`2`/`3` loops
  - reconnect after disconnect
  - teardown clears raw capture
- Keep test cleanup proportional:
  - retain reconnect, idle-connection, teardown, and shared-ingress coverage
  - retain or add one explicit unified-runner proof that the surviving socket-backed boundary still performs one handshake and concurrent protocol `1`/`2`/`3` capture after the seam cleanup
  - rename or replace tests that encode a stale architectural story
  - keep the single-connection live harness as the main integration truth
- Avoid widening into task-541 final-live-verification work unless implementation naturally requires one tiny naming follow-up in active plan artifacts

## Acceptance Criteria

- The production tracing runtime no longer exposes a stale sibling-session or generic session-client topology as an active design seam.
- `TracingConnectionManager` still owns one muxed tracing connection per eligible core node and preserves current reconnect plus teardown behavior.
- No production code path reintroduces separate per-family tracing transports.
- Focused tracing tests continue to cover reconnect, shutdown, eligibility, and unified ingress behavior after the cleanup.
- The post-cleanup verification set explicitly proves that the surviving transport seam still performs one socket connection, one forwarding handshake, and concurrent protocol `1`/`2`/`3` capture.
- Active tracing tests and live probes no longer frame separate trace-object or datapoint sessions as the accepted target architecture.
- Active planning artifacts remain aligned with the implemented single-connection design.

## Verification Plan

- Static verification:
  - confirm the surviving production transport seam, if any, clearly represents one unified connection rather than a generic session topology
  - confirm `TracingRawCaptureService`, `TracingDashboardSignalService`, `TraceForwardProtocol2Extractor`, `TraceForwardProtocol3Extractor`, and block persistence stay downstream and unchanged in responsibility
  - confirm no new transport abstraction, queue, or protocol-family-specific owner appears during cleanup
- Automated verification:
  - run focused tracing lifecycle tests, expected minimum shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" -x buildVue -x testVue`
  - if the surviving transport seam is renamed, inlined, or otherwise changed, keep or add one explicit transport-truth check with this acceptance target:
    - one socket connection is opened
    - one forwarding handshake is executed
    - protocol `1`, `2`, and `3` capture all run concurrently on that same muxed connection
  - preferred proof shape is a focused fixture-backed backend test near the transport seam or `TracingConnectionManager` coverage so task-540 stays narrow and does not widen into task-541
  - only if a minimal fixture-backed proof is not truthful after the seam cleanup, run the narrowest mandatory live subset that exercises the existing `captureAllProtocols(...)` path, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveSingleConnectionThreeProtocolCaptureToClockwork" -x buildVue -x testVue`
- Truthful validation boundary:
  - fixture-backed backend tests remain the default and preferred proof for this task
  - the narrow live `captureAllProtocols(...)` subset becomes mandatory only when the seam cleanup materially changes the surviving socket-backed runner and the fixture-backed proof can no longer demonstrate one socket, one handshake, and concurrent protocol `1`/`2`/`3` capture truthfully
  - if implementation changes durable understanding of the remaining transport seam beyond simple cleanup, record that in a short research note instead of leaving the decision only in code and task completion text

## Risks And Open Questions

- Main risk is over-cleanup: rewriting functioning transport code when a smaller rename or seam reduction would fully satisfy task-540.
- Another risk is under-cleanup: leaving the current abstraction and stale test names in place so later work still reads as if session topology is architecturally meaningful.
- Another risk is deleting useful low-level protocol diagnostics from `LiveTraceForwardIntegrationTest` when a rename or explicit probe framing would preserve value without architectural confusion.
- Open implementation choice to resolve minimally during coding:
  - whether `TraceForwardSessionClient` should be deleted outright, renamed to match unified connection semantics, or kept only as a much narrower testable connection opener

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-plan-review.md` during this planning pass.
- On implementation completion, update:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- Add a new research note only if implementation changes the durable transport boundary beyond simple naming or seam cleanup; otherwise `no new research` is appropriate.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on transport-seam truth, stale test assumptions, and active planning alignment rather than widening into another runtime redesign.
- Minimality check: the plan explicitly prefers rename, inlining, or seam reduction over functional transport churn.
- Verification check: the plan now requires one explicit unified-transport proof after any seam rename or removal, preferring fixture-backed coverage and allowing only the narrow existing live `captureAllProtocols(...)` subset when that is the smallest truthful proof.
- Consistency check: the plan matches the PRD, task graph, task-500 research, and the live repo by treating task-540 as the final cleanup pass after the architecture has already converged functionally.

## Final Outcome

- Result: completed.
- Final implementation summary:
  - Replaced the last active `TraceForwardSessionClient` seam with `TraceForwardConnectionRunner`, keeping `TracingConnectionManager` as the sole owner of one muxed tracing connection per eligible core node.
  - Updated active tracing tests and live probe names so they no longer advertise session-centric runtime topology.
  - Added a local socket-backed proof that the unified runner performs one handshake plus protocol `1`/`2`/`3` startup on a single connection, and aligned decoder tests with the final startup behavior.
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" -x buildVue -x testVue`
  - result: PASS
- Review results:
  - planning review completed via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-plan-review.md`
- implementation review approved via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-impl-review.md`
- Research outcome:
  - no new research
