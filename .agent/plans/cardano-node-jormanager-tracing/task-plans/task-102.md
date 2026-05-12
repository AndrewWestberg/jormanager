# Task 102 Plan

## Summary

- Task ID: `task-102`
- Title: `Wire core tracing listener into startup generation`
- Why now: next critical-path startup step after `task-100` pinned dispatcher config generation and `task-101` pinned the exact core-only listener CLI fragment
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Wire the already-verified core tracing listener argument into the startup artifacts JorManager actually generates or rewrites so newly created core nodes listen on their saved tracing port, while keeping relay and pool-owned startup artifacts listener-free.

In scope:
- update `NodeController` startup-artifact generation to reuse `renderTracingListenerArgument()` instead of duplicating the flag string
- reconcile the live core-create flow so `tracingPort` exists before core startup artifacts are generated
- cover both startup-generation call sites truthfully:
  - initial core-node creation, where the created node type and startup-artifact type are both core
  - pool creation, where the created node type is pool but the rewritten startup artifact belongs to the persisted parent core node
- preserve existing deployment style and current env/systemd/manual script conventions unless a minimal signature/order change is required
- add focused backend tests in `NodeControllerTest` for generated startup wiring

Out of scope:
- changing tracing config JSON shape beyond `task-100`
- changing the listener mechanism or bind model already pinned in `task-101`
- adding `cardano-tracer`, Hermod sidecars, or any extra runtime dependency
- changing manual script stdout/stderr redirection away from `${name}.log`; this task is only about listener wiring and artifact-type correctness
- removing request-model EKG/Prometheus fields (`task-104`)
- direct tracing client / monitor migration work
- frontend changes
- deployed template updates under `/home/westbam/bcsh/jormanager/`

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101-impl-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Attempted code-index-first inspection before planning
- Deep-index doc summaries were unavailable in practice, so live planning proceeded by using code-index on the Kotlin task surfaces first, then verifying all important findings against live file reads
- Code-index-synced findings confirmed live:
  - `NodeController.createSystemdFile()` and `createManualStartupScripts()` still emit `cardano-node run` commands without the tracing listener flag
  - `NodeController.renderTracingListenerArgument()` already pins the accepted core-only CLI fragment `--tracer-socket-network-accept 0.0.0.0:<tracingPort>`
  - during core creation, startup artifacts are generated before `allocateTracingPort()` runs and before the new `Node` is saved
  - during pool creation, JorManager rewrites the parent core systemd unit using persisted `coreNode` data, but `createSystemdFile()` currently branches on `request.type`, so that path cannot be fixed by `tracingPort` threading alone
  - `NodeControllerTest` already has focused helper coverage and is the smallest truthful place to add startup-generation assertions

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation of this task
- Truthful planning note:
  - no user interaction is needed to choose the startup mechanism; `task-101` already fixed the listener shape and this task is now a wiring/data-flow change

## Dependencies

- Required completed upstream tasks:
  - `task-100`
  - `task-101`
- Live code dependencies:
  - `NodeController.renderTracingListenerArgument()` as the single listener-fragment seam
  - existing `createNode()` flow ordering around `createConfigFile()`, `createEnvFile()`, `createSystemdFile()`, `createManualStartupScripts()`, and `allocateTracingPort()`
- Downstream tasks unblocked by this work:
  - `task-200`
  - `task-203`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - core creation currently calls `createSystemdFile()` and `createManualStartupScripts()` before `allocateTracingPort()`.
  - pool creation currently rewrites only the parent core systemd unit via `createSystemdFile(request, host, hostConnection, coreNode.name, ...)`.
  - that helper currently renders by `request.type`, so the pool-triggered parent-core rewrite is using the wrong startup-artifact type today.
  - `createManualStartupScripts()` only writes scripts for the node named by `request.name`, so pool creation does not currently regenerate a parent-core manual startup script.
  - `renderTracingListenerArgument()` already provides the exact core-only listener fragment and should be reused rather than duplicated.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - current tests cover tracing-port allocation, listener-argument rendering, and managed config generation.
  - there is no test yet that pins listener presence or absence inside generated startup artifacts, including the pool-triggered parent-core rewrite.

