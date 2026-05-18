Planner: Iteration 1
Timestamp: 2026-05-18T00:54:46Z

- Reviewed required planning inputs in order: `.agent/readme.md`, `.agent/system/architecture.md`, `.agent/workflows/backend.md`, `.agent/workflows/test.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`, `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`.
- Reviewed additional task-530-relevant research and existing planning surfaces:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-300-blockmonitor-boundary.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`
  - verified that `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md` and `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530-plan-review.md` did not yet exist
- Synced code-index to `/home/westbam/Development/jormanager`, rebuilt the deep index, used code-index first to locate tracing and monitor surfaces, then verified findings against live files including:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingMetricDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
- Confirmed the live-repo baseline differs from the still-pending task-graph wording: `NodeMonitor` is already downstream of unified raw capture plus protocol `1`/`2`/`3` extractors, so task-530 is now a convergence refactor rather than a first-time migration away from separate tracing consumers.
- Created the canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`.
- Classified interaction mode as `autonomous`.
- Planned task-530 narrowly around extracting the remaining dashboard signal assembly and precedence logic out of `NodeMonitor` into one tracing-side shared seam, specifically:
  - keep one-connection raw-capture architecture unchanged
  - keep `NodeMonitor` focused on lifecycle, eligibility, cadence, websocket batching, null fallback, and `latestNodeStats`
  - preserve current precedence already proven in live code and tests:
    - protocol `1` wins only when the overlapping dashboard subset is fresh and complete
    - protocol `2` remains the authoritative fresh peer-counter source and preserves `AddedToCurrentChain` chain fallback
    - protocol `3` remains the node-state fallback contributor
  - avoid widening `NodeStats`, changing websocket payloads, changing `epochLength` sourcing without need, or touching block-monitoring or tracing transport
- Captured expected files, implementation approach, acceptance criteria, verification plan, risks or open questions, docs or research follow-up expectations, and review-log paths in the canonical plan.
- Self-review outcome: plan stays minimal and convergent, uses current backend and test workflow guidance only, includes the necessary focused test coverage, and is consistent with the PRD, task-500 architecture note, task-520 through task-522 durable findings, and the live repo state.
- Outcome: canonical task-530 plan is now in place and review-ready at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md`.

Critiquer: Iteration 1
Timestamp: 2026-05-18T00:56:16Z

Blocking findings:
- None. The plan is materially aligned with the live repo state and narrows task-530 to the remaining truthful gap: `NodeMonitor` still owns protocol-family merge and precedence logic directly in `loadNodeStateMetrics(...)` even though transport, raw capture, and protocol `1`/`2`/`3` extraction already live downstream in tracing code (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:336-386`).

Non-blocking observations:
- The plan should continue to prefer the smallest possible seam. Its current wording allows that (`service or equivalent seam` in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-530.md:18,170-173`), and that flexibility is good. A plain tracing-side assembler class is sufficient; this does not need to become a new Spring-heavy subsystem.
- The live code confirms the plan’s boundary call is correct: `NodeMonitor` still depends directly on `TracingRawCaptureService`, `TracingMetricDecoder`, `TraceForwardProtocol2Extractor`, and `TraceForwardProtocol3Extractor` (`NodeMonitor.kt:71-74`), so centralizing that assembly is the right convergence step.
- The plan appropriately avoids drifting back into transport ownership or protocol-family sessions. Nothing in it reintroduces sibling sessions, consumer-owned transport, or a protocol-3-only dashboard model, and that matches the PRD and task-500 architecture anchor.
- Keep the implementation from accidentally stranding protocol `3` startup metadata while narrowing the dashboard seam. Task-522 explicitly made `NodeStartupInfo` a retained production capability even though `NodeStats` does not expose it (`task-522` research: lines 9-16). This does not need a new dashboard field, but the refactor should avoid collapsing the seam to “dashboard-only” in a way that silently discards startup access for later tasks.
- The verification plan is broadly sufficient for a refactor task, but if the new seam changes extractor entry points or protocol-3 access shape, it should run the directly affected extractor tests too, not only `NodeMonitorTest` plus the new seam test. The plan already hints at this and should keep that bar.
- The plan correctly leaves `BlockMonitor` alone. That matches the accepted task-300 boundary that block startup seeding and validation still live there, while tracing transport already moved elsewhere; there is no task-530 reason to reopen block-monitoring architecture.

Approval bar:
- Keep task-530 limited to extracting dashboard snapshot assembly and precedence from `NodeMonitor` into one tracing-side seam.
- Do not add a second cache, second freshness owner, or any transport/reconnect/session logic.
- Preserve current observable `NodeStats` behavior, `latestNodeStats` behavior, default-node behavior, and websocket payload shape.
- Preserve the current precedence truth: protocol `1` only wins when the overlapping subset is fresh and complete; protocol `2` remains authoritative for peer counters and `AddedToCurrentChain` fallback; protocol `3` remains fallback and startup-info owner.
- Add focused automated coverage for the new seam and keep existing `NodeMonitor` behavior tests green.

Decision: approved

