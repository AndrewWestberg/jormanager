# Task 104 Request Plumbing Cleanup

## Summary

- Date: 2026-05-12
- Task: `task-104`
- Scope: remove EKG and Prometheus HTTP port fields from new-node request and UI plumbing while preserving internal controller allocation needed by later monitor migration

## Durable Findings

- Request-level and UI-level removal of `ekgPort` and `promPort` is safely separable from later monitor migration work.
- `NodeController` still needs an internal metrics-port allocation step because persisted `Node.ekgPort` and `Node.promPort` remain live dependencies for `NodeMonitor`, and core tracing-port allocation still derives from the resolved internal `promPort`.
- Because `task-100` already removed generated HTTP metrics config from new managed node configs, this cleanup must not claim restored runtime HTTP metrics behavior for newly created nodes; that remains blocked on later monitor migration.

## Verification Evidence

- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed.
- `npm run build` in `vue/` passed.
