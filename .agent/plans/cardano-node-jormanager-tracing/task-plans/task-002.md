# Task 002 Plan

## Summary

- Task ID: `task-002`
- Title: `Allocate and save core tracing ports`
- Why now: next unblocked critical-path task after `task-001`
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Teach node creation to allocate and persist `tracingPort` for new core nodes only.

In scope:
- allocate a tracing port during core-node creation starting at `promPort + 1`
- scan upward with the existing host-local port probe until a free port is found
- persist `Node.tracingPort` for newly created core nodes
- persist `null` for relay and pool nodes in phase 1
- add the smallest truthful backend verification for core, collision, and non-core behavior

Out of scope:
- tracing listener startup wiring
- tracing config generation changes
- any trace-forward protocol work
- any frontend or request-model tracing port field
- retroactive assignment for existing nodes
- relay or pool tracing enablement

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/database.md`
  - `.agent/workflows/test.md`
- Skills consulted: none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-001-tracing-port-persistence.md`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001.md`
  - no existing `task-002*` docs were present before this plan

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Refreshed and deep-indexed the repo for this task
- Verified important indexed findings against live file reads before finalizing the plan

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none for implementation planning or code changes in this task
  - broader manual runtime validation against a real host remains a later operator checkpoint, not a blocker for this minimal code task
- Rejected user-facing fields:
  - do not add `tracingPort` to `CreateNodeRequest`
  - do not add Add Node wizard input or any other UI control for tracing port in phase 1

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `createNode()` is the only live creation flow and already splits relay vs core/pool behavior.
  - relay nodes are saved directly in the relay branch around lines `183-252`.
  - core and pool nodes are saved in the shared branch around lines `993-1162`.
  - `createConfigFile()` around lines `3366-3430` already allocates `ekgPort` and `promPort` using `isPortUsed(hostConnection, port)`.
  - `isPortUsed()` at lines `3433-3436` is the existing host-local port probe and should be reused rather than replaced.
- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - `Node` already has nullable persisted `tracingPort` at the constructor tail, so this task is controller allocation and persistence only.
- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
  - no tracing port field exists, which matches the fixed phase-1 decision to keep tracing-port input internal.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - existing test is disabled and not a trustworthy approval path for this task.
  - current controller tests do not provide a clean seam around `HostConnection` construction, so testing must stay focused and realistic.
- `src/main/kotlin/com/swiftmako/jormanager/repositories/NodeRepository.kt`
  - repository seam is unchanged; persistence is via full `Node` saves already used in `createNode()`.

## Planned Implementation Shape

- Keep the change in `NodeController`.
- Reuse the existing `isPortUsed(hostConnection, port)` logic for tracing-port probing.
- Add one tiny allocation helper in `NodeController` as the explicit test seam for this task.
- Make that helper take the resolved node type, the resolved `promPort`, and a lightweight port-probe function so tests do not depend on `HostConnection` or the full `createNode()` path.
- Allocate tracing port only after `promPort` is known for a core node.
- Use the fixed policy from the PRD and tasks JSON:
  - start at `promPort + 1`
  - while the port is in use on that host, increment by one
  - save the first free result as `tracingPort`
- Save `tracingPort = null` in both non-core cases:
  - relay branch
  - pool branch within the shared core/pool flow
- Do not widen `createConfigFile()` to tracing config generation in this task unless implementation truth forces a tiny signature change strictly for locality.

Concrete seam to require in implementation:
- add a small helper with behavior equivalent to `allocateTracingPort(nodeType, promPort, isPortUsed): Int?`
- wire it from `createNode()` with the existing host-local probe model, for example by passing a lambda that delegates to `isPortUsed(hostConnection, port)`
- keep the helper focused on allocation policy only; do not move broader creation logic into it

## Expected Change Set

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - add the minimal allocation helper seam for tracing-port policy
  - add a minimal tracing-port allocation step for new core nodes by calling that helper
  - thread the saved `tracingPort` into the two `Node(...)` save paths
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - add focused unit tests against the new helper seam for first-free selection, upward collision scan, and relay/pool null behavior
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002.md`
  - canonical task plan for this work

## Implementation Notes

