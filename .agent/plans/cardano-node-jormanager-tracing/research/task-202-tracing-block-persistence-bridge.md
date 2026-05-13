# Task 202 Research Note

## Summary

- `task-202` bridged forwarded adopted-block events into the existing candidate-block persistence flow through a dedicated tracing sink and a shared post-normalization persistence service.

## Durable Findings

- The production `TracingConnectionManager` default no-op sink was no longer safe once tracing events were expected to reach persistence. The live manager now requires an injected `TraceForwardMessageSink`, and `TracingBlockMessageSink` is the concrete Spring-wired implementation for block persistence.
- The persisted `Block.host` value for tracing-driven saves should remain the managed repository host value (`host.hostname`), not the forwarded wrapper `toHostname`, to stay aligned with the legacy `BlockMonitor` persistence semantics.
- While legacy SSH/file discovery still exists, duplicate suppression must be serialized in the shared persistence seam itself rather than only in the old monitor path. A shared persistence mutex in `TracingBlockPersistenceService` prevents same-slot duplicate `completed` rows when tracing and legacy discovery interleave before `task-300` removes the old writer.
- The tracing path intentionally reuses `BlockUtils.getEpochAndSlot(...)` with the existing `latestNodeStats` dependency. If node stats are not ready yet, persisted tracing-driven candidate blocks may legitimately carry `epoch = -1` and `slotInEpoch = -1`; this is the current utility contract and was kept rather than inventing a buffering policy inside `task-202`.

## Verification Evidence

- Focused verification passed with:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest"`
- The repo's Gradle wiring also ran `:testVue` and `:buildVue` during that command, and both passed.

## Deferred Follow-Up

- `task-300` should remove legacy SSH/file discovery and can then simplify the now-redundant legacy-path `blockFoundMutex` around parsing and enrichment.
- If tracing event volume makes it worthwhile later, genesis parsing on the tracing path can be cached or hoisted without changing the persistence contract established here.
