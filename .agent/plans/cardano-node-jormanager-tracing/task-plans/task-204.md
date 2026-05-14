# Task 204 Plan

## Summary

- Task ID: `task-204`
- Title: `Pin the node-state protocol family and mapping`
- Why now: `task-301` should not start until node-state consumption is specified as concretely as the already-landed adopted-block path
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Pin the exact direct node-state wire contract and the exact `NodeStats` mapping for the first migration version.

In scope:
- pin the exact Hermod-compatible protocol family
- pin the exact `DataPoint` wire envelope and message ids
- pin the exact per-metric `DataPointName` key set for all current `NodeStats` fields
- pin the minimum session topology against the existing tracing manager
- make fixture-backed protocol and mapping artifacts a required outcome before `task-301`

Out of scope:
- refactoring `NodeMonitor` yet
- deleting `EkgService`
- broad observability or subscription abstractions
- any UI, schema, or request-model changes

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-impl-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-203-tracing-fixture-foundation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-204-node-state-datapoint-contract.md`
- Upstream references verified during this pass:
  - `trace-forward/README.md`
  - `trace-forward/src/Trace/Forward/Protocol/DataPoint/Type.hs`
  - `trace-forward/src/Trace/Forward/Protocol/DataPoint/Codec.hs`
  - `trace-forward/src/Trace/Forward/Run/DataPoint/Acceptor.hs`
  - `trace-forward/src/Trace/Forward/Utils/DataPoint.hs`
  - `cardano-node/src/Cardano/Tracing/Tracers.hs`
  - `cardano-node/src/Cardano/Node/Tracing/Tracers/ChainDB.hs`
  - `cardano-node/src/Cardano/Node/Tracing/Tracers/Consensus.hs`
  - `cardano-node/src/Cardano/Node/Tracing/Tracers/KESInfo.hs`
  - `cardano-tracer/docs/cardano-tracer.md`
  - `cardano-tracer/configuration/metrics_help.json`
  - `nixedge/hermod-rust` `src/server/datapoint.rs`
  - `nixedge/hermod-rust` `src/dispatcher/backend/datapoint.rs`

## Code-Index And Live-File Verification

- Code-index was used first, then verified against live files.
- Live repo facts pinned from current code:
  - `NodeMonitor` still polls EKG over HTTP and maps `EkgMetrics2` into `NodeStats`.
  - `TracingConnectionManager` currently owns one long-lived trace-object session per eligible core node.
  - `TraceForwardSessionClient` is trace-object-specific today and hard-codes message ids `1/2/3` for request/done/reply.
  - No node-state mini-protocol exists yet in `src/main/kotlin/com/swiftmako/jormanager/tracing/`.

## Verified Current `NodeStats` Semantics

From `NodeMonitor.EkgMetrics2.toNodeStats(...)`, the existing contract is:

- `peers <- cardano.node.metrics.connectionManager.outgoingConns`
- `incomingPeers <- cardano.node.metrics.connectionManager.incomingConns`
- `blockHeight <- cardano.node.metrics.blockNum`
- `remainingKESPeriods <- cardano.node.metrics.remainingKESPeriods`
- `epoch <- cardano.node.metrics.epoch`
- `slot <- cardano.node.metrics.slotNum`
- `slotInEpoch <- cardano.node.metrics.slotInEpoch`
- `txsProcessed <- cardano.node.metrics.txsProcessedNum`

These semantics stay locked for the first direct-protocol migration.

## Pinned Design Decision

- Pin node-state to the direct `DataPoint` mini-protocol, not forwarded `TraceObject`s.
- Reason:
  - upstream `trace-forward` defines `TraceObject` and `DataPoint` as separate protocol families
  - upstream `cardano-tracer` documentation states that data points are queried explicitly on demand, which matches dashboard metric reads better than log-event streaming
  - the current `NodeStats` contract is already a named-metric contract, not an event-derived one

## Exact Wire Contract

The first node-state migration version must use the upstream `DataPointForward` protocol shape from `trace-forward/src/Trace/Forward/Protocol/DataPoint/Type.hs` and `Codec.hs`.

Pinned messages:

- `MsgDataPointsRequest`
  - message id: `1`
  - wire envelope: `array(2)[1, [DataPointName...]]`
  - direction: JorManager acceptor/client -> node forwarder/server
- `MsgDone`
  - message id: `2`
  - wire envelope: `array(1)[2]`
  - direction: JorManager -> node
- `MsgDataPointsReply`
  - message id: `3`
  - wire envelope: `array(2)[3, [(DataPointName, Maybe DataPointValue)...]]`
  - direction: node -> JorManager

Pinned payload rules:

- `DataPointName` is the exact requested key string.
- `DataPointValue` is raw JSON bytes.
- Each reply item is a tuple `array(2)[name, maybeValue]`.
- `Nothing` is encoded as `array(0)`.
- `Just value` is encoded as `array(1)[bytes]` where `bytes` hold raw JSON for that key.
- The implementation must accept definite and indefinite-length CBOR arrays and byte strings, matching upstream Haskell `serialise` behavior and the already-verified Hermod Rust compatibility code.

## Exact Node-State Key Set

For this migration version, `task-204` pins the required `DataPointName` requests to these exact keys:

- `cardano.node.metrics.connectionManager.outgoingConns` -> `NodeStats.peers`
- `cardano.node.metrics.connectionManager.incomingConns` -> `NodeStats.incomingPeers`
- `cardano.node.metrics.blockNum` -> `NodeStats.blockHeight`
- `cardano.node.metrics.remainingKESPeriods` -> `NodeStats.remainingKESPeriods`
- `cardano.node.metrics.epoch` -> `NodeStats.epoch`
- `cardano.node.metrics.slotNum` -> `NodeStats.slot`
- `cardano.node.metrics.slotInEpoch` -> `NodeStats.slotInEpoch`
- `cardano.node.metrics.txsProcessedNum` -> `NodeStats.txsProcessed`

Evidence basis for the key set:

- `cardano-node/src/Cardano/Tracing/Tracers.hs` emits the `cardano.node.metrics.*` metric namespace for direct EKG metrics.
- `ChainDB.hs` emits `slotNum`, `blockNum`, `slotInEpoch`, and `epoch`.
- `Consensus.hs` emits `txsProcessedNum`.
- `KESInfo.hs` emits `remainingKESPeriods`.
- `cardano-tracer` docs and `metrics_help.json` show the same metric names as the external monitoring surface.
- `NodeMonitor` already maps those same metrics from `EkgMetrics2`, so task-204 does not redefine dashboard semantics.

Pinned value decoding rules:

- each `DataPointValue` JSON payload is decoded as a single scalar number for these 8 keys
- `peers`, `incomingPeers`, and `remainingKESPeriods` must downcast to `Int`
- `blockHeight`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed` must decode to `Long`
- any missing key, `Nothing` value, non-numeric JSON, or overflow is a safe decode failure for that update
- `isDefault`, `timestamp`, `nodeName`, `color`, and `epochLength` remain assembled by `NodeMonitor`, not the protocol decoder

