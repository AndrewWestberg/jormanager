# Task 101 Tracing Listener Mechanism

## Status

- Verified and implemented on 2026-05-12.

## Verified Runtime Fact

- Local `cardano-node 11.0.1` `run --help` exposes the listener flag `--tracer-socket-network-accept HOST:PORT`.
- This confirms the accepted JorManager listener mechanism for this migration is CLI startup wiring, not an extra dispatcher config JSON key.
- The matching client-side flag family also exists, but the PRD keeps JorManager as the client and the node as the listener, so `--tracer-socket-network-connect` remains out of scope for generated node startup.

## JorManager Pin

- `NodeController.renderTracingListenerArgument(nodeType, tracingPort)` is the code-adjacent source of truth for the pinned listener fragment.
- Current pinned output for eligible core nodes:
  - `--tracer-socket-network-accept 0.0.0.0:<tracingPort>`
- Relay and pool nodes intentionally return no listener fragment in phase 1.

## Follow-On Constraint For Task 102

- Startup generation does not yet consume this helper.
- During core creation, `createSystemdFile()` and `createManualStartupScripts()` currently run before `allocateTracingPort()`.
- The pool-creation flow also rewrites the parent core systemd unit from persisted core-node data.
- `task-102` therefore needs both:
  - helper reuse for the pinned flag string
  - startup-generation ordering or input-flow changes so `tracingPort` is available in both artifact-generation paths

## Durable Evidence

- Code seam: `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- Focused tests: `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
- Verification command: `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
