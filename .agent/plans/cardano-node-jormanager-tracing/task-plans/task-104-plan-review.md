Planner: Iteration 1
Timestamp: 2026-05-12T22:35:48Z
- Approved draft plan created at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md` for `task-104: Remove EKG and Prometheus HTTP request plumbing`.
- Interaction mode: `autonomous`.
- Consulted docs/workflows: `.agent/readme.md`, `.agent/system/architecture.md`, `.agent/workflows/backend.md`, `.agent/workflows/frontend.md`, `.agent/workflows/test.md`, `.agent/workflows/update-doc.md`, tracing PRD, tasks JSON, `task-100`/`task-101`/`task-102` canonical plans, and research notes `task-100-config-generation-baseline.md` plus `hermod-protocol-reference-note.md`. Skills consulted: none.
- Code-index was set to the repo and refreshed during planning; indexed findings were then verified against live files before writing the plan.
- Main repo findings:
  - `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt` still exposes request-level `ekgPort` and `promPort`.
  - `vue/src/components/AddNodeWizard.vue` still renders EKG/Prometheus inputs, validates them, stores them in `formNode`, and copies them from parent nodes.
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt` still reads `request.ekgPort` and `request.promPort` for relay/core create flows even though task-100 already removed generated HTTP metrics config from rendered node configs.
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt` still persists `ekgPort` and `promPort`, and `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` still depends on persisted `node.ekgPort`, so this task cannot truthfully remove internal/persisted metrics-port plumbing yet without widening into later monitor migration.
  - `allocateTracingPort()` still derives the tracing listener from the internally allocated Prometheus port, so request cleanup must preserve internal allocation sequencing.
Outcome: Plan scope was kept narrow to removing user-facing/request-supplied EKG and Prometheus port plumbing while preserving internal controller allocation and persisted node fields until `task-301`/`task-302`. Files expected to change were pinned to `CreateNodeRequest.kt`, `NodeController.kt`, `NodeControllerTest.kt`, `vue/src/components/AddNodeWizard.vue`, and the canonical task plan doc. No database/Liquibase work was included. Planning result: approved, with build status left `not started` for implementation.

Critiquer: Iteration 1
Timestamp: 2026-05-12T22:37:37Z

Blocking findings:
- `task-104.md:14-15,20-21,149,170-172` overclaims that keeping internal `Node.ekgPort` and `Node.promPort` allocation will preserve current `NodeMonitor` behavior. Live code already renders new managed configs without HTTP metrics exposure in `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3394-3433`, and the PRD status already records that rollout remains gated because monitor migration has not happened yet in `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md:775-778`. Revise the plan so it only promises contract/UI cleanup plus preservation of persisted fields and controller sequencing, not working runtime node-stats behavior for newly created nodes.
- `task-104.md:139-166` leaves the main backend regression under-specified. After removing `CreateNodeRequest.ekgPort` and `CreateNodeRequest.promPort`, the controller must still derive internal metrics ports deterministically, but the plan only says to “add or adjust focused assertions if needed.” Live code still depends on request-fed ports at both create paths and inside `createConfigFile()` in `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:202-208`, `:1031-1036`, `:3288-3313`, while existing tests still build the old DTO in `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt:83-123`. Require one explicit low-cost seam or equivalent directly testable helper for internal metrics-port allocation/passthrough, with coverage for auto-allocation, collision scanning, and core tracing-port sequencing from the resolved internal `promPort`.
- `task-104.md:160-166` makes frontend verification optional even though the store forwards an untyped payload in `vue/src/stores/jormanager.ts:711-712` and the cited plan names no existing wizard test. This task removes live template bindings, refs, and validators from `vue/src/components/AddNodeWizard.vue:109-133` and `:736-900`, so backend tests cannot catch SFC compile/runtime regressions. Make `npm run build` in `vue/` part of the required verification, not an optional fallback.

Non-blocking observations:
- The task boundary is otherwise well chosen: no schema changes, no `NodeMonitor` refactor, no `EkgService` retirement, and no tracing UI widening.
- Keeping the internal allocator inside `NodeController` is the simplest truthful shape here; there is no need for a new shared service just for this cleanup.
- The plan is right to leave `vue/src/stores/jormanager.ts` untouched unless implementation introduces actual typing there.

Approval bar:
- Remove any acceptance or verification language that implies task-104 preserves working HTTP metrics for newly created nodes; replace it with the narrower truth that persisted metrics-port fields and controller sequencing remain until later monitor tasks retire them.
- Add one mandatory backend verification seam for internal `ekgPort` and `promPort` derivation after DTO cleanup, with focused tests for first-free allocation, collision handling, and core tracing-port sequencing from the internally resolved `promPort`.
- Require `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` and `npm run build` in `vue/` as the minimum automated verification set.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-12T22:39:51Z
- Revised `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md` in response to the critique and kept interaction mode `autonomous`.
- Consulted again: `.agent/readme.md`, `.agent/system/architecture.md`, `.agent/workflows/backend.md`, `.agent/workflows/frontend.md`, `.agent/workflows/test.md`, `.agent/workflows/update-doc.md`, the tracing PRD, tasks JSON, research files under `.agent/plans/cardano-node-jormanager-tracing/research/`, the canonical `task-104.md`, and `task-104-plan-review.md`.
- Code-index-first planning remains documented and unchanged; the revision pass stayed grounded in the already verified live seams for `CreateNodeRequest`, `AddNodeWizard.vue`, `NodeController`, `Node`, and `NodeMonitor`.
- Main fixes applied to the canonical plan:
  - narrowed scope, acceptance, risk, and checkpoint wording so the plan no longer overclaims preserved working runtime HTTP-metrics monitoring for newly created nodes; it now only preserves persisted metrics-port fields and controller-side sequencing until later monitor tasks
  - required one explicit low-cost backend seam or equivalent directly testable helper in `NodeController` for internal `ekgPort`/`promPort` derivation, with focused test coverage for first-free allocation, collision scanning, and core tracing-port sequencing from the resolved internal `promPort`
  - upgraded frontend verification from optional to required by making `npm run build` in `vue/` part of the minimum automated verification set alongside `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
Outcome: Canonical `task-104.md` now matches the critique approval bar. Task scope remains narrowly on request/UI cleanup plus the smallest backend seam needed to keep internal metrics-port derivation testable and sequenced correctly. No schema, `NodeMonitor`, `EkgService`, or tracing-UI scope was added.

