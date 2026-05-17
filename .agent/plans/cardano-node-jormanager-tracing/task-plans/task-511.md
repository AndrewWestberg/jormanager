# Task 511 Plan

## Summary

- Task ID: `task-511`
- Title: `Add production raw capture models for metrics, trace objects, and datapoints`
- Why now: `task-500` and the live single-connection harness already prove that one muxed tracing session can capture protocol `1`, `2`, and `3` together, but production code still lacks a shared raw-capture layer and still routes protocol data straight into feature-specific decoders or sinks
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Introduce the minimum production raw-capture layer that retains protocol `1` metrics, protocol `2` trace-object batches, and protocol `3` datapoint snapshots as shared in-memory tracing data, without widening into dashboard-specific typed extraction, block-specific business logic, or durable trace history.

In scope:
- create and maintain `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md` as the canonical task plan
- land the minimum production unified ingress seam needed so one tracing-runtime-owned connection path becomes the authoritative source of protocol `1`, `2`, and `3` messages
- add production raw message or snapshot models for protocol `1`, protocol `2`, and protocol `3`
- keep protocol `1` values type-safe at the raw layer so counters, integer gauges, and label or text values remain distinguishable before later typed extraction
- keep protocol `2` capture as raw trace-object batches with the full forwarded payload preserved for later decoders
- keep protocol `3` capture as raw datapoint snapshots that preserve missing versus present values accurately
- add one production routing or capture service that is the first visibility point for all production protocol `1`, `2`, and `3` ingress and stores or republishes raw data without dashboard or block semantics baked in
- reroute the current production ingress paths so feature code no longer owns authoritative protocol sockets for the captured families
- add focused automated tests for raw capture retention and routing

Out of scope:
- adding chain, forge, KES, mempool, peer-counter, startup-info, or block-event typed extraction logic beyond generic raw decoding
- broad `NodeMonitor` or block-persistence business-logic refactors beyond the minimal ingress reroute needed so they stop owning direct protocol reads
- durable trace persistence, database storage, journald integration, or replay
- adding new UI, config-generation, or rollout-template work

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
  - `.agent/plans/readme.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-500-single-connection-trace-forward-architecture.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`

## Code-Index Sync

- Set the code-index project path to `/home/westbam/Development/jormanager`
- Verified code-index settings and watcher state with `code-index_get_settings_info` and `code-index_get_file_watcher_status`
- Confirmed the watcher is active, the project path is correct, and the index is ready, so no `refresh_index` call was needed during planning
- Used code-index summaries first for `TracingConnectionManager.kt`, `TraceForwardMessage.kt`, `TraceForwardSessionClient.kt`, `DataPointSessionClient.kt`, `TracingConnectionManagerTest.kt`, `NodeMonitor.kt`, and `LiveTraceForwardIntegrationTest.kt`
- Re-verified all material findings against the live files before drafting the plan because task sequencing is partially stale and runtime truth matters more than tracker labels here

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning
- Truthful autonomy basis:
  - the work is a repo-local backend refactor plus focused tests
  - the required architecture anchor is already established by repo-local research and tests
  - no operator-run live validation is required to implement the raw models honestly, even though optional live harness reuse remains useful later

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-510`
- Practical live repo dependencies this task must stay aligned with:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-520`
  - `task-521`
  - `task-522`
  - `task-530`
  - `task-531`

## Transport Boundary

- By the end of `task-511`, production ingress for forwarded tracing data must have exactly one authoritative runtime owner in the tracing package: the unified tracing connection path introduced by `task-510`.
- That ingress seam is the first visibility point for all production protocol `1`, `2`, and `3` messages.
- Raw capture begins at that seam, before `NodeMonitor`, block persistence, or any other feature-specific decoder filters or projects the data.
- `NodeMonitor` and block-related consumers may continue using their existing typed decoders temporarily, but only downstream of the shared raw capture service.
- `NodeMonitor` must no longer open authoritative protocol `2` or protocol `3` sockets of its own once this task is complete.
- `TracingBlockMessageSink` may remain a downstream consumer, but it must receive protocol `2` data only after the shared raw layer has recorded first visibility for that ingress path.

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - production message modeling currently exposes only `TraceObjectsReply`, `DataPointsReply`, and `Done`
  - there is no protocol `1` production message family yet
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - production trace session client still opens one socket, performs the forwarding handshake, and runs only `ForwardingTraceObjectsProtocol`
  - this is not yet the final unified production capture topology
- `src/main/kotlin/com/swiftmako/jormanager/tracing/DataPointSessionClient.kt`
  - datapoint collection still owns a separate socket, separate handshake, and protocol `3`-only session
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - the manager currently owns only protocol `2` transport and reconnect lifecycle
  - the existing test coverage proves lifecycle behavior for that one-family production manager, not full unified raw capture yet
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - node-state loading still opens separate short-lived protocol `3` and protocol `2` sessions per sample
  - current production node-state and block-event decoders consume feature-specific message types directly rather than a shared raw layer
