Planner: Iteration 1
Timestamp: 2026-05-13T00:26:00Z

Reviewed the required docs, PRD, tasks JSON, research inventory, and adjacent task plans; refreshed the code index first and verified the important seams against live files before pinning the plan.
The code index materially affected planning because it exposed that the prompt nodeclient anchor is stale in this repo, while the real direct protocol foundation now lives in src/main/kotlin/com/swiftmako/jormanager/tracing/ and NodeMonitor still uses only EKG over HTTP plus SSH port forwarding.
Based on the verified upstream split between TraceObject and DataPoint families and the current structured NodeStats contract, I created the canonical plan at .agent/plans/cardano-node-jormanager-tracing/task-plans/task-204.md and pinned the first node-state migration version to the direct Hermod-compatible DataPoint family rather than forwarded TraceObjects.
The plan preserves the current EKG semantic mapping: peers <- outgoingConns, incomingPeers <- incomingConns, blockHeight <- blockNum, remainingKESPeriods <- remainingKESPeriods, epoch <- epoch, slot <- slotNum, slotInEpoch <- slotInEpoch, txsProcessed <- txsProcessedNum.
Outcome: Plan drafted and ready for critique

Critiquer: Iteration 1
Timestamp: 2026-05-13T00:27:56Z

Blocking findings:
- The plan picks DataPoint as the family but still does not pin the actual wire contract or concrete metric keys. It leaves the mapping at the level of equivalents to current EKG fields and defers the exact envelope and per-metric keys to implementation, which is too open for this task.
- The plan also leaves the runtime session topology too open. It needs to decide whether node-state uses a second long-lived session, a muxed/shared connection, or some other explicit minimal model against the existing trace-object manager.

Non-blocking observations:
- Choosing DataPoint over forwarded TraceObjects is the simpler and more credible direction for NodeStats.
- The later implementation should preserve the current default-node caching and websocket publication cadence behavior in NodeMonitor.

Approval bar:
- Pin the exact DataPoint wire envelope, message ids, and concrete per-metric data-point keys needed for peers, incomingPeers, blockHeight, remainingKESPeriods, epoch, slot, slotInEpoch, and txsProcessed, with upstream-backed evidence or committed research.
- Pin the minimum session topology for node-state consumption against the existing tracing manager.
- Make the exact node-state envelope and key set a concrete fixture-backed task outcome before task-301 starts.
Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-13T00:32:15Z

Reviewed the required docs, PRD, tasks JSON, research inventory, critique, and live tracing and monitor files again; used code-index first and then verified the important seams directly in NodeMonitor, NodeStats, TraceForwardSessionClient, TraceForwardMessage, and TracingConnectionManager.
Revised the canonical plan at .agent/plans/cardano-node-jormanager-tracing/task-plans/task-204.md to pin the node-state family to the upstream DataPoint mini-protocol with the exact wire contract: MsgDataPointsRequest id 1 as array(2)[1, [names...]], MsgDone id 2 as array(1)[2], and MsgDataPointsReply id 3 as array(2)[3, [(name, maybeValue)...]], with Maybe encoded as array(0) or array(1)[bytes].
The plan now pins the exact node-state key set needed for the current NodeStats contract: cardano.node.metrics.connectionManager.outgoingConns, cardano.node.metrics.connectionManager.incomingConns, cardano.node.metrics.blockNum, cardano.node.metrics.remainingKESPeriods, cardano.node.metrics.epoch, cardano.node.metrics.slotNum, cardano.node.metrics.slotInEpoch, and cardano.node.metrics.txsProcessedNum.
The minimum runtime topology is also pinned: keep the current long-lived trace-object session per eligible core node for block events and add one separate long-lived sibling DataPoint session per eligible core node on the same host and tracing port, instead of widening the current trace-object client into a mixed-session abstraction first.
The plan makes the exact node-state envelope and key set a fixture-backed prerequisite before task-301: committed request, reply, and done CBOR fixtures, positive and negative node-state reply fixtures for the eight keys, a fake-server or session round-trip test, and decoder coverage proving parity with the current EKG-derived NodeStats mapping.
Outcome: Canonical plan revised and ready for re-review

