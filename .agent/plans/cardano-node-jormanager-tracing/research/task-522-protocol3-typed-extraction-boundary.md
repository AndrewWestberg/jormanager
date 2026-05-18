# Task 522 Protocol 3 Typed Extraction Boundary

## Status

- Verified during task-522 implementation on 2026-05-18.

## Durable Findings

- The production protocol `3` request manifest must explicitly include `NodeStartupInfo`; otherwise startup metadata remains test-only even if a typed decoder exists. Runtime capture now requests it through `NodeStateDataPointDecoder.REQUESTED_NAMES`, which is passed unchanged into `TraceForwardSessionClient`.
- The shared production seam for protocol `3` typed extraction is `TraceForwardProtocol3Extractor`, which stays downstream of `TracingRawCaptureService.latestFreshDataPointSnapshot(...)` and does not own transport, reconnects, or snapshot retention.
- `TraceForwardProtocol3Extractor` intentionally keeps scope narrow for this phase:
  - current scalar node-state datapoint fallback remains derived through `NodeStateDataPointDecoder`
  - `NodeStartupInfo` is parsed into a small typed production model carrying `era`, `epochLength`, `slotLength`, and `slotsPerKESPeriod`
  - `NodeAddBlock` remains an internal fallback datapoint contract for epoch and slot-in-epoch only
- `NodeAddBlock` is still explicitly non-authoritative for block height. Task-522 preserves the existing corrected contract by keeping block-height sourcing out of protocol `3` structured datapoint parsing.
- `NodeMonitor` now consumes protocol `3` through `TraceForwardProtocol3Extractor` rather than calling `NodeStateDataPointDecoder` directly, while still preserving the existing precedence order from earlier tasks: protocol `1` wins for overlapping dashboard fields when fresh and complete, protocol `2` still supplies peer counters when available, and protocol `3` remains a fallback contributor plus the production owner of startup metadata.

## Verification Evidence

- Focused backend verification command:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.NodeStateDataPointDecoderTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" -x buildVue -x testVue`
- Result: passed.
- Note: Gradle printed a non-fatal KSP AWT-thread `NullPointerException` before tasks completed, but compilation and the requested tests still finished successfully.

## Files

- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3Extractor.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol3ExtractorTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`
