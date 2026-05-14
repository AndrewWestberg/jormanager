# Task 301 Plan

## Summary

- Task ID: `task-301`
- Title: `Replace NodeMonitor HTTP metrics polling with direct node protocol consumption`
- Why now: `task-204` already pinned and test-backed the direct `DataPoint` node-state contract, and `task-300` already removed the legacy block-discovery path, so the next smallest truthful migration step is to move `NodeMonitor` off HTTP polling and onto the same direct node protocol boundary as the tracing subsystem
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Replace `NodeMonitor`'s HTTP EKG polling and SSH port-forward transport with direct `DataPoint` protocol consumption for eligible core nodes while preserving the current `NodeStats` contract, the existing 5-second sampling plus grouped websocket cadence, and the current default-node cache behavior within the new core-only boundary.

In scope:
- refactor `NodeMonitor` so core-node stats come from the pinned direct `DataPoint` protocol path
- remove `NodeMonitor` runtime dependence on `EkgService`, Retrofit-built node metrics clients, and SSH local port forwarding for metrics
- keep `NodeMonitor` as the lifecycle owner for node-state jobs instead of adding a second standalone connection manager unless a tiny factory seam is the only new code needed
- reuse the task-204 `NodeStateDataPointDecoder` and pinned eight-key request set without redefining the node-state contract
- preserve current `NodeStats` fields, null-on-failure behavior, default-node caching into `latestNodeStats`, and grouped websocket publication behavior where practical
- pin node-state sampling to one short request or reply `DataPoint` session per eligible core node every 5 seconds, matching the current aligned `NodeMonitor` cadence
- self-seed eligible nodes from the repository on `NodeMonitor` startup so monitoring does not depend on `nodesChannel` replay behavior or `BlockMonitor` startup ordering
- keep first-version direct node-state support limited to core nodes only
- add focused automated coverage for the new node-state session and `NodeMonitor` mapping behavior

Out of scope:
- relay or pool node direct node-state parity
- widening the tracing package into a generalized mixed-protocol mux abstraction
- changing block-event tracing behavior from `task-200` to `task-202`
- deleting `EkgService.kt` outright if it becomes unused; that cleanup belongs to `task-302`
- removing persisted `Node.ekgPort` or `Node.promPort` fields, or changing node-creation persistence/model shape in this task
- frontend, template-config, journald, replay, or rollout-template work

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
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-impl-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-300-impl-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-204-node-state-datapoint-contract.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built the deep code index before planning and then verified important findings against live files
- Code-index materially affected this plan because the prompt's broad `nodeclient/` anchor is stale for this workstream; the live direct protocol foundation for this migration is now in `src/main/kotlin/com/swiftmako/jormanager/tracing/`, while `NodeMonitor` still lives entirely on the old HTTP-plus-SSH path
- Code-index-synced findings confirmed live:
- `NodeMonitor` still creates Retrofit `EkgService` clients for local nodes and SSH-tunneled remote nodes, then polls every 5 seconds
- `NodeMonitor` still owns current `NodeStats` assembly, null-on-failure fallback events, `latestNodeStats` updates for the default node, and grouped websocket emission to `/topic/messages`
- live `NodeMonitor` still starts jobs for every non-pool node, so core-only direct support now needs an explicit non-core contract instead of implementation-time discovery
- `NodeStateDataPointDecoder` already pins the exact eight-key `cardano.node.metrics.*` mapping into `NodeStats`
- `SocketDataPointSessionClient` already exists as the minimal `DataPoint` client, and its live contract is single-request-per-connect rather than a reusable multi-request stream
- `TracingConnectionManager` currently owns only the trace-object session per eligible core node and already protects itself against `nodesChannel` timing by self-seeding from `nodeRepository.findAll()` on startup
- `BlockMonitor` still owns the cold-start `nodesChannel` seeding that `NodeMonitor` relies on today, and `nodesChannel` remains a non-replay `MutableSharedFlow()`
- `latestNodeStats` is only refreshed from successful stats for the default node today, while `BlockUtils` reads that cache later and no current wiring guarantees the default node is an eligible core node
- the Vue store still consumes grouped `/topic/messages` payloads with `type = "nodestats"`, so that websocket shape must remain intact

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning
- Truthful autonomy basis:
  - the node-state protocol family and fixtures are already pinned by `task-204`, the live `NodeMonitor` migration boundary is fully inside backend code already present in the repo, and this task does not require a new product decision or runtime operator choice to plan honestly

## Dependencies

- Required completed upstream tasks:
  - `task-204`
  - `task-300`
