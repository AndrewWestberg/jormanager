# Task 104 Plan

## Summary

- Task ID: `task-104`
- Title: `Remove EKG and Prometheus HTTP request plumbing`
- Why now: next small unblocked cleanup after `task-100` to align new-node backend and UI inputs with the dispatcher-era tracing design before the larger monitor migration tasks
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Remove EKG and Prometheus HTTP port fields from the create-node request contract and new-node UI plumbing, while preserving the internal persisted fields and controller-side port-derivation sequencing that later monitor-migration tasks still depend on.

In scope:
- remove `ekgPort` and `promPort` from `CreateNodeRequest`
- remove the EKG and Prometheus input controls, defaults, and validation from `AddNodeWizard.vue`
- update `NodeController.createNode()` so it no longer depends on request-supplied HTTP metrics ports during relay/core creation
- keep internal port allocation for persisted `Node.ekgPort` and `Node.promPort` alive behind the controller/config-generation seam until later monitor tasks retire that model
- add one explicit low-cost backend verification seam or equivalent directly testable helper for internal metrics-port derivation and core tracing-port sequencing
- update focused backend tests affected by the request-model change

Out of scope:
- removing persisted `Node.ekgPort` or `Node.promPort`
- changing `NodeMonitor`, `EkgService`, or any live HTTP polling behavior
- changing database schema, entities, or Liquibase
- adding tracing controls or tracing-port inputs to the UI
- changing deployed node template configs under `/home/westbam/bcsh/jormanager/`
- direct Hermod-compatible protocol work and monitor migration

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/frontend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-100-config-generation-baseline.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Used code-index summaries and searches first, then verified all important findings with live file reads
- Refreshed the code index during planning after stale search results missed live `ekgPort` and `promPort` references
- Code-index-synced findings confirmed live:
  - `CreateNodeRequest` still exposes `ekgPort` and `promPort`
  - `AddNodeWizard.vue` still renders EKG and Prometheus fields, validates them, and seeds them from parent-node defaults
  - `NodeController.createNode()` still threads `request.ekgPort` and `request.promPort` into `createConfigFile()` for relay and core creation
  - `Node.ekgPort` and `Node.promPort` are still persisted and read by `NodeMonitor`, so this task cannot retire them yet without widening into later monitor migration work

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation of this task
- Truthful planning note:
  - this task is a contract and UI cleanup, not a release-complete removal of HTTP metrics plumbing across the runtime or a guarantee that newly created nodes already have working replacement node-stats behavior

## Relevant Dependencies

- Required completed upstream task:
  - `task-100`, which already removed generated EKG and Prometheus config from newly rendered node configs
- Live code dependencies this task must respect:
  - `NodeController.createConfigFile()` still returns allocated `ekgPort` and `promPort`
  - `Node` persistence still requires `ekgPort` and `promPort`
  - `NodeMonitor` still uses persisted `node.ekgPort` for HTTP polling
- Downstream tasks this cleanup should simplify:
  - `task-301`
  - `task-302`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
  - live request contract still declares `@JsonProperty("ekgPort")` and `@JsonProperty("promPort")`
  - `toString()` still logs both request-supplied HTTP port fields
- `vue/src/components/AddNodeWizard.vue`
  - first-step form still exposes `EKG Port` and `Prometheus Port` inputs
  - local form state still carries `ekgPort` and `promPort`
  - step validation still requires both port fields to be valid
  - pool-parent initialization still copies `ekgPort` and `promPort` from the parent node into the form
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - relay creation passes `request.ekgPort` and `request.promPort` into `createConfigFile()`
  - core creation does the same before allocating `tracingPort`
  - `allocateTracingPort()` still derives core tracing ports from `promPort + 1`, so internal Prometheus-port allocation remains a real controller dependency even after request cleanup
- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - persisted node model still requires non-null `ekgPort` and `promPort`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - current node dashboard polling still constructs EKG HTTP clients and SSH port-forwards using `node.ekgPort`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - helper request builders still instantiate `CreateNodeRequest` with explicit `ekgPort` and `promPort`, so test fixtures will need updating alongside the DTO change
- `vue/src/stores/jormanager.ts`
  - `createNode(formNode: unknown)` forwards the wizard payload opaquely, so no store typing change is required unless implementation introduces one for clarity

## Files Expected To Change

- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
  - remove request-level `ekgPort` and `promPort`
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - stop depending on request-supplied HTTP metrics ports and allocate them internally for the still-persisted model
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - update request builders and add or adjust focused assertions around internal port-allocation behavior if needed
- `vue/src/components/AddNodeWizard.vue`
  - remove EKG and Prometheus input controls, related form state, validation, and parent-copy defaults
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md`
  - canonical task plan

## Implementation Approach

- Keep the backend change minimal by treating EKG and Prometheus ports as internal controller-derived values rather than user input.
- Remove `ekgPort` and `promPort` from `CreateNodeRequest` completely instead of making them optional placeholders.
- Keep `NodeController.createConfigFile()` as the internal seam that still allocates and returns `ekgPort` and `promPort` for now, because:
  - `Node` persistence still needs them
  - `allocateTracingPort()` still depends on the internally allocated `promPort`
  - later monitor-migration work still depends on those persisted fields and that sequencing, even though task-100 already removed generated HTTP metrics exposure from newly rendered configs
- Make the internal metrics-port derivation directly testable with one small seam in `NodeController` rather than leaving it implicit inside `createNode()` only.
- Preferred minimal shape:
  - extract or expose a helper that resolves internal `ekgPort` and `promPort` from host and node-type context without reading request DTO fields
  - keep that helper local to `NodeController`
  - keep `createConfigFile()` or its replacement responsible for using that resolved pair and returning it for persistence
- Minimal controller shape:
  - replace request-fed `requestEkgPort` and `requestPromPort` usage with internal auto-allocation inputs or helper defaults
  - preserve existing relay/core behavior that saves allocated `ekgPort` and `promPort` onto the created `Node`
  - do not broaden this task into changing pool inheritance or monitor consumers
- Frontend shape:
  - delete the two obsolete form groups from step 1
  - delete the matching `formNode` properties and validation computed state
  - remove those fields from the step-1 required-fields gate
  - remove parent-node copy defaults for `ekgPort` and `promPort`
  - keep the rest of the create-node payload unchanged so store messaging stays simple
- Prefer the smallest truthful test updates:
  - update `NodeControllerTest.createRequest()` for the DTO change
  - add focused assertions around the explicit internal metrics-port derivation seam so the backend regression is covered directly, not only through larger create-node flows

## Acceptance Criteria

- No create-node API request contract remains for `ekgPort` or `promPort`.
- No new-node UI control remains for EKG or Prometheus HTTP ports.
- New-node UI validation no longer depends on EKG or Prometheus port fields.
- `NodeController` no longer reads EKG or Prometheus HTTP ports from the request.
- Backend creation flow still allocates and persists `Node.ekgPort` and `Node.promPort` internally, and core tracing-port derivation still follows the internally resolved `promPort` sequencing used by current controller code.
- The plan makes no claim that newly created nodes regain working HTTP-metrics-based runtime monitoring; that rollout remains gated behind later monitor-migration tasks.
- No tracing-specific user interaction is introduced.
- No database or entity-shape change is introduced by this task.

## Verification Plan

- Static verification:
  - confirm `CreateNodeRequest` has no `ekgPort` or `promPort` properties or `toString()` references
  - confirm `AddNodeWizard.vue` has no EKG or Prometheus input, validation, or parent-default code left behind
  - confirm `NodeController.createNode()` no longer references `request.ekgPort` or `request.promPort`
  - confirm persisted `Node.ekgPort` and `Node.promPort` writes remain intact
  - confirm the new explicit controller seam for internal metrics-port derivation is the path used before core tracing-port derivation
- Automated verification:
  - update `NodeControllerTest` fixtures to build `CreateNodeRequest` without HTTP metrics fields
  - add focused `NodeControllerTest` coverage for the explicit internal metrics-port derivation seam covering:
    - first-free auto-allocation
    - collision scanning
    - core tracing-port sequencing from the resolved internal `promPort`
  - run `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
  - run `npm run build` in `vue/`
  - if the repo’s coupled frontend tasks run through Gradle as they did for recent controller-task verification, record that truthfully, but do not treat that as a substitute for the required standalone `vue` build

## Risks And Open Questions

- Main risk: over-interpreting “remove HTTP request plumbing” as permission to remove all runtime HTTP metrics plumbing now; that would conflict with the still-live `NodeMonitor` dependency.
- `allocateTracingPort()` currently depends on the internally allocated `promPort`, so controller cleanup must preserve that sequencing truthfully rather than pretending Prometheus port no longer exists anywhere.
- Because `task-100` already removed generated HTTP metrics config, newly created nodes are still not rollout-complete for node stats until later monitor migration; this task must document that limitation and avoid implying preserved working runtime monitoring for those nodes.
- Frontend payload forwarding is untyped in the store today; keeping the cleanup local to the wizard avoids unnecessary store abstraction changes.

## Required Docs, Tracking, And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md`.
- Do not write the planning review log from this pass.
- Do not update database workflow docs because no schema change is in scope.
- Mark `task-104` completed in `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` only after implementation and review are done.
- Add a narrow research note only if implementation reveals that internal HTTP metrics-port allocation cannot be cleanly separated from request plumbing without widening into later monitor tasks.

## Final Outcome

- Result: completed and review-approved.
- Implementation summary:
  - removed `ekgPort` and `promPort` from `CreateNodeRequest` and the new-node wizard so new node creation no longer exposes HTTP metrics-port inputs in the request or UI contract
  - added a small internal `NodeController.allocateMetricsPorts()` seam so relay/core creation still derives persisted `ekgPort` and `promPort` values without reading them from the request DTO
  - preserved existing core tracing-port sequencing from the internally resolved `promPort` while keeping `NodeMonitor` and entity-shape cleanup out of scope
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` (passed)
  - `npm run build` in `vue/` (passed)
- Review outcome:
  - planning completed via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104-plan-review.md`
  - implementation approved via `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104-impl-review.md`
- Durable findings:
  - removing request/UI HTTP metrics plumbing is cleanly separable from later runtime monitor migration as long as `NodeController` keeps deriving persisted metrics ports internally
  - newly created nodes still do not regain working HTTP-metrics-based runtime monitoring from this task alone because generated configs already dropped those endpoints in `task-100`; monitor migration remains the rollout gate

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104-impl-review.md`

## Self-Review

- Scope is kept narrow to request and UI cleanup plus the smallest backend seam changes needed to preserve current persistence behavior.
- No stale workflow assumption was carried in about database work; Liquibase and entity-shape changes are explicitly excluded.
- Tests and verification now cover the real regression risk explicitly: removing request fields without accidentally dropping internal metrics-port derivation or the `promPort`-to-`tracingPort` sequencing later controller code still needs.
- The plan stays internally consistent with current repo reality: generated configs no longer expose HTTP metrics, but persisted node metrics-port fields and later monitor migration work still exist; this plan no longer overclaims preserved runtime monitoring for newly created nodes.
