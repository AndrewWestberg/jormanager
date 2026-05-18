Planner: Iteration 1
Timestamp: 2026-05-18T00:20:05Z

- Interaction mode classified: `autonomous`
- Code-index actions:
  - set project path to `/home/westbam/Development/jormanager`
  - ran `code-index_refresh_index`
  - ran `code-index_build_deep_index`
  - verified all material planning claims against live files after index use
- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Governing plan sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-202-tracing-block-persistence-bridge.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
- Existing task-plan docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-510.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md`
  - confirmed `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md` and `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md` did not exist before this pass
- Live code verified for truthful planning:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/mux/Mux.kt`
  - relevant tracing and monitor tests under `src/test/kotlin/com/swiftmako/jormanager/`
- Key planning findings:
  - transport and raw capture for protocol `2` are already production-owned by the unified tracing runtime
  - current production protocol `2` knowledge already exists, but is still split across `TraceForwardAdoptedBlockDecoder` and `TraceForwardNodeStateDecoder`
  - `NodeMonitor` already consumes protocol `2` only through shared fresh raw batches and mainly needs peer-counter preservation after task-520
  - `TracingBlockMessageSink` is the clearest remaining direct feature-specific protocol `2` decoder seam
  - the smallest truthful task is promoting a shared typed protocol `2` extraction layer downstream of raw capture, then rewiring `NodeMonitor` and block persistence to consume it with minimal behavior change
- Canonical task plan created:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
- Plan summary:
  - promote protocol `2` typed extraction for forged/adopted block events and connection counters into a shared production tracing layer
  - preserve forged=`created` and adopted=`completed` semantics
  - keep peer counters sourced from protocol `2`, not protocol `1`
  - reuse `TracingRawCaptureService.recentFreshTraceObjectBatches(...)`
  - keep consumer edits minimal: `NodeMonitor` and `TracingBlockMessageSink` switch to the shared extractor, while `TracingBlockPersistenceService` and `BlockMonitor` stay behaviorally unchanged
  - verify with focused tracing and monitor tests only
- Skills needed:
  - none
- Self-review:
  - scope-creep check passed: plan stays inside protocol `2` extraction promotion and avoids widening into `task-530` or `task-531`
  - stale-workflow-text check passed: only backend/test/update-doc workflows were relevant
  - missing-tests/docs check passed: focused tracing, block-sink, and `NodeMonitor` regression coverage are included
  - consistency check passed: plan matches PRD and task-500 by keeping protocol `2` as the truthful source for peer counters and block-event transitions

Critiquer: Iteration 1
Timestamp: 2026-05-18T00:22:18Z

Blocking findings:
- `task-521.md` treats `ChainDB.AddBlockEvent.AddedToCurrentChain` as optional (`if it remains...`, `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md:152-153,223`), but live production still depends on that signal to build the protocol-2 fallback snapshot in `NodeMonitor` (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:361-377`) and the current tests already pin that behavior (`src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt:219-252`; `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt:37-49`). The plan needs to make chain fallback an explicit preserved protocol-2 responsibility, not a maybe.
- The plan leaves an unresolved contract conflict around forged block hashes. It notes the current decoder still accepts `toMachine.block` for forged events (`.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md:117-118`), and the live decoder does exactly that (`src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt:33-36`), but the durable decoder research requires a strict machine-owned `blockHash` contract (`.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md:11-18`). Task-521 cannot truthfully promote this seam until the accepted forged payload shape is decided and reflected in tests and, if needed, research.
- The verification plan is missing an automated stale-protocol-2 regression. Task-511 made freshness-gated trace-object reads a hard runtime boundary (`.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md:9-19`), and `NodeMonitor` currently enforces that via `recentFreshTraceObjectBatches(...)` (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:384-390`), but `task-521.md` only checks this statically and does not require a test proving stale trace-object batches stop driving peer counters or chain fallback after the rewiring (`.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md:212-231`). Add one focused `NodeMonitorTest` for stale protocol-2 batches.

Non-blocking observations:
- The smallest truthful implementation is narrower than some plan wording suggests: one shared protocol-2 extraction component over raw batches is enough, and existing DTOs plus thin delegating wrappers are fine if that avoids rename churn.
- Keeping `BlockMonitor`, schema/startup/config generation, frontend payloads, and wider docs updates out of scope for task-521 is correct.
- Live protocol-2 coverage in `LiveTraceForwardIntegrationTest.kt` currently proves family presence more than typed round-trip behavior (`src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt:759-764`). That is acceptable here if fixture-backed tests are strengthened, but task-541 should close that live typed-assertion gap.

Approval bar:
- Make `AddedToCurrentChain` preservation explicit in task scope, acceptance, and tests.
- Resolve the forged-block hash contract and align code/tests/research on one accepted payload shape.
- Add explicit stale trace-object freshness regression coverage for `NodeMonitor` after the shared-extractor swap.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-18T00:24:04Z

- Interaction mode confirmed: `autonomous`
- Revision inputs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521-plan-review.md`
- Live code and test surfaces re-verified for blocker resolution:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
- Blocking critique items resolved in the canonical plan:
  - made `ChainDB.AddBlockEvent.AddedToCurrentChain` an explicit preserved protocol `2` responsibility in scope, verified surfaces, promotion boundary, implementation approach, acceptance criteria, and verification plan
  - resolved the forged-block hash plan conflict explicitly:
    - adopted blocks remain strict on machine-owned `toMachine.blockHash`
    - forged blocks are planned against current repo truth `toMachine.block` unless implementation gathers stronger upstream or live evidence for `toMachine.blockHash`
    - the task now requires code, tests, and research to pin whichever forged contract is accepted so repo and durable research stop drifting
  - added explicit stale protocol `2` freshness regression coverage to the verification plan:
    - `NodeMonitorTest` must prove stale trace-object batches stop driving peer counters
    - `NodeMonitorTest` must also prove stale trace-object batches stop driving the `AddedToCurrentChain` chain fallback
- Canonical plan revised:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md`
- Revision summary:
  - narrowed task-521 more truthfully to shared protocol `2` extraction for block events, connection counters, and required chain fallback
  - kept the smallest implementation option open: one shared extractor with existing DTOs and thin delegating wrappers is explicitly acceptable
  - added a required research-sync note if forged-block payload truth remains `toMachine.block` or changes during implementation
- Self-review after revision:
  - scope-creep check passed: still no spill into `task-530` or `task-531`
  - repo-vs-research conflict check passed: forged-block hash handling is now explicitly resolved in-plan instead of left ambiguous
  - missing-tests check passed: stale protocol `2` freshness regression is now required
  - consistency check passed: plan now matches live `NodeMonitor` fallback behavior and task-511 freshness boundaries

