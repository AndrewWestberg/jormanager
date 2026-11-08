# Chain synchronization and ledger readiness

**Proposed design — not implemented or approved.** Model the durable per-network readiness transitions explicitly inside `ChainIngestionCoordinator`; do not replace the database lock with a coroutine state machine. This machine is about verified projection coverage, not the already explicit wire-protocol enums in `ChainSyncProtocol` or `BlockFetchProtocol`. See [fetch validity](block-fetch-validity.md), [block adjudication](block-adjudication.md), and [connection supervision](connection-supervision.md).

## What exists

[`SyncStatesTable`](../../src/main/kotlin/com/swiftmako/jormanager/tables/ChainTables.kt#L5) stores network, generation, observed tip, last complete inclusion, status and reason. [Migration 044](../../src/main/resources/db/changelog/044-dijkstra-ledger.xml#L19) permits `complete`, `syncing`, `incomplete`, `stale`, `unavailable`; the presence of `stale` in the constraint does not establish a current transition to it. A missing row is reported as unavailable by [`status`](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L277). The observed tip is a peer observation; only a committed complete inclusion represents projected ledger coverage.

The coordinator [creates and locks](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L65) the network row `FOR UPDATE`, serializing projection even across processes. `registerNetwork` verifies genesis identity and seeds outputs under that lock, with guarded recovery when genesis outputs are absent; it refuses unsafe replacement after ingestion ([source](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L35)). `beginSession` increments generation and marks syncing. `observeTip` checks generation and moves only complete/syncing between those two states according to exact hash *and* slot equality; it does not clear incomplete/unavailable. [`ChainSyncProtocol`](../../src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/chainsync/ChainSyncProtocol.kt#L239) supplies observations, forwards and rollbacks.

[`adopt`](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L106) checks the requested point, content/certificate boundaries, generation and predecessor against the complete tip (or verified genesis). Existing identical complete adopted data is idempotent; conflicting bytes are rejected. Under the same database transaction it stores inclusion, applies predecessor-endorsed content before direct batches, flushes history, updates matching reviewed-operation confirmations, marks inclusion complete and advances the pointer/status. [`BatchEffectProjector`](../../src/main/kotlin/com/swiftmako/jormanager/services/BatchEffectProjector.kt#L16) applies adopted outcomes, not independent consensus validation. All of this must commit or roll back as one unit.

[`rollback`](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L211) increments generation, reverses ledger projection and derived changes newest-first, marks matching operation confirmations rolled back, clears adoption/completeness, and changes the pointer to the target in one transaction. Origin is a null target. An unknown non-origin target instead commits a new generation and unavailable status, *then* throws `ReplayRequiredException`; it does not pretend that the old projection was undone. Incomplete ancestry aborts the transaction. `boundaryFailure` marks incomplete only for the current generation. [`LedgerReplayService.inspect`](../../src/main/kotlin/com/swiftmako/jormanager/services/LedgerReplayService.kt#L27) reports coverage and required recovery; it is not a destructive replay engine. Intersections are drawn from up to 64 adopted complete retained points plus origin; a peer retaining the necessary history and matching genesis is required.

## Proposed representation and transition ownership

Keep existing durable columns and external `ChainReadState` string values. Use a private Kotlin enum for readiness, since each status has no unique required payload; keep point, reason and generation in the persisted snapshot, not duplicated across sealed subclasses:

```kotlin
private enum class Readiness { COMPLETE, SYNCING, INCOMPLETE, STALE, UNAVAILABLE }
private data class SyncSnapshot(
    val generation: Long,
    val readiness: Readiness,
    val observed: ChainPoint?,
    val completePoint: ChainPoint?,
    val reason: String?,
)
```

Decode DB strings explicitly and fail closed for unknown values; use an exhaustive `when` for storage/wire spelling (`complete`, `syncing`, `incomplete`, `stale`, `unavailable`). Existing clients and DB constraints retain their wire vocabulary. Do not infer a stale transition or change how existing stale rows are read. Keep local transition functions adjacent to the coordinator rather than building a reusable FSM. `beginSession`, `observeTip`, `adopt`, `rollback` and `boundaryFailure` remain the only authorities and must use the locked row. Avoid redundant stored sealed payloads that could disagree with the inclusion pointer.

* New session fences previous work and requests intersection; no point is declared complete merely because a connection opened.
* Matching current-generation observed tip can mark the existing complete point ready only in the currently eligible statuses. A later distinct tip returns complete to syncing. A successful adoption sets readiness by equality with the observed tip; it can recover a previously incomplete/unavailable boundary only after verified adoption, as current code does.
* In-range rollback makes the target the complete projected point but status syncing; out-of-history rollback fences first and leaves coverage unavailable, with controlled re-intersection/replay. A generation mismatch must not update data, readiness, or the boundary reason.
* Publication (`chainstatus` and wallet refresh) occurs after commit and is a best-effort notification, not authorization or the durable event log ([source](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L287)). Subscribers reconcile from a fresh status/query after reconnect or missed notification. `StateFlow` may represent an observation, never a command queue or the authoritative state.

## Concurrency, recovery and reads

The generation must be compared while holding the same DB row lock as the write. A local volatile check avoids wasted work but cannot close the race with rollback, a new process or cancellation. The database transaction is the linearization point. Do not permit a stale fetch to project even if it passed a previous local check. Cancellation alone neither undoes a committed projection nor fences an old worker.

Preserve read-side locking: [`LedgerDao.queryUtxos`](../../src/main/kotlin/com/swiftmako/jormanager/repositories/LedgerDao.kt#L13) locks the same row and requires `complete` before querying; [`TransactionMemberRepository`](../../src/main/kotlin/com/swiftmako/jormanager/repositories/TransactionMemberRepository.kt#L17) locks it while reading transaction/account history. Do not separate readiness checking from data reads or downgrade the lock without proving snapshot consistency. History can report incomplete coverage rather than authorizing wallet use.

A crash before transaction commit leaves neither partial ledger effects nor a newer pointer; after commit all are durable even if publication was lost. On restart register matching genesis, start a new fenced session, intersect against retained complete history and reconcile from the database. Do not assign unscoped legacy rows to a guessed network, reset history, or treat a connection tip as proof of complete coverage. A rollback beyond history is a recovery boundary, not permission to silently rebuild or delete data; controlled replay needs a source with adequate retained blocks and verified genesis.

## Migration and verification

1. Introduce the private enum/explicit mapping at coordinator and DB boundaries; audit all status consumers, including `LedgerReplayService`, wallet readiness and transaction views. Keep JSON, WebSocket and SQL values unchanged; no DB migration is required for a representation-only cutover.
2. Keep SQL transaction boundaries, network row locks, generation increments and the projection code together. Keep `ChainSyncProtocol`/`NetworkChainSession` callers passing the generation; no protocol enum replacement. Maintain existing diagnostics and error semantics.
3. Extend behavior tests for matching/nonmatching observed tips, unavailable/incomplete not cleared by observation, verified adoption after boundary, duplicate and conflicting inclusion, genesis mismatch and endorsed-before-direct application. Inject projection failure before commit and assert no effects/pointer/readiness escape.
4. Exercise concurrent reader versus adoption/rollback; each read must see a coherent locked snapshot. Race old-generation fetch and observation with rollback/new session, including multiple application processes. Test retained target, origin, unknown target (committed fence and exception), broken ancestry (transaction abort), operation confirmation reversal, restart after commit and before notification, and genesis-recovery guard.

**Risks and rejected scope.** Current statuses encode operational coverage, not a full consensus proof; `complete` depends on observed peer tip and verified local projection. The `stale` schema value has no demonstrated writer here. Do not introduce a generic transition engine, event-sourcing, in-memory authorization cache, silent legacy backfill, or a new reorg policy to make the enum look cleaner. A future change to stale/readiness policy needs separate behavioral and migration approval.
