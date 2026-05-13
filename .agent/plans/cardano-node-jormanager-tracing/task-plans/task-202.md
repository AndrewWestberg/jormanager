# Task 202 Plan

## Summary

- Task ID: `task-202`
- Title: `Bridge tracing events into block persistence flow`
- Why now: `task-201` completed the forwarded adopted-block decoder, so the next smallest unblocked step is to reuse the current candidate-block save semantics from live tracing before `task-300` removes the old SSH and file discovery path
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Bridge normalized `ForwardedAdoptedBlockEvent` values from the tracing subsystem into the existing candidate-block persistence flow with the smallest truthful change set, while keeping the downstream validation loop and websocket behavior aligned with current `BlockMonitor` semantics.

In scope:
- add the minimal production bridge from `TraceForwardMessage` replies to post-normalization candidate block persistence
- reuse the `task-201` decoder output as the tracing-side contract instead of adding another event shape
- extract only the post-normalization candidate-block persistence behavior from `BlockMonitor.saveBlocksFromRemoteNode(...)` so tracing can reuse duplicate suppression, block construction, repository save, and websocket publication without inheriting legacy parsing or host-shell lookups
- preserve current duplicate suppression, saved-block shape, websocket publishing, and later forged or missed or orphaned validation semantics
- keep the legacy SSH or file discovery path in place for now on its current path, including its existing parse and optional tip-hash enrichment behavior, until `task-300`
- add focused automated tests for tracing-driven candidate block saves and duplicate handling

Out of scope:
- deleting SSH or file discovery from `BlockMonitor` (`task-300`)
- changing the forwarded adopted-block decoder contract from `task-201`
- changing tracing connection lifecycle ownership from `task-200`
- node-state protocol work or `NodeMonitor` migration (`task-204`, `task-301`)
- journald replay, historical recovery, or any new durable trace-event storage
- broad repository or websocket contract redesign

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Upstream task artifacts consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-200-plan-review.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-201-plan-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-201-forwarded-adopted-block-decoder.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-202-tracing-block-persistence-bridge.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built and refreshed the deep code index before live verification
- Used code-index file summaries and symbol reads first, then verified important seams against live file contents directly
- Code-index-synced findings confirmed live:
  - `BlockMonitor.saveBlocksFromRemoteNode(...)` currently mixes three concerns in one method: legacy line parsing, candidate-block persistence, and legacy-only optional `cardano-cli ... query tip` hash enrichment through `HostConnection`
  - task-202 should split out only the post-normalization candidate-block persistence seam needed by tracing and leave legacy parsing plus tip-hash enrichment on the old scraper path until `task-300`
  - `TraceForwardAdoptedBlockDecoder` already preserves the downstream fields task-202 needs from forwarded wrappers: `slot`, `blockHash`, `timestamp`, and `hostname`
  - `TracingConnectionManager` still hands raw `TraceForwardMessage` values to a single `TraceForwardMessageSink`, and its constructor currently defaults that sink to a no-op lambda, so task-202 must replace optional helper-only wiring with a real production sink path
  - `BlockUtils.getEpochAndSlot(...)` still depends on node-specific genesis files plus the global `latestNodeStats` bean and returns `-1/-1` when `latestNodeStats` has not yet been populated with epoch, slot, and slot-in-epoch
  - persisted `Block.host` on the legacy path is the managed repository host hostname passed into `saveBlocksFromRemoteNode(...)`, not the log payload host field, so tracing should pin the same repository-host source rather than forwarding `ForwardedAdoptedBlockEvent.hostname` into persistence
  - there are no existing monitor tests under `src/test/kotlin/com/swiftmako/jormanager/monitors/`, so focused backend tests should stay near the new tracing or persistence seam rather than introducing broad monitor integration coverage just for this task

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for this planning pass
- Truthful autonomy basis:
  - the repo already contains the needed prerequisites for a narrow implementation: tracing connection lifecycle (`task-200`), a normalized adopted-block decoder (`task-201`), persisted tracing endpoints, and the current block save semantics in one verified method

## Relevant Dependencies

- Required completed upstream tasks:
  - `task-201`
