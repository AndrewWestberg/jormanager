# Era evidence and refresh lifecycle

**Status: PROPOSED design, not implemented or approved.**

## Recommendation

Model the per-target **cache load lifecycle** explicitly; keep the observed era and
its provenance as evidence, not as a finite-state machine for the Cardano ledger.
The network can be queried again, report a different era, or have its observation
invalidated; a local cache must not decree a chain transition. A small sealed load
state replaces the combination of `initialized`, `refreshing`, `context` and
freshness strings. Persisted network-level era generation remains separate from
local query revisions and from the connection supervisor's generation.

This is worth doing because authorization consumes `current`, and concurrent
refresh, invalidation and reconnect can otherwise produce combinations whose
meaning is unclear. It does not warrant a generic FSM, event log, or durable
copy of every load state. See [capability qualification](capability-qualification.md)
and [connection supervision](connection-supervision.md).

## What exists

[`CardanoRepository`](../../src/main/kotlin/com/swiftmako/jormanager/repositories/CardanoRepository.kt#L18)
keeps an entry per network key and node ID. `networkKey` incorporates genesis
identity and configuration, not just network magic; target context also binds
host, node, host paths and CLI path
([source](../../src/main/kotlin/com/swiftmako/jormanager/repositories/CardanoRepository.kt#L54)).
`EraEvidence` carries network, era, `unavailable`/`refresh_required`/`current`,
source, optional chain point, observed time, generation and reason. `evidence`
compares a current local generation against the shared DB row and marks it
refresh-required on mismatch
([source](../../src/main/kotlin/com/swiftmako/jormanager/repositories/CardanoRepository.kt#L133)).
The DB stores one latest observation per network, not one per node
([schema](../../src/main/resources/db/changelog/043-operational-schema.xml#L9)).

`getEra` validates supplied network/socket arguments and queries if the entry
is uninitialized, context changed or freshness requires it. Chain monitor
reconnect requests a forced refresh
([monitor](../../src/main/kotlin/com/swiftmako/jormanager/monitors/ChainMonitor.kt#L129));
chain sync passes block eras as **refresh triggers**, not authoritative node-era
responses ([caller](../../src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/chainsync/ChainSyncProtocol.kt#L138)).
The query verifies installed configuration against selected genesis identities,
socket owner and process before and after CLI tip, target/network after the query,
and accepts known era names. Successful upsert increments the persisted generation
only when the stored era changes; same-era provenance refresh does not increment
it ([source](../../src/main/kotlin/com/swiftmako/jormanager/repositories/CardanoRepository.kt#L192)).

Current edge cases matter for a redesign: `refreshing` drops a force/reconnect
request while another query runs, and there is no queued successor representing
the latest requested target. Failure marks unavailable but `initialized` stays
true, and `getEra` does not retry an unavailable result for unchanged context;
a reconnect or other trigger is needed. A joining caller may return before an
in-flight query completes. A local serial does not order writes from other
processes, and the database's generation check alone does not detect same-era
provenance changes. These are source-level design risks, not claims of observed
production failures.

## Proposed model

Kotlin sketch; names and signatures illustrate ownership, not executable code:

```kotlin
data class Target(val networkKey: String, val nodeId: Long, val context: TargetContext)
data class Observation(
    val target: Target, val era: EraName, val point: ChainPoint?,
    val observedAt: Instant, val eraGeneration: Long,
    val connectionGeneration: Long, val queryTicket: Long
)
sealed interface Load {
    data object Empty : Load
    data class Loading(val revision: Long, val target: Target,
                       val connectionGeneration: Long, val previous: Observation?) : Load
    data class Current(val observation: Observation) : Load
    data class RefreshRequired(val previous: Observation?, val reason: String) : Load
    data class Unavailable(val previous: Observation?, val reason: String) : Load
}
```

`previous` is display/diagnostic evidence only in non-current states; it is never
accepted by authorization. Public freshness remains explicitly mapped to existing
wire strings: `Current` → `current`, `Empty`/`Unavailable` → `unavailable`,
`Loading`/`RefreshRequired` → `refresh_required`. Keep the existing response
fields and nullable values; do not serialize Kotlin class names. State changes:

| Input | Decision and effect |
| --- | --- |
| First request, explicit retry, or refresh-required | Under the entry lock start one query with a new local revision; concurrent requests for the same still-valid target join its result. |
| Reconnect, target/config change, changed block-era trigger, DB generation mismatch | Fence the old revision immediately, mark evidence non-current; enqueue/coalesce a successor for the latest target and connection generation, including when a query is already in flight. |
| Matching successful query | Revalidate identity, commit observation with the DB concurrency guard below, then publish `Current` only if revision, target and connection generation still match. |
| Matching query failure | Publish `Unavailable`; wake joiners with failure. An explicit query/reconnect or subsequent eligible request may retry; avoid tight automatic retry loops. |
| Late success/failure | Discard its publication; do not clear or overwrite a newer load. Start the pending successor after the old flight completes. |

Use a per-entry mutex/lock for state, revision, pending refresh and flight
ownership; release it for SSH/CLI and DB I/O. Joiners wait on the same completion,
then re-evaluate the state and DB guard. Cancellation of a waiter does not cancel
the shared query. Fencing makes a result unusable; it does **not** guarantee that
a remote command was cancelled. Handle cancellation and cleanup in `finally`,
only if that flight still owns the slot. A reconnect must invalidate even if the
host/node context string is unchanged; pass the connection supervisor's generation
rather than treating it as the DB era generation. If the supervisor cannot provide
it, explicitly allocate a local reconnect epoch at its boundary. Never use
`StateFlow` as a lock or durable authority; an optional state-only flow may notify
UI subscribers after a committed transition, but is unnecessary for correctness.

## Authority and cross-process ordering

Three counters serve different purposes:

* **Era generation:** persisted per network; changes when the accepted network
  era changes, including change back. Qualifications bind to it.
* **Query revision:** local to a target entry; increments on invalidation/new work
  and fences stale responses, including same-connection requests.
* **Connection generation:** owned by the connection supervisor; changes on a
  session replacement, so a response from an old session cannot restore currentness.

Retain actual node query and socket/config checks as the source of an observation;
a block's era only requests a refresh. A cached current observation must match
its target and current connection generation. At authorization, its accepted
`queryTicket` must equal **both** the shared committed ticket and latest reserved
ticket, and its era generation must match the database. Comparing only the two
database tickets would miss a same-era query that already completed elsewhere.
A point/time is provenance, not proof of irreversible chain adoption.

For multiple processes, local revisions cannot prevent an older query in process A
from overwriting a newer observation committed by B. Proposed minimal durable guard:
reserve a monotonically increasing **network query ticket** in PostgreSQL before
the remote query (an atomic increment in a network row), and commit only if that
ticket is still the newest reserved ticket. In the same transaction update the
observation and increment era generation only on era change. A process that loses
this compare-and-set discards its result and may retry against the newest ticket;
failed or crashed latest tickets leave existing evidence non-current until a new
query succeeds, not authorized by an old row. Readers compare both committed ticket
and latest reserved ticket as well as era generation; thus a remote reservation,
including one for the same era, invalidates local current evidence on the next
authorization read. Reserve and compare atomically; a notification can accelerate
refresh but cannot replace these checks. This is conservative under concurrent
observers and can cause retries; it does not assume slots order all node views.
Define bounded/request-driven retries rather than livelock under constant queries.
This network-wide invalidation is intentionally stricter than today's same-era
refresh behavior: frequent refreshes by different targets can make one another's
evidence temporarily unusable. It addresses competing writers to the existing
network-scoped observation row, not an assumption that multiple processes are
currently deployed. If the supported deployment contract instead enforces one
writer per network, preserve that ownership and defer distributed ticketing;
local sealed state and query fencing can be introduced independently. Do not
silently add this availability tradeoff as a representation-only refactor.
Do not hold a DB transaction or row lock over SSH. A crash after commit but before
local publication requires re-query on restart, because persisted provenance alone
does not establish a live target connection.

Read/validate DB state at authorization boundaries, fail closed if unavailable,
and recheck immediately before irreversible effects; concurrent external artifact
or network changes cannot be made atomic with an SSH command. The system must not
promise stronger atomicity than it has. Era evidence is necessary but not by itself
permission to perform an operation.

## Migration and behavioral checks

1. Add the minimal ticket/reservation columns and transactional guarded upsert via
   Liquibase, backfilling existing rows consistently. Preserve existing network
   keys, era generation semantics and `EraEvidence` wire shape. Drain/coordinate
   old writers during rollout: old instances can bypass the new guard.
2. Introduce sealed cache state inside `CardanoRepository`, migrate `getEra`,
   `evidence`, block triggers and monitor reconnect together; propagate a session
   generation from the supervisor. Audit all era-evidence consumers including
   capability, reviewed operations, pool deployment and Leios observation.
3. Preserve identity/path/socket verification and source binding; failure remains
   unavailable. Keep read-only legacy API consumers on explicit freshness mapping.
   No data deletion, no new event infrastructure.
4. Existing [`PoolDraftServiceTest`](../../src/test/kotlin/com/swiftmako/jormanager/controllers/PoolDraftServiceTest.kt#L27)
   mocks evidence and checks stale/wrong-network handling; it does not establish
   cache concurrency or database ordering. Add repository-level behavioral tests
   with controllable query/DB timing and PostgreSQL integration coverage.

Scenarios: concurrent requests issue one query and all join; invalidation and
reconnect during a flight lead to a successor and reject the old response; query
failure is retryable without a reconnect; waiter cancellation leaves the flight
usable; changed context/network cannot publish old evidence; same-era refresh
preserves era generation but updates provenance. Across processes, reverse query
completion order cannot commit an older ticket; reservation or DB failure prevents
current authorization; restart after reservation/commit requires fresh validation.
Exercise changed-era and changed-back generation, unknown era, bad socket owner,
config changes during query, and DB mismatch between validation and use.

Risks are extra database traffic and conservative availability when another process
reserves a query and dies; a fresh query recovers it. A timer-based trust window,
permanent cached `current`, and optimistic notifications alone would trade away
the requested authority boundary. A general-purpose FSM or streaming event bus
would add machinery without strengthening it.
