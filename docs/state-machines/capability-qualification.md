# Capability qualification and effective authorization

**Status: PROPOSED design, not implemented or approved.**

## Recommendation

Do not build a persistent capability state machine. A qualification is a stored,
operator-supplied statement; the effective capability is a **derived validation
result** over that statement, a live era observation, target identity and actual
artifacts. Its value can change without a qualification transition: reconnect,
era change, modified files or unavailable SSH are enough. Make those inputs and
result variants explicit and exhaustively map them at the API boundary. Persisting
an effective `verified` result as unconditional authority would be unsafe.

Recording/replacing a qualification is a real guarded write, but does not need an
independent lifecycle engine. The [era cache](era-evidence.md) has a separate
load lifecycle; a capability check consumes only current evidence. Related side
effects are described in [reviewed transactions](reviewed-transactions.md),
[pool deployment](pool-deployment.md) and [Leios key installation](leios-key-installation.md).

## Current evidence and decisions

[`CardanoCapabilities`](../../src/main/kotlin/com/swiftmako/jormanager/services/CardanoCapabilities.kt#L13)
returns operation, wire status, reason, optional evidence ID and qualification
fingerprint. Its qualification request supplies operation, status, evidence ID and
SHA-256, CLI/node/config SHA-256, expected era and era generation. The tables
[`qualified_operations` and `host_qualified_operations`](../../src/main/resources/db/changelog/043-operational-schema.xml#L13)
store the operator's status and evidence, keyed respectively by network/era/host/
node/operation and network/era/host/config digest/operation. Upsert replaces the
row for a key; it is not an immutable audit log, even though the evidence is
described as immutable by the service comment. The DB stores a digest and ID,
not evidence bytes, and does not itself authenticate or verify those bytes.

Node recording checks host ownership, known operation/status, digest format,
current same-network same-source era and expected generation, then hashes the live
CLI, node binary and installed node configuration. It rechecks era and performs
an `INSERT ... SELECT ... WHERE EXISTS` against network/era/generation
([source](../../src/main/kotlin/com/swiftmako/jormanager/services/CardanoCapabilities.kt#L142)).
This SQL predicate checks a snapshot, not a lock held throughout subsequent
external actions. Qualification validity is recomputed at use: Dijkstra checks
record generation/status/provenance, live hashes and another current-era read
([source](../../src/main/kotlin/com/swiftmako/jormanager/services/CardanoCapabilities.kt#L174)).
The fingerprint length-prefixes network, era, target, operation, generation,
status, normalized artifact hashes and evidence ID/hash
([source](../../src/main/kotlin/com/swiftmako/jormanager/services/CardanoCapabilities.kt#L37)).
It is a change detector, not a signature or proof the evidence was inspected.

Host qualification is a different scope: `node.create`, `leios.key.prepare`, and
`leios.key.import` use a target host, an active observer node on the selected
network, and a supplied effective configuration digest. It hashes target host CLI
and node binaries; the config digest is part of the record/key, not a claim that
an installed node config was hashed. The observer must have current evidence from
its own host/node ([source](../../src/main/kotlin/com/swiftmako/jormanager/services/CardanoCapabilities.kt#L75)).
The host may differ from the observer host. Host qualification does not establish
that anything is installed or adopted on chain.

Legacy behavior is deliberate and subtle: for Byron through Conway,
`capability` reports `unverified` without inspecting a qualification, while
`requireOperation` separately allows the existing workflow if evidence is current
and bound to the right target. Host `requireHostOperation` requires `verified`;
callers such as pool draft choose the legacy observer-node gate instead
([source](../../src/main/kotlin/com/swiftmako/jormanager/controllers/PoolDraftController.kt#L161)).
Leios key handling explicitly checks verified instead of the legacy bypass
([source](../../src/main/kotlin/com/swiftmako/jormanager/services/LeiosKeyService.kt#L90)).
Do not turn a legacy allowance into a verified claim or universally add/remove a
gate during a refactor.

[`CapabilitiesController`](../../src/main/kotlin/com/swiftmako/jormanager/controllers/CapabilitiesController.kt#L41)
exposes query and separate node/host recording messages. Wallet, governance,
pool registration/retirement, deployment, Leios and reviewed-operation callers
apply these gates at different workflow points
([reviewed operations](../../src/main/kotlin/com/swiftmako/jormanager/services/ReviewedWalletOperations.kt#L239),
[deployment](../../src/main/kotlin/com/swiftmako/jormanager/services/PoolDeploymentService.kt#L114),
[observation](../../src/main/kotlin/com/swiftmako/jormanager/services/LeiosObservationService.kt#L39)).
Leios observation checks the qualification fingerprint again before saving;
other workflows have their own revalidation needs.

## Proposed explicit types

The following is a conceptual Kotlin sketch, not executable application code:

```kotlin
sealed interface Scope {
    data class Node(val hostId: Long, val nodeId: Long) : Scope
    data class Host(val hostId: Long, val configSha256: Digest,
                    val observerHostId: Long, val observerNodeId: Long) : Scope
}
data class QualificationRecord(
    val scope: Scope, val network: NetworkKey, val era: EraName,
    val eraGeneration: Long, val operation: Operation,
    val attestedStatus: AttestedStatus, val artifacts: ArtifactDigests,
    val evidenceId: String, val evidenceSha256: Digest
)
sealed interface EffectiveCapability {
    data class Verified(val evidenceId: String, val fingerprint: Digest) : EffectiveCapability
    data class Unsupported(val evidenceId: String?) : EffectiveCapability
    data class Unverified(val reason: String, val evidenceId: String? = null) : EffectiveCapability
    data class Unavailable(val reason: String) : EffectiveCapability
    data object LegacyAllowed : EffectiveCapability
}
```

`Scope.Host` carries observer identity as an evaluation input, not a claim that
it is currently stored in the host qualification row. Node and host records have
different lookup keys and required artifact comparisons; do not coerce a host
record into a node record. Avoid assuming an `AttestedStatus.Verified` means the
result is verified. A pure decision function can use already obtained typed inputs;
SSH, current-era reads and DB operations remain effects owned by the service.

Derivation, in order, is approximately:

1. Validate operation, target ownership/scope, network and current target or
   observer provenance. Missing or stale evidence/failed access → `Unavailable`.
2. For the existing node legacy path, produce `LegacyAllowed` before considering
   a qualification, exactly as today's policy does. Do not apply it to strict
   host or explicit Leios verified checks.
3. Look up the appropriately scoped persisted statement. Missing, wrong era
   generation, incomplete ID/digest or changed artifacts → `Unverified`;
   non-verified recorded status maps to its existing effective status.
4. For an attested verified statement, hash the actual applicable artifacts,
   re-read current era/source/network/generation, and only then return `Verified`
   with the existing canonical fingerprint. Unavailable DB, SSH, hashing or
   currentness check fails closed. Unknown/invalid stored status fails closed.

`when` over every result at the boundary maps `Verified`, `Unsupported`,
`Unverified`, `Unavailable` to the existing lowercase strings. Map
`LegacyAllowed` to `unverified` with the existing legacy reason, and keep the
policy gate distinct: node `requireOperation` accepts `Verified` or
`LegacyAllowed`; strict verified consumers and host gates accept only `Verified`.
Maintain the existing message types, response fields, error behavior, fingerprint
encoding and request statuses; an internal type change is not a wire change.
Reasons may remain strings; do not conflate a display result with authority.

## Write, race and recovery ownership

The recording service validates input and live scope/artifacts; read current era
before and after external hashing, then guard the DB write against the same
network, era and era generation. Serialize the guard with updates of the era row
(e.g. a short transaction that locks the observation row and verifies it before
upsert); never hold a DB lock over SSH. With the proposed era query ticket in
[era evidence](era-evidence.md), check both committed and latest reserved ticket
as well; no reservation may be pending at the guarded write. The lock and
reservation updates must use the same DB row. A concurrent era update after commit
is allowed: the record becomes ineffective at the next validation, not retroactively
a durable permission. Operator-supplied evidence is an attestation; hash syntax
and matched artifacts do not prove who reviewed it or that its referenced bytes
are authentic. Keep evidence ID/hash in the record and fingerprint; any stronger
reviewer identity or immutable audit requirement needs a separate explicit policy,
not an invented guarantee.

At use, read the persisted record and current evidence afresh; perform artifact
checks and repeat era/currentness checks before the sensitive operation. A
qualification upsert on another process may change the record after the first
read: re-read and compare the record fingerprint immediately before an effect,
and in durable workflows bind it to the reviewed operation and revalidate it at
each existing authorization boundary. Coordinate database-owned state transitions
with a short transaction/conditional update where feasible. A hash check cannot
atomically prevent a remote executable or config changing between check and SSH
execution, nor can a DB transaction atomically bind a remote command; document the
remaining TOCTOU risk and use existing host controls. Do not claim that repeating
checks eliminates it.

On restart do not restore an in-memory verified verdict. Reload qualification and
reacquire current target/observer evidence and hashes. Cross-process invalidation
comes from shared DB reads and era ticket/generation guards, not a local
`StateFlow`, cache notification, or connection-generation equality alone. A flow
is optional for UI snapshots, not a mutex, command queue, durable event history or
permission source. Query revisions fence late query responses; connection generation
fences sessions; neither is the persisted era generation bound in qualifications.

Host-side qualification permits an operation on a host/configuration under an
observer's current network evidence. It does not prove the host runs a node, that
an installed key works, or that a chain has adopted it. Installation, observation
and adoption must retain their own proofs and reconciliation.

## Migration and behavioral checks

* Keep the SQL record schema and keys unless separately changing era observation
  locking/tickets; migrate the service's local decision to sealed variants and
  explicitly map existing API strings. Preserve `verified`, `unsupported`,
  `unverified`, `unavailable` as *attested* database values and as wire values,
  not as unconditional runtime permission. No automatic conversion of old rows to
  new assertions. Preserve current fingerprint canonicalization.
* Move all node and host callers to the typed policy gate at once: capability
  controller, reviewed wallet/governance/pool, wallet fee preview, pool draft and
  deployment, Leios key/install/observation and reviewed operation revalidation.
  Retain each caller's legacy-versus-strict choice, host config digest source,
  target/observer distinction and persisted review fingerprint. Update request and
  response fixtures only if internal construction changes; network API stays stable.
* Existing [`PoolDraftServiceTest`](../../src/test/kotlin/com/swiftmako/jormanager/controllers/PoolDraftServiceTest.kt#L27)
  demonstrates mocked current/stale/wrong-network evidence, not actual qualification
  or cross-process DB behavior. Add service-level behavior tests and PostgreSQL
  race tests with controlled era and artifact changes; keep controller mapping tests.

Exercise: each recorded status and absent record; invalid digest/empty evidence;
wrong network, host ownership, source or observer; changed installed CLI, node or
node config; host target/config mismatch; observer and target on different hosts;
era generation changes and changes back; era changes during hashing and during
conditional recording; another process replaces the qualification before effect;
query reservation/reconnect invalidates a previously current result; restart with
persisted `verified` but no current evidence; DB/SSH failure; successful strict
fingerprint revalidation; unchanged Byron–Conway legacy wire result and allowance,
with Leios strict gate still denied absent verified qualification. Ensure an
installed key or an observed block never alone establishes qualification or chain
adoption.

The principal risks are remote artifact TOCTOU, concurrent-writer rollout, and
availability under deliberately conservative invalidation. A new workflow engine,
generic FSM, in-memory authorization cache, mandatory event stream, or blanket
legacy migration would add complexity or change policy without solving those risks.