## Files Expected To Change

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - split startup-artifact rendering inputs from created-node request inputs with the smallest truthful signature/order change
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - add focused startup-generation assertions for direct core creation, relay creation, and pool-triggered parent-core rewrite
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`
  - canonical task plan

## Implementation Approach

- Keep the change local to `NodeController`.
- Reuse `renderTracingListenerArgument(nodeType, tracingPort)` as the only source for the listener CLI fragment.
- Minimal core-create flow change:
  - allocate `tracingPort` immediately after `createConfigFile()` returns `promPort`, before `createSystemdFile()` and `createManualStartupScripts()` are called
  - pass that `tracingPort` into startup-artifact generation for core creation only
- Minimal startup-generation seam change:
  - make startup rendering accept the type of artifact being emitted or rewritten, not only `CreateNodeRequest.type`
  - extend `createSystemdFile()` to render from an explicit startup-artifact node type plus `tracingPort: Int?`
  - extend `createManualStartupScripts()` only as needed for the direct create path so core manual scripts get the same listener flag as core systemd units
  - append the rendered listener argument only when the helper returns a value
  - keep the rest of the generated command layout unchanged
- Pool-path handling:
  - when pool creation rewrites the parent core systemd unit, preserve the existing bulk-credentials startup shape while driving listener eligibility from persisted parent core node type and `coreNode.tracingPort`, not from `request.type` alone
  - do not add pool-node listener wiring; the rewritten unit still belongs to the parent core service
  - if the persisted parent core has `tracingPort = null`, preserve the no-listener result rather than backfilling in this task
  - do not broaden this task into generating a parent-core manual startup script during pool creation because that artifact is not currently regenerated there
- Manual-script stance:
  - keep manual scripts in scope only for direct node creation so a newly created core node has consistent listener wiring across both startup entry points JorManager emits today
  - explicitly do not treat this task as the fix for manual-script file-log redirection; that startup/logging drift remains outside `task-102`
- Prefer one small startup-command rendering seam if needed for testability, but stay inside `NodeController` and avoid introducing new abstractions unless the existing string assembly becomes untestable otherwise

## Acceptance Criteria

- New core-node startup artifacts include the verified CLI listener fragment `--tracer-socket-network-accept 0.0.0.0:<tracingPort>`.
- Relay and pool startup artifacts do not include a tracing listener.
- Pool creation preserves the parent-core rewrite behavior while ensuring the regenerated parent core systemd unit keeps the existing bulk-credentials runtime shape and adds listener eligibility from persisted parent-core data rather than the pool request type alone.
- Pool-triggered parent-core rewrite is covered by an automated assertion, including the `tracingPort = null` no-listener case if that branch remains supported.
- Existing non-tracing startup arguments remain intact.
- No `cardano-tracer` dependency or alternate listener mechanism is introduced.
- The change preserves JorManager's existing deployment style apart from the minimal ordering/signature updates required to make `tracingPort`, startup-artifact shape, and listener semantics available.
- Manual startup scripts may still redirect output to `${name}.log`; this task only requires listener-flag consistency, not stdout/journald redesign.

## Verification Plan

- Static verification:
  - confirm startup generation reuses `renderTracingListenerArgument()` instead of hardcoding the flag in multiple places
  - confirm core-create ordering now makes `tracingPort` available before startup artifacts are written
  - confirm pool-path parent-core rewrite preserves the bulk-credentials startup shape while rendering listener eligibility from persisted parent-core type plus `coreNode.tracingPort`, not from `request.type` alone
- Automated verification:
  - add focused `NodeControllerTest` coverage for core startup content containing the listener flag exactly once
  - add focused assertions that relay startup content does not contain the listener flag
  - add focused assertions that pool creation rewrites the parent core systemd content with the existing bulk-credentials startup shape and includes the listener when persisted `coreNode.tracingPort` is set
  - add a focused assertion that the same parent-core rewrite omits the listener when persisted `coreNode.tracingPort` is null
  - assert an existing non-tracing startup argument such as `--config ${CONFIG}`, `--shelley-operational-certificate ${SHELLEY_OPCERT}`, or `--bulk-credentials-file ${BULK_CREDENTIALS}` still remains in the generated artifact as appropriate to the startup shape under test
- Command target once implementation exists:
  - run `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
  - if unrelated workspace failures block the target, record that truthfully

## Risks And Open Questions

- The main risk is accidental scope creep into broader startup-template refactoring when the real need is only ordering plus a small separation between created-node type and startup-artifact type.
- Pool creation currently rewrites only the parent core systemd unit, not a parent-core manual script; implementation should preserve that truth.
- Existing request/UI plumbing still carries `ekgPort` and `promPort`; this task should continue tolerating that temporary state rather than trying to clean it up early.
- Manual scripts still redirect to `${name}.log`; this plan treats that as known pre-existing drift and avoids claiming that `task-102` completes the stdout/journald cleanup.
- If startup-string assertions are hard to reach without extracting a tiny render seam, keep that seam internal to `NodeController` and limited to this artifact generation concern.

## Required Docs, Tracking, And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`.
- Mark `task-102` complete in `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` only after implementation and review are done.
- Add an implementation review log at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102-impl-review.md` after coding.
- Do not write the planning review log from this planning pass.
- Add a narrow research note only if implementation uncovers a mismatch between planned startup wiring and actual runtime expectations.

## Final Outcome

- Result: completed and review-approved.
- Implementation summary:
  - direct core creation now allocates `tracingPort` before writing startup artifacts and threads that saved port into both systemd and manual startup generation
  - `NodeController` now renders startup artifacts with separate startup-shape and listener-semantics inputs, so the pool-triggered parent-core rewrite keeps the existing bulk-credentials unit shape while still adding the core-only tracing listener from persisted `coreNode.tracingPort`
  - startup-content rendering stays centralized around `renderTracingListenerArgument()` with small internal helpers used only for startup generation and focused test coverage
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` (passed)
  - this Gradle target also ran the repo-coupled frontend `vitest` suite and frontend build successfully as part of the existing build graph
- Review outcome:
  - planning approved via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102-plan-review.md`
  - implementation approved via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102-impl-review.md`
- Durable findings:
  - the pool-triggered parent-core rewrite must keep the bulk-credentials startup branch and only borrow core semantics for listener rendering; changing that branch to a plain core unit breaks the env/unit contract
  - manual startup scripts still redirect to `${name}.log`; that known drift remains out of scope for `task-102`

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102-impl-review.md`

## Self-Review

- Scope stays on startup-generation wiring and the real `tracingPort` plus startup-artifact-type gaps; it does not widen into config, request-model, monitor, manual-script logging cleanup, or template-update work.
- Workflow text matches current repo reality: backend/test guidance is current, and no stale claim is made that startup artifacts already carry listener wiring.
- Test coverage needs are called out explicitly for both startup-generation call sites, which are the main missing regression surfaces for this task.
- The plan is internally consistent with live `NodeController` behavior: core creation needs an ordering change, and pool creation needs rendering driven by the rewritten parent-core artifact rather than by the pool request type.