- Practical live repo dependencies this task should reuse carefully:
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/spring/config/Configuration.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-302`
  - `task-103`
  - `task-400`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - `start()` still depends on `BlockMonitor` cold-start seeding and starts three concerns: ledger backfill, per-node monitor jobs, and grouped websocket publication
  - `monitorNodeLocal(...)` and `monitorNodeRemote(...)` are transport-specific wrappers that should disappear or collapse once metrics no longer come from HTTP
  - `monitorNodeRemote(...)` is entirely about SSH port forwarding to `node.ekgPort`; that transport should be deleted rather than preserved behind a flag
  - `monitorNode(...)` currently owns the 5-second cadence, default-node cache update, and null-valued fallback event behavior; those semantics should stay with minimal internal reshaping
  - task-301 should make `start()` self-seed from `nodeRepository.findAll()` before or alongside `nodesChannel` collection so existing eligible core nodes are monitored even when `nodesChannel` emissions were missed before this bean starts
  - task-301 should also pin the runtime contract that relay and pool nodes no longer get `nodestats` events from `NodeMonitor`; only eligible core nodes continue to publish stats on the direct path
  - the EKG-specific `EkgMetrics2.toNodeStats(...)` adapter is an obvious replacement point for `NodeStateMetrics.toNodeStats(...)`
- `src/main/kotlin/com/swiftmako/jormanager/model/NodeStats.kt`
  - the contract is still the eight pinned state fields plus identity/color/timestamp/epochLength, so no model change is needed unless implementation uncovers a real mismatch
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - already codifies the exact eight-key request set and the `NodeStateMetrics.toNodeStats(...)` adapter needed by `NodeMonitor`
  - safe decode failure already returns `null`, which aligns with `NodeMonitor`'s current null-valued fallback event behavior
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - already provides the minimal direct socket session that writes exactly one pinned request per connect and then reads reply or done messages until the socket closes
  - that existing shape makes repeated short-lived per-interval sessions the smallest truthful task-301 cadence model
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - already solves the right self-seeding and eligibility problem shape for core-node trace-object sessions
  - should be reused as a lifecycle reference only; task-301 should not introduce a second independent manager unless implementation proves a tiny helper is smaller than keeping lifecycle ownership in `NodeMonitor`
- `src/main/kotlin/com/swiftmako/jormanager/controllers/utils/BlockUtils.kt`
  - consumes `latestNodeStats` for epoch and slot-derived calculations, so the plan must explicitly define what happens when the configured default node is not an eligible core node
- `src/main/kotlin/com/swiftmako/jormanager/services/EkgService.kt`
  - is now only an HTTP client seam; task-301 should eliminate its production use from `NodeMonitor`, but the file itself can remain until `task-302` deletes dead plumbing
- `src/main/kotlin/com/swiftmako/jormanager/entities/Node.kt`
  - still persists `ekgPort` and `promPort`; task-301 should stop reading them in `NodeMonitor` but should not widen into persistence cleanup that belongs later

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - replace HTTP and SSH transport with direct node-state session consumption, preserve current publication semantics, pin the core-only plus default-node behavior, and add startup self-seeding for existing eligible nodes
- `src/test/kotlin/com/swiftmako/jormanager/monitors/`
  - add a new focused `NodeMonitorTest.kt` or equivalent coverage for current `NodeStats` semantics on the direct path
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - extend focused session-client tests only if a tiny helper or factory seam is introduced around the existing single-request `DataPoint` client
- `.agent/plans/cardano-node-jormanager-tracing/research/task-301-*.md`
  - only if implementation reveals a durable reason that `NodeMonitor` could not remain the node-state lifecycle owner after all

## Implementation Approach

- Preferred smallest truthful production shape:
  - keep `NodeMonitor` as the owner of dashboard timing, fallback event generation, default-node caching, and websocket grouping
  - stop creating per-node Retrofit `EkgService` clients entirely
  - stop using `node.ekgPort`, SSH local port forwarders, `SSHClientPool`, and remote/local transport split for node stats
  - swap the current EKG fetch step for a direct `DataPoint` fetch result that feeds the existing `NodeStats` event path
- Cadence and session model:
  - pin task-301 to repeated short-lived `DataPoint` request or reply sessions, not a new long-lived sibling connection
  - each eligible core-node monitoring job should align to the current 5-second boundary, open a socket to `host.hostname:node.tracingPort`, send the existing eight-key request once, accept the first valid `DataPointsReply` or `Done`, close the socket, and repeat on the next interval
  - do not expand `SocketDataPointSessionClient` into a multi-request stream in this task; the existing one-request-per-connect contract is the smaller truthful fit
- Lifecycle ownership and startup behavior:
  - keep lifecycle ownership inside `NodeMonitor`'s existing per-node job map instead of adding a second independent node-state manager
  - on startup, `NodeMonitor` must self-seed by enumerating `nodeRepository.findAll()` and refreshing the same per-node monitor logic used for later `nodesChannel` updates; it must not rely solely on the unreplayed `nodesChannel`
  - if a tiny helper or factory is introduced for session creation, it must stay stateless and must not become a separate eligibility-owning manager
- Data flow approach:
  - decode `TraceForwardMessage.DataPointsReply` with the existing `NodeStateDataPointDecoder`
  - hand the resulting `NodeStateMetrics` into `NodeMonitor` in a small internal DTO or callback shape, then let `NodeMonitor` assemble the final `NodeStats` using existing identity and epoch-length context
  - on decode failure, disconnect, or missing reply, emit the same null-valued `NodeStats` shape `NodeMonitor` already emits on connection failure
- Eligibility and scope rules:
  - direct node-state sessions apply only to core nodes with a persisted `tracingPort`
  - relay and pool nodes remain out of scope; `NodeMonitor` should not try to open a node-state session for them, should not emit replacement null-valued `nodestats` events for them, and should not reintroduce any HTTP fallback path
  - if the configured default node is not an eligible core node, `latestNodeStats` simply stops receiving fresh updates from `NodeMonitor` in this task and remains the last successful eligible-core value or `null` after startup; task-301 does not invent a new fallback default-node source
- Cleanup boundary:
  - remove all `NodeMonitor` imports, constructor injections, and helpers that only exist for HTTP polling or SSH port forwarding
  - do not delete `EkgService.kt` unless the implementation naturally proves there are zero remaining production call sites and the cleanup remains trivial; otherwise leave file retirement to `task-302`
- Testing approach:
  - add focused tests for `NodeMonitor` event production on successful decoded node-state replies and on failure or disconnect
  - add focused tests to prove startup self-seeding monitors existing eligible core nodes even without a replayed `nodesChannel` event
  - add focused tests to prove relay and pool nodes no longer produce `nodestats` events and that a non-core default node leaves `latestNodeStats` unchanged
  - prefer fake-server and direct sink tests over broader integration harnesses; task-204 already provides deterministic protocol fixtures

## Acceptance Criteria

- `NodeMonitor` no longer polls EKG HTTP endpoints.
- `NodeMonitor` no longer polls Prometheus or any other node HTTP metrics surface.
- `NodeMonitor` no longer uses SSH port forwarding to reach node metrics.
- Direct node-state consumption uses the same JorManager-to-node Hermod-compatible boundary as the tracing subsystem and reuses the pinned `DataPoint` contract from `task-204`.
- The current `NodeStats` contract remains intact where practical: `peers`, `incomingPeers`, `blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed` still map from the same metric meanings.
- The default-node fast path remains intact where practical: successful updates for the default node still refresh `latestNodeStats`.
- Grouped websocket publication for node stats remains intact where practical.
- First-version direct node-state support applies to core nodes only.
- Relay and pool nodes produce no replacement `nodestats` events in task-301, and no HTTP fallback path remains for them.
- If the default node is not an eligible core node, `latestNodeStats` is not refreshed by task-301 and may remain `null` or stale until an eligible core default exists.
- Node-state sampling is explicitly one short `DataPoint` request or reply session per eligible core node every 5 seconds; task-301 does not introduce a multi-request long-lived data-point stream.
- `NodeMonitor` self-seeds existing eligible nodes from `nodeRepository.findAll()` on startup and does not rely only on `nodesChannel` replay behavior.
- `NodeMonitor` no longer has a production dependency on `EkgService`, Retrofit-built node metrics clients, `node.ekgPort`, `SSHClientPool`, or local port-forward helpers.

## Verification Plan

- Static verification:
  - confirm `NodeMonitor.kt` no longer imports or references `EkgService`, Retrofit, `SSHClientPool`, `LocalPortForwarder`, `Parameters`, `ServerSocket`, `DatagramSocket`, or `node.ekgPort`
  - confirm `NodeMonitor.kt` now uses `NodeStateDataPointDecoder`-backed state rather than `EkgMetrics2`
  - confirm the direct path depends on `host.hostname` plus `node.tracingPort` for eligible core nodes, not HTTP endpoint assumptions
  - confirm no HTTP fallback path remains behind conditionals
  - confirm relay and pool direct-node-state parity is not silently widened in this task and that non-core nodes do not emit replacement `nodestats` events
  - confirm startup self-seeding exists in `NodeMonitor` rather than relying only on `nodesChannel`
  - confirm grouped websocket publication still sends `SocketResponse.Success(type = "nodestats", data = ...)` to `/topic/messages`
- Automated verification:
  - add focused backend tests covering:
  - successful direct node-state reply produces the expected `NodeStats` values
  - decode failure or disconnect produces the same null-valued fallback event shape used today
  - default-node successful update still refreshes `latestNodeStats`
  - a non-core default node does not refresh `latestNodeStats`
  - startup self-seeding begins monitoring an already persisted eligible core node even when no new `nodesChannel` event is emitted
  - relay and pool nodes do not start direct node-state monitoring jobs
  - grouped websocket publication still emits the `nodestats` payload shape consumed by the Vue store
  - run the narrowest relevant backend target, preferably the new `NodeMonitor` tests plus any affected tracing lifecycle tests
  - expected command shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.*"`
  - note: the repo's Gradle test graph may also run frontend build and Vue tests as part of the configured task graph
