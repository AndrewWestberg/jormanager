Implementation: Iteration 1
Timestamp: 2026-05-18T00:59:55Z

Changes made:
- added `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt` as the single tracing-side dashboard assembly seam downstream of `TracingRawCaptureService`
- narrowed `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt` so it consumes `TracingDashboardSignalService` instead of directly decoding protocol `1`/`2`/`3` snapshots
- preserved protocol precedence in the shared seam and fixed one live gap discovered during verification: fresh protocol `2` counters now override protocol `3` peer values even when protocol `1` metrics are absent
- added focused service-level tests in `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalServiceTest.kt`
- updated `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt` to construct `NodeMonitor` through the new seam and added a monitor-level regression test for fresh protocol `2` peer counter precedence without protocol `1` metrics

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalServiceTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingDashboardSignalServiceTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardProtocol3ExtractorTest" -x buildVue -x testVue`

Deviations from approved plan:
- none in scope; implementation stayed within the approved seam extraction
- verification surfaced a real precedence bug in the live baseline, so the shared seam now enforces the already-planned rule that fresh protocol `2` peer counters outrank protocol `3` peer values even without protocol `1` metrics

User interaction required:
- no

Outcome: implementation iteration 1 completed with targeted tests passing and the monitor boundary narrowed to the shared tracing-side dashboard seam.

Code Review: Iteration 1
Timestamp: 2026-05-18T01:01:13Z

Blocking findings:
- None.

Non-blocking observations:
- `NodeMonitor` is truthfully narrowed away from direct protocol-family assembly ownership. It now consumes only `TracingDashboardSignalService` for tracing-derived node-state assembly and keeps the expected lifecycle, eligibility, cadence, websocket batching, and `latestNodeStats` responsibilities (`src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt:54-71`, `304-333`).
- The new seam stays downstream of `TracingRawCaptureService` and does not become a new cache or transport owner. Freshness and invalidation still come from raw capture, while the new service only assembles a snapshot (`src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt:12-85`, `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingRawCaptureService.kt:47-126`).
- Precedence and fallback rules remain aligned with the approved plan and are covered by focused tests: fresh complete protocol `1` metrics win for overlapping fields, fresh protocol `2` counters override protocol `3` peer values, stale protocol `2` batches do not keep supplying peers or chain fallback, and `AddedToCurrentChain` remains the chain fallback when fresher complete snapshots are absent (`src/test/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalServiceTest.kt:32-149`, `src/test/kotlin/com/swiftmako/jormanager/monitors/NodeMonitorTest.kt:255-507`).
- Startup-info availability was preserved without widening `NodeStats`. The new tracing seam keeps `startupInfo` available independently of dashboard metrics, and there is explicit coverage for the startup-only case (`src/main/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalService.kt:6-9`, `73-75`; `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingDashboardSignalServiceTest.kt:151-174`).
- No tracing-boundary drift or block-monitoring regression showed up in this pass. `BlockMonitor` was left alone, and the new seam is node-dashboard specific rather than a competing tracing consumer path.
- Workflow compliance is satisfied for this task slice: the focused backend verification passed with `NodeMonitorTest`, `TracingDashboardSignalServiceTest`, and `TraceForwardProtocol3ExtractorTest`.

Approval bar:
- Keep `NodeMonitor` consuming the tracing-side dashboard seam rather than reintroducing direct raw-capture or extractor assembly there.
- Keep `TracingRawCaptureService` as the only freshness and raw-snapshot owner; do not let the dashboard seam grow into a second cache or transport lifecycle.
- Preserve the current `NodeStats` and websocket contract unless a later approved task explicitly broadens it.

Decision: approved

