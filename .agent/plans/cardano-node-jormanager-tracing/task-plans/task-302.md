# Task 302 Plan

## Summary

- Task ID: `task-302`
- Title: `Retire HTTP-only metrics plumbing`
- Why now: `task-300` removed the legacy block-discovery transport and `task-301` already moved `NodeMonitor` off EKG and Prometheus HTTP polling, so the next smallest truthful step is to delete the now-dead HTTP-only metrics client surface without widening into persistence or rollout-template work
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Retire the remaining HTTP-only node-metrics plumbing now that production block and node-state monitoring both use the direct Hermod-compatible boundary, while explicitly keeping unrelated persistence and node-creation sequencing that still depends on the old port fields.

In scope:
- delete `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt` once live call-site verification stays at zero
- remove any now-orphaned HTTP-only metrics DTOs under `src/main/kotlin/com/swiftmako/jormanager/model/ekg/` and `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/` if they truly have no remaining production or test references after `EkgService` removal
- remove or tighten any tests that still exist only to support HTTP metrics plumbing
- verify that `NodeMonitor` remains intact on the direct `DataPoint` path after the cleanup
- keep Retrofit available for non-metrics consumers such as `PooltoolService`

Out of scope:
- removing `Node.ekgPort` or `Node.promPort` from persistence or adding a Liquibase migration
- changing `NodeController` node-creation flow, `allocateMetricsPorts(...)`, or current tracing-port sequencing in this task
- updating deployed template configs under `/home/westbam/bcsh/jormanager/`
- widening into general Retrofit cleanup unrelated to HTTP-only metrics
- relay or pool node-state parity work, tracing protocol changes, or websocket contract changes
- PRD or tasks JSON synchronization beyond what later tracking tasks already own

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/database.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none needed
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301-impl-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-plan-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-301-node-monitor-direct-datapoint-outcome.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-104-request-plumbing-cleanup.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-300-blockmonitor-boundary.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built the deep code index before planning, then verified the important findings against live files
- Code-index materially affected this task because the repo still contains substantial `model/ekg*` trees even though the production monitor migration is already complete, so this cleanup must distinguish dead HTTP-only code from still-live persisted port plumbing
- Code-index-synced findings confirmed live:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` no longer imports or uses `EkgService`, Retrofit, SSH port forwarding, or `node.ekgPort`; it now loads `NodeStateMetrics` through `DataPointSessionClientFactory` against `host.hostname:node.tracingPort`
  - `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt` is a standalone Retrofit interface with no reported callers
  - `src/main/kotlin/com/swiftmako/jormanager/model/ekg/` and `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/` appear to be legacy HTTP response DTO trees that no longer have live references outside `EkgService`
  - `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt` still provides a shared `Retrofit` bean that remains live through `PooltoolService`, so task-302 should not remove Retrofit itself
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt` still allocates and persists `ekgPort` and `promPort`, and `allocateTracingPort(...)` still sequences tracing ports from `promPort + 1`
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt` still persists non-null `ekgPort` and `promPort`, so deleting those fields here would widen into schema and controller work that task-302 does not need

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning
- Truthful autonomy basis:
  - the live monitor migration is already in place, the remaining work is a contained dead-code cleanup pass, and no unresolved product or operator decision is required to plan this accurately

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-300`
  - `task-301`
- Practical live repo dependencies this task should reuse carefully:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/ekg/`
  - `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/`
  - `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-103`
  - `task-400`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
  - is now a dead Retrofit seam for the removed HTTP metrics path and should be deleted if final verification still shows zero callers
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - already uses direct `DataPoint` sessions and is the main regression surface to protect during cleanup
- `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - still needs its `Retrofit` bean for `PooltoolService`, so the cleanup boundary should stop at the EKG-specific layer rather than removing shared HTTP infrastructure wholesale
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - still allocates `ekgPort` and `promPort` and persists them on newly created nodes even though generated configs no longer expose HTTP metrics
  - still derives tracing-port allocation from `promPort + 1`, so removing persisted metrics ports here would force a wider node-creation redesign
- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - still requires `ekgPort` and `promPort` as non-null persisted fields
  - those fields stay for now because they are part of the current database shape and active controller persistence path even after monitor migration
- `src/main/kotlin/com/swiftmako/jormanager/model/ekg/` and `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/`
  - appear to be legacy DTO-only packages that become eligible for removal once `EkgService` is deleted and no tests still reference them

## Persistence Decision

- `Node.ekgPort` and `Node.promPort` persistence stays for now.
- Reason: the live repo still persists both fields on node creation, `NodeController.allocateMetricsPorts(...)` is still part of that creation flow, and `allocateTracingPort(...)` currently derives tracing-port sequencing from `promPort + 1`.
- Removing those fields in `task-302` would require schema migration, entity changes, controller rewiring, and likely test fixture churn that goes beyond the smallest truthful HTTP-only cleanup accepted for this task.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
  - delete the dead HTTP-only Retrofit metrics interface
- `src/main/kotlin/com/swiftmako/jormanager/model/ekg/`
  - deleted after final search confirmed the package was fully orphaned
- `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/`
  - deleted after final search confirmed the package was fully orphaned
- `src/test/kotlin/...`
  - adjust or delete only any tests that still reference removed HTTP-only metrics DTOs or the deleted service seam
