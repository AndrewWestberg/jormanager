Implementation: Iteration 1
Timestamp: 2026-05-18T01:14:52Z

Changes made:
- Registered TraceForwardProtocol2Extractor as a Spring component so the protocol-2 block and node-state extractor can be consumed as an explicit shared production dependency instead of only via per-consumer ad hoc construction.
- Added focused TracingBlockMessageSink coverage proving the sink delegates block decode to the injected shared TraceForwardProtocol2Extractor before handing normalized events to TracingBlockPersistenceService.
- Kept the production block boundary otherwise unchanged: raw capture -> shared protocol-2 extraction -> sink node/host resolution -> persistence -> downstream BlockMonitor validation.

Files touched:
- src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt
- src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt

Verification run:
- ./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest" -x buildVue -x testVue
- Result: passed

Deviations from approved plan:
- None. The implementation followed the narrowed plan outcome that task-531 should land as the smallest production-boundary clarification plus regression coverage, not a new block-signal service.

User interaction required:
- No

Outcome: task-531 implementation changes are complete and ready for code review

Code Review: Iteration 1
Timestamp: 2026-05-18T01:16:20Z

Blocking findings:
- None.

Non-blocking observations:
- TraceForwardProtocol2Extractor is now Spring-managed in src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardProtocol2Extractor.kt, which is enough to make the production TracingBlockMessageSink and TracingDashboardSignalService consume the shared extractor bean through constructor injection. The remaining default-constructed fallbacks in TracingBlockMessageSink, TracingDashboardSignalService, TraceForwardAdoptedBlockDecoder, and TraceForwardNodeStateDecoder still allow ad hoc instantiation in tests or non-Spring call sites, but they do not reintroduce per-feature transport ownership in production and are better left for task-540 cleanup.
- Test coverage is aligned with the narrowed task scope. TracingBlockMessageSinkTest now explicitly pins the extractor -> sink -> persistence delegation boundary, while existing BlockMonitorTest coverage continues to protect the validation-only downstream boundary.

Approval bar:
- No further changes needed for task-531. Keep follow-up work limited to later cleanup; do not expand this task into a new block-signal service or broader tracing refactor.

Decision: approved

