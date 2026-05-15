# Task 400 Plan

## Summary

- Task ID: `task-400`
- Title: `Sync plan docs and prompt artifacts`
- Why now: `task-103` closed the last tracked implementation-alignment step and its approved review explicitly deferred the remaining PRD, prompt, and plan-index cleanup to `task-400`, while the live plan artifacts still present stale active-project and draft-status wording
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Synchronize the tracing plan's documentation and tracking artifacts to the already-landed implementation state, without widening back into code changes, rollout execution, or historical backfill.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md` as the canonical task plan
- update `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md` so its status, implementation notes, resolved forward-looking sections, and rollout wording truthfully reflect the completed implementation state
- update `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` so `task-400` records completion truthfully and the plan metadata reflects that no tracked execution work remains pending
- apply minimal archival cleanup to `.agent/plans/cardano-node-jormanager-tracing/prompt.md` so it preserves historical orchestration guidance while no longer reading like an active implementation prompt for an unfinished task graph
- update `.agent/plans/readme.md` so the tracing plan's index/status entry no longer remains `Draft`
- verify whether any additional durable research note is actually needed for final-state evidence

Out of scope:
- backend, frontend, database, config-template, or runtime code changes
- DB-backed template import or sync work for `FileRepository` records used by `NodeController.createConfigFile()`
- remote-host, live-node, firewall, journald, or forwarded-trace validation
- rewriting historical PRD or review-log history beyond appending the new truthful task-400 outcome updates in the normal task artifacts
- creating a new research note unless the sync work uncovers a durable gap not already captured by the PRD, tasks tracker, prompt, or existing task-specific research

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
  - `.agent/plans/readme.md`
- Workflows consulted:
  - `.agent/workflows/update-doc.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none needed
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-impl-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-301-node-monitor-direct-datapoint-outcome.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-302-http-metrics-cleanup.md`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Built the deep index before the document review because this task touches many plan artifacts across the same plan directory
- Verified the required post-selection sync state with `code-index_get_settings_info` and `code-index_get_file_watcher_status`
- Confirmed the watcher is active, the project path is correct, and the index is ready, so no `refresh_index` call was needed during planning
- Code-index was used as guidance for plan-artifact discovery, but all material status findings were re-verified against the live files before drafting this plan because this is a documentation-truth task rather than a symbol-structure task

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation
- Truthful autonomy basis:
  - the task is limited to repo-local documentation and tracking artifacts, and final verification is static consistency checking rather than environment-owned validation

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-103`
- Practical live-repo dependencies this task must stay aligned with:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103-impl-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-302.md`
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- Downstream tasks this work should unblock:
  - none; this task should close the tracked tracing plan workstream

## Verified Task Surfaces

- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - still reports `**Status:** 📝 Draft`
  - still says rollout is gated until later plan-artifact synchronization completes, which is stale once `task-400` finishes
  - still contains resolved forward-looking sections such as `## Open Questions` and `## Implementation Readiness Summary` that read like pre-implementation guidance even though later tasks already settled several items
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - still shows `task-400` as `pending`
  - remains the source of truth for final task completion metadata and should be updated without inventing new implementation scope
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - still frames this plan as an active implementation project with task selection, planning, build loops, and mandatory task commits
  - should be preserved as historical orchestration guidance, but needs minimal completed-plan framing so it no longer implies new work is still queued under this plan