- `.agent/plans/cardano-node-jormanager-tracing/research/task-302-*.md`
  - only if implementation reveals a durable finding about why persisted metrics ports must remain beyond this cleanup

## Implementation Approach

- Preferred smallest truthful production shape:
  - treat this as dead-code retirement, not as a new metrics redesign
  - remove `EkgService` first
  - immediately re-run search for `model.ekg`, `model.ekg2`, `EkgMetrics`, and `EkgMetrics2`
  - delete DTO packages only if that search stays empty outside the files being removed
- Keep the cleanup boundary narrow:
  - do not touch `NodeMonitor` behavior except for compile-safe import cleanup if any appears
  - do not remove the shared `Retrofit` bean because `PooltoolService` still depends on it
  - do not edit `NodeController` metrics-port allocation or persisted node fields in this task
- Testing approach:
  - prefer static verification plus the narrowest backend regression target that proves `NodeMonitor` and related tracing code still compile and pass after EKG cleanup
  - if no tests directly cover the removed files, avoid inventing new behavior tests solely for a dead-code deletion

## Acceptance Criteria

- No production call site remains for `EkgService` or any equivalent HTTP-only node-metrics client.
- The codebase no longer carries an active EKG or Prometheus HTTP metrics path for node monitoring.
- `NodeMonitor` remains on the direct `DataPoint` path and its current behavior stays intact after cleanup.
- Retrofit remains available for still-live non-metrics consumers such as `PooltoolService`.
- `Node.ekgPort` and `Node.promPort` remain persisted in this task, with the reason documented rather than removed implicitly.
- No schema migration, template-config update, or node-creation redesign is introduced by this cleanup.

## Verification Plan

- Static verification:
  - confirm `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt` is removed
  - confirm no production file still references `EkgService`, `EkgMetrics`, `EkgMetrics2`, `com.swiftmako.jormanager.model.ekg`, or `com.swiftmako.jormanager.model.ekg2`
  - confirm `NodeMonitor.kt` still contains no HTTP metrics imports or transport helpers
  - confirm `Configuration.kt` still wires Retrofit only for live consumers such as `PooltoolService`
  - confirm `NodeController.kt` and `Node.kt` are unchanged unless a minimal compile-fix is truly required
- Automated verification:
  - run the narrowest relevant backend target, preferably:
    - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
  - if package deletion touches wider compilation, expand only as needed to a compile-safe backend test target
- Truthful validation boundary:
  - this task does not require live node or SSH validation because the production behavior change already landed in `task-301`; here we are only proving that removing dead HTTP-only code does not regress that path

## Risks And Open Questions

- Main risk is over-cleanup: removing persisted metrics ports or shared Retrofit infrastructure would widen the task beyond the accepted cleanup scope.
- Another risk is under-cleanup: deleting only `EkgService` while leaving obviously orphaned DTO packages would preserve dead HTTP-only code and miss the task's real value.
- Another risk is stale test fixtures or imports still referencing removed DTOs; implementation should search tests after each deletion step rather than assuming only production files matter.
- Open question for later work, not this task: when node creation and persistence are revisited, `ekgPort` and `promPort` can likely be retired together with tracing-port allocation resequencing, but that is a separate schema-and-controller change.

## Required Docs, Tracking, And Research Updates

- Create this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`.
- Planning and implementation review logs now exist at the recorded review-log paths.
- Update the PRD implementation notes and the tasks tracker after review approval.
- Record the durable cleanup boundary in a narrow research note, especially that persisted `ekgPort` and `promPort` stay intentionally outside this dead-code retirement task.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-impl-review.md`

## Self-Review

- Scope creep check: the plan retires dead HTTP-only metrics plumbing but explicitly avoids schema removal, template updates, and node-creation redesign.
- Persistence check: the plan states clearly that `Node.ekgPort` and `Node.promPort` stay for now because current persistence and tracing-port sequencing still depend on them.
- Verification check: the plan includes both static dead-reference checks and focused backend regression tests against the live direct `DataPoint` path.
- Consistency check: the plan matches the PRD and task graph by treating `task-302` as post-migration cleanup after `task-300` and `task-301`, not as a second monitor refactor.

## Final Outcome

- Result: `completed`
- Final implementation:
  - deleted `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
  - deleted the orphaned `src/main/kotlin/com/swiftmako/jormanager/model/ekg/**` and `src/main/kotlin/com/swiftmako/jormanager/model/ekg2/**` HTTP metrics DTO trees
  - preserved `NodeController`, `Node`, and shared Retrofit wiring because they still back live metadata and persistence behavior outside this task
- Verification completed:
  - static dead-reference search for `EkgService`, `EkgMetrics`, `EkgMetrics2`, `com.swiftmako.jormanager.model.ekg`, and `com.swiftmako.jormanager.model.ekg2` returned no remaining Kotlin matches after cleanup
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
  - repo Gradle wiring also ran Vue unit tests and frontend production build during that verification target
- Final review result:
  - planning review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-plan-review.md`
  - implementation review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302-impl-review.md`
- Durable task boundary confirmed:
  - `Node.ekgPort` and `Node.promPort` remain persisted because `NodeController.allocateMetricsPorts(...)` and `allocateTracingPort(...)` still depend on them; retiring those fields is follow-up work, not part of this HTTP-only client cleanup
