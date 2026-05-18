Implementation: Iteration 1
Timestamp: 2026-05-18T00:27:36Z

Changes made:
- added `TraceForwardProtocol2Extractor` as the shared protocol 2 typed extraction seam for block events, connection counters, and `AddedToCurrentChain` fallback state
- rewired `NodeMonitor` to load forwarded protocol 2 state through the shared extractor over `recentFreshTraceObjectBatches(...)`
- rewired `TracingBlockMessageSink` to decode block events through the shared extractor
- reduced `TraceForwardNodeStateDecoder` and `TraceForwardAdoptedBlockDecoder` to thin delegating wrappers to preserve existing seams with minimal churn
- added focused regression coverage for malformed trace-object merge handling, forged-block contract strictness, and stale trace-object freshness boundaries in `NodeMonitor`

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- result: passed

Deviations from the approved plan:
- none

User interaction required now:
- no

Outcome: implementation iteration 1 complete and ready for code review

Code Review: Iteration 1
Timestamp: 2026-05-18T00:30:14Z

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt:64-68,151-154` hard-codes block-event namespaces to `["Forge","AdoptedBlock"]` and `["Forge","ForgedBlock"]`. The governing live research for this work says the live protocol-2 families are `Forge.Loop.AdoptedBlock` and `Forge.Loop.ForgedBlock` (`.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md:47-49,90-91,130-132`). Current tests only cover the shorter fixture namespace, so this change can still pass locally while dropping live forged/adopted block events entirely. That would break both `created` and `completed` candidate-block persistence in production. The decoder needs to accept the live namespace(s), or the task-500 note must be reconciled with a live-backed fixture/test that proves the shorter namespace is the real wire contract.

Non-blocking observations:
- The new shared seam is only tested indirectly through `TraceForwardAdoptedBlockDecoder`, `TraceForwardNodeStateDecoder`, and `NodeMonitor`; there is no direct unit coverage for `TraceForwardProtocol2Extractor` itself. That is acceptable short-term, but it leaves the new production boundary harder to maintain and easier to regress during task-530/task-531 cleanup.
- The forged-block hash contract is now explicitly pinned in code/tests to `toMachine.block` only (`TraceForwardProtocol2Extractor.kt:67-68`, `TraceForwardAdoptedBlockDecoderTest.kt:85-90`), but the task plan called for recording that contract durably in research/docs as part of this promotion (`.agent/plans/cardano-node-jormanager-tracing/task-plans/task-521.md:205-208,269`). That documentation update does not appear to have landed, so repo guidance is still split between live research and fixture-backed code.

Approval bar:
- Reconcile the protocol-2 forged/adopted block namespace with the live task-500 evidence and add a test that proves the accepted wire shape.
- Update the task-local durable docs/research so the forged-block hash contract and accepted namespace contract are no longer implicit fixture knowledge.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-18T00:31:13Z

Changes made:
- updated `TraceForwardProtocol2Extractor` to accept both fixture-era `Forge.*` and live-proven `Forge.Loop.*` block-event namespaces for forged and adopted blocks
- extended trace-object fixtures and block-decoder tests to prove the accepted live loop namespace contract explicitly
- added durable task research at `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md` capturing the accepted namespace and forged/adopted hash-field contracts

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
- `.agent/plans/cardano-node-jormanager-tracing/research/task-521-protocol2-typed-extraction-contracts.md`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- result: passed

Deviations from the approved plan:
- none

User interaction required now:
- no

Outcome: implementation iteration 2 complete and ready for code review

Code Review: Iteration 2
Timestamp: 2026-05-18T00:31:56Z

Blocking findings:
- none

Non-blocking observations:
- `TraceForwardProtocol2Extractor` is now the real production seam, but it still has only indirect test coverage through wrapper decoders and consumers. That is acceptable for task-521, though a direct extractor test would make later cleanup safer.
- The new task-local research note correctly resolves the previously open namespace and forged-hash contract drift, and it now matches the live code and fixtures.

Approval bar:
- No further blocking changes required for task-521 iteration 2.
- Keep future task-530/task-531 cleanup aligned with the now-documented namespace compatibility and forged/adopted field contracts.

Decision: approved