- `.agent/plans/readme.md`
  - still lists the tracing plan as `Draft`
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-103.md` and `task-103-impl-review.md`
  - already capture the critical residual boundary that deployed filesystem templates were aligned but DB-backed template content used by `createConfigFile()` remains a separate operational surface
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - remains the live repo anchor for the DB-backed template boundary referenced by the final docs and must not be contradicted by doc-sync wording

## Artifact Update Decision

- Update now:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - `.agent/plans/readme.md`
- Verify but do not update unless a real inconsistency is discovered:
  - `.agent/readme.md`
  - existing task-specific research notes under `.agent/plans/cardano-node-jormanager-tracing/research/`
- Research-note decision:
  - no new research note is expected at plan time because the durable final-state evidence already exists in the PRD implementation notes, `task-103` canonical and review docs, and the `task-301` and `task-302` research notes
  - if implementation uncovers a genuinely new durable boundary not captured there, add one narrow research note and cross-reference it from the PRD; otherwise record `no new research`

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md`
  - canonical task plan
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - final status, resolved-question cleanup, and implementation-note synchronization
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `task-400` completion metadata and final plan-state sync
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md`
  - minimal completed-plan archival cleanup
- `.agent/plans/readme.md`
  - tracing plan index/status entry
- `.agent/plans/cardano-node-jormanager-tracing/research/*.md`
  - only if the sync work discovers a real uncaptured durable finding

## Implementation Approach

- Keep the solution narrow and documentation-only:
  - do not reopen implementation tasks that are already complete
  - do not convert residual operational boundaries into new code work
- PRD synchronization:
  - add a `task-400` implementation note that closes the documentation-sync pass
  - move the PRD status from draft to completed if the final artifacts are consistent
  - replace the stale "later plan-artifact synchronization" gating wording with final wording that preserves the already-documented DB-backed template boundary as outside this plan, not as unfinished repo implementation
  - sweep resolved forward-looking sections such as `## Open Questions`, `## Implementation Readiness Summary`, and any remaining implementation-time verification language already settled by completed tasks so they read as final documented history instead of open execution guidance
- Tasks JSON synchronization:
  - mark `task-400` completed with truthful `completedAt` and `completionNotes`
  - update `metadata.updated` and version if needed for the final artifact change set
  - keep the task graph historical rather than redesigning it; only change fields needed to make the final state truthful
- Prompt synchronization:
  - keep `prompt.md` as a durable historical orchestration artifact for this plan rather than replacing it with a new artifact type
  - add only the minimum completed-plan or archival framing needed so future readers understand the prompt is retained for historical context and reuse, not live execution of an unfinished task graph
  - preserve the useful anchors to PRD, tasks, task plans, research, and repo boundaries, but remove or archive stale instructions that imply new unblocked tasks still exist
- Plans index synchronization:
  - update `.agent/plans/readme.md` to list the tracing plan as completed
  - leave the top-level `.agent/readme.md` unchanged unless verification finds a concrete stale reference that depends on plan status
- Research handling:
  - prefer `no new research` if all durable final-state evidence is already captured by the synchronized plan docs

## Acceptance Criteria

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md` exists and truthfully records this task as a narrow documentation or tracking synchronization pass
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md` no longer reports the plan as draft or still gated on later plan-artifact synchronization, while still preserving the DB-backed template boundary documented by `task-103`
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md` no longer leaves already-resolved listener, protocol-pin, monitor-migration, or readiness items phrased as open implementation work, while still preserving any truthful residual follow-up boundary such as the separate DB-backed template import surface
- `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` marks `task-400` complete with truthful notes and leaves no tracked tracing-plan task pending
- `.agent/plans/cardano-node-jormanager-tracing/prompt.md` no longer instructs active task selection or implementation loops for this completed plan and instead reflects its final historical orchestration role with minimal archival cleanup
- `.agent/plans/readme.md` lists the tracing plan status truthfully as completed
- No new research note is added unless the sync work discovers a durable final-state fact not already preserved elsewhere; if none is needed, the final outcome says so explicitly

## Verification Plan

- Static verification:
  - re-read the updated PRD, tasks JSON, prompt, plans index, and canonical task-400 plan for cross-consistency
  - confirm the PRD status is completed and includes a truthful `task-400` closure note
  - confirm the PRD's `Open Questions`, `Implementation Readiness Summary`, and other resolved implementation-era uncertainties no longer read like unfinished execution work where later completed tasks already settled them
  - machine-parse `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` as valid JSON and assert `task-400.status == "completed"`
  - assert no tracing-plan task remains `pending` after the final sync
  - confirm `.agent/plans/readme.md` lists the tracing plan as `Completed`
  - confirm `prompt.md` no longer contains stale active-execution guidance for this completed task graph while still preserving the durable orchestration anchors, fixed decisions, and repo-boundary notes with minimal archival framing
  - confirm the synchronized docs still preserve the verified `FileRepository` or DB-backed template boundary rather than silently erasing it
- Automated verification:
  - no Gradle or npm test run is required if the task remains documentation-only
  - use local JSON parsing or equivalent machine checks only for the tasks tracker because this task does not change executable code
- Truthful validation boundary:
  - this task does not require runtime validation because it changes only project documentation, tracking, and orchestration artifacts

## Risks And Open Questions

- Main risk is overstating final rollout completeness by erasing the already-documented DB-backed template boundary from `task-103` and the live controller path.
- Another risk is under-syncing the plan by leaving stale `Draft`, `pending`, or active-orchestrator wording in one of the final source-of-truth artifacts.
- Another risk is widening into historical cleanup that does not materially improve final-state truth.
- Open question for future work, not this task: whether the DB-backed template refresh or import path should become a separate operator procedure or a later tracked plan item.

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md`.
- Planning and implementation review logs live at the paths recorded below.
- Update the PRD, tasks tracker, prompt, and plans index during implementation.
- Do not backfill unrelated historical task docs or research files.
- If no durable new finding is uncovered, record `no new research` in the final outcome instead of creating a filler research note.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on PRD, tasks tracker, prompt, plan index, and canonical task-doc synchronization only.
- Workflow check: `update-doc` and `test` guidance are sufficient because this task is documentation-only and does not widen into code changes.
- Tracker check: the plan names exactly which artifacts should change now and explicitly avoids unrelated backfill.
- Consistency check: the plan preserves the already-verified environment-template versus DB-backed-template boundary instead of trying to resolve it by assumption.

## Final Outcome

- Result: `completed`
- Final outcome approach:
  - closed the remaining documentation and tracking drift for the tracing migration plan without widening back into code or rollout work
  - left the historical implementation tasks untouched except for final cross-references and status closure in the governing plan artifacts
  - recorded `no new research` because the durable final-state evidence was already captured in existing task docs, implementation notes, and research artifacts
- Final implementation:
  - updated the PRD to mark the plan completed, convert resolved listener and node-state questions into settled history, and preserve the DB-backed template import boundary as a separate follow-up surface
  - marked `task-400` completed in the tasks tracker with refreshed metadata timestamp and version
  - converted `prompt.md` from an active execution prompt into a historical orchestration reference with archival framing and no operative task-loop instructions
  - updated the plans index to list the tracing plan as completed
  - closed the canonical task-400 plan fields to the approved completed state
- Verification completed:
  - machine-checked JSON parsing and completion assertions for `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - machine checks confirming the PRD now reports `**Status:** Completed`, no longer contains stale implementation-era wording, and still preserves the `FileRepository` boundary
  - machine checks confirming `prompt.md` now retains only archival framing plus historical reference content and no longer contains active execution sections such as `Execution loop per task`, `Task selection (`@Selector` required)`, or `Final task signoff and commit`
  - cross-consistency re-read of the canonical plan, PRD, tasks tracker, prompt, and plans index
- Final review result:
  - implementation review iteration 1 required canonical-plan status closure and stronger archival neutralization of `prompt.md`
  - implementation review iteration 2 approved after those two documentation-truth fixes landed
  - approved review entry recorded in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-400-impl-review.md`
- Research outcome:
  - no new research
