# Reviewed transactions: durable authorization and chain outcome

**Status: proposed design, not implemented or approved.**

## Recommendation

Make this machine explicit. It protects reviewed wallet and governance transactions across concurrent requests, irreversible network writes, chain rollback and process crashes. It already has durable state and good conservative boundaries; the gain is making legal combinations and writers visible, not replacing PostgreSQL with a coroutine actor or introducing an FSM framework. The database remains authoritative. See [chain synchronization](chain-synchronization.md), [era evidence](era-evidence.md), and [pool deployment](pool-deployment.md).

## What exists

[`ReviewedWalletOperations`](../../src/main/kotlin/com/swiftmako/jormanager/services/ReviewedWalletOperations.kt#L25) backs wallet, pool registration/retirement and governance controllers. [`AuthorizedOperation`](../../src/main/kotlin/com/swiftmako/jormanager/entities/AuthorizedOperation.kt#L10) holds a string state, reviewed body and fingerprints, optional signed batch and optional confirmation point; [`authorized_operation_inputs`](../../src/main/resources/db/changelog/043-operational-schema.xml#L29) uniquely reserves each network/input. Request ID, preview ID and network/transaction ID are unique. The request payload is intended to be secret-free.

[`preview`](../../src/main/kotlin/com/swiftmako/jormanager/services/ReviewedWalletOperations.kt#L250) checks current target era and capability, body identity, fee/interval and evidence, then inserts `previewed`; an equivalent existing request/transaction returns its existing review, a different review is rejected. A review does not authorize a replacement body.

[`submit`](../../src/main/kotlin/com/swiftmako/jormanager/services/ReviewedWalletOperations.kt#L309) claims `previewed → signing` under a short pessimistic lock. A competing submission returns the existing record. Outside the transaction it revalidates, signs, checks body identity/consequences, revalidates, and atomically reserves all decoded normal and collateral inputs and stores the signed batch as `submission_uncertain`. Signing errors before this point produce `not_submitted` if still signing. A final pre-callback revalidation failure releases the reservations and produces `not_submitted`. After entering the submit callback an exception may mean broadcast happened, so uncertainty and reservations remain; a normal callback return changes uncertainty to `submitted`, unless a concurrent chain writer already changed the state. This is not exactly-once delivery, and `submitted` is not chain inclusion.

[`ChainIngestionCoordinator.adopt`](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L178) checks the exact persisted signed batch against direct and endorsed batches and writes `confirmed` or `phase2_failed` plus point inside the chain transaction. [`rollback`](../../src/main/kotlin/com/swiftmako/jormanager/services/ChainIngestionCoordinator.kt#L211) marks matching confirmations `rolled_back` and clears the point. **`OperationConfirmationsTable` is an Exposed view of `authorized_operations`, not a separate `operation_confirmations` table** ([definition](../../src/main/kotlin/com/swiftmako/jormanager/tables/ChainTables.kt#L172)). It is not a second authorization store. Adoption and rollback do not themselves release input reservations.

[`reconcile`](../../src/main/kotlin/com/swiftmako/jormanager/services/ReviewedWalletOperations.kt#L79) is operator initiated and does not sign or submit. It locks sync before operation. If a sync row exists it cancels interrupted `signing` as `not_submitted`; otherwise it leaves the review alone. For uncertain/submitted/rolled-back/confirmed/phase-2-failed records it validates reviewed and signed identity, gathers node observations outside the transaction, then requires a complete index and matching node, observed and complete chain points. An exact adopted parent batch confirms or records phase-2 failure; multiple or differing occurrences remain unresolved. A missing previously confirmed occurrence needs rollback investigation. Mempool presence keeps uncertainty; expiry with no matching occurrence or mempool entry releases inputs. Unspent inputs and mempool absence alone do not prove non-submission. There is no automatic resend.

## Proposed model

Use a sealed internal projection decoded from the existing durable row; it is a sketch, not executable scaffolding:

```kotlin
sealed interface ReviewState {
    data object Previewed : ReviewState
    data object Signing : ReviewState
    data class SubmissionUncertain(val signed: SignedBatch) : ReviewState
    data class Submitted(val signed: SignedBatch) : ReviewState
    data class Included(val signed: SignedBatch, val point: ChainPoint, val validity: Validity) : ReviewState
    data class RolledBack(val signed: SignedBatch) : ReviewState
    data object NotSubmitted : ReviewState
    data class Expired(val signed: SignedBatch) : ReviewState
}
enum class Validity { VALID, PHASE2_FAILED }
```

Keep immutable review details, input reservations and the network/target separately; constructors/decoder validate required signed batch and full confirmation point, reject impossible combinations rather than inventing evidence. `NotSubmitted` may retain signed bytes after the last pre-submit check fails; do not erase historical data merely to satisfy the sketch. Decode legacy rows explicitly and fail closed on malformed/unknown states. Use exhaustive `when` for permitted commands and a single transition writer per boundary; map `Included(VALID)` to `confirmed`, `Included(PHASE2_FAILED)` to `phase2_failed`, and all other variants explicitly to their existing snake-case wire strings. No endpoint gets a new status accidentally.

### Transition ownership and safeguards

* Review service owns preview, claim, signing result, pre-submit failure and post-callback acknowledgement. Claim and reservations remain committed in short transactions; never hold locks across signer, node query, CLI or network call. The signer must return a complete batch whose parent and children correspond to the reviewed body and whose inputs are uniquely reserved. Keep request, parameter, state, capability and era-generation checks before and after signing and immediately before broadcast.
* Chain coordinator owns inclusion and rollback in the same transaction as adopted chain projections. It must match the exact whole batch, including witnesses/auxiliary data, not merely parent tx ID; phase-2 failure is inclusion without successful effects. Rollback clears the inclusion point and returns the operation to a non-final outcome, without resubmitting or deleting signed bytes/reservations. Respect sync-row-before-operation lock order in reconciliation and any cross-writer update.
* Operator reconciliation owns conservative evidence-based resolution, rechecks state and signed identity after the external observation, and retains all current point-match, expiry and exact-batch guards. Keep unresolved observations as explanations, not as permission to sign again. Do not interpret `confirmed` as immutable finality.
* A crashed signer can be invalidated only with an atomic state change that makes its later write fail. `signing → not_submitted` fences the old worker's durable transition; it does not cancel its running process. A signer must not submit independently. The transition after persisted `submission_uncertain` must never be retried merely because the process restarted: crash could occur before, during, or after broadcast. Callback return is an acknowledgement, not proof of adoption.
* A rollback, late adoption, callback completion and reconcile can race. Serialize against the row and chain sync generation/point, condition each update on expected prior state and signed identity, and ensure callback completion cannot overwrite inclusion or rollback. Generation fencing rejects stale commits; it does not cancel an already-issued external effect.

## Migration and evidence

1. Add the decoder/encoder and transition functions around the existing schema; retain independent transactions, uniqueness constraints, and wire vocabulary. Inventory existing rows before introducing any DB checks: an interrupted deployment or historic row may carry nullable evidence that needs explicit conservative classification. Do not silently rewrite an unresolved record to a terminal one. DB constraints can follow a data repair plan.
2. Migrate all wallet, governance, pool-registration and pool-retirement result mappings and pending-operation queries; migrate the coordinator's Exposed updates and transaction-view projection together, preserving their shared row and chain transaction. Keep deployment's registration query consistent with the `confirmed` mapping. No in-memory `Flow` should authorize a command; notifications can project committed state only.
3. Exercise concurrent preview/submit and input collision; injected failure at every signing, durable reservation, final-check and callback boundary; restart with each persisted state; callback success racing chain inclusion; exact versus different Dijkstra whole batch; phase-2 failure, rollback, re-adoption, stale generation and missing retained history. Reconciliation scenarios must cover mismatched points, unavailable observation, mempool presence, missing inputs, expiry, and previously confirmed missing inclusion. Test legacy wire status and historical-row decoding.

**Risks and rejected scope.** Reserving inputs indefinitely is intentionally conservative when expiry cannot be proven; an operator needs useful diagnostics, not a forced retry. Whole-batch identity and lock ordering are safety-critical. A generic state-machine library, event sourcing, automatic rebroadcast and automatic expiry on an unaligned tip add complexity or weaken authorization and are not proposed.
