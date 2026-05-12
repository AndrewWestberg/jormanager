# Task 200 Plan

## Summary

- Task ID: `task-200`
- Title: `Add core tracing connection lifecycle management`
- Why now: first unblocked phase-2 production task after `task-102` and `task-203`, needed before decode and monitor migration can move off SSH/file scraping
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Create the smallest truthful production tracing subsystem foundation that can own long-lived direct TCP tracing connections for eligible core nodes, reconnect on loss, and shut down cleanly without depending on SSH, `cardano-tracer`, or the existing node mini-protocol stack.

In scope:
- add a dedicated tracing connection manager/service under a new tracing-focused production package
- manage eligible core-node connection lifecycle from app startup and node update events
- open the minimum Hermod-compatible trace session needed for the first production lifecycle path using the `task-203` fixtures as the contract anchor
- make reconnect, cancellation, and shutdown behavior explicit and directly testable
- keep a small handoff seam so later tasks can attach decoder and block-event handling without rewriting the lifecycle layer

Out of scope:
- adopted-block decode logic from forwarded trace objects (`task-201`)
- block persistence bridging or `BlockMonitor` refactor (`task-202`, `task-300`)
- node-state protocol selection or `NodeMonitor` migration (`task-204`, `task-301`)
- journald access, replay, historical recovery, or trace persistence
- any reuse of SSH transport, file tailing, `Mux`, or `MiniProtocol` as the tracing protocol implementation itself
- expanding into relay or pool tracing connections

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
- Task-plan templates consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Refreshed the code index and rebuilt the deep index before planning
- Used code-index summaries/search/symbol reads first, then verified live file contents directly
- Code-index-synced findings confirmed live:
  - `ChainMonitor` is the clearest existing long-lived outbound socket lifecycle reference: `SmartLifecycle` + coroutine reconnect loop + explicit graceful shutdown
  - `BlockMonitor`, `NodeMonitor`, and `PooltoolMonitor` already use the shared `nodesChannel` plus per-node `Job` maps to react to node create/update/delete events
  - `nodeclient/protocols/Mux.kt`, `HandshakeProtocol.kt`, and `KeepAliveProtocol.kt` are useful style references for lifecycle/state handling, but they are specific to the node-to-client mini-protocol stack and should stay separate from tracing
  - `Node.tracingPort` now exists and is nullable, which matches the core-only tracing requirement
  - `Configuration.getNodesBroadcastChannel()` already provides the singleton `MutableSharedFlow<Node>` that the tracing manager can reuse
  - `task-203` added reusable trace-forward fixtures and a scripted fake server, so this task can stay autonomous without live-node experimentation
  - there is still no production tracing package or connection manager in the repo today

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for this planning pass
- Truthful autonomy basis:
  - the local repo now contains both the listener-port persistence/startup wiring and the reusable protocol fixtures needed to plan task-200 without depending on external live validation first

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-102`
  - `task-203`
- Live repo dependencies this task should reuse carefully:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/ChainMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/mux/Mux.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/`
- Downstream tasks this work should unblock cleanly:
  - `task-201`
  - `task-202`
  - `task-204`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/ChainMonitor.kt`
  - good reference for reconnect-loop structure, `SmartLifecycle` integration, and shutdown intent
  - also shows where current lifecycle handling is weaker than ideal, especially `stop()` vs `stop(callback)`, which task-200 should make explicit rather than copy blindly
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - confirms the current per-core-node job-map pattern and `nodesChannel` restart behavior that tracing should align with
  - also confirms task-200 must not widen into block decode or persistence yet
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - shows another per-node lifecycle manager and highlights that later node-state work will likely reuse the same tracing connection foundation
- `src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/mux/Mux.kt`
  - useful framing/state-machine style reference only
  - should not become the tracing transport abstraction for this task
- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - confirms `tracingPort` is available for persisted core-node endpoint selection
- `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - confirms a shared singleton `nodesChannel` already exists for node lifecycle fan-out
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
  - provides the minimum evidence-backed request/reply/done byte shapes for the first trace session
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
  - provides reusable repeated-session and forced-disconnect coverage for reconnect tests

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/...`
  - new production tracing lifecycle package for manager, per-node connection runner, and the smallest raw session abstraction or sink seam
- `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt` only if the smallest truthful implementation benefits from a tiny bean seam for socket/session construction or clock/backoff injection
- `src/test/kotlin/com/swiftmako/jormanager/tracing/...`
  - focused lifecycle tests reusing the existing `fixtures` package
- `.agent/plans/cardano-node-jormanager-tracing/research/task-200-connection-lifecycle.md` only if implementation pins durable lifecycle or session-shape findings worth carrying forward

## Implementation Approach

- Add a dedicated tracing manager, preferably something close to `TracingConnectionManager`, under a new production tracing package rather than inside `monitors/` or `nodeclient/protocols/`.
- Keep the new subsystem separate from the existing mini-protocol code:
  - reuse lifecycle ideas from `ChainMonitor` and per-node job ownership from `BlockMonitor`/`NodeMonitor`
  - do not force Hermod-compatible tracing into `Mux`, `MiniProtocol`, `HandshakeProtocol`, or `KeepAliveProtocol`
- Preferred minimal production shape:
  - a manager-level `SmartLifecycle` bean owning a coroutine scope and a `MutableMap<Long, Job>` for active node connection jobs
  - one per-node connection runner that resolves `host.hostname` plus `node.tracingPort`, opens the trace session, and loops with reconnect delay until canceled
  - one tiny raw-session seam that can send the first trace-objects request and receive reply or done messages without decoding adopted-block payload contents yet
  - one no-op or minimal sink interface so later tasks can attach decoder/event handoff without rewriting transport lifecycle
- Eligibility rules should be explicit and centrally testable:
  - node must be `type == "core"`
  - node must not be deleted
  - node must have non-null `tracingPort`
  - host lookup must succeed
- Startup behavior should mirror existing monitors but stay isolated:
  - on `start()`, seed the manager with existing eligible nodes from `nodeRepository`
  - subscribe to `nodesChannel` to create, refresh, or tear down per-node jobs when node metadata changes
  - if a node becomes ineligible, cancel and remove its tracing job
- Connection behavior should stay intentionally small for this task:
  - open a direct outbound TCP socket to `host.hostname:node.tracingPort`
  - send the minimum trace-objects request shape pinned by `task-203`
  - treat disconnect, EOF, or `MsgDone` as session end conditions
  - reconnect after a fixed internal delay unless shutdown or node ineligibility intervenes
  - avoid any adopted-block JSON decode, `TraceObject` normalization, repository writes, or websocket publishing here
- Shutdown behavior must be more explicit than several existing monitors:
  - implement clean manager shutdown that cancels child jobs and closes active sockets/session runners
  - support both Spring lifecycle stop paths truthfully if the chosen class implements `SmartLifecycle`
  - ensure shutdown prevents another reconnect attempt after cancellation
- Keep future extensibility without over-building:
  - model the per-node runner around "tracing session(s)" rather than baking block decode into the transport layer
  - initial implementation may run only the trace-objects session today, but the API should not hardcode block-specific semantics into the manager name or state model

## Acceptance Criteria

- JorManager has a dedicated production tracing connection manager or equivalent service separate from SSH/file scraping and separate from the existing node mini-protocol stack.
- Eligible core nodes can start a direct tracing session using persisted tracing-port metadata.
- Connection loss causes reconnect attempts through explicit, test-covered behavior.
- Node deletion or ineligibility tears down the corresponding tracing session cleanly.
- Application shutdown tears down tracing jobs and prevents reconnect churn after stop.
- The lifecycle layer exposes a small seam that later tasks can extend for trace-object decode and node-state consumption without architectural reset.

## Verification Plan

- Static verification:
  - confirm production tracing lifecycle code lives outside `nodeclient/protocols/` and outside existing SSH/file monitor code
  - confirm eligibility checks exclude relay, pool, deleted, and null-`tracingPort` nodes
  - confirm reconnect logic and shutdown guards are explicit rather than implicit side effects
  - confirm no adopted-block decoder, block repository writes, or node-state mapping logic leaks into this task
- Automated verification:
  - add focused lifecycle tests using `ScriptedTraceForwardServer` for:
    - initial eligible core-node connection success
    - reconnect after server-side close across repeated scripted sessions
    - clean teardown when a node update arrives with `isDeleted = true` or missing eligibility
    - manager shutdown preventing a further reconnect after cancellation
    - ineligible node types or null `tracingPort` never opening a socket
  - keep tests local to the tracing package and reuse the committed `task-203` fixtures rather than inventing new live dependencies
  - run the narrowest relevant backend target once class names exist, preferably a task-specific package or class such as:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.*"`
    - or the concrete task-200 lifecycle test class
