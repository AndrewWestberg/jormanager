Planner: Iteration 1
Timestamp: 2026-05-12T22:17:30Z

Summary:
- Drafted the canonical plan for `task-102` at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`.
- Kept the task `autonomous` and limited to `NodeController` startup-artifact wiring plus focused `NodeControllerTest` coverage.
- Identified the real implementation gap as data-flow and ordering: core startup artifacts are generated before `tracingPort` is allocated, and the pool path rewrites a parent core systemd unit from persisted core-node data.

Planned approach:
- Allocate the core tracing port earlier in the core-create path so it exists before startup artifacts are written.
- Thread `tracingPort` into `createSystemdFile()` and `createManualStartupScripts()` with the smallest signature change.
- Reuse `renderTracingListenerArgument()` as the sole source of the `--tracer-socket-network-accept 0.0.0.0:<tracingPort>` fragment.
- Feed `coreNode.tracingPort` into the pool-triggered parent-core systemd rewrite without widening scope into broader startup refactors.
- Add focused backend tests for listener presence in core startup artifacts, absence in relay/pool artifacts, and preservation of existing non-tracing arguments.

Outcome: Canonical `task-102` plan drafted and ready for critique/build.

Critiquer: Iteration 1
Timestamp: 2026-05-12T22:18:58Z

Summary:
- The plan is close on the main core-create ordering fix, but it still has one wrong boundary and two places where acceptance is stronger than the proposed verification.
- The biggest issue is the pool-triggered parent-core systemd rewrite: the current helper renders by `request.type`, not by the artifact being rewritten, so simply threading `coreNode.tracingPort` through is not enough.

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1123-1129` and `3166-3271`: the plan assumes the pool path can be fixed by passing `coreNode.tracingPort` into `createSystemdFile()`. That is incomplete. `createSystemdFile()` currently branches on `request.type`, so the pool-create path renders the pool unit shape for the parent core service. If listener rendering follows the same input, the rewritten parent core unit will still not get the core-only listener. The plan needs an explicit split between the created node type and the startup-artifact node type, or it should de-scope the pool rewrite from `task-102`.
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md:141-143` vs. `NodeController.kt:1048-1129`: the plan leaves the pool rewrite verification conditional with "if extracted into a testable seam," but the task acceptance says that path must remain wired from persisted core data. That makes a required path effectively manual. The plan should require an automated assertion for both startup-generation call sites, or narrow the task so only the directly tested path is promised.
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md:19-23,127-132` and `NodeController.kt:3094-3143`: the plan keeps manual startup scripts in scope without addressing that they still redirect stdout/stderr to `${name}.log`. That is startup-level file-log drift in a migration whose accepted model is stdout for journald. If manual scripts are touched in this task, the plan needs to either explicitly de-scope them from rollout/acceptance or state why preserving that behavior is acceptable for this task.

Non-blocking observations:
- The proposed early core `tracingPort` allocation is the simplest truthful fix for the main create flow.
- Reusing `renderTracingListenerArgument()` as the only listener-fragment seam is the right simplification.
- It would help to state expected behavior when a pool rewrite targets an older persisted core with `tracingPort = null`; no backfill is needed here, but the rollout boundary should stay explicit.

Approval bar:
- Update the implementation approach so startup rendering is driven by the artifact being generated or rewritten, not only by `CreateNodeRequest.type`, and make the pool-triggered parent-core rewrite explicit.
- Replace the conditional pool-path verification with committed automated coverage for that rewrite path, or de-scope that path from this task and acceptance text.
- Clarify the manual-start artifact stance so the task does not silently preserve a stale file-log startup path while claiming startup-generation alignment.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-12T22:20:21Z

Summary:
- Revised the canonical `task-102` plan in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`.
- Fixed the blocking pool-path boundary by making startup rendering depend on the startup artifact type, not only `CreateNodeRequest.type`.
- Tightened verification so the pool-triggered parent-core rewrite now requires automated coverage.
- Clarified the manual-start-script stance: this task only aligns listener wiring for directly generated core scripts and does not claim to resolve the existing `${name}.log` redirection drift.

Planned approach:
- Keep the implementation local to `NodeController` and reuse `renderTracingListenerArgument()` as the sole source of the core listener CLI fragment.
- Move core `tracingPort` allocation earlier in the direct core-create flow so startup artifacts are generated with the saved listener port available.
- Split startup-artifact rendering inputs from created-node request inputs so `createSystemdFile()` can render by the artifact being emitted or rewritten.
- For pool creation, render the rewritten parent core systemd unit using persisted parent-core type and `coreNode.tracingPort`, not the pool request type.
- Preserve current behavior when an older persisted parent core has `tracingPort = null`; no backfill is added in this task.
- Keep manual scripts in scope only for direct node creation so new core nodes get consistent listener wiring across both startup entry points JorManager currently emits.
- Require focused `NodeControllerTest` coverage for:
  - direct core startup artifacts containing the listener once
  - relay startup artifacts omitting the listener
  - pool-triggered parent-core rewrite rendering as a core artifact and including the listener when persisted `tracingPort` exists
  - the same pool rewrite omitting the listener when persisted `tracingPort` is null
  - preservation of existing non-tracing startup arguments

Outcome: Canonical `task-102` plan revised to address all blocking critique findings and keep the task minimal, convergent, and truthfully scoped.

