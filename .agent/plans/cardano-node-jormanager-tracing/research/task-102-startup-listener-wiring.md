# Task 102 Startup Listener Wiring

## Summary

- Date: 2026-05-12
- Task: `task-102`
- Scope: startup artifact wiring for the verified `cardano-node 11.0.1` tracing listener flag

## Durable Findings

- Direct core creation must allocate `tracingPort` before writing startup artifacts so both systemd units and manual startup scripts can include `--tracer-socket-network-accept 0.0.0.0:<tracingPort>` from the saved port rather than a later recomputation.
- The pool-create path rewrites the parent core systemd unit, but that rewrite intentionally stays on the existing bulk-credentials startup shape driven by `BULK_CREDENTIALS`.
- Listener rendering for the pool-triggered parent-core rewrite therefore needs two inputs:
  - startup artifact shape: keep the existing pool or bulk-credentials systemd branch
  - listener semantics: apply core-only listener eligibility from persisted `coreNode.type` and `coreNode.tracingPort`
- Collapsing the pool rewrite into a plain core systemd shape is a regression because it drops `--bulk-credentials-file ${BULK_CREDENTIALS}` while the rewritten env file still provides bulk credentials.
- Manual startup scripts still redirect output to `${name}.log`; that is known pre-existing drift and was intentionally left out of scope for `task-102`.

## Verification Evidence

- Focused backend verification passed with `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`.
- The added tests cover:
  - direct core manual startup listener wiring
  - relay systemd listener absence
  - pool-triggered parent-core bulk-credentials rewrite with listener present when persisted `tracingPort` exists
  - the same pool rewrite with listener omitted when persisted `tracingPort` is null
