# task-002 core tracing port allocation

- Date: 2026-05-12
- Task: `task-002`
- Outcome: completed

## Durable findings

- The smallest truthful way to verify host-local tracing-port allocation was to add a tiny `NodeController` helper seam for allocation policy only instead of refactoring the full `createNode()` flow.
- The approved allocation behavior is now encoded as: for `core` nodes only, start at `promPort + 1` and scan upward until the existing host-local port probe reports a free port.
- `pool` nodes share the core/pool creation branch but must still persist `tracingPort = null`, so allocation must gate on `nodeType == core`, not on branch location.
- Relay-node saves should continue to persist `tracingPort = null` explicitly for clarity and future drift resistance.

## Verification evidence

- Added focused `NodeControllerTest` coverage for first-free core allocation, upward collision scanning, and relay/pool null behavior.
- Attempted `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`.
- Backend test execution was blocked by unrelated pre-existing `:compileKotlin` failures in `NodeController.kt` involving unresolved extension imports/call sites (`hexToByteArray`, `sumByBigInteger`, `toNativeAssetMap`) already present in current workspace state.
- Frontend test/build tasks ran incidentally through the Gradle task graph and passed.

## Residual notes

- After the unrelated backend compile breakage is resolved, rerun the focused `NodeControllerTest` target to restore clean automated verification for this task.
- No new tracing config, startup wiring, request-model, or frontend behavior was introduced in this task.
