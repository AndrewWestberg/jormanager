Implementation: Iteration 1
Timestamp: 2026-05-13T00:14:25Z

Changes made:
- added `TracingBlockPersistenceService` to own the narrow post-normalization candidate-block persistence seam for tracing-driven saves
- added `TracingBlockMessageSink` as the concrete production `TraceForwardMessageSink` wired through `TracingConnectionManager`
- removed the production default no-op sink from `TracingConnectionManager` so message delivery requires a real sink bean
- updated `BlockMonitor` to reuse the extracted persistence seam only after the legacy path has already done its existing parsing and optional tip-hash enrichment
- added focused tracing tests covering manager-delivered persistence, duplicate suppression, sentinel epoch behavior, websocket publication, and safe missing-node handling
- updated the tracing connection manager test helper to pass the now-mandatory sink dependency

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt`
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManager.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingConnectionManagerTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest"`
- result: passed
- repo build wiring also ran `:testVue` and `:buildVue` as part of the Gradle target; both passed

Deviations from approved plan:
- none beyond keeping the persistence seam as a service instead of a private helper so both tracing and `BlockMonitor` can reuse the same save semantics cleanly

User interaction required:
- no

Outcome: first implementation pass is complete with a real production tracing sink, a narrow shared candidate-block persistence seam, and focused passing tracing tests.

Code Review: Iteration 1
Timestamp: 2026-05-13T00:15:57Z

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt:62-77`, `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:101-149`, `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:459-504`, `src/main/resources/db/changelog/016-update-blocks-schema.xml:6` — task-202 introduces a second live discovery writer before task-300 removes the old one, but the new tracing path is not coordinated with the still-running SSH/file scraper. `persistCandidateBlock()` uses a non-atomic `findBySlot(...).firstOrNull()` then `save(...)`, while only the legacy path is serialized by `blockFoundMutex`, and the old unique constraint on `blocks.slot` was explicitly dropped. If the same adopted block arrives from forwarding and log scraping close together, both paths can observe no existing row and insert duplicate `completed` blocks for the same slot. That breaks the task acceptance on duplicate suppression and can skew later forged/missed/orphaned validation. This needs a shared atomic persistence gate across both writers, or an interim rule that only one discovery source is allowed to persist while both paths coexist.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSink.kt:22-40` preserves the intended repo-host persistence semantics and safely no-ops on missing node or host state; I did not find schema/API drift there.
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt:29-59` reparses both genesis files on every forwarded event, unlike the legacy monitor which parses once per monitoring loop. That is probably acceptable for now, but it is unnecessary work on the hot path and an easy future simplification point.
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt` has useful focused coverage, but it only proves sequential duplicate suppression. There is no test for the real interim convergence case where tracing and legacy scraping race on the same slot.

Approval bar:
- Make candidate-block persistence atomic across tracing and legacy discovery while both are still enabled.
- Add one focused test that exercises the concurrent/interleaved same-slot case, not just repeated sequential tracing events.
- Preserve the current good parts of the patch: real production sink wiring, managed-host persistence, websocket publish on actual save, and no replay/backfill scope creep.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-13T00:17:16Z

Changes made:
- moved duplicate suppression into a mutex-guarded shared save path inside `TracingBlockPersistenceService` so tracing and legacy block discovery serialize through the same atomic persistence gate while both writers still exist
- updated `BlockMonitor` to stop pre-binding legacy save IDs into the candidate block and instead hand the post-enrichment block to the shared serialized persistence seam
- added a focused interleaving test proving same-slot saves through the shared seam result in only one persisted candidate block
- made `persistTracingCandidateBlock(...)` suspend-aware to match the shared serialized save path and updated the affected tests

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.tracing.TracingBlockMessageSinkTest" --tests "com.swiftmako.jormanager.tracing.TracingConnectionManagerTest" --tests "com.swiftmako.jormanager.tracing.TraceForwardAdoptedBlockDecoderTest"`
- result: passed
- repo build wiring also ran `:testVue` and `:buildVue`; both passed

Deviations from approved plan:
- none

User interaction required:
- no

Outcome: the shared candidate-block persistence seam now serializes both tracing and legacy discovery writes, and focused verification covers the same-slot interleaving case that previously risked duplicate persisted blocks.

Code Review: Iteration 2
Timestamp: 2026-05-13T00:19:42Z

Blocking findings:
- none

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt:65-88` now gives both tracing and legacy discovery a shared serialized save path, which closes the duplicate-race issue called out in iteration 1 and preserves the existing empty-hash upgrade rule.
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt:459-503` still keeps `blockFoundMutex` around the legacy parser/enrichment path before delegating into the shared persistence mutex. That is redundant now but not incorrect; it can be simplified later when `task-300` removes the legacy scraper.
- `src/main/kotlin/com/swiftmako/jormanager/tracing/TracingBlockPersistenceService.kt:37-49` still reparses genesis files for each forwarded event, so there is avoidable hot-path work left after this task. I do not see a correctness problem from it in the current scope.
- `src/test/kotlin/com/swiftmako/jormanager/tracing/TracingBlockMessageSinkTest.kt:180-229` now covers the important same-slot interleaving case the previous pass was missing, and the focused Gradle verification passed against the live workspace.

Approval bar:
- No further task-202 changes required from this review pass.
- Safe to move on to `task-300`, where the remaining legacy discovery path and its extra locking can be removed rather than further refined here.

Decision: approved