- Prefer the smallest truthful solution inside `NodeController`.
- Do not add a new request property or frontend plumbing just to carry an internal derived port.
- Do not allocate tracing ports for relay or pool nodes in phase 1 even though pool nodes share the core/pool code path.
- Keep the allocation host-local by using the same remote `ss -tulw` probe already used for `ekgPort` and `promPort`.
- Determinism means the chosen tracing port is a pure function of the saved `promPort` plus current host port occupancy at creation time; it does not require a new global allocator.
- Avoid introducing a new service or abstraction unless needed to make testing possible with materially less risk.

## Verification Plan

- Static verification:
  - confirm relay-node saves explicitly persist `tracingPort = null`
  - confirm core-node saves persist a computed tracing port
  - confirm pool-node saves persist `tracingPort = null`
  - confirm allocation starts from `promPort + 1` and scans upward using the existing port-probe behavior
- Automated verification target:
  - require one concrete low-cost seam inside `NodeController`: a tiny allocation helper that accepts a port-probe function and returns `Int?`
  - add unit tests for `core` returning the first free port when `promPort + 1` is unused
  - add unit tests for `core` scanning upward until the first unused port when one or more collisions are reported
  - add unit tests for `relay` and `pool` returning `null` without probing for a tracing port
- Test strategy constraint:
  - the existing disabled `NodeControllerTest` is not a truthful approval path for this task
  - do not widen into a controller integration-test refactor; approval for this task should come from the new helper tests plus static confirmation of the save sites
- Command guidance:
  - run targeted backend tests for `NodeControllerTest` covering the helper seam, plus a focused backend compile if needed
  - broader runtime validation on a real node host is deferred to later tracing tasks

## Risks And Watchouts

- Port probing is advisory rather than reserving the port, but that matches the existing EKG/Prometheus allocation model and is acceptable for this phase.
- The shared core/pool branch makes it easy to accidentally assign a tracing port to pool nodes; implementation must gate on `request.type == NODE_TYPE_CORE` only.
- Widening this task into config or startup generation would be scope creep; those changes belong to `task-100` and `task-102`.
- Over-engineering a reusable allocator or introducing user-facing inputs would conflict with the fixed minimal phase-1 decisions.

## Planning Outcome

- Approved plan keeps the solution inside existing `NodeController` seams.
- `createConfigFile()` and `isPortUsed()` provide the right existing model for host-local scanning, so no new allocator subsystem is planned.
- `CreateNodeRequest` stays unchanged and user-facing tracing-port input is explicitly rejected.
- Test coverage is now explicit rather than optional: one small helper seam in `NodeController`, with unit tests for first-free core allocation, upward collision scanning, and relay/pool null behavior, without turning this task into a controller-test refactor.

## Final Outcome

- Implementation completed in `NodeController` with a minimal `allocateTracingPort` helper that applies the approved `promPort + 1` upward scan only for `core` nodes.
- Relay-node saves now persist `tracingPort = null` explicitly, and the shared core/pool save path threads the computed tracing port so `core` receives a saved port while `pool` remains `null`.
- `CreateNodeRequest` and frontend plumbing stayed unchanged, preserving the phase-1 no-user-input decision.
- Review result: approved in the latest implementation review entry.
- Verification result:
  - focused helper tests were added in `NodeControllerTest`
  - `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` was attempted but backend test execution remained blocked by unrelated pre-existing `NodeController.kt` compile failures involving missing extension imports/call sites already present in the workspace
  - frontend tests/build ran incidentally via Gradle and passed
- Residual gap:
  - rerun the focused backend test once the unrelated workspace compile breakage is resolved elsewhere

## Required Docs And Research Updates

- Update task tracker state for `task-002` to completed with scoped verification notes.
- Record durable findings in a dedicated `task-002` research note, including the helper-seam decision and the unrelated backend compile blocker observed during verification.
- PRD unchanged for this task because implementation matched the existing design intent without introducing new tracing-port behavior or changing fixed decisions.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002-impl-review.md`

## Self-Review

- Scope stays limited to allocation and persistence of `tracingPort` for core-node creation.
- No stale workflow text or fake verification claims were carried into the plan.
- Required docs, task sources, prior research, and live task surfaces were all reviewed.
- The plan is consistent with fixed decisions: core-only tracing port, `promPort + 1` upward scan, relay/pool null, and no user-facing tracing-port field.