- Truthful validation boundary:
  - live `cardano-node` interoperability is intentionally not required to complete this task because `task-203` already established the non-live fixture foundation and this task is scoped to connection lifecycle management, not full decode semantics

## Risks And Open Questions

- Main risk is accidental scope creep into decoder, block persistence, or `NodeMonitor` migration; this task should stop at lifecycle and raw session ownership.
- A second risk is over-reusing `Mux` or `MiniProtocol`, which would blur the PRD-required separation between tracing and existing node-to-client code.
- Existing monitor stop semantics are inconsistent; task-200 should improve its own lifecycle behavior without broad cleanup of unrelated monitors.
- The initial trace session request-count or batching constant can remain an internal implementation detail, but it should be pinned in tests once chosen rather than left implicit.
- Future node-state work may need additional session families per core node. This plan should preserve that extension path without forcing a larger abstraction than task-200 needs today.

## Required Docs, Tracking, And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`.
- Do not write the planning review log from this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation reveals durable findings about:
  - chosen session topology per node
  - reconnect delay/backoff constraints
  - socket-close or shutdown behavior needed by later decode and node-state tasks

## Final Outcome

- Result: completed and review-approved in implementation iteration 2.
- Final implementation summary:
  - added a dedicated production tracing lifecycle package under `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - added `TracingConnectionManager` as a `SmartLifecycle` service that seeds eligible core nodes from `NodeRepository`, reuses `nodesChannel` refresh signals, keeps duplicate updates idempotent, reconnects after real session loss, and tears down jobs on ineligibility or shutdown
  - added `SocketTraceForwardSessionClient` with the smallest direct TCP trace-forward session contract needed for this task: send the pinned trace-objects request, surface raw reply or done messages to a single sink seam, and keep quiet blocking sessions alive across socket read timeouts
  - added focused lifecycle tests covering seeded startup, reconnect after disconnect, deletion teardown, idle-session no-reconnect behavior, direct `stop()` shutdown, and ineligible-node suppression
- Files changed:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-200-connection-lifecycle.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
  - repo wiring also ran Vue unit tests and frontend production build during that Gradle target; both passed on the final run
- Review outcome:
  - planning log approved in iteration 1
  - implementation review approved in iteration 2 after fixing idle-session timeout handling and direct `stop()` shutdown semantics
- User handoff: none required

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200-impl-review.md`

## Self-Review

- Scope is intentionally tight: dedicated tracing lifecycle only, with no decoder, persistence, or node-state mapping expansion.
- Workflow and repo assumptions are current: `nodesChannel`, `Node.tracingPort`, and the `task-203` fixture package all exist live, while production tracing code does not yet.
- Test coverage is not missing from the plan; reconnect, ineligibility teardown, and shutdown are all called out explicitly.
- The plan stays consistent with the PRD by keeping tracing separate from the node mini-protocol stack while still reusing proven lifecycle patterns where that reduces risk.
