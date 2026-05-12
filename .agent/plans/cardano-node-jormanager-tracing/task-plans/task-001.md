# Task 001 Plan

## Summary

- Task ID: `task-001`
- Title: `Add tracing port persistence for nodes`
- Why now: first unblocked pending critical-path task
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Add only the schema and backend entity persistence needed for `nodes.tracing_port`.

In scope:
- add nullable `tracing_port` to the `nodes` table
- map nullable `tracingPort` on `Node`
- update direct `Node(...)` construction sites that must compile once the entity shape changes
- preserve existing node serialization behavior so `tracingPort` is present when non-null and tolerated when null

Out of scope:
- tracing port allocation logic
- config generation changes
- startup wiring
- UI controls or user-entered tracing port fields
- relay or pool tracing enablement behavior beyond storing `null`

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/database.md`
  - `.agent/workflows/test.md`
- Skills consulted: none
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built deep index for the repo
- Verified important indexed findings with live file reads before finalizing the plan

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - `Node` is a Kotlin data class used as the JPA entity and as the websocket payload returned by `NodeController.getNodes()` and create-node flows.
  - Current persisted network ports are `ekgPort` and `promPort`; there is no tracing field yet.
  - Positional `Node(...)` construction still exists in `JormanagerApplication.kt` and `HostConnectionTest.kt`, so constructor compatibility depends on appending the new field at the tail with a default rather than inserting it mid-constructor.
- `src/main/resources/db/liquibase-changelog.xml`
  - master changelog currently includes changes through `038-update-transaction-schema.xml`
  - new node-schema migration should be appended as the next include, not folded into an existing file
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - relay creation saves a `Node` directly
  - core creation saves a `Node` directly
  - pool creation reuses the parent core config and ports and also saves a `Node` directly
  - `createConfigFile()` currently returns only `(configFileId, ekgPort, promPort)`; that remains task-002 scope
- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
  - no tracing port request field exists, which matches the no-UI/no-user-input requirement for this task
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - existing disabled test constructs `CreateNodeRequest`
  - this disabled test is not a valid approval path for this schema task
- `src/main/kotlin/com/swiftmako/jormanager/repositories/NodeRepository.kt`
  - repository uses full `Node` entities, with no custom projections that need separate schema mapping updates for this field
 - `src/main/kotlin/com/swiftmako/jormanager/JormanagerApplication.kt`
   - bootstrapping code still constructs `Node` positionally, reinforcing the need for a tail-appended nullable field with a default
 - `src/test/kotlin/com/swiftmako/jormanager/controllers/utils/HostConnectionTest.kt`
   - test helpers also construct `Node` positionally and should remain source-compatible under the same tail-appended defaulting strategy

## Expected Change Set

- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - append `@Column(name = "tracing_port") val tracingPort: Int? = null` at the end of the constructor to preserve positional constructor compatibility
- `src/main/resources/db/changelog/039-update-node-schema.xml`
  - add nullable `tracing_port` column to `nodes`
- `src/main/resources/db/liquibase-changelog.xml`
  - include `039-update-node-schema.xml`
- tests
  - optional only if a low-cost backend seam already exists; do not introduce broad new test scaffolding for this schema task

## Implementation Notes

- Prefer the smallest truthful change set.
- Keep `tracingPort` nullable in both Liquibase and the entity because relay and pool nodes do not receive a tracing port in phase 1.
- Append the new field at the end of the `Node` constructor with a default `null` value so positional `Node(...)` call sites remain compatible and `NodeController` stays out of scope.
- Do not add `CreateNodeRequest.tracingPort` or frontend plumbing in this task.
- Because `Node` is serialized directly to websocket clients, adding the nullable entity property is sufficient for exposure when future work starts saving a value.
- Task-002 dependency context only: allocation should likely flow from the existing `createConfigFile` / node-save path, but this task should not widen into allocation or scanning logic.

## Verification Plan

- Static verification:
  - confirm Liquibase master changelog includes the new `039` file once
  - confirm `Node` maps `tracingPort` as nullable
  - confirm the new field is appended at the constructor tail so positional `Node(...)` construction sites still compile without unrelated edits
- Runtime verification:
  - run backend compile or test task sufficient to compile all Kotlin sources touched by the entity change
  - do not trigger Liquibase against the local database during this task; manual migration verification is deferred until the broader tracing work is validated end to end
  - if no focused automated persistence test is added, record that gap truthfully rather than treating the disabled `NodeControllerTest` as evidence
- Acceptance checks:
  - schema includes `nodes.tracing_port`
  - backend can read and persist nullable `tracingPort`
  - no tracing-specific UI control or request field is introduced

## Risks And Watchouts

- `Node` constructor changes can ripple into multiple direct `Node(...)` call sites outside the immediate create-node path; implementation should search all constructor uses before finishing.
- Existing database rows will have `NULL` in `tracing_port`; any accidental non-null Kotlin type would break reads.
- There is little existing focused coverage around node entity serialization, so implementation should avoid broad refactors.

## Planning Outcome

- Critique required tightening constructor-compatibility and verification details.
- Approved plan keeps changes limited to `Node.kt`, a new `039` Liquibase changeset, and the master changelog include.
- `NodeController.kt`, request models, and UI plumbing are no longer expected task-001 changes unless live implementation truth forces a narrower compatibility fix.

## Build Outcome

- Implementation stayed within the approved minimal code-only scope.
- Manual Liquibase execution and schema-application validation are intentionally deferred until the user runs the broader migration flow later.
- Review outcome: implementation review approved on the first pass.
- Final changed files:
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - `src/main/resources/db/changelog/039-update-node-schema.xml`
  - `src/main/resources/db/liquibase-changelog.xml`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001-impl-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-001-tracing-port-persistence.md`

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001-impl-review.md`

## Self-Review

- Scope stays limited to schema/entity persistence for `nodes.tracing_port`.
- No stale workflow text copied into implementation claims.
- Migration, entity, direct serialization impact, and verification needs are all covered.
- Task-002 is noted only as dependency context, not as current work.
