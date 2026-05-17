Planner: Iteration 1
Timestamp: 2026-05-17T23:08:11Z

Created the canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md` after reading the required docs, PRD, tasks graph, task-500 research, config baselines, and the live tracing code and tests. The plan truthfully marks interaction mode as `autonomous`, records the code-index sync state from `code-index_get_settings_info` and `code-index_get_file_watcher_status`, and aligns task-511 to the live repo baseline rather than the stale tracker alone: the single-connection architecture is already proven in research and `LiveTraceForwardIntegrationTest`, but production runtime unification is still incomplete, so task-511 is planned as the smallest raw-capture layer on top of that foundation, not as a return to sibling-session topology. The plan keeps scope narrow to shared in-memory raw models and routing for protocol `1` metrics, protocol `2` trace-object batches, and protocol `3` datapoint snapshots, explicitly defers typed extraction and consumer migration, and includes focused verification for raw-model fidelity and routing.

Self-review completed:
- no scope creep into downstream dashboard or block consumers
- no missing code-index or live-file verification
- no unnecessary docs or research additions
- no internal inconsistency with the current tracing implementation state

Outcome: canonical `task-511` plan drafted and ready for formal review.

Critiquer: Iteration 1
Timestamp: 2026-05-17T23:09:40Z

Blocking findings:
- `task-511.md:27-29,154-156` leaves the transport prerequisite materially under-specified even though the live repo still has no production unified capture surface. `TracingConnectionManager` only owns protocol-2 trace sessions (`TracingConnectionManager.kt:34-40,152-176`), `TraceForwardSessionClient` only runs `ForwardingTraceObjectsProtocol` (`TraceForwardSessionClient.kt:57-66`), and protocol-3 still uses a separate socket via `DataPointSessionClient` (`DataPointSessionClient.kt:37-66`). As written, the plan allows “finish only the minimal task-510 seam needed” without explicitly defining that seam or making it part of task-511 acceptance. That can produce a half-raw layer on top of the old topology and still claim the task complete. The plan must either: 1. explicitly require the production single-connection message surface first, or 2. narrow task-511 to raw model definitions only and stop claiming shared production retention/routing.
- `task-511.md:22-24,163-168,176-183` claims JorManager will have a shared raw capture layer that retains protocol `1`, `2`, and `3` before feature filtering, but the plan does not require rerouting the current production consumers that still originate data outside that shared path. `NodeMonitor` still opens its own protocol-3 and protocol-2 sessions per sample (`NodeMonitor.kt:337-387`), and `TracingBlockMessageSink` still consumes manager-delivered protocol-2 messages directly (`TracingBlockMessageSink.kt:18-40`). Without explicitly moving “first visibility” of incoming data to the shared raw layer, task-511 cannot truthfully satisfy the PRD/task requirement that JorManager retains all incoming protocol data before consumer-specific filtering. The plan should state exactly which production ingress paths must be routed through raw capture in this task and which direct session paths must no longer be authoritative.
- The verification plan is too weak for the runtime boundary it claims. `task-511.md:192-201` focuses on fixture tests plus maybe `TracingConnectionManagerTest`, but today that manager test suite only proves single-family trace-session lifecycle (`TracingConnectionManagerTest.kt:20-303`). There is no required verification that one production ingress path now carries protocol `1`, `2`, and `3` messages into raw capture, nor that existing direct consumers no longer bypass capture. Add explicit verification for the production ingress seam you intend to own in this task; otherwise the task can pass while the old bypass paths remain the real runtime.

Non-blocking observations:
- `task-511.md:48` references `task-400.md`, which looks stale and not directly relevant to the final tracing architecture. Removing that input would simplify the plan.
- `task-511.md:145-146,216` is right to avoid mandatory new research, but if implementation chooses a bounded protocol-2 replay buffer versus pure latest-only/event-only fan-out, that boundary is durable enough to merit a short research note or at least an impl-review note.
- The smallest correct implementation likely keeps raw capture as one service plus one expanded message model, not “one or two new tracing files” plus optional follow-up edits across multiple clients. The current wording leaves too much room for architecture spread.
- The plan correctly avoids live-node validation as a completion gate for this task; that matches the repo’s existing fixture-heavy tracing tests and keeps the task from becoming task-541 early.

Approval bar:
- Make the transport/runtime boundary explicit: either require the minimal production unified ingress seam as part of task-511, or narrow task-511 so it no longer claims shared production capture for all three families.
- Make “first visibility” explicit: define which current ingress paths (`TracingConnectionManager`, `NodeMonitor`, any new unified session client) must flow through raw capture by the end of this task, and which direct feature-owned reads are deferred.
- Strengthen verification so it proves the chosen production ingress path actually feeds protocol `1`/`2`/`3` raw capture and that consumer-owned bypass is either removed or explicitly out of scope and reflected in acceptance language.

Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-17T23:11:13Z

Re-read the full `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511-plan-review.md` and revised `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md` with the smallest truthful change set needed to clear the blocker list. The plan now makes the transport boundary explicit in a dedicated `## Transport Boundary` section: task-511 is not complete unless production tracing has one tracing-owned unified ingress seam, inherited from the task-510 target architecture, that becomes the authoritative first-visibility point for protocol `1`, `2`, and `3` messages before any feature-specific filtering. The prior “finish only the minimal task-510 seam needed” wording is now pinned to that concrete requirement instead of leaving the prerequisite under-specified.

The revised plan also makes first visibility explicit for current production bypass paths. It now states that `NodeMonitor` must no longer own authoritative protocol `2` or protocol `3` sockets by the end of task-511, and that `TracingBlockMessageSink` may remain a downstream consumer only after shared raw capture has already recorded protocol `2` ingress. Acceptance criteria and implementation approach were tightened to match that boundary, so the task no longer overclaims a shared raw layer while allowing direct consumer-owned ingress to remain authoritative.

Verification was strengthened around the chosen ingress seam rather than only raw-model fixtures. The plan now requires:
- one focused ingress test proving a single production tracing ingress path accepts representative protocol `1`, `2`, and `3` messages and records them into shared raw capture
- static verification that there is one tracing-owned ingress seam instead of separate feature-owned socket entry points
- a wiring test proving `NodeMonitor` no longer owns authoritative direct tracing session ingress
- a wiring test proving protocol `2` block handling is downstream of raw capture rather than a parallel first-visibility path
- the expanded backend test target including `NodeMonitorTest` and `TracingBlockMessageSinkTest`

I also removed the stale `task-400.md` input reference to simplify the plan. Self-review after revision: the plan still stays minimal, does not widen into typed extraction or full consumer migration, and now truthfully aligns its acceptance and verification language with the live repo’s current transport gaps and the blocker feedback from `Critiquer: Iteration 1`.

Outcome: canonical `task-511` plan revised to address the blocker list and ready for implementation.

