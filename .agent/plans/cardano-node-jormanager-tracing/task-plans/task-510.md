# Task 510 Plan

## Summary

- Task ID: `task-510`
- Title: `Replace runtime sibling sessions with one muxed tracing session per core node`
- Why now: `task-500` and `task-501` established the final single-connection architecture, `task-511` is already completed downstream of that design, and `task-510` is the next unblocked pending task on the authoritative critical path that still needs a canonical plan and a truthful completion boundary
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Finish and harden the runtime topology so production tracing is truthfully owned by one muxed outbound session per eligible core node, with one forwarding handshake and concurrent protocol `1`, `2`, and `3` loops on the same transport, while also aligning live eligibility and legacy backfill behavior with the PRD's core-node-only tracing rule, without widening into typed extraction, dashboard semantics, or block-validation behavior.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md` as the canonical task plan
- keep `TracingConnectionManager` as the only production owner of per-node tracing transport lifecycle
- keep `TraceForwardSessionClient` as one socket, one handshake, and one muxed execution of protocols `1`, `2`, and `3`
- remove or retire stale sibling-session runtime code that still implies protocol-family-specific transport ownership, especially `DataPointSessionClient`
- tighten manager and session tests so reconnect, teardown, and eligibility rules are explicitly pinned to the one-session topology
- make runtime eligibility truthful to the PRD by rejecting relay nodes in `TracingConnectionManager` even if they still have a tracing port
- remove the stale `promPort + 1` tracing-port backfill assumption from `NodeMonitor` for relays and any other non-core legacy node rows
- preserve the rule that JorManager captures raw protocol-family data before downstream typed extraction or consumer-specific filtering

Out of scope:
- new typed decoding for protocol `1`, `2`, or `3`
- new `NodeMonitor`, `BlockMonitor`, or dashboard feature behavior beyond keeping them downstream of tracing-runtime-owned transport and cleaning up stale relay tracing eligibility assumptions
- journald, persistence, replay, or historical recovery
- protocol-`3`-only dashboard transport, sibling per-family runtime sessions, or consumer-owned tracing transport
- config generation, node template rollout, or live cluster operations

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
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Used code-index first to inspect `BlockMonitor.kt`, locate tracing runtime files, and search for remaining `TraceForward`, `DataPoint`, and mux-related code before reading live files
- Re-verified all material findings against live files before planning because tracker state and runtime state are not perfectly aligned for this task
- Code-index materially helped confirm the relevant runtime surface is concentrated in `src/main/kotlin/com/swiftmako/jormanager/tracing/` plus `NodeMonitor` and tracing tests, not `BlockMonitor`

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - the task is a repo-local backend runtime cleanup or hardening step plus focused automated tests
  - the governing runtime architecture is already locked by repo-local research and current code
  - no manual live-node validation is required to claim this task complete if the final runtime shape and automated lifecycle tests are truthful

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-501`
- Important downstream alignment:
  - `task-511` is already completed and assumes the one-connection runtime boundary remains authoritative
  - `task-520`
  - `task-521`
  - `task-522`
  - `task-530`
  - `task-531`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - already opens one socket, performs one forwarding handshake, starts `ForwardingMetricsProtocol`, `ForwardingTraceObjectsProtocol`, and `ForwardingDataPointsProtocol`, and executes them together on one `Mux`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - already owns one-session transport lifecycle, reconnect behavior, and teardown through one `TraceForwardSessionClient` per managed node, but its current eligibility filter still allows relays because it only excludes pools
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - already records protocol `1`, `2`, and `3` raw capture before passing protocol `2` batches downstream to block persistence
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already reads downstream raw capture and no longer owns direct tracing transport lifecycle, but startup backfill still derives `tracingPort = promPort + 1` for any non-pool legacy node, including relays
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - still exists as a separate protocol-`3` session client with its own socket and handshake and is the clearest stale sibling-session artifact left in production code
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - already covers seed, reconnect, teardown, eligibility, and unified ingress scenarios around the one-session manager shape
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - remains the strongest repo-local proof that the accepted topology is one connection, one handshake, and concurrent protocol `1`, `2`, and `3` loops
- Tracker versus live-repo truth:
  - although `task-510` is still marked `pending`, the core architecture is already mostly landed in code
  - the smallest truthful task completion is therefore not a fresh redesign; it is finishing and hardening the one-session runtime contract, removing stale sibling-session artifacts, aligning runtime eligibility and legacy backfill with core-node-only tracing, and leaving downstream consumers transport-agnostic

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - likely deletion as stale sibling-session transport code unless a narrow repo-local use is discovered during implementation
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - only if small cleanup is needed to make the unified ownership contract clearer or remove duplication left by `DataPointSessionClient`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - small eligibility cleanup so only core nodes remain tracing-eligible at runtime while retiring sibling-session assumptions
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - small legacy cleanup so tracing backfill no longer manufactures relay tracing ports from `promPort` adjacency
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - strengthen assertions around one-session ownership, reconnect restoration of all three protocol families, teardown behavior, and core-only eligibility
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - only if a small production-alignment assertion or fixture reuse materially improves task-510 verification
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
  - replace the stale relay-backfill expectation with assertions that relays are not auto-assigned tracing settings and pools are still cleared

## Implementation Approach

- Treat the accepted runtime topology as already decided and mostly implemented:
  - one outbound connection per eligible core node
  - one forwarding handshake per connection
  - protocols `1`, `2`, and `3` concurrent on the same muxed transport
  - raw capture recorded before typed extraction
- Prefer the smallest truthful implementation:
  - remove stale sibling-session production code instead of introducing another abstraction layer
  - keep transport ownership in `TracingConnectionManager` plus `TraceForwardSessionClient`
  - do not move socket lifecycle into `NodeMonitor`, block persistence, or any future typed extractor
- Bring runtime eligibility back to PRD truth as part of task completion:
  - treat only core nodes as tracing-eligible in production runtime ownership
  - do not allow relay nodes to stay tracing-eligible merely because legacy rows still contain `tracingPort`
  - do not keep or introduce backfill logic that infers tracing ports from `promPort` adjacency for relays or other non-core nodes
- Keep downstream boundaries explicit:
  - `TracingRawCaptureService` remains the first visibility point for incoming protocol-family data
  - `TracingBlockMessageSink`, `NodeMonitor`, and later typed extractors consume data downstream of that runtime-owned ingress
- Reject invalid runtime shapes explicitly during implementation:
  - no separate runtime socket per protocol family
  - no protocol-`3`-only dashboard transport model
  - no consumer-owned tracing transport lifecycle
- If `DataPointSessionClient` has no remaining production use, delete it and update any affected tests instead of preserving dead transport code for compatibility

## Acceptance Criteria

- Production tracing runtime uses one socket per eligible core node.
- That socket performs one forwarding handshake and runs protocol `1`, `2`, and `3` concurrently on the same muxed transport.
- Production code no longer depends on protocol-family-specific sibling session clients for runtime tracing transport.
- `DataPointSessionClient` or any equivalent stale sibling-session runtime transport is removed or made provably unreachable from production runtime ownership.
- `TracingConnectionManager` remains the only production owner of tracing transport lifecycle for managed nodes.
- Runtime tracing eligibility matches the PRD's core-node-only rule instead of the current non-pool shortcut.
- `NodeMonitor` no longer backfills tracing host or tracing port onto relays from `promPort + 1`, and pool cleanup behavior remains intact.
- Raw protocol-family data is captured by JorManager before typed extraction or consumer-specific handling.
- Downstream consumers do not own tracing transport lifecycle.
- Reconnect behavior restores full three-protocol capture after disconnect.
- Node teardown cleans up the single mux session and clears per-node raw capture.

## Verification Plan

- Static verification:
  - confirm production tracing transport ownership is centralized in `TracingConnectionManager` plus `TraceForwardSessionClient`
  - confirm no production caller still depends on `DataPointSessionClient` or any equivalent protocol-family-specific sibling session transport
  - confirm runtime tracing eligibility excludes relays as well as pools, matching the PRD's core-node-only forwarding rule
  - confirm `NodeMonitor` and block-related consumers remain downstream of `TracingRawCaptureService` and do not open their own tracing sessions
  - confirm legacy tracing-setting backfill no longer derives tracing ports from `promPort` adjacency for relays or other non-core nodes
  - confirm protocol `1`, `2`, and `3` are still requested from the same muxed session path
- Automated verification:
  - run `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
  - if sibling-session cleanup touches raw-capture ingress behavior, also run `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" -x buildVue -x testVue`
  - if session cleanup touches the live harness seam, run the narrowest affected tracing test class or fixture-backed tests only, still skipping frontend tasks
