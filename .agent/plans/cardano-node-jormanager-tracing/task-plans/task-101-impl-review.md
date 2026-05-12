Implementation: Iteration 1
Timestamp: 2026-05-12T22:10:41Z

Changes made:
- Added `NodeController.renderTracingListenerArgument(nodeType, tracingPort)` to pin the verified `cardano-node 11.0.1` CLI listener fragment for eligible core nodes only.
- Added focused `NodeControllerTest` coverage for the exact core listener argument, non-core exclusions, and null-port exclusion.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
- Result: passed. The Gradle target also ran the existing frontend build/test tasks in this workspace before backend compilation and test execution, and the overall task completed successfully.

Deviations from the approved plan:
- None. The change stays limited to pinning the reusable listener fragment seam and focused tests; startup artifact wiring remains deferred to `task-102`.

User interaction required:
- No

Outcome: Implementation ready for code review

Code Review: Iteration 1
Timestamp: 2026-05-12T22:12:14Z

Blocking findings:
- None.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3551-3560` keeps the task-101 change appropriately narrow: the verified `cardano-node 11.0.1` listener syntax is pinned in one reusable helper, without prematurely wiring startup generation.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt:140-167` covers the core-only flag shape, non-core exclusion, and null-port exclusion, which matches the approved plan verification bar for this task.
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3094-3286` still has no listener wiring, so `task-102` remains responsible for threading `tracingPort` into both startup artifact generation paths and the parent-core rewrite path called out in the approved plan.

Approval bar:
- Preserve this scope cut: task-101 only pins the listener mechanism and its focused assertions.
- In task-102, reuse this helper rather than duplicating the flag string, and resolve the existing startup-generation ordering/data-flow gap before wiring it into generated artifacts.
Decision: approved

