# Task 500 Single-Connection Trace-Forward Architecture

## Status

- Research completed and live-verified on 2026-05-17.

## Durable Findings

- The correct long-term JorManager architecture is one outbound TCP connection per eligible core node, not separate runtime sockets for trace objects, datapoints, and metrics.
- That one connection must perform the trace-forward version handshake first and then run all required mini-protocols concurrently over the same muxed connection.
- For the current node/forwarder stack, the mini-protocol allocation is:
  - protocol `1`: EKG metrics
  - protocol `2`: trace objects
  - protocol `3`: datapoints
- JorManager should treat the trace-forward stack as three distinct data families sharing one transport:
  - protocol `1` for dispatcher-backed metrics
  - protocol `2` for event-rich trace objects
  - protocol `3` for explicit named datapoints

## Protocol-Level Findings

### Connection Handshake

- The outer connection handshake uses `ForwardingV_1` with `ForwardingVersionData { networkMagic }`.
- The live-compatible network magic in this environment is `141`.

### Protocol 1 EKG Metrics

- Protocol `1` uses the EKG metrics request/response family.
- The request modes now proven by tests are:
  - `GetAllMetrics`
  - `GetMetrics(names)`
- The response shape is nested and must be decoded as:
  - outer message id `1`
  - inner `ResponseMetrics` constructor tag `0`
  - metric list payload
- Metric values are typed by constructor tag in the CBOR/JSON bridge used by the tests:
  - `[0, n]` counter
  - `[1, n]` integer gauge
  - `[2, "..."]` label or real encoded as text
- Metric names in protocol `1` are the actual EKG store keys, not the old datapoint keys. Dispatcher-backed metrics use the configured prefix plus type suffixes such as `_int`, `_real`, and `_counter`.

### Protocol 2 Trace Objects

- Protocol `2` is the richest event stream and should be treated as an append-only live event feed.
- JorManager now has live proof for these trace families on real nodes:
  - `Forge.Loop.NodeIsLeader`
  - `Forge.Loop.ForgedBlock`
  - `Forge.Loop.AdoptedBlock`
  - `ChainDB.AddBlockEvent.AddedToCurrentChain`
  - `Net.ConnectionManager.Remote.ConnectionManagerCounters`
  - `Mempool.AddedTx`
  - `Mempool.RemoveTxs`
- Trace objects remain the only proven source for connection counters and block-event timing/state transitions.

### Protocol 3 DataPoints

- Protocol `3` cannot enumerate names; exhaustive capture requires a source-derived manifest.
- DataPoint names are exact namespace joins from `mkDataPointTracer` / `dataPointTracer` in `trace-dispatcher`, not metric names.
- The current source-derived exhaustive datapoint manifest for the traced node-state family is:
  - `NodeInfo`
  - `NodeStartupInfo`
  - `NodeTracingOnlineConfiguring`
  - `NodeTracingFailure`
  - `NodeTracingForwardingInterrupted`
  - `PrometheusSimple.Start`
  - `PrometheusSimple.Stop`
  - `OpeningDbs`
  - `NodeReplays`
  - `NodeInitChainSelection`
  - `NodeKernelOnline`
  - `NodeAddBlock`
  - `NodeStartup`
  - `NodeShutdown`

## Corrected Semantics

### NodeAddBlock

- `NodeAddBlock` is not block height.
- The real structured payload is:
  - epoch
  - slot in epoch
  - sync percentage
- JorManager test and decoder assumptions were corrected accordingly.
- Full slot and block height should come from protocol `1` chain metrics and/or protocol `2` trace objects, not from `NodeAddBlock`.

### Forged vs Adopted Blocks

- `Forge.Loop.ForgedBlock` must be captured and persisted as block status `created` immediately.
- `Forge.Loop.AdoptedBlock` must still be captured and persisted as block status `completed`.
- This preserves the old SSH/log-scrape semantics while aligning them to the new trace-object stream.

## Live-Proven Signal Inventory

### Dispatcher-Backed Protocol 1 Metrics Observed Live On `clockwork:18401`

- Chain/state metrics:
  - `cardano.node.metrics.blockNum_int`
  - `cardano.node.metrics.slotNum_int`
  - `cardano.node.metrics.slotInEpoch_int`
  - `cardano.node.metrics.epoch_int`
  - `cardano.node.metrics.density_real`
  - `cardano.node.metrics.tipBlock`
- Forge metrics:
  - `cardano.node.metrics.forging_enabled_int`
  - `cardano.node.metrics.Forge.about-to-lead_counter`
  - `cardano.node.metrics.Forge.node-not-leader_counter`
  - `cardano.node.metrics.Forge.node-is-leader_counter`
  - `cardano.node.metrics.forgedSlotLast_int`
  - `cardano.node.metrics.Forge.forged_counter`
  - `cardano.node.metrics.Forge.adopted_counter`
