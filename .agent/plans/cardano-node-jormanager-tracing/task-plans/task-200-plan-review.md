Planner: Iteration 1
Timestamp: 2026-05-12T23:28:39Z
- Read required planning inputs in order: `.agent/readme.md`, `.agent/system/architecture.md`, `.agent/workflows/backend.md`, `.agent/workflows/test.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`, `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`, `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`, `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`, and `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`.
- Synced code-index for this task by setting project path, refreshing the index, and rebuilding the deep index before seam analysis.
- Used code-index first and then live reads to verify the relevant repo seams: `ChainMonitor`, `BlockMonitor`, `NodeMonitor`, `nodeclient/protocols/*`, `Configuration.getNodesBroadcastChannel()`, `Node.tracingPort`, and the `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/` task-203 fixture package.
- Created the canonical task plan doc at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`.
- Chose interaction mode `autonomous` because the local repo now truthfully contains the required prerequisites for planning and later implementation: persisted core tracing ports, verified startup listener wiring, and committed fixture-backed protocol lifecycle test assets from task-203.
- Planned the smallest truthful task-200 solution around a dedicated production tracing connection manager/service, separate from the existing node-to-client mini-protocol code, while reusing only proven lifecycle patterns from `ChainMonitor` and the per-node job ownership pattern from `BlockMonitor` and `NodeMonitor`.
- Scoped task-200 strictly to:
  - per-core-node eligibility and connection lifecycle
  - direct outbound TCP session startup against `host.hostname:tracingPort`
  - explicit reconnect behavior
  - explicit shutdown and node-ineligibility teardown
  - a small downstream handoff seam for later decoder/state-family tasks
- Explicitly kept out of scope:
  - adopted-block decode
  - block persistence bridging
  - `BlockMonitor` migration
  - `NodeMonitor` migration
  - node-state protocol-family selection
  - journald/replay/history
  - relay/pool tracing
  - reuse of `Mux`/`MiniProtocol` as the tracing implementation
- Expected files to change during implementation:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/...`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/...`
  - possibly `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt` only for a minimal bean seam if needed
  - optional research note `.agent/plans/cardano-node-jormanager-tracing/research/task-200-connection-lifecycle.md` if implementation yields durable findings
- Verification plan captured in the task plan:
  - fixture-backed lifecycle tests for initial connect, reconnect after disconnect, teardown on deletion/ineligibility, shutdown preventing reconnect churn, and ineligible nodes never opening sockets
  - narrow backend test execution against the tracing package or concrete task-200 lifecycle test class
- Risks/open questions captured:
  - scope creep into decoder or monitor migration work
  - over-reusing existing mini-protocol abstractions
  - making shutdown less explicit than the task requires
  - pinning an initial request-count/batching constant without leaving it implicit
- Review-log paths recorded in the plan:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200-impl-review.md`
- Self-review completed:
  - no scope creep into block decoding/persistence or node-state protocol work
  - no stale workflow assumptions carried forward
  - reconnect and shutdown are explicit and testable
  - plan stays consistent with the PRD requirement to keep tracing separate from the existing mini-protocol stack
Outcome: Plan drafted for critique.

Critiquer: Iteration 1
Timestamp: 2026-05-12T23:30:31Z
- Reviewed the canonical `task-200` plan against the PRD, tasks JSON, research notes, current review log, and the live seams in `ChainMonitor`, `BlockMonitor`, `NodeMonitor`, `Configuration`, `NodeRepository`, `Node.tracingPort`, and `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/`.
- The plan is converged on the right scope for this task: lifecycle/session ownership only, separate from `nodeclient/protocols/*`, and explicitly not widening into decode, block persistence, or node-state mapping.
- The repo state matches the plan prerequisites: `Node.tracingPort` is persisted, listener startup wiring is already pinned in `NodeController`, the shared `nodesChannel` exists, and `task-203` fixtures provide a credible non-live contract for initial connect/reconnect testing.

Blocking findings:
- None.

Non-blocking observations:
- `task-200.md:140-149` should be implemented idempotently against duplicate node events. `Configuration.kt:204-209` provides a plain `MutableSharedFlow`, `BlockMonitor.start()` still emits every non-deleted node on startup (`BlockMonitor.kt:99-105`), and `NodeController` also emits node updates, so the new manager should avoid needless cancel/reconnect churn when it receives the same eligible core more than once.
- `task-200.md:131-156` is right to keep a future handoff seam, but the implementation should keep that seam as small as possible. A single callback/interface for raw session output is enough here; anything larger starts pulling task-201/task-204 complexity forward.
- `task-200.md:182-186` has the right verification boundary. Keep the automated proof fixture-backed and package-local; do not let this task drift into live-node interoperability or protocol-decoder assertions that belong to later tasks.

Approval bar:
- Keep production changes confined to a new tracing lifecycle package plus focused tests.
- Seed eligible core nodes from `NodeRepository` at startup, treat `nodesChannel` updates as refresh signals, and make duplicate events safe.
- Prove initial connect, reconnect, ineligibility teardown, and shutdown using `ScriptedTraceForwardServer` and a narrow backend test target.
- Do not introduce `Mux`/`MiniProtocol` reuse, adopted-block decode, repository writes, or node-state mapping in this task.

Decision: approved

