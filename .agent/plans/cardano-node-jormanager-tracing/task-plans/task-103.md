# Task 103 Plan

## Summary

- Task ID: `task-103`
- Title: `Update deployed node template configs`
- Why now: `task-302` completed the last blocking runtime cleanup, so the next truthful rollout-alignment step is updating the deployed environment template configs to the dispatcher-era tracing shape already proven in app-side generation, while keeping the separate DB-backed import path explicit
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Update the deployed node template configs under `/home/westbam/bcsh/jormanager/` so the environment-side template source is aligned to the dispatcher-era tracing baseline instead of the old legacy tracing and HTTP metrics shape.

In scope:
- update `/home/westbam/bcsh/jormanager/guild-config.json`
- update `/home/westbam/bcsh/jormanager/mainnet-config.json`
- update `/home/westbam/bcsh/jormanager/preprod-config.json`
- update `/home/westbam/bcsh/jormanager/preview-config.json`
- align those four templates to the proven dispatcher-era non-core tracing baseline already used by `NodeController.normalizeTracingConfig(...)`
- remove legacy tracing and HTTP metrics keys from those four templates only
- record task-specific plan, review-log, tracking, PRD, and research updates required by this rollout-alignment task
- document that JorManager node creation still reads DB-backed templates through `FileRepository`, so this task aligns the deployed environment templates only and does not claim direct create-node rollout completion by itself

