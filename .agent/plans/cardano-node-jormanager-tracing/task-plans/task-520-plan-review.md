Planner: Iteration 1
Timestamp: 2026-05-17T23:51:20Z

Created the canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-520.md` after reading the required docs, workflows, PRD, tasks graph, task-500 research, the existing task-510 and task-511 plans, and the live tracing and monitor code and tests. The plan truthfully marks interaction mode as `autonomous`, records code-index sync as consulted context, and aligns to live-repo truth rather than stale tracker wording: production already has protocol `1` raw capture through `ForwardingMetricsProtocol` and `TracingRawCaptureService`, but production consumption still lacks the typed extraction layer and `NodeMonitor` still prefers protocol `3` datapoints with protocol `2` fallback. The plan keeps scope narrow to production typed extraction for chain, forge, KES, and mempool metrics plus the smallest `NodeMonitor` precedence change needed so existing `NodeStats` fields prefer fresh protocol `1` metrics while peer counters remain on protocol `2` and startup or structured datapoint work remains deferred to later tasks.

Expected implementation files are pinned to the smallest likely surface: a new or minimal production metric-extraction seam under `src/main/kotlin/com/swiftmako/jormanager/tracing/`, `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`, focused tracing tests, and `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`. The verification plan explicitly covers safe missing-metric handling, production reuse of `TracingRawMetricValue` instead of the live test JSON-string bridge, `NodeMonitor` protocol-1-first precedence for overlapping fields, and continued protocol-2 peer-counter fallback. The plan also calls out the stale-tracker situation directly without broadening scope: task-520 is not transport work and not a full unified dashboard migration; it closes the remaining production typed-consumption gap on top of the already-landed raw-capture seam.

Self-review completed:
- no scope creep into `task-521`, `task-522`, or `task-530`
- no stale workflow references beyond `backend` and `test`
- no missing canonical plan doc creation, expected file list, or verification steps
- no inconsistency with live repo truth about raw capture already being present while typed production consumption is still missing

Outcome: canonical `task-520` plan drafted and ready for formal review.

Critiquer: Iteration 1
Timestamp: 2026-05-17T23:53:21Z

The draft is close, but it still leaves one architectural regression path and one task-boundary ambiguity unpinned. Live code confirms the central premise of the plan is correct: production has raw protocol `1` capture already, while `NodeMonitor` still reads protocol `3` datapoints first and then protocol `2` trace objects, with no production typed protocol `1` path yet (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:334-374`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt:50-104`). The remaining changes should stay small, but the plan needs two corrections before approval.

Blocking findings:
- The plan makes protocol `1` the preferred dashboard source without pinning a freshness-safe metric read path. It explicitly says to reuse `TracingRawCaptureService.latestMetricSnapshot(...)` and to prefer protocol `1` when a fresh metric snapshot exists, but live production code has no `latestFreshMetricSnapshot(...)` equivalent for metrics, only for datapoints and trace objects. `NodeMonitor` currently preserves the task-511 freshness rule by age-gating protocol `3` and protocol `2` reads, and the PRD now treats freshness-gated dashboard reads plus teardown invalidation as a durable runtime boundary. As written, task-520 can regress that invariant and surface stale healthy-looking stats. The plan needs to either include the freshness helper and tests explicitly, or narrow itself to extractor-only work and defer `NodeMonitor` wiring.
- The plan promises partial protocol `1` decoding plus field-level precedence/fallback, but it does not pin the merge shape needed to do that without spilling into task-530. It says missing metrics should return partial or null typed results and that `NodeMonitor` should merge protocol `1` with protocol `2` peers and protocol `3` fallback, but the current bridge object is all-required `NodeStateMetrics`, and current `NodeMonitor` logic still builds one fully-populated `NodeStateMetrics` from one source path at a time. The plan needs to choose one minimal truth up front: either require a complete protocol `1` dashboard subset before switching precedence, or explicitly add a small nullable or mergeable intermediate model. Leaving that open is likely to turn task-520 into a broader consumer refactor.

Non-blocking observations:
- `Planning status: approved` is premature in the draft plan before review has actually passed.
- The live test helper block remains the only canonical source for the concrete protocol `1` metric names and typed decode behavior. Even if task-520 stays small, it would be safer to call out one shared constant or fixture extraction or at least require production tests to pin `density_real` and `tipBlock`, since both arrive through `TracingRawMetricValue.Label` in production.
- The proposed Gradle selector is only a shape example, but there are currently no tracing test classes matching `*Metrics*` yet. Naming the intended new test class(es) in the plan would make the verification step more reproducible.

Approval bar:
- Update the plan so task-520 either includes an explicit freshness-gated protocol `1` read seam plus tests, or removes the `NodeMonitor` precedence change and leaves consumer wiring to a later task.
- Pin the merge strategy for protocol `1` -> `NodeMonitor` consumption so the task has a stable minimal implementation boundary and does not silently expand into task-530.
- Downgrade the draft plan status from approved/passed language until those two points are resolved.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-17T23:54:17Z

Revised the canonical `task-520` plan to resolve the review blockers after re-checking the live production seams in `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` and `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt`. The updated plan now treats `NodeMonitor` wiring as still in scope, but only behind one explicit freshness-safe read seam: task-520 must add the metric equivalent of the existing datapoint and trace freshness helpers and require `NodeMonitor` to read protocol `1` through a fresh-only accessor rather than `latestMetricSnapshot(...)` directly. This keeps task-511 stale-state boundary intact and prevents protocol `1` promotion from surfacing stale healthy-looking dashboard stats after disconnect or teardown.

The plan also now pins the merge boundary so task-520 does not drift into `task-530`. Instead of leaving field-level partial merge behavior open against the all-required `NodeStateMetrics` bridge, the canonical plan now requires one minimal strategy: protocol `1` only becomes the primary source when a fresh typed metric snapshot can supply the full existing protocol-1-overlapping dashboard subset for this task (`blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, `txsProcessed`). If that subset is incomplete or stale, `NodeMonitor` stays on the existing datapoint/trace fallback path for the whole snapshot rather than introducing a broader nullable per-field merge model in task-520. Peer counters remain protocol `2` only, startup metadata remains protocol `3` only, and any broader cross-family snapshot assembly is explicitly deferred to `task-530`.

I also corrected the premature status language in the canonical plan: `Planning status: approved` is downgraded to draft / in-review wording until review passes, and the verification section now names the expected new focused test surfaces more concretely instead of relying on a wildcard `*Metrics*` selector. The plan remains intentionally small: production protocol `1` typed extraction, one fresh-only metric access helper, one constrained precedence switch in `NodeMonitor`, and focused tests that pin freshness gating, subset completeness, label-backed metric decoding such as `density_real` and `tipBlock`, and continued protocol `2` peer fallback.

Self-review completed:
- freshness regression path closed without removing the minimal `NodeMonitor` wiring already intended for task-520
- merge strategy pinned to a complete protocol-1-overlapping subset gate, avoiding silent expansion into `task-530`
- premature approved language removed from the canonical plan
- scope still limited to protocol `1` typed extraction plus the smallest safe consumer change on top of existing raw capture

Outcome: canonical `task-520` plan is now minimally corrected and ready for re-review.