- `src/main/kotlin/com/swiftmako/jormanager/tracing/NodeStateDataPointDecoder.kt`
  - protocol `3` production decoding is already opinionated toward `NodeStateMetrics`, not generic raw capture
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardNodeStateDecoder.kt`
  - protocol `2` node-state extraction is already typed toward `ForwardedNodeState`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - protocol `2` block extraction is already typed toward `ForwardedBlockEvent`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - block persistence is currently wired straight from raw `TraceObjectsReply` messages into the block decoder and persistence service
- `src/test/kotlin/com/swiftmako/jormanager/tracing/LiveTraceForwardIntegrationTest.kt`
  - `captureAllProtocols(...)` already provides the strongest live repo proof for the final transport shape: one connection, one handshake, and concurrent protocol `1`, `2`, and `3` loops
  - the test file already contains a working generic `EkgReply` and `EkgMetricsProtocol` harness for protocol `1`, but those pieces are still test-only and not available to production code
- Tracker versus live-repo truth:
  - `task-510` is still marked `pending` in the tasks JSON, and the production runtime agrees that full unification is not landed yet
  - however, the single-connection foundation is already landed in research and tests, so `task-511` must explicitly complete the minimum production ingress seam it depends on and must not design around sibling sockets as if they were still the target architecture

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardMessage.kt`
  - expand or reshape the production message model so protocol `1` raw messages can be represented alongside protocol `2` and `3`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - add the smallest truthful raw-capture models and one tracing-owned ingress or capture service rather than spreading raw state across existing decoders
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardSessionClient.kt`
  - complete the minimum unified session API needed to surface protocol-family-tagged production messages through one tracing-owned ingress path
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - remove direct authoritative protocol ingress ownership and switch any remaining decode path to downstream raw-capture consumption only
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - only for minimal wiring changes so trace-object persistence remains downstream of shared raw capture rather than a parallel first-visibility path
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
  - extend only if unified runtime routing needs focused manager-level assertions
- `src/test/kotlin/com/swiftmako/jormanager/tracing/`
  - add focused raw-capture tests and promote only the minimum protocol `1` generic fixtures needed out of the live harness shape
- `.agent/plans/cardano-node-jormanager-tracing/research/task-511-*.md`
  - only if implementation uncovers a durable raw-model boundary decision not already captured by task-500 research

## Implementation Approach

- Keep the solution minimal and layered:
  - do not turn `task-511` into the full consumer migration
  - do not introduce durable storage or a trace archive
  - do not add separate per-family runtime ownership again
- Treat `task-510` as the transport prerequisite and make the dependency explicit in this task's implementation boundary:
  - `task-511` is not complete unless production code has one tracing-owned ingress seam that carries protocol `1`, `2`, and `3` into raw capture
  - if the working branch does not already provide that seam, implement only the minimum additional `task-510` follow-through required to make it real, then stop at raw capture rather than widening into typed extraction
- Preferred raw production model shape:
  - protocol `1`: raw metric snapshot keyed by metric name, with a sealed raw value type that preserves counter versus integer versus label or text semantics
  - protocol `2`: raw trace-object batch preserving the full forwarded payload needed by future decoders
  - protocol `3`: raw datapoint snapshot keyed by datapoint name, preserving nullable value presence exactly
  - keep timestamps and node identity on the outer raw capture records if they help downstream consumers without forcing typed business meaning into the model
- Preferred routing shape:
  - one small in-memory tracing raw capture service in `src/main/kotlin/com/swiftmako/jormanager/tracing/`
  - unified ingress writes into that service first for protocol `1`, `2`, and `3`
  - that service should keep the latest protocol `1` and protocol `3` snapshots per node and expose protocol `2` batches as raw event flow without feature-specific filtering
  - if a tiny replay or buffer is needed for protocol `2` fan-out, keep it bounded and in-memory only
- Reuse, do not duplicate, proven protocol knowledge:
  - lift only the generic protocol `1` reply decoding needed for raw metric capture from the live harness shape
  - keep `NodeStateDataPointDecoder`, `TraceForwardNodeStateDecoder`, and `TraceForwardAdoptedBlockDecoder` intact for now, but move them behind the shared ingress so they are no longer the first visibility point for production traffic
- Avoid consumer coupling in the raw layer:
  - no `NodeStats`, block status, KES math, or peer-counter business rules in the raw models
  - no assumptions that only today's fields matter
  - preserve enough raw structure that downstream tasks can add typed extraction without changing transport or raw storage semantics again

## Acceptance Criteria

- Production code has a shared raw capture representation for protocol `1`, protocol `2`, and protocol `3` tracing data.
- Protocol `1` raw values preserve counter, integer, and label or text distinctions or an equivalently lossless generic representation.
- Protocol `2` raw capture preserves full trace-object batch payloads needed by later decoders.
- Protocol `3` raw capture preserves missing versus present datapoint values accurately.
- Production tracing runtime has one tracing-owned ingress path that feeds protocol `1`, `2`, and `3` raw capture before consumer-specific filtering.
- `NodeMonitor` no longer owns direct authoritative protocol `2` or protocol `3` socket reads; any remaining decode logic runs downstream of shared raw capture.
- Protocol `2` block-persistence flow no longer acts as a parallel first-visibility ingress path; shared raw capture records protocol `2` payloads before block-specific handling.
- The raw capture layer is in-memory only and does not introduce journald reads, database persistence, or replay.
- The raw capture layer does not encode dashboard-specific or block-specific business semantics.
- The implementation does not reintroduce protocol-family-specific sibling socket topology as a desired production shape.
- Later typed extraction tasks can consume shared raw capture without redesigning transport or raw storage again.

## Verification Plan

- Static verification:
  - confirm production tracing code now contains a protocol `1` raw message path rather than only test-only `EkgReply` handling
  - confirm there is one tracing-owned production ingress seam for protocol `1`, `2`, and `3`, rather than separate feature-owned socket entry points
  - confirm raw capture models exist for all three protocol families and stay free of `NodeStats`, `ForwardedBlockEvent`, `ForwardedNodeState`, or other consumer-specific semantics
  - confirm `NodeMonitor` no longer opens direct authoritative protocol `2` or protocol `3` sessions in production code
  - confirm block persistence is downstream of shared raw capture rather than a parallel first-visibility path
  - confirm no new durable persistence surface was introduced
- Automated verification:
  - add one focused ingress test proving a single production tracing ingress path accepts representative protocol `1`, `2`, and `3` messages for one node and records them into shared raw capture
  - add focused tests proving protocol `1` raw metric capture preserves constructor-type distinctions for representative counter, integer, and label values
  - add focused tests proving protocol `2` raw capture preserves forwarded trace-object batches without dropping fields used by current fixture-backed decoders
  - add focused tests proving protocol `3` raw capture preserves nullable datapoint values exactly
  - add one narrow wiring test proving `NodeMonitor` no longer creates or uses direct tracing session clients as authoritative ingress and instead reads downstream data only, or remove those direct clients entirely if that is smaller
  - add one narrow wiring test proving protocol `2` block handling observes raw capture after ingress rather than bypassing it
  - run the narrowest relevant backend targets, expected shape:
    - `./gradlew test --tests "com.swiftmako.jormanager.tracing.*Raw*" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest"`
