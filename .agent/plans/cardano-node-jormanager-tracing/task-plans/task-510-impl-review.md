Implementation: Iteration 1
Timestamp: 2026-05-17T23:44:09Z

Changes made:
- Enforced core-only tracing transport eligibility in TracingConnectionManager by rejecting any node whose type is not core.
- Tightened NodeMonitor legacy tracing backfill and active monitoring eligibility to core nodes only, removing the stale relay promPort + 1 tracing-port assumption.
- Deleted the unused sibling-session transport file src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt.
- Updated tracing and monitor tests to cover relay rejection and core-only backfill behavior.

Files touched:
- src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt
- src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt
- src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt
- deleted src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt

Verification run:
- ./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue
- Result: BUILD SUCCESSFUL

Deviations from approved plan:
- None. The implementation stayed within the approved plan boundaries and did not widen into typed extraction or live-node validation.

User interaction required:
- No

Outcome: Core-only unified tracing eligibility, relay backfill removal, stale sibling-session deletion, and focused backend verification are complete for iteration 1.

Code Review: Iteration 1
Timestamp: 2026-05-17T23:45:24Z

Summary:
- Reviewed the required tracing PRD, task-500 architecture note, task-510 plan/review artifacts, and the live implementation in TracingConnectionManager, NodeMonitor, and their focused tests.
- Verified the implementation now matches the task-510 convergence target: runtime tracing transport eligibility is core-only, NodeMonitor no longer backfills relay tracing from promPort + 1, and the stale sibling-session transport src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt is deleted with no live Kotlin references remaining.
- Focused verification evidence is appropriate and matches the workflow guidance: ./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue passed.

Blocking findings:
- None.

Non-blocking observations:
- The code change is appropriately minimal: TracingConnectionManager.resolveTarget() now enforces the PRDs core-only tracing boundary directly, and NodeMonitor.shouldMonitorNode() plus backfillTracingSettingsIfNecessary() now tell the same truth, which reduces the chance of runtime drift between transport ownership and dashboard sampling.
- Test coverage is good for this iterations scope: relay rejection is now exercised in both tracing transport and node-monitoring paths, and the legacy core backfill path remains covered.
- I did not find schema/API drift, startup-generation drift, deployed-config drift, decoder regressions, or block-classification regressions introduced by this iteration; those concerns remain outside the touched surface here.

Approval bar:
- Current bar is met for iteration 1. No additional changes are required before accepting this implementation pass.

Decision: approved

