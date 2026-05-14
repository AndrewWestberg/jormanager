Implementation: Iteration 1
Timestamp: 2026-05-14T22:23:03Z

Changes made:
- deleted the dead HTTP-only `EkgService` Retrofit interface
- deleted the orphaned legacy HTTP metrics DTO trees under `src/main/kotlin/com/swiftmako/jormanager/model/ekg/` and `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/` after confirming they had no remaining production or test references
- left `NodeController`, `Node`, shared Retrofit wiring, and the direct `NodeMonitor` path unchanged to preserve the approved narrow scope

Files touched:
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-plan-review.md`
- `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/model/ekg/**`
- `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/**`

Verification run:
- static dead-reference search for `EkgService`, `EkgMetrics`, `EkgMetrics2`, `com.swiftmako.jormanager.model.ekg`, and `com.swiftmako.jormanager.model.ekg2` returned no remaining Kotlin matches after deletion
- `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"` passed
- repo Gradle wiring also ran Vue unit tests and frontend production build during that verification target; both passed

Deviations from approved plan:
- none

User interaction required:
- no

Outcome: implementation cleanup completed and ready for code review.

Code Review: Iteration 1
Timestamp: 2026-05-14T22:24:28Z

Reviewed the required docs, PRD/tasks, prior task-301/task-104 outcomes, the live diff, and the affected repo surfaces including `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`, the deleted `src/main/kotlin/com/swiftmako/jormanager/model/ekg/**` and `model/ekg2/**` trees, `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`, `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`, and `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`. The implementation matches the approved narrow scope: it removes the dead HTTP-only metrics client and its orphaned DTOs, leaves the live direct `DataPoint` node-state path intact in `NodeMonitor.kt:281-321`, preserves shared Retrofit infrastructure still used outside EKG cleanup in `Configuration.kt:182-202` and `NodeController.kt:3053-3068`, and does not widen into persisted `ekgPort` / `promPort` or tracing-port sequencing changes in `NodeController.kt:3285-3453`.

Blocking findings:
- None.

Non-blocking observations:
- `NodeController.kt:3323-3356` still carries a large commented legacy HTTP-metrics/config-mutation block. It does not affect correctness for `task-302`, but it is now stale relative to the implemented dispatcher-era config path and could be removed in a later truth-alignment cleanup.
- The shared Retrofit bean remains justified by more than `PooltoolService`; `NodeController.uploadMetadata(...)` also still builds Retrofit-backed `MetadataService` clients at `NodeController.kt:3053-3068`. The current implementation correctly leaves that infrastructure alone.

Approval bar:
- Keep `EkgService.kt` deleted, keep both `model/ekg/**` and `model/ekg2/**` deleted now that no production or test references remain, and do not widen this task into `Node.ekgPort` / `Node.promPort` persistence removal or broader Retrofit cleanup.
- Preserve `NodeMonitor` on the direct tracing `DataPoint` path and maintain focused regression coverage proving that cleanup did not regress `NodeMonitor`, node-state decoding, or tracing lifecycle behavior.
- Leave rollout-template and schema follow-up to their planned later tasks.

Decision: approved