- Practical live repo dependencies this task should reuse carefully:
  - `task-200`
  - `task-203`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/repositories/BlockRepository.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/controllers/utils/BlockUtils.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/entities/Block.kt`
- Downstream tasks this work should unblock cleanly:
  - `task-300`

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `saveBlocksFromRemoteNode(...)` is the critical existing behavior to split carefully: tracing should reuse only the post-normalization persistence subset, while legacy parsing and hash enrichment stay here until `task-300`
  - current validation logic is separate in `validateBlocks()`, so task-202 should leave that loop untouched
  - legacy discovery still lives in `monitorBlocksLocal(...)` and `monitorBlocksRemote(...)`, so task-202 should not widen into deleting those paths yet
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - current bridge point is the injected `TraceForwardMessageSink`
  - task-202 should attach persistence behind that sink instead of modifying reconnect or lifecycle behavior
  - production wiring is not optional because the current default sink is a no-op
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TraceForwardAdoptedBlockDecoder.kt`
  - already normalizes forwarded replies into `ForwardedAdoptedBlockEvent(slot, blockHash, timestamp, hostname)`
  - task-202 should treat that as the tracing-side input contract rather than re-parsing wrapper JSON elsewhere
- `src/main/kotlin/com/swiftmako/jormanager/repositories/BlockRepository.kt`
  - duplicate suppression still depends on `findBySlot(...)` plus the existing `hash.isEmpty()` update rule
  - task-202 should preserve that semantics instead of adding a new repository contract unless live code proves it is necessary