- Truthful validation boundary:
  - this task should not require live node or SSH experimentation because the wire contract is already fixture-pinned by `task-204`

## Risks And Open Questions

- The main implementation risk is overdesign: broadening the tracing package into a generalized multi-protocol framework would be larger than this task needs.
- Another risk is breaking startup monitoring by continuing to rely on `BlockMonitor`'s channel seeding; the plan now resolves that by requiring `NodeMonitor` to self-seed its own eligible nodes on startup.
- Another risk is subtly regressing `NodeMonitor`'s null-on-failure or default-node cache behavior while removing the old transport; focused tests should pin those semantics.
- The main product tradeoff left in this task is intentional de-scope: if operators leave the default node on a relay or pool, `latestNodeStats` can stay stale or null until they choose an eligible core default.
- `latestNodeStats` bean wiring currently reads as nullable in configuration while `NodeMonitor` injects a typed reference; that is a pre-existing seam to handle carefully if touched during tests, not a reason to widen task scope.

## Required Docs, Tracking, And Research Updates

- Create this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`.
- Do not write the planning review log during this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation reveals a durable boundary decision for node-state session ownership or non-core behavior worth preserving for `task-302` and later rollout tasks.
- Record the final node-state migration outcome in a task-specific research note, including the decision to keep lifecycle ownership in `NodeMonitor`, the core-only default-node cache limitation, and the required client-side `MsgDone` direction for the pinned `DataPoint` mini-protocol.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-301-impl-review.md`

