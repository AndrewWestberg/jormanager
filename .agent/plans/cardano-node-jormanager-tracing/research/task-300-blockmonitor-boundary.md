# Task 300 Research: BlockMonitor Boundary After Legacy Discovery Removal

## Status

- Date: 2026-05-14
- Task: `task-300`
- Status: accepted implementation note

## Durable Findings

- `BlockMonitor` still owns the cold-start `nodesChannel` seed for persisted non-deleted nodes, and that contract must remain intact until a later task deliberately re-homes startup ownership.
- `NodeMonitor` and `PooltoolMonitor` both still depend on that startup seed because the shared `nodesChannel` is a plain `MutableSharedFlow()` without replay.
- The smallest truthful tracing-era `BlockMonitor` is startup seeding plus candidate-block validation only; block discovery transport now belongs entirely to the tracing subsystem landed by `task-200` through `task-202`.
- Removing `SSHClientPool.shutdown()` from `BlockMonitor.stop(...)` is safe and desirable because SSH pool ownership still belongs to other monitors, not to tracing-era block discovery.

## Validation Evidence

- Production code no longer contains `monitorBlocksLocal(...)`, `monitorBlocksRemote(...)`, `saveBlocksFromRemoteNode(...)`, `ProcessBuilder`, `SSHClient`, `HostConnection`, `SSHClientPool.shutdown()`, or `QueryTip`-driven hash enrichment inside `BlockMonitor`.
- Focused backend verification passed with `./gradlew test --tests "com.swiftmako.jormanager.monitors.BlockMonitorTest"`.

## Follow-Up Notes

- The shared startup seed remains timing-sensitive in production because `nodesChannel` has no replay buffer. That pre-existing behavior is preserved by `task-300`, not solved here.
