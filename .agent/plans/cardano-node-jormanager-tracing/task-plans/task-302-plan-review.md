Planner: Iteration 1
Timestamp: 2026-05-14T22:20:00Z

Reviewed the required docs, workflows, prior task artifacts, and research notes, then built and used the code index before verifying live repo anchors in `EkgService.kt`, `NodeMonitor.kt`, `NodeController.kt`, `Node.kt`, `Configuration.kt`, and related test surfaces. Drafted the canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md` as an autonomous, code-index-synced cleanup task focused on retiring dead HTTP-only node-metrics plumbing only after confirming `NodeMonitor` is already fully on the direct `DataPoint` path and `EkgService` has no live callers.

The approved task boundary is intentionally narrow: delete `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`, then remove the legacy `model/ekg/` and `model/ekg2/` DTO trees only if final search confirms they are truly orphaned. Keep shared Retrofit wiring in `Configuration.kt` because it still serves `PooltoolService`, and do not widen task-302 into schema changes, template updates, or broader HTTP client refactors.

The plan explicitly keeps `Node.ekgPort` and `Node.promPort` persistence for now. Live code still persists both fields in `Node.kt`, `NodeController.createNode(...)` still allocates and saves them, and `allocateTracingPort(...)` still sequences tracing ports from `promPort + 1`. Removing those fields here would force a larger migration across persistence, controller logic, and tests, which is outside the smallest truthful acceptance for task-302.

Expected implementation surfaces are limited to the new plan doc, `EkgService.kt`, the two legacy EKG DTO packages if orphaned, and any tests that still reference those deleted HTTP-only types. Verification is pinned to dead-reference searches plus focused backend regression on the live direct-protocol path, preferably `NodeMonitorTest`, `NodeStateDataPointDecoderTest`, and `TracingConnectionManagerTest`. No additional skill was needed. Review-log paths and optional research follow-up were recorded in the plan, and the self-review confirmed the draft does not overreach into schema removal or node-template work.

Outcome: canonical `task-302` plan drafted and ready for formal review.

Critiquer: Iteration 1
Timestamp: 2026-05-14T22:21:59Z

I re-read the required docs, PRD/tasks, prior task-301/task-104 research, and the live repo surfaces for this cleanup. The current plan is acceptably scoped and matches the implementation truth: `NodeMonitor` is already on the direct `DataPoint` path (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:281-321`), `EkgService` is now an uncalled Retrofit seam (`src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt:1-21`), and the remaining `model/ekg/` and `model/ekg2/` trees appear to be orphaned DTOs with no surviving production or test references beyond that service. The plan also correctly avoids widening into persistence and provisioning work that still exists separately in `NodeController` and `Node` (`src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3285-3453`, `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt:35-38`).

Blocking findings:
- None.

Non-blocking observations:
- The plan’s Retrofit justification is directionally correct but slightly under-specific: Retrofit is still live not only through `PooltoolService` in `Configuration.kt:195-202`, but also through `MetadataService` creation inside `NodeController.uploadMetadata(...)` (`src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3053-3068`). This does not require scope change; it only means implementation notes should continue to say “keep shared Retrofit for other live consumers,” not imply Pooltool is the sole one.
- The live repo still contains stale commented legacy HTTP-metrics mutation code in `NodeController.createConfigFile(...)` (`src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3323-3356`). I would not raise the bar over it for `task-302`, but if implementation touches nearby code anyway, removing that comment would be a cheap truth-alignment cleanup.
- Live searches already show no remaining test references to `EkgService`, `EkgMetrics`, or `EkgMetrics2`, so the plan’s “delete DTOs if orphaned after final search” wording is conservative rather than risky. That is acceptable and should converge quickly in implementation.

Approval bar:
- Keep the task narrow: delete `EkgService.kt`, delete the orphaned `model/ekg/` and `model/ekg2/` DTO packages once final searches remain empty, and verify no production or test references remain.
- Do not widen into removing `Node.ekgPort`/`Node.promPort`, changing `allocateMetricsPorts(...)`, resequencing `allocateTracingPort(...)`, or removing shared Retrofit infrastructure.
- Run the focused backend regression the plan already names to prove `NodeMonitor` and the direct node-state path remain intact after cleanup.

Decision: approved