- Truthful validation boundary:
  - fixture-backed and unit-level tracing tests are sufficient for this task
  - re-running optional live tracing tests is useful but not required to claim task-511 complete

## Risks And Open Questions

- Main risk is accidental scope creep into task-520 through task-531 by letting raw capture types become de facto dashboard or block DTOs.
- Another risk is implementing raw capture against the old sibling-session production topology instead of the already-proven single-connection architecture.
- Another risk is under-preserving protocol `2` or protocol `3` payload shape and forcing later tasks to reopen raw-model design.
- Another risk is over-preserving data with an unnecessary in-memory history or archive abstraction; bounded fan-out plus latest snapshots is the intended scale here.
- Open implementation choice to resolve minimally during coding: whether protocol `2` raw retention needs only event fan-out, or a tiny bounded replay buffer as well. Choose the smaller option that satisfies the first downstream consumers.

## Required Docs, Tracking, And Research Updates

- Maintain this canonical plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`.
- Do not write the planning review log during this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation uncovers a durable raw-model or buffering boundary not already covered by task-500 research.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on raw models and routing only, and explicitly defers typed extraction and consumer migration.
- Transport-boundary check: the plan now makes the unified production ingress seam mandatory instead of assuming it might exist already.
- Verification check: the plan calls for focused fixture-backed tests and does not falsely require live-node validation.
- Research check: the plan reuses task-500 as the architecture anchor and only allows new research if a real durable boundary appears.
- Consistency check: the plan now states explicitly that current direct `NodeMonitor` and block-persistence ingress bypass cannot remain authoritative if task-511 claims shared first-visibility raw capture.

## Final Outcome

- Result: `completed`
- Final implementation summary:
  - added a production raw-capture layer for protocol `1`, `2`, and `3` with explicit raw metric, datapoint, and trace-object representations
  - promoted protocol `1` metrics into production through `ForwardingMetricsProtocol`
  - updated the tracing session client and connection manager so one tracing-owned ingress seam now carries protocol `1`, `2`, and `3` into shared raw capture
  - moved `TracingBlockMessageSink` downstream of raw trace-object capture and removed `NodeMonitor`'s direct tracing-session ownership in favor of reading raw capture snapshots and batches
  - added freshness and teardown invalidation so stale raw capture cannot keep publishing healthy dashboard state after tracing ingress stops
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.monitors.NodeMonitorTest"`
  - repo lifecycle side effects from that Gradle invocation also ran `testVue` and `buildVue`, and both passed
- Final review result:
  - implementation review iterations 1 and 2 required fixes for stale dashboard snapshots, raw trace buffer safety, and cancellation-driven teardown invalidation
  - implementation review iteration 3 approved after those fixes landed
  - approved review entry recorded in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-511-impl-review.md`
- Research outcome:
  - added `.agent/plans/cardano-node-jormanager-tracing/research/task-511-raw-capture-freshness-and-invalidation.md` to record the durable freshness and teardown invalidation boundary for shared raw capture