## Minimum Session Topology

Task-204 pins the minimum truthful topology against the existing tracing manager as:

- one long-lived `TraceObject` session per eligible core node remains in `TracingConnectionManager` for block events
- one separate long-lived `DataPoint` session per eligible core node is added as a sibling connection, not multiplexed into the existing `SocketTraceForwardSessionClient`
- both sessions connect to the same node host and tracing port
- both sessions reuse the same eligibility rules as the current manager: core node only, non-deleted, persisted `tracingPort` present
- `task-301` may share lifecycle patterns or extend manager ownership, but must not block on inventing a shared mixed-message session abstraction first

Why this is the minimum topology:

- the current repo has a single-purpose trace-object client and sink seam only
- adding a sibling `DataPoint` client is smaller and lower-risk than widening the existing client into a polymorphic mux manager before monitor migration
- upstream supports muxed protocols on one bearer, but this repository does not currently expose that level of channel management in the tracing package, so task-204 pins the smallest truthful production step rather than a larger redesign

## Required Fixture-Backed Outcome Before `task-301`

Before `task-301` starts, `task-204` must leave a committed fixture-backed foundation that proves the exact node-state envelope and key set above.

Required artifacts:

- committed `DataPoint` request fixture bytes for the exact 8-key request list
- committed `MsgDataPointsReply` fixture bytes for a full successful reply covering all 8 keys
- committed negative fixtures for:
  - missing key item
  - `Nothing` value item
  - malformed scalar JSON
  - wrong numeric type or overflow case