- Truthful validation boundary:
  - fixture-backed and unit-level tracing tests are sufficient for this task
  - optional live-node tracing tests may provide extra confidence, but they are not required to claim task-510 complete

## Risks And Open Questions

- Main risk is scope creep into typed extraction or consumer migration work that belongs to `task-520` through `task-531`.
- Another risk is preserving dead sibling-session code because it looks harmless, leaving the repo architecture less truthful than the actual runtime.
- Another risk is declaring task-510 complete while relays still remain runtime-eligible or still get synthetic tracing ports from legacy backfill, which would contradict the PRD's core-node-only tracing boundary.
- Another risk is over-editing already-correct runtime code when the real remaining work is contract cleanup plus stronger tests.
- Open question to confirm during implementation:
  - whether `DataPointSessionClient` is fully unused and can be deleted outright, or whether one narrow non-production test seam still needs replacement first

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`.
- Do not edit `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510-plan-review.md` or `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510-impl-review.md` during this planning pass.
- Do not update the PRD or tasks JSON during planning-only work.
- No new research note is expected; add one only if implementation uncovers a durable runtime-boundary decision not already captured by task-500 research.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on runtime topology truth, stale sibling-session cleanup, and focused lifecycle tests rather than widening into typed extraction or monitor semantics.
- Workflow check: `backend`, `test`, and `update-doc` guidance are sufficient for this task because the expected work is backend runtime code plus a canonical task-plan update.
- Consistency check: the plan matches the PRD and task-500 research by rejecting sibling per-family sessions, protocol-`3`-only transport, and consumer-owned tracing transport.
- Tracker-truth check: the plan explicitly acknowledges that the one-session runtime is already mostly present in code and therefore frames task-510 as completion and hardening work, not a greenfield build.

## Final Outcome

- Result: `completed`
- Final implementation summary:
  - enforced the PRD's core-only tracing transport boundary in `TracingConnectionManager` by rejecting non-core nodes even when legacy rows still contain tracing ports
  - tightened `NodeMonitor` startup backfill and active monitoring eligibility to core nodes only, removing the stale relay `promPort + 1` tracing-port assumption while preserving pool cleanup behavior
  - deleted the unused sibling-session transport file `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - updated focused tracing and monitor tests so relays are rejected for transport ownership and legacy backfill now applies only to core nodes
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Final review result:
  - implementation review iteration 1 approved with no blocking findings
  - approved review entry recorded in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510-impl-review.md`
- Research outcome:
  - no new research
