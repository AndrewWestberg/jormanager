# Chain delivery and interpretation contract

**Status:** proposed implementation contract; Musashi Dōjō is the Dijkstra qualification baseline, not a replacement for previously supported networks/eras or an implemented node protocol. Exact observed source/binary/configuration identities are captured per qualification run. B1 in [plan.md](../plan.md) requires actual Dijkstra delivery evidence; this contract does not invent an EB-fetch protocol.

## Authority and wire boundaries

Use the Musashi source observations and conflicts in [research.md](../research.md), sections 1–5, for Dijkstra. JorManager currently uses TCP node-to-node ChainSync and BlockFetch. Neither existing handshake versions nor proposal text about node-to-client inlining proves Musashi delivery. Before enabling Dijkstra ingestion, record negotiated network/reset identity, version, era discriminator, header framing, body commitment, EB acquisition, direct-plus-certified ordering and adoption semantics in the [evidence manifest](evidence-manifest.md), using public Musashi captures and controlled private same-bundle scenarios. This Dijkstra gate does not disable qualified existing Conway delivery.

An era-specific decoder returns either a complete typed value or a contextual failure. Dispatch each historical or live block using its own authoritative era discriminator, independently of the current tip era. Unknown eras, malformed headers, unsupported non-null features and missing committed content must never become an empty successful result. Earlier supported eras keep their intentional wire/validation paths; unknown is not Conway. Writes use the per-network node-derived current-era cache, separately from per-block decoding. A block differing from that cache marks `refresh_required` and triggers a fresh node query; a matching block reuses the cache, and no block overwrites the node-derived value. Pending or failed refresh blocks dependent writes, not historical decoding or other networks; the full cache and authorization contract is in [operator-messages.md](operator-messages.md).

| Dijkstra context | Accepted Dijkstra baseline representation | Required distinction |
| --- | --- | --- |
| Preferred submission | `[body, witnesses, auxiliaryData / nil]` | Author supplies no phase-2 outcome |
| Transitional submission | `[body, witnesses, true, auxiliaryData / nil]` | `false` is rejected, not repaired |
| Included top-level transaction | `[body, witnesses, auxiliaryData / nil, bool]` | Producer validity applies to the batch |
| Child | `[childBody, witnesses, auxiliaryData / nil]` | No independent validity, fee, collateral or recursive children |
| Ledger block | `[header, [transactions, leiosCertificate / nil, perasCertificate / nil]]` | Ledger encoding is not assumed to equal transport framing |

Submitted, included and child decoders are explicit entry points. Recognizing the same array length does not make a child independently submittable. Original hash-bearing bytes must survive parsing. Body-derived TxIds, full-transaction EB commitments, header hashes and EB-reference hashes have distinct types.

## Normalized event boundary

These are internal messages at the existing protocol-to-index boundary, not new external network messages:

- **AdoptedBlock**: network identity; baseline ID; chain point `(slot, headerHash)` and predecessor; block number; era; exact header/body bytes; ordered directly included batches; announcement/certification data; closure references and content; source/transport evidence identity.
- **Rollback**: network identity; exact intersection point or origin; delivery generation. Must be delivered even if no subsequent block arrives.
- **BoundaryFailure**: network, attempted point/era, last complete point, operation, sanitized cause, unavailable commitments and affected dependent views. Not a successful block event.

The ingestion coordinator owns one ordered stream and commit authority per network. A rollback increments the delivery generation, invalidates in-flight work from the abandoned branch, and prevents stale BlockFetch completions from reapplying it. Node tip, observed header position and complete-index position are separate values.

## Closure and commit rules

1. An announcement alone may be retained as provenance but creates no confirmed transaction effects.
2. For adopted certified content, obtain all ordered referenced transactions from the verified delivery integration. Check reference identity, full-transaction commitments, byte sizes, duplicates and linkage to the announcing/adopting points. The selected node establishes consensus acceptance; decoding by JorManager does not.
3. Musashi's candidate ledger permits a certificate alongside ordinary body transactions. Acquire and apply both certified EB closure and direct transactions exactly once, retaining each source's provenance and positions plus the verified cross-source application order. Consensus source applies announced closure before its certifying mempool view; actual transport/replay may inline content, so do not concatenate a closure twice or replace direct content based on an assumed wire layout. This must be proven by B1. A non-null Peras certificate remains baseline-disabled.
4. Missing committed content stops completion at the preceding complete point. Later headers may be observed; no later balance can be advertised as complete while the gap remains.
5. Decode and validate the entire batch before writing effects. Retain each child TxId, its parent TxId, child position and own auxiliary data. Outputs are `(network, memberTxId, localOutputIndex)`, never flattened parent references.
6. Apply child effects in their supplied order, then parent effects, respecting original versus threaded state and aggregate accounting. Charge the top-level fee/collateral only once. Spending, collateral and reference inputs remain distinct. Do not infer permission to spend an earlier member's output from traversal order.
7. A producer-recorded phase-2 failure leaves ordinary parent/child effects unapplied. Store unsuccessful inclusion for inspection and apply only the reference-prescribed top-level collateral effects, including its exact collateral-return reference.
8. Persist inclusion, effective UTxOs, member-owned account/certificate/governance records, key-registration observations, reversible changes and complete progress in one PostgreSQL transaction. Publish consumer updates only after commit. A failure leaves the prior complete state intact.
   Pool certificate/deposit/refund effects, projected pool lifecycle and operation confirmation use this same atomic authority; node-query snapshots supplement observations but do not replace adopted-chain effects. Shared batch/member and ordinary data mappings serve supported eras without a second writer.
9. Duplicate delivery of the same adopted inclusion is idempotent; contradictory bytes for an existing identity are a boundary error. A body may have historical occurrences on competing branches: do not globally overwrite its provenance.
10. Roll back all derived state to the intersection in one database transaction before publishing the new state. Keep installed-key observations separate: a chain rollback cannot undo an SSH deployment. Reconcile retained keys and pending authorized batches without automatic resubmission.

## Interpretation coverage

For Dijkstra preserve guards and top-level-required guard maps, script version/purpose/member ownership, structured redeemer pointers, datums/reference scripts, auxiliary data, `directDeposits` at parent and child level, account checkpoint intervals, certificates/votes/proposals, treasury declarations/donations and relevant protocol parameters. Unknown fields and invalid duplicates cannot disappear through generic map overwrite. Preserve meaningful absent/null/empty distinctions, binary asset names and exact integers. Apply the applicable era's actual parameter enforcement, including candidate Musashi minimum pool cost for Dijkstra, rather than rules from a different ledger selection. See [data-model.md](../data-model.md).

JorManager is an indexer, not a second consensus or Plutus implementation. Independently executed ledger examples establish acceptance and rejection expectations, including same-batch spending, legacy-script restrictions and account checkpoint rules. Raw retention alone does not satisfy semantic indexing.

## Observable failure contract

Dependent wallet/pool/transaction responses carry their `asOf` point, `lastCompletePoint`, freshness/completeness status and reason. No unknown balance becomes zero. Confirmed unsuccessful inclusion differs from pending submission and ledger rejection. An unavailable balance disables spending that depends on it. Protocol failure closes/cancels owned work without terminating the whole application or silently advancing progress. A gap or non-current era cache on one network does not prevent unrelated supported networks or readable history from operating.

Acceptance: every case in research section 8, all SC-001–SC-004 and SC-007 comparisons, immediate rollback without replacement, and the real-boundary scenarios in [quickstart.md](../quickstart.md). This document is not passing evidence for those scenarios.