## Self-Review

- Scope creep check: the plan keeps persistence, UI, config generation, relay or pool parity, and `EkgService` file retirement out of `task-301`.
- Workflow check: verification relies on the repo's backend and test workflows plus the already-landed task-204 fixtures, not stale live-node-only instructions.
- Missing tests/docs check: the plan explicitly calls for a new focused `NodeMonitor` backend test surface covering startup self-seeding, non-core silence, default-node cache behavior, and the unchanged `/topic/messages` `nodestats` payload shape.
- Consistency check: the plan aligns with the PRD's core-only first migration version and with `task-302` by removing `NodeMonitor`'s dependency on `EkgService` now, keeping lifecycle ownership in `NodeMonitor` for the smallest truthful change, and leaving dead-file retirement as a follow-up cleanup task.

## Final Outcome

- Final result:
  - `NodeMonitor` now consumes node-state through direct short-lived `DataPoint` protocol sessions against `host.hostname:tracingPort` for eligible core nodes and no longer depends on Retrofit HTTP polling, `EkgService`, SSH local port forwarding, or `node.ekgPort`.
  - Startup monitoring no longer depends on `BlockMonitor` channel timing alone because `NodeMonitor` self-seeds eligible persisted nodes from `nodeRepository.findAll()`.
  - Relay and pool nodes are intentionally silent in this task's direct node-state path, and if the configured default node is not an eligible core node then `latestNodeStats` remains unchanged rather than inventing a new fallback source.
  - The pinned task-204 `DataPoint` mini-protocol contract is enforced with client-side `MsgDone` after the first reply, backed by a socket-level regression test.
- Review outcome:
  - Planning loop completed with Planner iteration 2 after one critique pass.
  - Implementation loop completed with Code Review iteration 2 approval.
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest"`
  - Repo task graph also ran Vue unit tests and frontend production build successfully during the Gradle target.
- Research outcome:
  - Added `.agent/plans/cardano-node-jormanager-tracing/research/task-301-node-monitor-direct-datapoint-outcome.md` with the durable lifecycle and protocol findings from this task.
