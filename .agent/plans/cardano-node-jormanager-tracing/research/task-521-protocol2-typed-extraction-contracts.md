# Task 521 Protocol 2 Typed Extraction Contracts

## Status

- Verified during task-521 implementation on 2026-05-18.

## Durable Findings

- The promoted production protocol `2` extractor must accept both the older fixture namespace shape and the live dispatcher-era namespace shape for block events:
  - adopted block: `Forge.AdoptedBlock` and `Forge.Loop.AdoptedBlock`
  - forged block: `Forge.ForgedBlock` and `Forge.Loop.ForgedBlock`
- This compatibility is required because task-500 live-node validation proved `Forge.Loop.*` families on real nodes, while existing repo fixtures and earlier decoder tests still exercise the shorter `Forge.*` shape.
- Adopted block decoding remains strict on machine-owned `toMachine.blockHash`.
- Forged block decoding remains pinned to machine-owned `toMachine.block` for current repo truth; `toMachine.blockHash` alone is not accepted for forged events until new live or upstream evidence proves that shape.
- The shared production seam for task-521 is `TraceForwardProtocol2Extractor`, with `NodeMonitor` and `TracingBlockMessageSink` consuming it downstream of `TracingRawCaptureService.recentFreshTraceObjectBatches(...)`.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardNodeStateDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- The updated block-decoder tests now cover both short fixture namespaces and live `Forge.Loop.*` block-event namespaces.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoderTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
