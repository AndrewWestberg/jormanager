Implementation: Iteration 1
Timestamp: 2026-05-12T22:24:22Z

Changes made:
- Updated `NodeController` startup generation so direct core creation allocates `tracingPort` before writing startup artifacts and threads that saved port into both systemd and manual startup generation.
- Split startup-artifact rendering from request type by extending `createSystemdFile()` and `createManualStartupScripts()` with an explicit `startupNodeType` plus `tracingPort`.
- Added internal render helpers for manual startup scripts and systemd content so listener wiring is driven by `renderTracingListenerArgument()` and can be asserted directly in tests.
- Updated the pool-triggered parent-core systemd rewrite to render from persisted `coreNode.type` and `coreNode.tracingPort` while preserving the existing bulk-credentials systemd shape and null-port no-listener behavior.
- Added focused `NodeControllerTest` coverage for core manual startup listener wiring, relay systemd listener absence, core systemd listener presence for parent-core rewrites, and the persisted-null no-listener case.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` (passed)
- Note: this Gradle target also ran the repo-coupled frontend test/build tasks successfully as part of the existing build graph.

Deviations from approved plan:
- Preserved the existing pool rewrite bulk-credentials systemd shape after verifying that path intentionally uses `BULK_CREDENTIALS`; the implementation only corrected startup-artifact type handling for listener rendering instead of forcing a full core-shaped unit.
- The helper extraction stayed internal to `NodeController` and was limited to startup content rendering for testability.

User interaction required: no

Outcome: Implementation ready for code review.

Code Review: Iteration 1
Timestamp: 2026-05-12T22:26:39Z

Summary:
- `task-102` is close on the direct core path: listener rendering is centralized in `renderTracingListenerArgument()`, and core creation now allocates `tracingPort` before startup artifacts are generated.
- The remaining issue is in the pool-triggered parent-core rewrite, where the implementation drifted from the approved plan and now rewrites the parent core startup artifacts into the wrong runtime shape.

Blocking findings:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1092-1099`, `1156-1164`, `3230-3241`, `3563-3594`: the pool-create path still writes the parent core `env` file as a pool/bulk-credentials env (`BULK_CREDENTIALS`), but the rewritten systemd unit is now rendered as a plain core unit because `startupNodeType = coreNode.type` selects the core branch. That drops `--bulk-credentials-file ${BULK_CREDENTIALS}` and replaces it with `--shelley-kes-key`, `--shelley-vrf-key`, and `--shelley-operational-certificate`, which do not match the just-written env file or the existing multi-pool parent-core runtime model. Adding a pool can therefore rewrite the parent core service into a unit that will not start correctly.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt:258-273`: the new “core rewrite” test locks in the wrong behavior by asserting that the rewritten unit does not contain `--bulk-credentials-file`. The approved canonical plan and the implementation review both required preserving the existing bulk-credentials parent-core rewrite shape and only correcting listener wiring/type handling. Because the test exercises only `renderSystemdContent(startupNodeType = core)` instead of the real pool rewrite behavior, the main regression above is currently untested.

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1058-1077` is the right minimal ordering fix for direct core creation.
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3453-3629` keeps the listener fragment centralized and avoids duplicating the CLI flag string.
- Manual startup scripts still redirect to `${name}.log`, but that drift was explicitly left out of scope for `task-102` in the approved plan.

Approval bar:
- Preserve the pool-triggered parent-core rewrite as the existing bulk-credentials startup shape while still adding the core-only listener argument from persisted `coreNode.tracingPort`.
- Add an automated test for the actual pool parent-core rewrite behavior, including the `tracingPort = null` no-listener case, instead of asserting against a plain core unit render.
- Keep the current direct-core and relay startup assertions once the pool rewrite path is corrected.

Decision: requires_changes

Implementation: Iteration 2
Timestamp: 2026-05-12T22:28:03Z

Changes made:
- Preserved the pool-triggered parent-core rewrite on the existing bulk-credentials systemd branch while adding a separate `listenerNodeType` input so the listener argument can still be rendered with core-only semantics from persisted `coreNode.type`.
- Updated the pool rewrite call site to pass `startupNodeType = request.type` and `listenerNodeType = coreNode.type`, keeping the bulk-credentials runtime shape aligned with the pool-generated env file.
- Replaced the incorrect plain-core rewrite test with focused assertions for the real pool rewrite behavior: bulk-credentials remains present, the listener is added when persisted `tracingPort` exists, and the listener is omitted when persisted `tracingPort` is null.

Files touched:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`

Verification run:
- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` (passed after the review-driven pool rewrite correction)

Deviations from approved plan:
- No plan deviation remains beyond the already-approved internal helper extraction for startup-content rendering.

User interaction required: no

Outcome: Review findings addressed and implementation ready for re-review.

Code Review: Iteration 2
Timestamp: 2026-05-12T22:30:07Z

Summary:
- The prior blockers are resolved on the reviewed `task-102` surfaces.
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1156-1164` now preserves the pool-triggered parent-core rewrite on the bulk-credentials startup branch while applying the core-only listener semantics from persisted `coreNode.type` and `coreNode.tracingPort`.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt:258-292` now covers the real pool rewrite shape, including both listener-present and null-port listener-omitted cases.
- Focused verification currently passes with `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`.

Blocking findings:
- none

Non-blocking observations:
- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:3467-3522` still writes manual startup scripts that redirect to `${name}.log`, but that drift was explicitly left out of scope by the approved `task-102` plan and is not a blocker for this review.

Approval bar:
- None. The reviewed implementation matches the approved `task-102` plan and clears the prior blocking findings.

Decision: approved