Out of scope:
- modifying any `*-db-sync-config.json` file under `/home/westbam/bcsh/jormanager/`
- changing `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt` or any app-side tracing-generation logic
- modifying the DB-backed config template records that `NodeController.createConfigFile()` reads through `FileRepository`
- changing startup wiring, tracing-port allocation, schema, monitor code, or frontend code
- forcing core-only forwarding settings into shared deployed templates without repo-validated proof that those files are core-only artifacts
- auto-migrating existing deployed nodes

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/update-doc.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none needed
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`

## Code-Index Sync

- Verified the code-index project path is `/home/westbam/Development/jormanager`
- Refreshed the index because the watcher reported `needs_rebuild` with no active indexed files
- Verified the live app-side tracing normalization after refresh against `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- Code-index materially affected planning by confirming the current app-side template alignment target is the shared non-core dispatcher baseline in `normalizeTracingConfig(...)`, with core-only `Forwarder` and `TraceOptionForwarder` added only when `nodeType == NODE_TYPE_CORE`

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - the task is a static environment-template alignment pass plus project documentation/tracking updates, and it does not require live nodes, remote host validation, secrets, or operator-only environment access beyond the local deployed-template directory already present in the workspace environment

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-302`
- Practical live repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
- Downstream task this work should unblock:
  - `task-400`

## Verified Task Surfaces

- `/home/westbam/bcsh/jormanager/guild-config.json`
  - still uses legacy tracing toggles, `UseTraceDispatcher: false`, legacy scribes, `hasEKG`, and `hasPrometheus`
- `/home/westbam/bcsh/jormanager/mainnet-config.json`
  - still uses the same legacy tracing and HTTP metrics shape
- `/home/westbam/bcsh/jormanager/preprod-config.json`
  - still uses the same legacy tracing and HTTP metrics shape
- `/home/westbam/bcsh/jormanager/preview-config.json`
  - still uses the same legacy tracing and HTTP metrics shape
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `normalizeTracingConfig(...)` is the live app-side source of truth for the accepted dispatcher-era shape
  - root tracing backends are always `Stdout MachineFormat`, with `Forwarder` and `TraceOptionForwarder` added only for core nodes
  - `createConfigFile(...)` still reads DB-backed template content through `fileRepository.findByName(...)`, so editing `/home/westbam/bcsh/jormanager/*.json` alone does not directly change create-node behavior until that separate template source is refreshed outside this task

## Template Alignment Decision

- These four deployed templates should align to the shared dispatcher-era baseline used for new-node config generation, not to the core-only branch unconditionally.
- Reason: the live templates are network baselines reused during node creation, while the app-side config generator adds forwarding conditionally by node type.
- Therefore this task should make the four templates safe shared baselines: dispatcher enabled, Markus-rooted `TraceOptions`, machine stdout preserved, and all legacy file-scribe plus HTTP metrics keys removed.
- This task does not claim that editing these files alone immediately changes JorManager node creation, because the live controller still renders from DB-backed template content.
- If later evidence shows a separate core-only deployed template path is needed, that should land as a follow-up rather than widening this task beyond the proven current seam.

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md`
  - canonical task plan
- `/home/westbam/bcsh/jormanager/guild-config.json`
  - deployed guild template tracing migration
- `/home/westbam/bcsh/jormanager/mainnet-config.json`
  - deployed mainnet template tracing migration
- `/home/westbam/bcsh/jormanager/preprod-config.json`
  - deployed preprod template tracing migration
- `/home/westbam/bcsh/jormanager/preview-config.json`
  - deployed preview template tracing migration
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - task completion state and notes
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - implementation notes/status update for task completion

## Implementation Approach

- Update each of the four deployed templates with the smallest truthful set of tracing changes:
  - set `UseTraceDispatcher` to `true`
  - replace the current tracing section with Markus-rooted `TraceOptions`
  - make root backends exactly `Stdout MachineFormat`
  - preserve `TurnOnLogging`, `TurnOnLogMetrics`, and `minSeverity` in the accepted dispatcher-era form
  - remove legacy top-level `Trace*` booleans other than the accepted dispatcher-era keys
  - remove `TracingVerbosity`, `defaultBackends`, `defaultScribes`, `rotation`, `setupBackends`, `setupScribes`, `hasEKG`, `hasPrometheus`, `options`, and any analogous HTTP-metrics/file-scribe keys
  - do not add `Forwarder` or `TraceOptionForwarder` to all four files unless live implementation evidence shows these templates are core-only
- Keep network-specific, non-tracing settings intact in each file.
- After template edits, verify alignment against the non-core tracing subtree produced by `NodeController.normalizeTracingConfig(...)`, not against unrelated `renderManagedConfig(...)` genesis filename rewrites.
- Keep the rollout claim narrow: this task aligns the deployed environment templates and documents the still-separate DB-backed template source used by `createNode()`.

## Acceptance Criteria

- `guild-config.json`, `mainnet-config.json`, `preprod-config.json`, and `preview-config.json` are updated for the tracing migration.
- All four updated templates set `UseTraceDispatcher: true`.
- All four updated templates preserve machine-formatted stdout logging through root `TraceOptions[""]` backends.
- All four updated templates omit `TraceOptionForwarder` because these shared network templates align to the non-core dispatcher baseline.
- No updated template includes legacy file-log scribes, rotation settings, `hasEKG`, `hasPrometheus`, `EKGBackend`, or `PrometheusSimple`.
- The updated templates align with the PRD's dispatcher-era direct-protocol model and the proven app-side non-core tracing baseline.
- The task outcome truthfully records that JorManager create-node still reads DB-backed templates through `FileRepository`, so any DB/import sync remains a separate step outside this task.
- No `*-db-sync-config.json` file is modified.

## Verification Plan

- Static verification:
  - machine-parse each updated deployed template as valid JSON
  - assert `UseTraceDispatcher` is `true` in all four files
  - assert root `TraceOptions[""]` backends equal exactly `["Stdout MachineFormat"]`
  - assert `TraceOptionForwarder` is absent from all four files
  - assert legacy top-level `Trace*` booleans are absent from all four files except `TraceOptions`
  - assert `TracingVerbosity`, `defaultBackends`, `defaultScribes`, `setupBackends`, `setupScribes`, `rotation`, `options`, `hasEKG`, and `hasPrometheus` are absent from all four files
  - assert no serialized value anywhere in the four files contains `EKGBackend` or `PrometheusSimple`
  - confirm no `*-db-sync-config.json` file changed
  - confirm the retained tracing subtree aligns with the non-core branch of `NodeController.normalizeTracingConfig(...)`
- Automated verification:
  - no mandatory Gradle or npm test run is required if the task remains limited to deployed templates and project documentation/tracking updates
  - use local machine-check commands for JSON parsing and key assertions because no repo test target exercises these deployed template files directly
  - if implementation widens into repo code unexpectedly, run the narrowest relevant verification for the widened surface
- Truthful validation boundary:
  - this task does not require live node startup or forwarded-trace validation because it is a static rollout-alignment pass for new-node template inputs only

## Risks And Open Questions

- Main risk is overfitting the shared deployed templates to the core-only branch by adding `Forwarder` everywhere without proof that these files are core-only artifacts.
- Another risk is under-migration: leaving any legacy `Trace*` booleans, file-scribe keys, or HTTP metrics bindings behind would make the template rollout inconsistent with the implemented app-side model.
- Another risk is overstating rollout completion when create-node still reads DB-backed templates; the final outcome must describe this boundary explicitly.
- Open question for future work, not this task: whether the deployed-template flow eventually needs explicit per-node-type template separation rather than a shared network baseline.
- Research note decision: a separate task-103 research artifact is optional only if final task notes, tracker state, and PRD updates are insufficient to preserve the environment-vs-DB template boundary.

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md`.
- Planning and implementation review logs live at the paths recorded below.
- Update the tasks tracker with final completion metadata for `task-103`.
- Update the PRD implementation notes and rollout/status text for the deployed-template alignment step, including the still-separate DB-backed template source used by `createNode()`.
- Record `no new research` in the final outcome if the environment-vs-DB template boundary and shared-baseline decision are fully captured in the canonical plan, PRD, and task tracker.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on the four deployed templates and required task documentation/tracking only.
- Alignment check: the plan follows the proven non-core branch of app-side config generation instead of inventing a new template-specific tracing model.
- Truthfulness check: the plan now distinguishes environment-template alignment from the separate DB-backed template source used by `createNode()`.
- Workflow check: only `update-doc` and `test` guidance were needed because this task does not edit backend/frontend/database code.
- Verification check: the plan now requires machine-checked JSON parsing and explicit forbidden-key assertions rather than read-only inspection.

## Final Outcome

- Result: `completed`
- Final implementation:
  - updated `/home/westbam/bcsh/jormanager/guild-config.json`, `mainnet-config.json`, `preprod-config.json`, and `preview-config.json` to the shared non-core dispatcher tracing baseline used by app-side generation
  - removed the legacy top-level `Trace*` booleans, `TracingVerbosity`, `defaultBackends`, `defaultScribes`, `setupBackends`, `setupScribes`, `rotation`, `hasEKG`, `hasPrometheus`, and `options` keys from those four templates
  - preserved network-specific non-tracing settings and intentionally left `TraceOptionForwarder` absent because these are shared network templates rather than proven core-only artifacts
- Verification completed:
  - machine-checked JSON parsing for all four updated templates
  - machine assertions for `UseTraceDispatcher: true`, root backends `[`"Stdout MachineFormat"`]`, `minSeverity: "Critical"`, and absence of `TraceOptionForwarder`
  - machine assertions confirming legacy top-level `Trace*` booleans, `TracingVerbosity`, `defaultBackends`, `defaultScribes`, `setupBackends`, `setupScribes`, `rotation`, `options`, `hasEKG`, `hasPrometheus`, `EKGBackend`, and `PrometheusSimple` are absent
  - exact structure assertion confirming all four `TraceOptions` trees equal the non-core Markus baseline used by `NodeController`
  - `/home/westbam/bcsh/jormanager` git status confirmed only the four intended config templates changed and no `*-db-sync-config.json` files were modified
- Final review result:
  - implementation review iteration 1 required only tracker/PRD/canonical-plan synchronization
  - implementation review iteration 2 required only canonical-plan status closure and tracker-description truthfulness updates
  - implementation review approved after the final documentation-truth fixes in iteration 3
  - approved review entry recorded in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-impl-review.md`
- Research outcome:
  - no new research; the durable environment-vs-DB template boundary is captured in this task plan, the implementation review log, the PRD implementation notes, and the task tracker completion notes
