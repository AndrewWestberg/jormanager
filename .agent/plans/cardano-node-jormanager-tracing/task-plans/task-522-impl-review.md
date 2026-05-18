Implementation: Iteration 1
Timestamp: 2026-05-18T00:43:06Z

Changes made:
- widened the production protocol-3 requested datapoint manifest to include NodeStartupInfo so shared raw capture can receive startup metadata at runtime
- refactored NodeStateDataPointDecoder just enough to expose shared datapoint-map parsing and startup-info parsing, while preserving existing scalar fallback and NodeAddBlock semantics
- added TraceForwardProtocol3Extractor as the explicit production typed extraction seam for protocol-3 node-state metrics plus startup info
- swapped NodeMonitor from direct raw decoder usage to the new protocol-3 extractor seam without changing its existing precedence model
- added focused tests for protocol-3 typed extraction, manifest coverage, and NodeMonitor fallback behavior through the promoted seam

Files touched:
- src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt
- src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt
- src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3ExtractorTest.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt
- src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt

Verification run:
- ./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue
- result: passed
- note: Gradle printed a non-fatal KSP AWT-thread NullPointerException before tasks completed, but compilation and the requested tests still finished successfully

Deviations from approved plan:
- none; implementation stayed within the planned manifest update, protocol-3 extractor promotion, minimal NodeMonitor seam swap, and focused test coverage

User interaction required:
- no

Outcome: implementation iteration 1 completed with focused backend verification passing and no user handoff required.

Code Review: Iteration 1
Timestamp: 2026-05-18T00:44:47Z

Blocking findings:
- Missing required task documentation follow-through. The implementation lands the protocol-3 extractor and manifest change in code, but it does not add the narrow research update the approved task plan explicitly requires for implementation completion (\.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md:248-254). Live repo inspection still shows no new task-522 research note under \.agent/plans/cardano-node-jormanager-tracing/research/, and grep only finds existing NodeStartupInfo references in the older task-500 note. Given the review brief and update-doc workflow requirement that canonical task docs and research updates are part of completion, this is still a blocker.

Non-blocking observations:
- The code change itself stays narrow and aligned with the approved plan: NodeStateDataPointDecoder.kt, TraceForwardProtocol3Extractor.kt, and NodeMonitor.kt keep protocol 3 downstream of shared raw capture and do not reintroduce sibling sessions, transport ownership, or task-530-style consumer redesign.
- The manifest boundary is satisfied correctly: NodeStartupInfo is now included in NodeStateDataPointDecoder.REQUESTED_NAMES and therefore in the production request path owned by TraceForwardSessionClient.kt:40-68.
- The corrected NodeAddBlock contract remains intact in NodeStateDataPointDecoder.kt:165-184; it is still treated only as epoch and slot-in-epoch fallback metadata, not block height.
- NodeMonitor.kt:342-358 preserves the existing precedence model from tasks 520 and 521: fresh protocol 1 metrics still win for overlapping fields, protocol 2 still supplies peer counters when available, and protocol 3 remains a fallback contributor.
- Focused verification is in place and passes. The new extractor test covers startup-info plus node-state decode, the fixtures test covers manifest inclusion, and NodeMonitorTest.kt:324-345 proves the fallback path still works through the promoted seam.
- Residual test rigor gap is acceptable for this task: TraceForwardFixturesTest.kt:13-29 proves the manifest content, but the request fixture is still generator-backed rather than anchored to a new literal hex artifact. That is not a regression from this change, just a remaining sharp edge if future request-shape drift needs stricter protocol anchoring.

Approval bar:
- Add the required task-522 research/doc update under \.agent/plans/cardano-node-jormanager-tracing/research/ and record the production protocol-3 promotion boundary, including:
  - NodeStartupInfo now being part of the runtime request manifest and shared raw capture path
  - the explicit production TraceForwardProtocol3Extractor seam
  - the preserved NodeAddBlock non-block-height contract
- Keep the update narrow; no further production code changes are needed unless that documentation pass uncovers a real mismatch.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-18T00:45:30Z

Changes made:
- added the required task-522 research note documenting the accepted production protocol-3 extraction boundary, runtime request-manifest change for NodeStartupInfo, the explicit TraceForwardProtocol3Extractor seam, and the preserved NodeAddBlock contract
- updated the canonical task plan with final outcome and successful verification details
- updated the tracing task tracker to mark task-522 completed with completion notes

Files touched:
- .agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md
- .agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md
- .agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json

Verification run:
- no new code verification required; this iteration only satisfied the previously requested documentation/research follow-through
- prior focused backend verification from iteration 1 remains the task's code-validation evidence and already passed

Deviations from approved plan:
- none; this iteration closes the explicit research and tracking updates already required by the approved plan

User interaction required:
- no

Outcome: implementation iteration 2 completed the required research and tracker updates so the task is ready for final review.

Code Review: Iteration 2
Timestamp: 2026-05-18T00:46:21Z

Blocking findings:
- None.

Non-blocking observations:
- The prior blocker is resolved: the required research follow-through now exists and accurately records the production protocol-3 boundary, including the NodeStartupInfo request-manifest path, the TraceForwardProtocol3Extractor seam, and the preserved NodeAddBlock contract (\.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md:9-16).
- The canonical task artifact and tracker are now aligned with the implementation outcome (\.agent/plans/cardano-node-jormanager-tracing/task-plans/task-522.md:270-276, \.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json:226-252).
- Re-review of the live code diff did not surface a new regression from the iteration-2 follow-up; the production manifest, extractor seam, and focused tests still line up cleanly (src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt:95-107, src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt:8-26, src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt:24-27).

Approval bar:
- None.

Decision: approved

