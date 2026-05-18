Planner: Iteration 1
Timestamp: 2026-05-18T01:21:20Z

Task: task-540 - Remove transitional session clients and stale test assumptions
Action: created the canonical task plan at .agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md
Docs/workflows consulted:
- .agent/readme.md
- .agent/system/architecture.md
- .agent/workflows/backend.md
- .agent/workflows/test.md
- .agent/workflows/update-doc.md
Task sources consulted:
- .agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md
- .agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json
- .agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md
- .agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md
- .agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md
- .agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md
Code-index usage:
- set project path and rebuilt deep index before planning
- used code-index summaries and searches first to locate the remaining transport seam and current tracing test surfaces
- verified the material findings against live files before writing the plan
Key findings:
- the accepted production behavior is already the final single-connection design
- the clearest remaining transport-shaped seam is src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt, which already runs protocols 1/2/3 on one muxed connection but still exposes transitional session-centric naming and test indirection
- src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt and one path in TracingBlockMessageSinkTest.kt still stub that session-client seam directly
- src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt still contains probe names like liveTraceObjectsSessionToGld and liveDataPointSessionToGld that read like sanctioned runtime topology rather than diagnostic probes
Planned implementation shape:
- keep transport behavior unchanged unless a smaller cleanup requires tiny constructor rewiring
- remove, inline, or rename the stale TraceForwardSessionClient seam so the code reads as unified connection ownership rather than generic session ownership
- update focused tests to preserve reconnect, teardown, eligibility, and unified ingress coverage without implying sibling or per-family sessions
- clean up active live-test naming or redundant probes so the single-connection harness remains the architectural truth
- update PRD/tasks status notes only if implementation meaningfully changes the current implementation-baseline wording
Expected files to change:
- .agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md
- src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt
- src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt
- .agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md
- .agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json
Verification plan:
- focused backend tests:
  - ./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" -x buildVue -x testVue
- include the narrowest LiveTraceForwardIntegrationTest subset only if helper or naming cleanup touches that surface materially
Docs/tracking/research updates required:
- maintain the canonical plan doc as source of truth
- on implementation completion, update the PRD and tasks JSON for task-540 status/outcome
- no new research note unless the cleanup changes the durable transport boundary beyond naming or seam reduction
Planning status: approved
Build status: not started
Self-review:
- kept the plan narrow to cleanup, naming truth, and focused tests
- avoided widening into protocol semantics, dashboard logic, or another transport redesign
- explicitly recorded code-index use because it materially shaped the narrower task boundary
Outcome: Plan drafted and ready for critique

Critiquer: Iteration 1
Timestamp: 2026-05-18T01:22:56Z

Task: task-540 - Remove transitional session clients and stale test assumptions

Blocking findings:
- The plan under-specifies verification for the only production transport seam it proposes to rename/remove. In task-540.md the cleanup is framed as mostly naming/test truth, but the required verification set (TracingConnectionManagerTest, TracingBlockMessageSinkTest, TraceForwardAdoptedBlockDecoderTest; lines 193-199) does not prove that the surviving socket-backed runner still performs the accepted one outer handshake plus concurrent protocol 1/2/3 capture after the seam change. Live repo check: src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt:49-80 is the unified runner being targeted; src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt:118-177 only proves a protocol-2 trace-object request path; and the live probes in src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt:432-525 are optional in the current plan. For this task, that is a real gap: task-540 can otherwise "pass" while regressing the final single-connection architecture or quietly preserving a protocol-family-specific helper. The plan needs an explicit requirement to keep or add one transport-truth check for the surviving unified runner, either fixture-backed or via a mandatory narrow live captureAllProtocols-style subset when the transport seam changes.

Non-blocking observations:
- The scope is otherwise appropriately narrow and does not drift back into protocol semantics, dashboard precedence, or block-persistence redesign.
- The plan correctly treats TraceForwardSessionClient as likely stale naming/seam rather than evidence that sibling sessions still exist in production.
- Conditional PRD/tasks updates are reasonable for this cleanup task; no broader doc rewrite is needed unless the durable transport boundary wording actually changes.

Approval bar:
- Revise the verification plan so any rename/removal of TraceForwardSessionClient must prove the surviving boundary still does one socket, one handshake, and concurrent protocol 1/2/3 capture.
- Keep the rest of the task narrow: no new transport abstraction, no consumer-owned transport, no protocol-3-only fallback architecture, and no widening into task-541 live-sync work beyond the minimal transport proof above.

Decision: requires_changes
Outcome: Plan needs one final revision before implementation

Planner: Iteration 2
Timestamp: 2026-05-18T01:23:49Z

Task: task-540 - Remove transitional session clients and stale test assumptions
Action: revised the canonical task plan at .agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md to apply the planning-review blocker exactly once
Review input applied:
- .agent/plans/cardano-node-jormanager-tracing/task-plans/task-540-plan-review.md
Revision summary:
- added an explicit in-scope requirement to keep or add one transport-truth proof for any surviving renamed or reduced seam
- updated the implementation approach to preserve one explicit unified-runner proof after seam cleanup
- added a new acceptance criterion requiring post-cleanup verification of one socket, one forwarding handshake, and concurrent protocol 1/2/3 capture
- tightened the verification plan so seam cleanup must prove unified transport truth either:
  - preferably with a focused fixture-backed backend test near the transport seam or TracingConnectionManager, or
  - only if that is no longer truthful, with the narrow live subset LiveTraceForwardIntegrationTest.liveSingleConnectionThreeProtocolCaptureToClockwork
- clarified the validation boundary so task-540 still stays narrow and does not widen into task-541 except for that minimal transport proof if needed
Constraints preserved:
- no extra transport abstraction
- no consumer-owned transport
- no protocol-3-only fallback architecture
- no widening into task-541 beyond the minimal transport proof above
Planning status: approved
Build status: not started
Self-review:
- revised only the missing transport-proof requirement and left the rest of the task boundary intact
- kept fixture-backed proof as the preferred path and limited live verification to the existing narrow captureAllProtocols(...) subset only when necessary
- preserved the final single-connection architecture as the sole transport truth for implementation and verification
Outcome: Plan revised and finalized for build

