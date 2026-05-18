# Task 541 Live Verification Regression Fix

## Status

- Recorded on 2026-05-18 after task-541 implementation review approval.

## Durable Findings

- The reported clockwork protocol-2 regression was caused by the local live-test harness, not by a change on the `clockwork:18401` node.
- Raw protocol-2 probing against `clockwork:18401` still returned live trace objects, including block and peer-related families, during the investigation.
- The most important harness bug was JSON interpretation drift:
  - `traceKindOrNull(...)` only looked for a top-level `kind`
  - real forwarded trace-object payloads from clockwork carry the meaningful discriminator under `data.kind`
  - this made the dashboard and inventory probes report false zero-kind results even when protocol-2 traffic was present
- The second harness regression was request-shape drift in the shared three-protocol helper:
  - `captureAllProtocols(...)` had been widened to request protocol-2 batches with `traceRequestCount = 1_000`
  - that made the short one-connection verification path misleading and inconsistent with the smaller working trace-only probe path
- Narrowing the shared three-protocol metrics request from `GetAllMetrics` to the dashboard metric subset reduced verification noise without changing production runtime behavior.

## Verified Live Evidence

- `liveTraceObjectsProtocolProbeToGld` still returned trace replies locally after the suspected regression window.
- `liveKesDiscoveryProbeOnClockworkPrintsForgeRelatedSignals` proved direct clockwork protocol-2 forge traffic, including:
  - `Forge.Loop.NodeIsLeader`
  - `Forge.Loop.ForgedBlock`
  - `Forge.Loop.AdoptedBlock`
- After the harness fix, `liveSingleConnectionThreeProtocolCaptureToClockwork` recovered non-zero shared-capture counts:
  - `single connection ekg replies=1`
  - `single connection trace replies=2`
  - `single connection datapoint replies=1`
- After the harness fix, `liveClockworkDashboardSignalInventoryPrintsTypedSnapshot` recovered shared-capture protocol-2 kinds:
  - `AddedToCurrentChain`
  - `CompletedBlockFetch`
  - `DownloadedHeader`
  - `ResourceStats`
  - `SendFetchRequest`
  - `TraceAdoptedBlock`
  - `TraceForgedBlock`
  - `TraceNodeIsLeader`
- The new narrow inventory probe `liveClockworkTraceObjectInventoryPrintsKindsAndNamespaces` proved clockwork protocol-2 namespaces including:
  - `Forge.Loop.AdoptedBlock`
  - `Forge.Loop.ForgedBlock`
  - `Forge.Loop.NodeIsLeader`
  - `ChainDB.AddBlockEvent.AddedToCurrentChain`
  - `BlockFetch.Client.SendFetchRequest`
  - `BlockFetch.Client.CompletedBlockFetch`
  - `Mempool.RemoveTxs`

## Residual Note

- `liveFiveMinuteClockworkCapturePrintsInterestingSignals` still prints zero aggregate counts through the older long-running combined helper path and can still emit a connection-reset teardown artifact.
- Treat the short one-connection clockwork probe plus the explicit trace inventory probes as the authoritative final live verification package unless a future task intentionally rehabilitates the old five-minute aggregate printer.

## Files

- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-541.md`
