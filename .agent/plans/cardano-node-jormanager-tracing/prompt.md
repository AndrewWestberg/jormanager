This prompt is preserved as the historical orchestration guide that executed the Cardano node and JorManager tracing migration plan. The tracked task graph is now complete; use this file as a reference for the plan's execution model, review-log conventions, and repository boundaries rather than as an active queue for new work. If future work extends this area, reopen or create a new plan deliberately instead of resuming this completed task graph from its old execution instructions.

Project anchors
- Plan PRD: `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- Tasks: `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Task plans: `.agent/plans/cardano-node-jormanager-tracing/task-plans/`
- Research brain: `.agent/plans/cardano-node-jormanager-tracing/research/`
- Docs index: `.agent/readme.md`
- Architecture: `.agent/system/architecture.md`
- Plans index: `.agent/plans/readme.md`
- Backend node creation and startup wiring: `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- Block monitoring and tracing migration area: `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
- Existing direct node client foundation: `src/main/kotlin/com/swiftmako/jormanager/nodeclient/`
- Node entity and request models: `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`, `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
- Frontend plumbing entry point: `vue/src/components/AddNodeWizard.vue`
- Database migrations: `src/main/resources/db/`
- Deployed node template configs: `/home/westbam/bcsh/jormanager/`

Relevant workflows
- Backend: `.agent/workflows/backend.md`
- Frontend: `.agent/workflows/frontend.md`
- Database: `.agent/workflows/database.md`
- Test: `.agent/workflows/test.md`
- Docs updates: `.agent/workflows/update-doc.md`

Relevant skills
- `git-commit-formatter` for the final task commit message
- `cardano-cli-doctor` when a task needs verified `cardano-node` or `cardano-cli` syntax, version, or network-flag compatibility analysis
- `cardano-protocol-params` only if a task unexpectedly touches protocol-parameter interpretation or fee calculations
- `cbor-encoding-decoding` and `bech32-encoding-decoding` only if tracing payload investigation or Cardano data handling truly requires them
- `cardano-cli-wallets`, `cardano-cli-transactions`, or their operator variants only when a task expands into explicit operator-run validation that depends on local Cardano CLI flows
- code-index tools for active-role repository understanding after task selection, not before `@Selector`

Source of truth
- Task state, dependencies, ordering, and critical path come from `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Design intent, locked decisions, tracing architecture, config-shape requirements, rollout policy, and testing posture come from `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- Durable evidence, new decisions, implementation gotchas, and operational findings for this plan go into `.agent/plans/cardano-node-jormanager-tracing/research/`
- Final verification must always be checked against the live repository state and any required manual or operator evidence

Fixed decisions
- All newly created JorManager-managed nodes use dispatcher tracing with `UseTraceDispatcher: true`.
- JorManager supports this tracing migration only for `cardano-node 11.0.1+`.
- All newly created nodes continue writing machine-formatted logs to stdout for journald.
- Generated configs start from Markus's full tracing baseline and then apply only the minimal JorManager-owned mutations needed for dispatcher-era config shape, node-local ports, and core-node forwarding.
- Legacy tracing config must be removed from generated configs except for compatibility-era keys still required by the accepted `cardano-node 11.0.1` config shape.
- Core nodes automatically enable forwarding and listen for one JorManager tracing client.
- Relay and pool nodes do not enable forwarding in the first version.
- JorManager is always the client and the node is always the listener.
- JorManager connects directly to the node host using `host.hostname`.
- Tracing listeners bind on all interfaces and are restricted by firewall outside JorManager.
- Each core node gets its own dedicated tracing port.
- Tracing forwarding is not exposed as a UI option in the first version.
- JorManager is the only live tracing consumer.
- JorManager does not query journald and does not perform historical recovery.
- Missing events after the node's forwarder buffer window are acceptable in the first version.
- For `cardano-node 11.0.1`, the block-discovery event to consume remains `TraceAdoptedBlock`.
- JorManager matches forwarded adopted-block events by namespace `Forge.AdoptedBlock` and machine payload field `kind: "TraceAdoptedBlock"`.
- Forwarded tracing payloads are treated as `TraceObject` wrappers carrying machine JSON in `toMachine`, not as the old scraped log-line shape with outer `at` / `env` / `data` / `host` fields.
- Per-node forwarder buffer defaults come from the accepted research baseline unless `cardano-node 11.0.1` verification requires different safe defaults.
- JorManager implements only the minimum trace-forward client functionality needed for its own use cases and does not depend on `cardano-tracer`.
- Phase 1 live consumption scope is block discovery only, but the subsystem should be designed so future event families such as missed leadership checks and errors can be added without another architectural reset.
- Existing deployed nodes are not auto-modified; this plan targets newly created nodes and documents manual migration expectations for older nodes.
- The deployed node template configs in `/home/westbam/bcsh/jormanager/` must be updated in tandem so newly created nodes inherit the new tracing setup.
- The `*-db-sync-config.json` files in `/home/westbam/bcsh/jormanager/` are out of scope for this migration.

Workflow and skill policy (mandatory)
- Context minimization is mandatory. Selection analysis belongs in `@Selector` context rather than the Orchestrator context.
- Before task selection or resume analysis, `@Selector` must read `.agent/readme.md`, `.agent/system/architecture.md`, and this prompt inside the `@Selector` context.
- The Orchestrator must perform zero file reads, searches, skill loads, or repo-inspection tool calls before dispatching `@Selector` for task selection or resume analysis.
- The Orchestrator acting as Implementer or Scribe must read `.agent/readme.md` only after `@Selector` has returned a task handoff and the Orchestrator is about to perform implementation, review-loop, or documentation work for that selected task.
- All roles working primarily in backend Kotlin code for this plan must read `.agent/workflows/backend.md` before acting in that role.
- All roles whose task touches Liquibase, entities, or persistence-model changes must read `.agent/workflows/database.md` before acting in that role.
- All roles whose task touches `vue/src/**` must read `.agent/workflows/frontend.md` before acting in that role.
- All roles doing verification planning or test execution must read `.agent/workflows/test.md` before acting in that role.
- All roles whose task is primarily plan, PRD, runbook, workflow, skill, or documentation maintenance must also read `.agent/workflows/update-doc.md` before acting in that role.
- `@Selector` must read the PRD, tasks JSON, relevant research files, and any paused task's canonical plan doc plus review logs needed to truthfully decide whether to resume or select a new task.
- Before returning a selection, `@Selector` must also cross-check the candidate task against its canonical task-plan doc, review logs, and relevant git history whenever there is any sign that tracker state may be stale, so it does not resurface work that is already truthfully completed.
- Every active role doing repository understanding, architecture review, code search, or planning after task selection should use the code-index tools first where applicable, then verify important findings against live files before editing.
- `cardano-cli-doctor` should be used when a task depends on verified listener-flag, startup-command, or version-compatibility behavior rather than repo-local assumptions.
- If a task directly matches another supported skill under `.agent/skills/` or the shared tool skill catalog, the Orchestrator must explicitly include that skill in the `@Selector`, `@Planner`, `@Critiquer`, or `@Reviewer` prompt rather than assuming the subagent will discover it. Passing the skill requirement in the dispatch prompt is preferred over pre-loading the skill into the Orchestrator context solely for selection work.
- Canonical task plan docs must record which docs, workflows, and skills were consulted whenever they materially affected the approach, implementation, or verification plan.
- If workflow guidance, accepted tracing research, the approved canonical task plan, and the live repo state ever diverge, do not silently choose one source. Preserve repo-validated constraints, document the conflict in task docs or research, and update the governing docs so later roles do not inherit inconsistent instructions.

Code-index sync policy (mandatory after task selection)
- Do not use code-index tools before `@Selector` returns; selection context remains owned by `@Selector`.
- Immediately after `@Selector` returns a task handoff and before any active role performs repo exploration, planning reads beyond the task docs, code search, or implementation, the active role must sync the code-index state.
- The minimum sync sequence is:
    - call `code-index:get_settings_info`
    - call `code-index:get_file_watcher_status`
    - confirm the project path is `/home/westbam/Development/jormanager`
    - if project path, watcher state, or index freshness is wrong or uncertain, call `code-index:set_project_path` with `/home/westbam/Development/jormanager` and then `code-index:refresh_index`
- If a task needs symbol-level structure or nontrivial cross-file implementation planning, run `code-index:build_deep_index` after the basic sync unless the index is already known fresh enough for that task.
- If git operations, dependency installs, generated artifacts, or large file changes happen during the task and later code-index results may be stale, refresh the index again before relying on indexed search results.
- Code-index results are guidance, not source of truth. Before editing or making strong claims, verify important indexed findings against live files with direct reads.
- Record in the canonical task plan doc that code-index was synced, including any refresh or deep-index action that materially affected planning or implementation.

Elegance and convergence policy (mandatory)
- Prefer the smallest truthful solution that satisfies the selected task's acceptance criteria, fixed decisions, accepted research, and live repo constraints.
- Prefer existing seams, types, modules, tests, workflows, and package boundaries over new abstractions, helper layers, feature flags, caches, registries, diagnostics surfaces, or config knobs.
- Treat extra generality as a cost. Do not add infrastructure for hypothetical future tasks unless the PRD, accepted research, or live repo requires it now.
- When multiple designs are correct, choose the one with less new surface area, fewer moving parts, and clearer failure modes.
- If a broader design mostly prepares for later work, record it as a follow-up, non-goal, or research note instead of widening the current task.
- Prefer iteration-stable wording in canonical docs. Avoid hardcoding transient review-log iteration numbers or other text that will predictably go stale on the next append.
- `@Planner`, `@Critiquer`, `@Reviewer`, and the Orchestrator acting as Implementer must actively look for unnecessary complexity and prefer simplification or scope reduction when that still satisfies the task truthfully.

Research brain policy (mandatory)
- Do not front-load research into the Orchestrator context before task selection. Research should be loaded by the active role in its own context when that role actually needs it.
- Before choosing a task, `@Selector` must read the relevant files in `.agent/plans/cardano-node-jormanager-tracing/research/` needed to make a truthful selection decision.
- Before planning, critique, implementation, review, or documentation work for a selected task, the active role must read the research relevant to that role's responsibility and the selected task's scope.
- Treat the following tracing research anchors as high-priority context when the selected task touches config generation or dispatcher-era tracing shape:
    - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
    - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
- Treat the PRD and tasks JSON as the current source of truth when they supersede or narrow what is implied by raw research artifacts.
- During or after each task, write durable findings to the research brain: decisions, constraints, gotchas, failed approaches, validation evidence, performance findings, security caveats, required manual checkpoints, and intentional residual gaps.
- If nothing durable was learned, record `no new research` in the canonical task plan outcome.
- Preserve the difference between raw inputs, verified implementation behavior, and current accepted design. If a research note is superseded, annotate that status rather than silently contradicting it elsewhere.

Task plan doc policy (mandatory)
- For each future selected task, maintain exactly 3 task-specific docs under `.agent/plans/cardano-node-jormanager-tracing/task-plans/`:
- canonical task plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>.md`
- planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md`
- implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-impl-review.md`
- This `task-plans/` workflow is required for future tasks. Do not backfill historical completed tasks unless the user explicitly asks for that documentation work.
- The canonical task plan doc is the single source of truth for the task's current approved plan, current build state, and final outcome.
- The 2 review-log docs are the single source of truth for the full-fidelity role conversations during planning critique and implementation review.
- The Orchestrator (acting as Implementer or Scribe) and `@Selector`/`@Planner`/`@Critiquer`/`@Reviewer` must read the canonical plan doc plus the relevant review-log doc instead of relying on lossy summaries whenever those docs are relevant to the active task state.
- `@Planner` creates and revises the canonical task plan doc. The Orchestrator, acting as Implementer, creates and appends Implementation entries to the implementation review log. The Orchestrator, acting as Scribe, updates documentation and research. The Orchestrator is the only role allowed to modify `*-plan-review.md` and `*-impl-review.md`; review-log writes are the one exception to the normal `apply_patch` editing preference, and the Orchestrator must use the `bash` tool for literal end-of-file appends to those files. `@Planner`, `@Critiquer`, and `@Reviewer` must return exactly one proposed transcript entry block for the current turn and must not write review-log files directly.
- At minimum, each canonical task plan doc must capture:
- task id and title
- why this task was chosen now
- interaction mode (`autonomous`, `interactive_decision`, `interactive_validation`, or `manual_execution`)
- scope and non-goals
- relevant dependencies
- research consulted
- docs, workflows, and skills consulted
- files expected to change
- implementation approach
- acceptance criteria
- verification plan
- risks / open questions
- required docs / tracking / research updates
- review-log paths
- planning status (`draft`, `in_review`, `approved`)
- build status (`in_progress`, `in_review`, `completed`)
- Canonical task plan docs must never hardcode a specific future or current review-log iteration number in verification text. Refer to the current iteration generically or to the latest matching review-log entry so the plan does not become stale after a later append.

Review-log format rules (mandatory)
- both review-log docs are append-only chronological transcripts; every new entry must be appended at end-of-file only
- for `.agent/plans/cardano-node-jormanager-tracing/task-plans/*-plan-review.md` and `.agent/plans/cardano-node-jormanager-tracing/task-plans/*-impl-review.md`, never use `apply_patch` or any other anchor-based patching method to write the new entry
- for review-log appends, the Orchestrator must use the `bash` tool with direct EOF append semantics. Use the `printf` command with `>>` against the target file after inspecting the live end-of-file state. For example:
  ```
  printf '%s\n' "Planner: Iteration 1" >> ".agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md"
  printf '%s\n' "Timestamp: 2026-05-12T12:34:56Z\n" >> ".agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md"
  printf '%s\n' "<HeaderX>: <body text and/or bullets, etc...>" >> ".agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md"
  printf '%s\n\n' "Outcome: Plan drafted and ready for critique" >> ".agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md"
  ```
  Or as a single compound command:
  ```
  printf 'Planner: Iteration 1\nTimestamp: 2026-05-12T12:34:56Z\n\n<HeaderX>: <body text and/or bullets, etc...>\n\nOutcome: Plan drafted and ready for critique\n\n' >> ".agent/plans/cardano-node-jormanager-tracing/task-plans/<task-id>-plan-review.md"
  ```
  Note: Each entry must terminate with exactly two newlines so the file always ends with a blank line between entries and a final blank line after the last entry.
- never insert, reorder, delete, or rewrite prior entries, even to fix mistakes or add missing context
- if a prior entry is incomplete, incorrect, or out of order, append a new entry that corrects or supersedes it; do not edit history
- the Orchestrator is the only writer for review-log docs; named subagents may read the logs but must return exactly one proposed entry block for the current turn and must not edit review-log files directly
- before appending, the Orchestrator must read the full relevant review-log doc and inspect the final complete entry at literal end-of-file to determine the only valid next speaker and iteration number
- when appending to a review log, never target a mid-file anchor string, prior iteration header, approximate line number, or earlier section as the insertion point. Only append after inspecting the live end-of-file state
- each iteration must remain contiguous in the file; never append any `Iteration N+1` entry until the matching `Iteration N` response from the other speaker has already been appended or the loop has stopped on approval
- each appended entry must include speaker label, iteration number, UTC datetime stamp in ISO 8601 format (`Timestamp: YYYY-MM-DDTHH:MM:SSZ`), and outcome
- `Timestamp:` must come from the live system clock in UTC at append time. Do not invent, backdate, round, reuse, or use placeholders such as `00:00:00Z`
- planning review entries use `Planner:` and `Critiquer:` speaker labels; `Planner:` entries are written by `@Planner`, `Critiquer:` entries by `@Critiquer`
- implementation review entries use `Implementation:` and `Code Review:` speaker labels; `Implementation:` entries are written by the Orchestrator acting as Implementer, `Code Review:` entries by `@Reviewer`
- Critiquer and Code Review entries must end with a machine-readable decision: `Decision: approved` or `Decision: requires_changes`
- before appending, if the proposed entry does not match the only valid next speaker and iteration, stop and report `APPEND_ABORTED: invalid_transition`
- before appending, if the final complete entry at end-of-file cannot be determined truthfully, stop and report `APPEND_ABORTED: malformed_eof`
- before appending, if end-of-file already contains the same speaker and iteration, do not append a duplicate entry; stop and report `APPEND_ABORTED: duplicate_entry`
- append exactly one new entry after the current final newline at literal end-of-file only; the appended entry must terminate with exactly two newlines so the file always ends with a blank line between entries and a final blank line after the last entry
- after appending, re-read the file tail and confirm the new entry is now the final entry in the file; if not, stop and report `APPEND_ABORTED: post_append_validation_failed`
- `@Planner`, the Orchestrator (acting as Implementer), and `@Critiquer`/`@Reviewer` must re-read the full relevant review-log doc before preparing or appending a new response so prior context is preserved
- allowed planning-log transitions:
    - empty file -> `Planner: Iteration 1`
    - `Planner: Iteration 1` -> `Critiquer: Iteration 1`
    - `Critiquer: Iteration 1` with `Decision: requires_changes` -> `Planner: Iteration 2`
    - `Critiquer: Iteration 1` with `Decision: approved` -> planning loop stops; no further planning-log entries
    - `Planner: Iteration 2` -> planning loop stops; no further planning-log entries
- allowed implementation-log transitions:
    - empty file -> `Implementation: Iteration 1`
    - `Implementation: Iteration N` -> `Code Review: Iteration N`
    - `Code Review: Iteration N` with `Decision: requires_changes` -> `Implementation: Iteration N+1`
    - `Code Review: Iteration N` with `Decision: approved` -> build loop stops; no further implementation-log entries
- no other review-log transitions are valid
- if an existing review-log doc already violates ordering or iteration sequencing, do not repair it by rewriting history
- instead, append a new end-of-file entry that notes the sequencing problem and resumes from the next valid iteration number, or stop and escalate if no truthful next iteration can be determined

Comprehensive critique and review policy (mandatory)
- `@Critiquer` and `@Reviewer` must perform one broad pass across the full plan or implementation before returning a decision.
- Do not stop after the first blocker. Read enough of the obviously affected repo surfaces to surface all material issues knowable from that pass.
- For planning, that broad pass is single-shot: `@Critiquer` reviews the first Planner draft once, then the only follow-up allowed is one final Planner fix pass if changes are required.
- A `Decision: requires_changes` response must include one consolidated list of every known blocking issue needed for approval, ordered by severity or user impact.
- Include non-blocking observations separately. Non-blocking suggestions must not keep the loop open.
- New blockers in later iterations are acceptable only when the revision introduced them or when they were not truthfully discoverable in the earlier pass.
- Once the previously stated blockers are resolved, default to approval. Do not move the approval bar with previously knowable nits, wording churn, or avoidable bookkeeping drift.
- The goal of critique and review is convergence, not maximum iteration count.

Historical execution note

The sections that originally followed here defined the active planning loop, build loop, task-selection protocol, user-handoff rules, and final per-task signoff requirements used while this tracing plan was in flight. Those execution instructions are now archived with the completed plan history rather than left operative for new work. Future work in this area should reference the completed task docs and PRD, then create or reopen a plan intentionally instead of treating the archived loop rules below as a live queue.
