Planner: Iteration 1
Timestamp: 2026-05-18T01:41:13Z

Task: task-541 - Run final live verification and synchronize docs
Action: created the canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`

Docs/workflows consulted:
- `.agent/readme.md`
- `.agent/system/architecture.md`
- `.agent/plans/readme.md`
- `.agent/workflows/backend.md`
- `.agent/workflows/test.md`
- `.agent/workflows/update-doc.md`

Task sources consulted:
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-520-protocol1-typed-metric-promotion.md`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-522-protocol3-typed-extraction-boundary.md`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-530-node-monitor-dashboard-signal-seam.md`
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-540.md`

Code-index usage:
- checked code-index settings and watcher status first
- confirmed project path is `/home/westbam/Development/jormanager`
- rebuilt the deep index before planning
- used code-index first to locate the current transport seam, live verification harness, and task-plan conventions
- verified the material findings against live files before writing the plan

Key findings:
- `task-541` is the final pending tracker item after `task-540`
- the production transport seam is already `TraceForwardConnectionRunner`, and `NodeMonitor` already consumes the downstream `TracingDashboardSignalService`
- the accepted live verification surface is `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
- the live tests are explicitly gated by `JORMANAGER_RUN_LIVE_TRACING_TESTS=true`
- the live block-producer target is `clockwork:18401`
- because truthful completion depends on a running forwarded trace stream outside normal repo-local control, the task must be classified as `interactive_validation`, not `autonomous`

Planned implementation shape:
- keep the task narrow: verify first, then sync docs only to the evidence actually established
- run a focused repo-local regression suite for tracing transport plus downstream consumers
- run the smallest live single-connection suite that proves:
  - one muxed connection and one forwarding handshake
  - concurrent protocol `1`/`2`/`3` capture
  - protocol `1` chain, forge, KES, and mempool signals
  - protocol `3` startup datapoint capture
  - protocol `2` block-related and peer-related trace-object evidence
- use the existing five-minute live capture only as the fallback when the short live window does not produce enough protocol `2` evidence
- update PRD, tasks JSON, canonical plan, and one research artifact only after live verification truth is established
- touch `prompt.md` only if it still contains materially stale active guidance after final verification

Expected files to change:
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md` only if final verification makes current active guidance stale
- one narrow research note or research update under `.agent/plans/cardano-node-jormanager-tracing/research/`
- no production Kotlin files are expected to change unless verification finds a concrete bug

Verification plan:
- focused repo-local suite:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" -x buildVue -x testVue`
- minimum live suite:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveSingleConnectionThreeProtocolCaptureToClockwork" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkKesMetricsAreCapturedFromProtocol1" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkMempoolMetricsTypedDecodeRoundTrips" --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkDashboardSignalInventoryPrintsTypedSnapshot" -x buildVue -x testVue`
- fallback live evidence if the short run does not surface enough protocol `2` output:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests "com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveFiveMinuteClockworkCapturePrintsInterestingSignals" -x buildVue -x testVue`
- if agent-run live verification is unavailable or inconclusive, stop for user or operator validation and request:
  - exact command run
  - confirmation that `JORMANAGER_RUN_LIVE_TRACING_TESTS=true` was set
  - pass/fail per live test
  - output excerpts showing observed signal inventory or the concrete failure

Docs/tracking/research updates required:
- maintain the canonical plan doc as source of truth
- on truthful completion, update the PRD status and recent implementation notes for final live verification
- mark `task-541` complete in the tasks JSON with completion notes tied to the actual verification outcome
- preserve final live verification evidence in research, either as a narrow new `task-541` note or the smallest truthful update to an existing active note
- do not mark the task complete if live verification cannot be established

Planning status: draft
Build status: in_progress

Self-review:
- kept the plan on final verification plus doc synchronization and avoided turning it into another runtime redesign
- explicitly classified the task as `interactive_validation` because live forwarded-trace evidence is an external checkpoint
- used existing live tests instead of inventing new harnesses
- kept prompt and research updates conditional so the docs sync stays minimal and truthful

Outcome: Plan drafted and ready for critique

Critiquer: Iteration 1
Timestamp: 2026-05-18T01:42:24Z

The plan is sufficient and converges on the right end state. It keeps the task narrow, treats live verification as the gating truth source before any completion claim, uses the existing `LiveTraceForwardIntegrationTest` surface rather than inventing new probes, and limits documentation sync to artifacts that are actually stale after verification. It also correctly classifies the task as `interactive_validation` instead of hiding the `clockwork:18401` checkpoint inside an autonomous loop.

Blocking findings:
- none

Non-blocking observations:
- The minimum live suite is a good first pass, but peer-related protocol `2` evidence may still depend on inspecting printed output or falling back to the five-minute capture; keep that as evidence gathering only, not as a reason to widen the task.
- If completion uses a new `task-541` research note instead of updating an existing note, keep it narrowly focused on final verification evidence so it does not duplicate the already accepted architectural conclusions in `task-500`.

Approval bar:
- Keep the task in `interactive_validation`.
- Do not mark `task-541` complete unless the live run or operator-returned evidence explicitly shows the required protocol-family coverage and the PRD/tasks/research artifacts are synchronized to that verified result.

Decision: approved