- KES metrics:
  - `cardano.node.metrics.operationalCertificateStartKESPeriod_int`
  - `cardano.node.metrics.operationalCertificateExpiryKESPeriod_int`
  - `cardano.node.metrics.currentKESPeriod_int`
  - `cardano.node.metrics.remainingKESPeriods_int`
- Additional dispatcher metrics observed:
  - `cardano.node.metrics.utxoSize_int`
  - `cardano.node.metrics.delegMapSize_int`
  - `cardano.node.metrics.blockReplayProgress_real`
  - `cardano.node.metrics.txsSyncDuration_int`
  - `cardano.node.metrics.txsSyncDurationTotal_counter`
  - `cardano.node.metrics.txsInMempool_int`
  - `cardano.node.metrics.mempoolBytes_int`
  - `cardano.node.metrics.txsProcessedNum_counter`

### Trace Families Observed Live On `clockwork:18401`

- `Forge.Loop.NodeIsLeader`
- `Forge.Loop.ForgedBlock`
- `Forge.Loop.AdoptedBlock`
- `Mempool.AddedTx`
- `Mempool.RemoveTxs`

### DataPoints Observed Live On `clockwork:18401`

- `NodeInfo`
- `NodeStartupInfo`
- `NodeKernelOnline`
- `NodeTracingOnlineConfiguring`
- `NodeAddBlock`

## Important Negative Findings

- The legacy direct EKG metrics written through `EKGDirect` are still not surfaced by protocol `1` in the current live tests.
- The absent families include:
  - `cardano.node.metrics.connectionManager.*`
  - `cardano.node.metrics.peerSelection.*`
  - `cardano.node.metrics.inboundGovernor.*`
- Those families are therefore currently recoverable only from trace objects where available, not from protocol `1`.
- JorManager must keep protocol `2` decoding for peer and connection counters even after protocol `1` integration lands.

## Typed Dashboard Snapshot Proven In Tests

The test harness now proves a single typed dashboard snapshot can be assembled from one connection using protocol `1` plus `NodeStartupInfo` from protocol `3`:

- `ChainMetrics`
  - `blockNum`
  - `slotNum`
  - `slotInEpoch`
  - `epoch`
  - `density`
  - `tipBlock`
- `ForgeMetrics`
  - `forgingEnabled`
  - `aboutToLead`
  - `nodeNotLeader`
  - `nodeIsLeader`
  - `forgedSlotLast`
  - `forged`
  - `adopted`
- `KesMetrics`
  - `operationalCertificateStartKesPeriod`
  - `operationalCertificateExpiryKesPeriod`
  - `currentKesPeriod`
  - `remainingKesPeriods`
- `MempoolMetrics`
  - `txsInMempool`
  - `mempoolBytes`
  - `txsProcessedNum`
  - `txsSyncDuration`
  - `txsSyncDurationTotal`
  - `txsMempoolTimeoutSoft`
- Startup datapoint JSON
  - `suiEpochLength`
  - `suiEra`
  - `suiSlotLength`
  - `suiSlotsPerKESPeriod`

## Architectural Conclusion

- The runtime architecture should be re-thought around one elegant per-node tracing session manager that owns exactly one muxed connection and fans data into typed collectors for protocols `1`, `2`, and `3`.
- JorManager should capture all raw replies first, then run typed extraction layers on top.
- Runtime decoding should no longer be centered on ad hoc one-off session clients per data family.
- The production subsystem should converge on:
  - one connection manager per core node
  - one handshake
  - three concurrent protocol loops
  - a raw-capture layer
  - typed extraction layers for dashboard and block-monitoring needs
  - future discard/filtering only after capture, not before

## Verification Evidence

- Focused live verification commands used during this research phase include:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkKesMetricsAreCapturedFromProtocol1 -x buildVue -x testVue`
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkMempoolMetricsTypedDecodeRoundTrips -x buildVue -x testVue`
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveFiveMinuteClockworkMempoolCapturePrintsObservedSignals -x buildVue -x testVue`
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest.liveClockworkDashboardSignalInventoryPrintsTypedSnapshot -x buildVue -x testVue`
- Wider verification command:
  - `JORMANAGER_RUN_LIVE_TRACING_TESTS=true ./gradlew test --tests com.swiftmako.jormanager.tracing.LiveTraceForwardIntegrationTest --tests com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest --tests com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest --tests com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest --tests com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest --tests com.swiftmako.jormanager.monitors.NodeMonitorTest -x buildVue -x testVue`

## Files

- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