- a reusable fake-server or session test path proving the request/reply/done round-trip for the `DataPoint` session
- a decoder or mapper test proving the 8-key reply becomes the same `NodeStats` field values as the current EKG path for equivalent inputs

The exact envelope and key set above are therefore part of task completion, not an implementation-time discovery item.

## Implementation Shape For The Later Code Task

- add one sibling `DataPoint` message model beside `TraceForwardMessage`
- add one sibling session client beside `TraceForwardSessionClient`
- add one focused node-state decoder or mapper for the 8-key contract only
- extend tracing fixture support with `DataPoint` request/reply bytes and fake-session coverage
- defer the actual `NodeMonitor` refactor to `task-301`

## Acceptance Criteria

- The node-state family is explicitly pinned to `DataPoint`.
- The exact wire envelope is pinned to:
  - request id `1` / `array(2)[1, [names...]]`
  - done id `2` / `array(1)[2]`
  - reply id `3` / `array(2)[3, [(name, maybeValue)...]]`
- The exact 8-key `DataPointName` set is pinned for `peers`, `incomingPeers`, `blockHeight`, `remainingKESPeriods`, `epoch`, `slot`, `slotInEpoch`, and `txsProcessed`.
- The minimum runtime topology is pinned to a second long-lived sibling `DataPoint` session per core node against the existing tracing manager pattern.
- Fixture-backed protocol and mapping artifacts for that exact envelope and key set are required before `task-301` begins.

## Verification Plan

- Static verification in the later implementation:
  - confirm the new client is a sibling `DataPoint` path, not an overloaded trace-object decoder
  - confirm the exact 8-key request list matches this plan
  - confirm no HTTP EKG or Prometheus polling remains on the migrated node-state path
- Automated verification required from `task-204` implementation output:
  - request fixture bytes match message id `1`
  - reply fixture bytes match message id `3`
  - done fixture bytes match message id `2`
  - full positive reply decodes into the same 8 field values the current EKG path would produce
  - negative fixtures fail safely without partial silent remapping
  - session-level fake-server coverage proves the `DataPoint` round-trip over reproducible transport fixtures

## Risks And Open Questions

- The main implementation risk is CBOR handling for Haskell `serialise` indefinite-length arrays and byte strings; this is why fixture bytes are a required outcome.
- Another risk is accidental overdesign into a generalized mux subsystem; this plan explicitly avoids that.
- No blocking protocol-selection open question remains for `task-301` after this revision.

## Required Docs And Tracking Updates

- Revise this canonical plan doc with the final approved outcome.
- Update the PRD and tasks JSON to record that task-204 is complete and that task-301 now depends on the pinned DataPoint contract and literal fixture anchors established here.
- Record the durable node-state contract findings in `.agent/plans/cardano-node-jormanager-tracing/research/task-204-node-state-datapoint-contract.md`.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-204-impl-review.md`

## Self-Review

- The critique items are resolved directly in the canonical plan:
  - exact `DataPoint` envelope and message ids are pinned
  - exact 8-key node-state key set is pinned with upstream evidence
  - minimum session topology is pinned against the existing tracing manager
  - fixture-backed exact-envelope output is now a hard prerequisite before `task-301`

## Final Outcome

- Approved implementation outcome:
  - added a sibling `DataPoint` protocol surface in `tracing/` without touching `NodeMonitor`
  - added `NodeStateDataPointDecoder` and `NodeStateMetrics` to codify the exact 8-key mapping into the current `NodeStats` contract
  - added literal-byte request and reply anchors plus negative and chunked-byte fixtures so the pinned wire contract is independently testable
  - added focused decoder and session tests proving positive mapping parity, safe failure handling, chunked byte-string compatibility, and fake-server round-trip behavior
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
  - result: passed; this repo's Gradle wiring also ran Vue unit tests and frontend production build during the command
- Review outcome:
  - planning review converged in 2 planner iterations with 1 critique pass
  - implementation review converged in 2 implementation iterations with final `Decision: approved`
- Residual scope intentionally deferred:
  - `TracingConnectionManager` is not yet widened to own the sibling node-state session
  - `NodeMonitor` still uses the current HTTP polling path until `task-301`
