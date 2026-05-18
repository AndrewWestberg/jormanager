# Task 541 Plan

## Summary

- Task ID: `task-541`
- Title: `Run final live verification and synchronize docs`
- Why now: `task-540` removed the last transitional transport seam and left `task-541` as the only pending tracker item. The remaining truthful work is to prove the final one-connection runtime against the live block-producer tracing target and then synchronize plan artifacts to that verified repo-plus-runtime truth.
- Interaction mode: `interactive_validation`
- Planning status: `approved`
- Build status: `completed`

## Scope

Run the smallest truthful final verification set against the live block-producer tracing endpoint and then update only the tracing-plan artifacts that still lag the verified final implementation state.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md` as the canonical task plan
- run a focused repo-local regression suite that confirms the final single-connection tracing code and downstream consumers still pass in the live repo
- run the existing live trace-forward harness against `clockwork:18401` using the accepted one-connection runtime path
- collect evidence for the final runtime claims that matter to this migration:
- one muxed connection and one forwarding handshake
- concurrent protocol `1`/`2`/`3` capture on that connection
- protocol `1` chain, forge, KES, and mempool signals
- protocol `3` startup datapoint capture
- protocol `2` trace-object evidence for block and peer-related tracing families
- compare the live verification outcome against the PRD, tasks JSON, prompt, and active research notes before changing docs
- synchronize the minimum required plan artifacts so future orchestration follows the implemented and live-verified single-connection design

Out of scope:
- new tracing runtime refactors, decoder changes, or consumer redesign unless final verification exposes a concrete blocker
- broad documentation cleanup unrelated to the tracing migration completion state
- relay or pool live verification beyond the accepted core-node and block-producer scope
- changing remote node, firewall, systemd, or environment configuration as part of this task
- claiming final live verification succeeded without executable evidence from the live suite or equivalent operator-provided output

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
  - `.agent/plans/readme.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md`

## Code-Index Sync

- Confirmed code-index project path is `/home/westbam/Development/jormanager`.
- Checked code-index settings and file-watcher state before planning.
- Built a fresh deep index for this planning pass and used code-index first to locate live verification surfaces, task-plan conventions, and active tracing docs.
- Re-verified all material findings against live files before writing the plan.
- Code-index materially confirmed that the remaining task is documentation-and-verification work, not another architecture migration: the production transport seam is `TraceForwardConnectionRunner`, `NodeMonitor` consumes `TracingDashboardSignalService`, and the live verification harness remains `LiveTraceForwardIntegrationTest` with environment-gated tests against `clockwork:18401`.

## Interaction Mode And Checkpoints

- Interaction mode: `interactive_validation`
- Why this is not `autonomous`:
  - final acceptance depends on a running forwarded trace stream from the live block producer at `clockwork:18401`
  - live tests are gated by `JORMANAGER_RUN_LIVE_TRACING_TESTS=true`
  - the prompt explicitly treats live forwarded-trace validation and running core-node verification as user-owned or operator-owned checkpoints unless the environment truthfully permits agent execution end to end
- Agent-executable work before user interaction:
  - repo-local regression tests
  - any doc, tracker, and research updates justified by successful agent-run live verification
  - if live verification cannot be executed from this environment, preparation of the exact operator handoff and evidence checklist
- Required manual checkpoint if agent-run live verification is unavailable or inconclusive:
  - run the specified live Gradle suite with access to the real forwarded trace endpoint
  - return the command output or a faithful log excerpt showing pass or failure for each required live test
- Evidence needed back from the user or operator when manual validation is required:
  - the exact command run
  - whether `JORMANAGER_RUN_LIVE_TRACING_TESTS=true` was set
  - pass or fail status for each live test
  - output proving the live suite observed the expected signal families or, if it failed, the concrete connection or assertion error

## Relevant Dependencies

- Required completed upstream task:
  - `task-540`
- Important architecture and consumer dependencies that final verification must stay aligned with:
  - `task-500`
  - `task-511`
  - `task-520`
  - `task-521`
  - `task-522`
  - `task-530`
  - `task-531`
- Practical live-repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardConnectionRunner.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/prompt.md`

## Verified Task Surfaces

- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - remains the accepted live verification surface
  - is explicitly environment-gated through `JORMANAGER_RUN_LIVE_TRACING_TESTS`
  - targets the live block producer at `clockwork:18401`
  - already contains the smallest useful single-connection proof and dashboard snapshot probes needed for this task
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardConnectionRunner.kt`
  - is the current production transport seam
  - already encodes one socket connection, one forwarding handshake, and concurrent protocol `1`/`2`/`3` execution
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already consumes `TracingDashboardSignalService` rather than owning tracing transport
  - confirms that task-541 should verify and document the final boundary rather than reopen consumer architecture
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - still reports the plan as `Re-opened For Final Architecture Alignment`
  - already contains recent implementation notes through `task-540`, so task-541 should append the final verification outcome and close the remaining status gap rather than rewrite historical sections
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - still shows `task-541` as pending and is the authoritative tracker that must be synchronized on completion
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - remains the orchestration source of truth
  - should be updated only if final live verification exposes stale active guidance; do not churn it if the current instructions remain truthful after task completion
- Active research notes consulted above already capture the accepted protocol-family boundaries and should only be updated where final live verification adds durable evidence or closes a remaining documentation gap

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`
  - canonical task plan
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - append final verification outcome and move status to the truthful final state
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - mark `task-541` complete and record the final verification outcome in completion notes
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - only if active orchestration guidance still implies unfinished tracing-architecture work after verification
- one research artifact under `.agent/plans/cardano-node-jormanager-tracing/research/`
  - either update the most appropriate existing active note with final live evidence or add a narrow `task-541` verification note if that is the smallest truthful durable record
- no production Kotlin files are expected to change unless final verification reveals a concrete bug that must be fixed before completion

## Implementation Approach

- Start with truth-preserving verification, not docs edits.
- Run a focused repo-local regression suite first so any purely code-level regression is caught before depending on live environment behavior.
- Then run the smallest existing live suite that proves the accepted final architecture without inventing new test harnesses.
- Prefer existing live test surfaces over ad hoc shell probes. The minimum intended live command set should be built from current `LiveTraceForwardIntegrationTest` methods instead of new one-off verification code.
- Treat final docs sync as evidence-driven:
  - if live verification passes, update PRD, tasks, and research to reflect verified completion
  - if live verification cannot be run here, stop at the user handoff and do not mark the task complete
  - if live verification reveals a real code or doc mismatch, fix or document that specific mismatch rather than broadening into a fresh redesign
- Keep `prompt.md` changes minimal. Only touch it if it would otherwise mislead future orchestration after the plan is complete.

## Acceptance Criteria

- A focused repo-local regression suite passes for the final tracing transport and downstream consumers.
- The final live verification evidence comes from the existing single-connection harness against the block producer and proves the accepted runtime shape.
- The verification evidence covers, directly or in combination:
  - one muxed connection and one forwarding handshake
  - protocol `1` chain, forge, KES, and mempool signals
  - protocol `3` startup datapoint capture
  - protocol `2` block-related and peer-related trace-object evidence
- The PRD, tasks JSON, canonical task plan, and any required research artifact are synchronized to the verified final implementation truth.
- No active planning artifact continues to present unfinished sibling-session cleanup or pre-final-verification status as current work.
- If the agent cannot truthfully execute live verification, the task remains in progress and the user receives a precise manual-validation handoff instead of a false completion claim.

## Verification Plan

- Static repo verification:
  - confirm `TraceForwardConnectionRunner` remains the one-connection transport seam
  - confirm `NodeMonitor` and tracing consumers remain downstream of shared raw capture and typed extraction
  - confirm docs changes are limited to stale completion or verification statements
- Focused repo-local automated verification:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" -x buildVue -x testVue`
- Minimum live verification target:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveSingleConnectionThreeProtocolCaptureToClockwork" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkKesMetricsAreCapturedFromProtocol1" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkMempoolMetricsTypedDecodeRoundTrips" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkDashboardSignalInventoryPrintsTypedSnapshot" -x buildVue -x testVue`
- Extended live verification when the minimum live subset does not surface enough protocol `2` block or peer evidence in its output window:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveFiveMinuteClockworkCapturePrintsInterestingSignals" -x buildVue -x testVue`
- Manual-validation fallback if the agent environment cannot run the live suite truthfully:
  - the user or operator runs the same commands above in an environment with access to `clockwork:18401`
  - they return pass or fail results plus the relevant output excerpt showing the observed signal inventory or the failure mode

## Risks And Open Questions

- Main risk is environment reachability: the repo may be correct while `clockwork:18401` or the required environment variable is unavailable from the current agent session.
- Another risk is over-updating docs after a partial or inconclusive live run. Documentation must reflect verified outcome, not optimistic intent.
- Another risk is underspecifying protocol `2` evidence: the shortest live tests may not always emit block or peer-related trace hints within a small window, so the five-minute trace capture must remain available as the smallest truthful fallback rather than forcing new probe code.
- Open documentation choice to resolve during implementation:
  - whether the final live evidence belongs as an update to an existing active research note, most likely `task-500`, or as a narrow new `task-541` verification note
