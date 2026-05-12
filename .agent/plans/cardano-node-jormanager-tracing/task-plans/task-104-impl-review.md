Implementation: Iteration 1
Timestamp: 2026-05-12T22:41:27Z
Changes made:
- removed `ekgPort` and `promPort` from `CreateNodeRequest` and its request logging
- updated `NodeController` to allocate metrics ports internally via a small `allocateMetricsPorts()` seam instead of reading them from the request DTO
- preserved persisted `Node.ekgPort` and `Node.promPort` writes and existing `promPort` to tracing-port sequencing for core nodes
- removed obsolete EKG and Prometheus fields, validation, and parent-copy defaults from `AddNodeWizard.vue`
- updated `NodeControllerTest` request builders for the DTO change and added focused coverage for internal metrics-port allocation and tracing-port sequencing
Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
- `vue/src/components/AddNodeWizard.vue`
Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed
- `npm run build` in `vue/` passed
Deviations from approved plan:
- none
User interaction required now: no
Outcome: Implementation completed for the planned task scope and is ready for code review.

Code Review: Iteration 1
Timestamp: 2026-05-12T22:43:25Z

Blocking findings:
- None. The implementation matches the approved `task-104` scope: `CreateNodeRequest` no longer exposes `ekgPort` or `promPort`, the wizard no longer renders or validates those inputs, and `NodeController` now derives metrics ports internally while preserving the existing `promPort -> tracingPort` sequencing (`src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt:6-41`, `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:202-207`, `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1028-1058`, `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3285-3312`, `vue/src/components/AddNodeWizard.vue:96-159`, `vue/src/components/AddNodeWizard.vue:795-869`).
- Focused verification is also consistent with the current repo state: `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed, and `npm run build` in `vue/` passed.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3323-3356` still carries the old commented regex-mutation block with stale `hasEkg`/`hasEKG`/`12798` references. It is inert, but it keeps obsolete HTTP-metrics logic adjacent to the new dispatcher path.
- The backend tests added in `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt:124-153` cover the new allocation seam well. There is still no frontend unit test specifically asserting the wizard no longer exposes EKG/Prometheus fields, so that part is currently protected by build-time validation rather than direct UI regression coverage.

Approval bar:
- Keep this task scoped to request/UI cleanup plus internal controller allocation only; do not widen it into `NodeMonitor`, `EkgService`, schema, or tracing UI work.
- Optional cleanup only: remove the stale commented legacy config block on a later touch to `NodeController`.

Decision: approved