- `src/main/kotlin/com/swiftmako/jormanager/controllers/utils/BlockUtils.kt`
  - `getEpochAndSlot(...)` remains the existing epoch and slot-in-epoch calculation seam and should keep owning that computation

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202.md`
  - canonical task plan
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - minimal extraction or delegation so the current save semantics can be reused by tracing without changing validation or deleting legacy discovery yet
- `src/main/kotlin/com/swiftmako/jormanager/tracing/...`
  - one small tracing bridge or sink component that decodes trace replies and hands normalized adopted-block events into the shared persistence seam
- `src/test/kotlin/com/swiftmako/jormanager/tracing/...`
  - focused tracing-to-persistence tests, and any tiny fixture additions only if needed
- `src/test/kotlin/com/swiftmako/jormanager/...`
  - possibly one small persistence-seam test class outside `tracing/` if that is the smallest way to cover duplicate suppression and websocket behavior
- `.agent/plans/cardano-node-jormanager-tracing/research/task-202-*.md`
  - only if implementation reveals durable persistence-bridge or Spring wiring findings worth carrying forward

## Implementation Approach

- Keep the tracing lifecycle and decoder responsibilities unchanged:
  - `TracingConnectionManager` should continue owning connection lifecycle only
  - `TraceForwardAdoptedBlockDecoder` should continue owning reply traversal and adopted-block normalization only
- Preferred minimal production shape:
  - one small shared persistence service or helper that accepts already-normalized candidate-block inputs and is extracted from the persistence subset of `BlockMonitor.saveBlocksFromRemoteNode(...)`
  - one concrete production `TraceForwardMessageSink` component behind `TracingConnectionManager` that handles `TraceObjectsReply`, decodes adopted-block events, resolves the needed node and host context, and invokes that persistence seam
- Preserve the current candidate-block semantics rather than inventing a new flow, but only after normalization:
  - compute `epoch` and `slotInEpoch` via `BlockUtils.getEpochAndSlot(...)`
  - persist `status = "completed"`
  - preserve `at`, `host`, `slot`, `hash`, and placeholder `pool = "---"` construction
  - preserve current duplicate handling via `findBySlot(...)` plus the `existingBlock == null || existingBlock.hash.isEmpty()` rule
  - preserve websocket publication of saved candidate blocks
- Keep the shared seam narrow:
  - tracing inputs stop at normalized `ForwardedAdoptedBlockEvent` plus resolved repository node and host context
  - legacy scraped-line parsing stays in `BlockMonitor`
  - legacy optional tip-hash enrichment through `HostConnection` and `cardano-cli ... query tip` stays in the legacy scraper path until `task-300`
  - task-202 must not add any new shell, SSH, or direct node lookup behavior to the tracing bridge
- Minimize surface area inside `BlockMonitor`:
  - if extraction is needed, move only the normalized candidate persistence logic, not monitor lifecycle, validation, scraped-line parsing, or legacy tip-hash enrichment
  - keep `monitorBlocksLocal(...)` and `monitorBlocksRemote(...)` intact for now; they may continue using `saveBlocksFromRemoteNode(...)` directly until `task-300` if that is smaller and avoids widening this task
- Keep node context resolution small and local to the tracing bridge:
  - use the sink's `nodeId` to load the current `Node`
  - load the managed `Host` from the repository and use `host.hostname` as the persisted `Block.host` value for tracing saves; do not persist `ForwardedAdoptedBlockEvent.hostname`
  - load the node's genesis files only as required to call `BlockUtils.getEpochAndSlot(...)`
  - treat missing or deleted node state as a safe no-op rather than a fatal tracing failure
- Pin the epoch and slot-in-epoch strategy explicitly:
  - tracing saves reuse the existing `BlockUtils.getEpochAndSlot(...)` calculation with the traced node's genesis files and the same global `latestNodeStats` dependency used today
  - task-202 does not add a new startup gate, buffering layer, or alternate epoch calculator just for tracing
  - if `latestNodeStats` is not ready yet and `BlockUtils.getEpochAndSlot(...)` returns `-1/-1`, tracing persists the candidate block with those sentinel values rather than dropping or delaying the event; this matches the existing utility contract more truthfully than inventing a new race policy here
  - verification must cover both the ready and not-yet-ready cases so startup behavior is pinned instead of assumed
- Avoid extra abstractions unless live implementation proves they are needed:
  - no second normalized block event model beyond `ForwardedAdoptedBlockEvent` unless a tiny shared candidate model makes the extraction clearly smaller
  - no repository API expansion if the current `findBySlot(...)` path is sufficient
  - no attempt to solve historical replay, missed-event repair, or monitor cleanup in this task

## Acceptance Criteria

- A forwarded adopted-block event can reach the current candidate-block persistence semantics end to end through the tracing subsystem.
- The tracing path reuses only post-normalization candidate persistence semantics, while legacy parsing and tip-hash enrichment remain on the old scraper path until `task-300`.
- The saved candidate block shape remains behaviorally aligned with the current `BlockMonitor` live path where task-202 intentionally reuses it: status, persisted repository host value, websocket publish, duplicate suppression, and `BlockUtils`-driven epoch and slot-in-epoch calculation.
- Existing forged or missed or orphaned validation semantics remain intact because `validateBlocks()` and its repository contract stay unchanged.
- Duplicate adopted-block events do not create duplicate persisted candidate blocks.
- Production `TracingConnectionManager` delivery no longer terminates at the default no-op sink; at least one manager-delivered `TraceObjectsReply` can reach persistence through a real Spring-wired sink.
- No new historical replay or journald recovery behavior is introduced.

## Verification Plan

- Static verification:
  - confirm tracing lifecycle code stays in `TracingConnectionManager` and is not widened by task-202
  - confirm the decoder remains the only forwarded adopted-block parser
  - confirm the extracted shared persistence seam contains only normalized candidate-block save behavior and does not absorb legacy scraped-line parsing or `HostConnection` tip-hash enrichment
  - confirm `BlockMonitor.validateBlocks()` remains unchanged apart from any minimal dependency injection or delegation needed by the extraction
  - confirm production Spring wiring injects a real `TraceForwardMessageSink` into `TracingConnectionManager` rather than leaving the default no-op sink active
- Automated verification:
  - add focused backend tests covering:
     - a valid forwarded adopted-block reply results in one saved candidate block with `status = "completed"`
     - duplicate forwarded adopted-block events for the same slot do not create duplicate saves
     - the persisted tracing-driven block uses the managed repository `host.hostname` value rather than the forwarded wrapper hostname
     - forwarded adopted-block handling still publishes the websocket block message when a save occurs
     - ignored or empty tracing replies do not trigger persistence
     - manager-delivered `TraceObjectsReply` values reach the real production sink and persist a block end to end without bypassing `TracingConnectionManager`
     - missing or deleted node cases fail safely without crashing the connection manager loop or persisting garbage
     - when `latestNodeStats` is populated, tracing persistence computes non-sentinel epoch and slot-in-epoch values through `BlockUtils.getEpochAndSlot(...)`
     - when `latestNodeStats` is absent or incomplete, tracing persistence saves with `epoch = -1` and `slotInEpoch = -1` rather than dropping the event or inventing a new startup gate
  - prefer package-local tests around the new tracing bridge or extracted persistence service instead of a broad `BlockMonitor` lifecycle test harness
  - run the narrowest relevant backend target once concrete class names exist, preferably a specific tracing bridge or persistence test class
- Truthful validation boundary:
  - live node interoperability is still not required to complete this task because the fixture-backed trace-forward reply foundation and decoder contract already exist; this task is about handing normalized live events into the existing repository flow, not proving end-to-end node deployment yet

## Risks And Open Questions

- Main risk is accidental scope creep into `task-300` by deleting SSH or file discovery too early or by dragging legacy tip-hash enrichment into tracing; task-202 should add the tracing bridge while leaving both of those old-path behaviors in place.
- A second risk is bypassing the existing candidate save semantics and creating slightly different persistence behavior for tracing; extraction should therefore target only the normalized save subset.
- `BlockUtils.getEpochAndSlot(...)` depends on the existing `latestNodeStats` context and can already return sentinel values; task-202 should preserve and test that startup-race behavior rather than silently redesign block dating.
- The current production tracing path drops messages by default because `TracingConnectionManager` defaults to a no-op sink; task-202 must replace that with explicit real wiring and verify it end to end.
- The current duplicate rule is slot-based with an `empty-hash` update exception; task-202 should preserve it even if a stricter hash-based contract might be attractive later.

## Required Docs, Tracking, And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202.md`.
- Do not write the planning review log from this pass.
- Do not update the PRD or tasks JSON during planning-only work.
- Add a narrow research note only if implementation reveals durable findings about:
  - the smallest Spring wiring needed for the production `TraceForwardMessageSink`
  - the exact normalized candidate-block persistence contract extracted from `BlockMonitor`
  - any truthfully unavoidable divergence between tracing-driven persistence and the legacy scraper-only hash-upgrade path

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202-impl-review.md`

## Self-Review

- Scope creep check: the plan stays on tracing-to-persistence bridging only and does not fold in task-300 monitor cleanup or any node-state work.
- Workflow guidance check: backend and test workflow references remain current and no stale live-node-only requirement was added.
- Missing tests or docs check: focused backend verification and optional durable research capture are both called out explicitly.
- Complexity check: the preferred path is one narrow normalized-persistence seam plus one real production tracing sink, which is smaller and more truthful than duplicating `BlockMonitor` save logic, widening into legacy transport reuse, or redesigning the repository contract.

## Final Outcome

- Final result:
  - `TracingConnectionManager` now requires a real production `TraceForwardMessageSink`, and `TracingBlockMessageSink` bridges forwarded `TraceObjectsReply` payloads into candidate-block persistence.
  - `TracingBlockPersistenceService` owns the shared post-normalization candidate-block persistence seam, including managed-host persistence, `BlockUtils` epoch and slot-in-epoch calculation, websocket publication, and duplicate suppression.
  - duplicate suppression is serialized through a shared persistence mutex so tracing and legacy SSH or file discovery cannot race the same slot into duplicate `completed` rows while both discovery paths still coexist before `task-300`.
  - `BlockMonitor.validateBlocks()` and the downstream forged or missed or orphaned classification flow stayed intact.
- Files changed in implementation:
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
  - `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest"`
  - Gradle also ran `:testVue` and `:buildVue`; both passed during this repo's focused backend target.
- Review outcome:
  - planning review completed in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202-plan-review.md`
  - implementation review completed with approved `Code Review: Iteration 2` in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-202-impl-review.md`
- Residual follow-up intentionally deferred:
  - legacy SSH or file discovery and the extra `blockFoundMutex` around that old path remain until `task-300`
  - per-event genesis parsing on the tracing path remains a later simplification opportunity rather than widened work here