- Open completion-state check to resolve during doc sync:
  - whether `prompt.md` contains any active guidance that becomes stale once final verification is complete; if not, leave it unchanged and note that no prompt edit was needed

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`.
- Do not write `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541-plan-review.md` during this planning pass.
- On truthful completion, update:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - the canonical task plan final-outcome section
- Update `.agent/plans/cardano-node-jormanager-tracing/prompt.md` only if final verification leaves any active orchestration instruction materially stale.
- Preserve final live evidence in research:
  - prefer the smallest durable update that future roles can cite
  - if no new durable finding emerges beyond confirmation, a narrow verification-evidence note is still acceptable if it is the clearest place to preserve the final live proof

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on final verification evidence plus plan-artifact synchronization and does not widen into speculative code changes.
- Interaction-mode check: the plan explicitly treats live forwarded-trace verification as a user-owned checkpoint when the environment cannot support agent execution.
- Minimality check: the verification plan prefers the existing live harness and only escalates from the short live subset to the five-minute capture when protocol `2` evidence is otherwise insufficient.
- Docs-sync check: the plan keeps `prompt.md` and research edits conditional on actual staleness or durable evidence instead of assuming every artifact must change.

## Current Implementation Status

- Focused repo-local regression suite passed with the expected tracing transport and downstream consumer coverage.
- Deep regression diagnosis showed the clockwork server and protocol `2` transport were still returning live trace objects; the breakage was in the local live-test harness.
- Verified harness regressions:
  - `traceKindOrNull(...)` was reading `kind` only from the top-level JSON object, while forwarded trace objects carry the meaningful discriminator under `data.kind` for clockwork protocol `2` events such as `TraceAdoptedBlock`, `TraceForgedBlock`, and `TraceNodeIsLeader`
  - the shared `captureAllProtocols(...)` helper had been widened to request protocol `2` trace batches with `traceRequestCount = 1_000`, which made the short one-connection verification path misleadingly report zero captured trace replies on clockwork while the raw trace-only probe still showed real protocol `2` traffic
  - the broad `GetAllMetrics` request in the three-protocol verification path also made the combined live probe noisier than necessary for final tracing verification
- Implemented harness fixes:
  - reduced the live trace batch size in the shared clockwork live helper back to a small rolling request size (`25`)
  - changed the clockwork three-protocol verification helper to request only the dashboard metric subset needed for the verification surface instead of `GetAllMetrics`
  - taught `traceKindOrNull(...)` to read `data.kind` when the top-level `kind` field is absent
  - added a narrow live inventory probe that prints the actual protocol `2` kinds and namespaces observed from `clockwork:18401`
- Verified post-fix live evidence from this environment:
  - `liveSingleConnectionThreeProtocolCaptureToClockwork` now reports `single connection ekg replies=1`, `single connection trace replies=2`, and `single connection datapoint replies=1`
  - `liveClockworkDashboardSignalInventoryPrintsTypedSnapshot` now reports `dashboard trace kinds (8)=[AddedToCurrentChain, CompletedBlockFetch, DownloadedHeader, ResourceStats, SendFetchRequest, TraceAdoptedBlock, TraceForgedBlock, TraceNodeIsLeader]`
  - `liveKesDiscoveryProbeOnClockworkPrintsForgeRelatedSignals` still proves direct clockwork protocol `2` forge-family traffic, including `TraceAdoptedBlock`
  - the new `liveClockworkTraceObjectInventoryPrintsKindsAndNamespaces` probe now proves both block and peer-related protocol `2` families from clockwork, including namespaces `Forge.Loop.AdoptedBlock`, `ChainDB.AddBlockEvent.AddedToCurrentChain`, `BlockFetch.Client.SendFetchRequest`, and `BlockFetch.Client.CompletedBlockFetch`
- Remaining note:
  - `liveFiveMinuteClockworkCapturePrintsInterestingSignals` still prints zero aggregate counts through the old long-running combined helper path and can still log a connection reset during teardown, so the final verification package should rely on the now-proven short one-connection probe plus the explicit clockwork trace inventory surfaces rather than treating that older aggregate printer as the primary truth source

## Final Outcome

- Task outcome: completed
- Final review result: approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541-impl-review.md`
- Final implementation truth:
  - the reported protocol-2 regression was caused by live-test harness drift in `LiveTraceForwardIntegrationTest.kt`, not by a clockwork node change and not by a production tracing transport regression
  - the harness now again proves clockwork protocol-2 block and peer-related trace families on the accepted one-connection verification path
  - the final durable regression note is recorded in `.agent/plans/cardano-node-jormanager-tracing/research/task-541-live-verification-regression-fix.md`
- Scribe updates required by this task are complete:
  - canonical plan doc updated
  - planning review log preserved
  - implementation review log preserved
  - PRD updated
  - tasks tracker updated
  - research brain updated
  - prompt review completed with no prompt edit required because the active orchestration guidance remains truthful after task completion
