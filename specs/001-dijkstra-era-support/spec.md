# Feature Specification: Dijkstra Era Support

**Feature Branch**: `001-dijkstra-era-support` (reported by feature-path discovery; this command did not create or switch a branch)

**Created**: 2026-10-27

**Status**: Draft — validated for planning; production compatibility remains subject to the evidence gates below

**Input**: Support Cardano Dijkstra transactions and data formats, including complete nested-transaction indexing, `directDeposits`, and Leios validation-key registration. Musashi Dōjō is the Dijkstra qualification baseline, not a replacement for existing supported eras and networks. JorManager users must be able to create new nodes and pools on Musashi using network files published on JorManager's remote configuration server. Establish independent compatibility evidence rather than relying on local round trips.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Follow the chain through Dijkstra (Priority: P1)

As a pool operator, I need JorManager to follow supported eras, including Dijkstra, without losing transaction effects or presenting incomplete wallet information as current.

**Why this priority**: Incorrect chain interpretation can hide funds or lead an operator to spend from an incorrect balance.

**Independent Test**: Replay reference blocks for each supported era and compare adopted blocks, output references, balances, and synchronization status with independently established results.

**Acceptance Scenarios**:

1. **Given** a synchronized installation for a supported era, **When** a supported block is adopted, **Then** synchronization continues, the block era is identified correctly, and earlier history remains unchanged.
2. **Given** a supported block containing ordinary transactions, **When** it is indexed, **Then** each transaction and its effective inputs, outputs, assets, and metadata are associated with the correct block without omissions or duplicate effects.
3. **Given** an incompatible node, missing referenced transaction content, or unsupported future format, **When** ingestion reaches that boundary, **Then** JorManager identifies the affected network and last complete chain point, marks dependent data unavailable or stale, and does not advance its complete-index position past the gap.
4. **Given** a structurally readable but ledger-invalid object, **When** it is inspected, **Then** readability is not reported as ledger acceptance or chain confirmation.
5. **Given** historical blocks from multiple supported eras, **When** they are read or replayed, **Then** each block uses its own era rules without rewriting historical identities.

### User Story 2 - Find every nested transaction and its effects (Priority: P1)

As a wallet owner or index consumer, I need a transaction included inside another transaction to remain discoverable under its own ledger identity, with its relationship to the enclosing batch intact.

**Why this priority**: Ignoring nested transactions produces missing UTxOs, incorrect balances, and untraceable payments.

**Independent Test**: Index reference batches containing multiple children, overlapping addresses, assets, metadata, and different validity outcomes; query each member and spend an output created by a child in a later confirmed transaction.

**Acceptance Scenarios**:

1. **Given** a valid batch with a parent and two children that each create output number zero, **When** it is indexed, **Then** all three outputs have distinct correct ledger references, and parent, child order, and chain inclusion are discoverable.
2. **Given** a batch whose individual members do not balance but whose total does, **When** it is confirmed, **Then** all ledger-effective effects appear once without requiring each child to pay its own fee or collateral.
3. **Given** a batch whose script execution fails and whose block records that failure, **When** it is indexed, **Then** ordinary parent and child effects are not applied; only the ledger-prescribed collateral effects are applied and the batch is visibly unsuccessful.
4. **Given** a reference input or metadata attached to a child, **When** the batch is indexed, **Then** the reference input is not marked spent and metadata remains attributed to its actual owner rather than copied to every member.
5. **Given** malformed nesting, duplicate child identity, or an otherwise rejected batch, **When** it is received, **Then** no partial member effects become confirmed state.

### User Story 3 - Register and maintain Leios voting keys safely (Priority: P1)

As a stake pool operator, I need to prepare, register, rotate, and install the additional voting key used for Leios without replacing unrelated keys, exposing secrets, or confusing registration with voting eligibility.

**Why this priority**: A valid on-chain registration alone does not establish that the running node has the right key or that the pool can vote in the current epoch.

**Independent Test**: On an isolated supported network, exercise initial registration, a normal pool update retaining the key, rotation, activation, expiration, and retirement; compare the displayed state with independent chain and managed-node observations.

**Acceptance Scenarios**:

1. **Given** compatible tools and an authorized pool, **When** the operator prepares or imports voting-key material, **Then** JorManager checks its relationship to the public key and proof of possession, identifies the target pool/network/host, and protects the signing material.
2. **Given** a prepared registration or rotation, **When** the operator reviews its target, fee, deposit consequences, and activation implications and authorizes submission, **Then** submission, confirmation, scheduled activation, actual eligibility, and node installation are reported as separate states.
3. **Given** an existing registered key, **When** an unrelated pool setting is updated, **Then** the key is retained unless the operator explicitly requests a supported replacement or removal.
4. **Given** a bad proof, mismatched signing/public material, an unsupported tool combination, or a failed remote installation, **When** registration or deployment is attempted, **Then** the failure is actionable and neither on-chain acceptance nor operational readiness is falsely reported.
5. **Given** a rotated or expired key, **When** epoch snapshots change, **Then** the old and new keys' eligibility and retention requirements follow the Musashi reference rules rather than an assumed immediate swap, invented BLS reservation rule, or fixed wall-clock lifetime.
6. **Given** an uncertain submission or a chain rollback, **When** the operator revisits the workflow, **Then** JorManager reconciles the registration before offering a repeat submission and does not automatically discard the still-needed key.
7. **Given** a Conway pool without Leios credentials, **When** the operator registers, updates, retires or cancels retirement using qualified Conway operations, **Then** those workflows remain available without Musashi configuration or a voting key; Dijkstra re-registration cancellation follows its qualified lifecycle and retains current/future keys as required.

### User Story 4 - Account for Leios-certified transactions (Priority: P1)

As an index consumer, I need transactions delivered through Leios to have the same completeness and rollback guarantees as directly included transactions.

**Why this priority**: An announcement or certificate is not itself a complete set of spendable transaction effects.

**Independent Test**: Replay direct transaction blocks and certified endorser-block cases through the supported node delivery contract, including missing content, repeated delivery, failed certification, and branch replacement.

**Acceptance Scenarios**:

1. **Given** an endorser-block announcement without committed adoption, **When** it is observed, **Then** its transactions are not presented as confirmed payments.
2. **Given** a committed certified endorser block and all its referenced transactions, **When** the node delivers them, **Then** each effective transaction is indexed once with its certification and chain provenance, including any nested members.
3. **Given** an announced block that never becomes adopted or a certificate with unavailable referenced content, **When** synchronization progresses, **Then** unadopted effects remain absent and missing committed content produces an explicit completeness gap.
4. **Given** a format slot reserved for a feature disabled by the selected ledger baseline, **When** an object occupies that slot, **Then** structural recognition does not cause JorManager to report the feature as accepted or supported.

### User Story 5 - Recover and upgrade without misleading state (Priority: P1)

As an existing installation's operator, I need upgrades, restarts, and chain reorganizations to preserve correct Dijkstra state and explain any recovery work required.

**Why this priority**: Correct first-pass decoding is insufficient if branch replacement leaves spent funds, nested effects, or key registrations behind.

**Independent Test**: Compare a fresh index with an upgraded installation after the same historical replay, restarts at commit boundaries, duplicate delivery, and ordinary rollbacks.

**Acceptance Scenarios**:

1. **Given** confirmed parent/child effects and a key-registration change on a discarded branch, **When** that branch is rolled back, **Then** all associated derived state is reversed together, even if no replacement block has arrived yet.
2. **Given** an interrupted ingestion or repeated delivery, **When** processing resumes, **Then** the resulting state equals uninterrupted processing, without duplicated outputs, fees, deposits, or confirmations.
3. **Given** an existing installation, **When** it is upgraded and any required replay completes, **Then** it agrees with a fresh installation at the same chain point and preserves existing identities and operator configuration.
4. **Given** required external prerequisites or incomplete backfill, **When** the operator reviews readiness, **Then** the installation explains what is verified, what is missing, the affected data, and the documented backup/recovery procedure; local data migration is not represented as a remote-node update.
5. **Given** an existing Conway installation, **When** it is upgraded before Dijkstra activation, **Then** its configuration, historical data and supported operations remain usable without synthetic Dijkstra keys or state; incompatible or ambiguous evidence blocks only dependent writes.

### User Story 6 - Continue existing transaction workflows (Priority: P2)

As an operator or wallet owner, I need existing send, reward withdrawal, stake registration/delegation, pool configuration, and retirement workflows to produce correct Dijkstra transactions and trustworthy outcomes.

**Why this priority**: Era support must preserve existing operational tasks, not merely make the chain parser accept a new name.

**Independent Test**: Complete each existing funds- or certificate-changing workflow using isolated accounts and a supported tool combination; reconcile the result through the index.

**Acceptance Scenarios**:

1. **Given** a Dijkstra network and compatible tools, **When** an existing workflow constructs and submits a transaction, **Then** it uses the network's current rules for fees, deposits, refunds, validity, and authorization, and the confirmed identity matches the submitted identity.
2. **Given** either accepted Dijkstra submission representation, **When** it is inspected or reconciled, **Then** the same body retains its identity without confusing the author's submission with the producer's validity decision.
3. **Given** an explicitly disallowed submission representation or a standalone incomplete child, **When** submission is requested, **Then** it is not silently rewritten into a different authorized transaction or reported as accepted.
4. **Given** a tool that recognizes the era but lacks a required operation, **When** the operator starts that operation, **Then** JorManager reports the missing capability before any spend or remote configuration change.
5. **Given** a Conway network and a Dijkstra-capable installed binary, **When** an existing workflow is reviewed and submitted, **Then** Conway rules and qualified Conway capabilities remain in use; binary version alone does not select Dijkstra certificates or commands.
6. **Given** a reviewed preview and a block with an era different from the target network's cached current era, **When** the node is queried, **Then** dependent side effects are blocked while the query is pending or unavailable; a confirmed changed era invalidates the preview and requires applicable qualification and renewed review, while a same-era response does not invalidate an otherwise unchanged review merely because its provenance was refreshed.
7. **Given** a target-network node-derived current-era cache, **When** a matching-era block is observed, **Then** the cache is reused without a new era query; historical blocks are decoded by their own era and never determine the current era.

### User Story 7 - Preserve the full meaning of Dijkstra data (Priority: P2)

As an index consumer, I need Dijkstra's new authorization, account, script, certificate, and governance information to remain attributable and exact, even when it does not create a payment output.

**Why this priority**: A transaction can affect rewards, pool state, or governance without changing the ordinary UTxO balance.

**Independent Test**: Inspect independently produced examples covering each changed data family and compare amounts, identities, ownership, accepted/rejected status, and effective state with the reference results.

**Acceptance Scenarios**:

1. **Given** guards, required batch-level authorizations, newer scripts, or execution arguments on a parent or child, **When** they are indexed, **Then** their purpose and owner are preserved without mislabeling them as ordinary signers or merging unrelated execution arguments.
2. **Given** direct account deposits, withdrawals, and balance constraints at their ledger-defined checkpoints, **When** a valid batch is applied, **Then** account effects are kept distinct from payment outputs, registration deposits, and fees, and resulting balances match the reference result.
3. **Given** pool, stake, committee, representative, or governance changes at any permitted transaction level, **When** they are confirmed, **Then** their identity, order, deposits/refunds, and effective state follow the baseline, including restrictions on what children may register or remove.
4. **Given** the largest permitted amounts, binary asset names, optional values, and legacy-compatible output forms, **When** data is stored, queried, or displayed, **Then** there is no rounding, truncation, identity change, or loss of a meaningful distinction.

### User Story 8 - Create new Musashi nodes and pools (Priority: P1)

As a JorManager operator, I need to select Musashi Dōjō and create a new relay or a new stake pool on a managed host without manually assembling network configuration or replacing an unrelated installation.

**Why this priority**: Indexing an already running node is not sufficient network support; an operator must be able to bootstrap the network and pool through the supported product workflow.

**Independent Test**: Starting with a clean isolated JorManager installation and disposable host, discover the published Musashi files through the normal remote catalog, create and synchronize a relay, then create and register a new pool with disposable test funds and keys. Independently verify its network, registration, committee/key observations and running producer configuration.

**Acceptance Scenarios**:

1. **Given** a published complete Musashi configuration bundle, **When** JorManager refreshes its remote configuration catalog, **Then** Musashi is selectable and the complete matching network/configuration/genesis/topology set is available without manual file imports.
2. **Given** a compatible managed host and selected Musashi bundle, **When** the operator reviews and authorizes a new relay, **Then** JorManager installs the correct role-specific configuration, starts the node and reports actual synchronization on the selected network incarnation.
3. **Given** a synchronized Musashi environment, disposable funded accounts and a clean producer target, **When** the operator authorizes creation of a new pool, **Then** JorManager prepares or imports the required separate keys, registers the pool, configures the producer and exposes submission, confirmation, eligibility and loaded-key states separately.
4. **Given** a missing, corrupt, mixed-reset or incompatible configuration bundle, **When** discovery or node/pool creation is attempted, **Then** the affected option/action is unavailable with an actionable explanation and no partially provisioned node is reported ready.
5. **Given** a Musashi reset or published bundle update, **When** JorManager refreshes the catalog, **Then** existing nodes retain their selected network identity/configuration and the operator explicitly reviews any replacement, replay or resync rather than having production or other testnet nodes silently rewritten.
6. **Given** an existing supported Conway network, **When** nodes or pools are provisioned or monitored, **Then** no Musashi bundle, Dijkstra genesis or Leios credential is required; a separate Dijkstra network can operate concurrently without changing the Conway selection.

### Edge Cases

- Empty blocks, certificate-only blocks, several nested members, legal maximum-size batches, and malformed extra levels of nesting.
- Same output number under different member identities; the same child delivered on competing branches; a later transaction spending a child's output.
- Script-failed batches with collateral return; reference inputs shared where permitted versus repeated spending of an input; attempted spending of outputs created inside the same batch.
- Shared scripts but separately owned execution arguments and datums; older-script compatibility restrictions inside otherwise valid new-era batches.
- Missing, empty, and explicit-null values; required nonempty collections; duplicate identifiers; meaningful sequence order; unknown fields; structurally decodable but semantically rejected inputs.
- Unsigned values beyond signed 64-bit and display-safe integer ranges, negative mint quantities, empty/non-text asset names, and hashes that change if original serialized data is rewritten.
- Account creation/deposit/withdrawal ordering, constraints at exact interval boundaries, and attempts to withdraw funds deposited earlier in the same batch.
- Missing, invalid, reused, scheduled, expired, or removed Leios keys; eligibility without matching installed material; retirement canceled by re-registration.
- Leios announcements across epochs, insufficient certificate weight, keyless signers, missing referenced transactions, repeated certification, and abandoned announcements.
- Rollback before replacement data arrives, restart between ingestion stages, and uncertain resubmission outcomes.
- Musashi catalog publication with missing files, mismatched genesis hashes, unavailable downloads, incompatible binaries, stale cached catalog content and repeated network magic after a testnet reset.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Dijkstra qualification baseline MUST be the supported Musashi Dōjō network bundle and its actual ledger/node/consensus/CLI behavior, not independently selected local Cardano revision pins. Each Dijkstra qualification run MUST record exact upstream release/source/binary/configuration/genesis identities. Musashi updates or resets MUST trigger review of affected requirements and evidence; a floating upstream URL MUST NOT silently update an existing node. This baseline MUST NOT replace qualification or configuration of previously supported networks and eras.
- **FR-002**: The system MUST identify each block's actual era independently for history, ingestion and replay and retain previously supported era behavior; writes MUST use the target network's authoritative node-derived current-era cache. At startup and re-established target context the system MUST query a verified node for that network, not infer current era from persisted provenance. A block with an era different from the cached era MUST mark the cache `refresh_required` and trigger a fresh node current-era query; a matching block MUST reuse the cache. Only a successful matching-network node response MUST update current era; a historical or mismatching block MUST NOT overwrite it. Unknown eras MUST NOT be treated as Conway or Dijkstra.
- **FR-003**: The system MUST distinguish submitted transactions, block-included transactions, and nested members, accepting the baseline's permitted forms and rejecting its disallowed forms without changing authorized content.
- **FR-004**: The system MUST ingest the baseline's headers and complete block contents, preserving block identity, transaction order, auxiliary information, producer-recorded validity, and certificate/announcement provenance.
- **FR-005**: The system MUST index every permitted nested member, retaining its own identity, enclosing transaction, member order, and inclusion status. Nested support MUST NOT be restricted to top-level payments.
- **FR-006**: Every output MUST use its ledger-defined transaction identity and local output number; every spend MUST resolve against that same reference. Parent linkage MUST NOT replace a child's ledger identity.
- **FR-007**: Batch effects MUST reflect the reference processing order and aggregate accounting, applying fees and collateral at the proper level without independently charging children or requiring each member to balance.
- **FR-008**: A script-failed confirmed batch MUST apply only its ledger-prescribed collateral effects. Ordinary member outputs, asset changes, certificates, account changes, and governance effects MUST NOT become successful state.
- **FR-009**: The system MUST distinguish spending, collateral, and reference inputs, and MUST NOT infer permission to spend a same-batch output from the order in which members are inspected.
- **FR-010**: Transaction, child, output, script, metadata, and block identities MUST agree with the pinned reference; full-transaction commitments MUST NOT be substituted for transaction-body identities.
- **FR-011**: The system MUST preserve the exact values and meaningful ordering, binary content, presence, and ownership distinctions of every supported Dijkstra data family. A serialization round trip alone MUST NOT count as compatibility evidence.
- **FR-012**: Guards, required top-level guards, all supported script versions, datums, reference scripts, execution arguments, and execution limits MUST be distinguishable and attributable at the correct transaction level, including legacy-script restrictions.
- **FR-013**: The system MUST support transactions containing `directDeposits` (direct account deposits) at both top-level and child level, retaining exact amounts, member ownership, query visibility, and effective account credits even when no payment output is created. It MUST account for these deposits, withdrawals, balance constraints, donations, and registration deposits/refunds without conflating them with UTxO outputs or with each other; script-failed batches MUST NOT credit direct deposits, and rollback MUST reverse their effective state.
- **FR-014**: Stake, pool, committee, representative, voting, and proposal records MUST retain the baseline's permitted transaction-level ownership, ordering, identifiers, and registration/removal restrictions.
- **FR-015**: The system MUST interpret current-era protocol parameters relevant to transaction costs, limits, deposits, pool settings, script execution, and Leios eligibility; it MUST NOT apply obsolete enforcement merely because a legacy field remains present.
- **FR-016**: Leios key registration and rotation MUST use Musashi's pool lifecycle, proof-of-possession scheme, activation/age rules and retirement behavior. The system MUST follow actual bundle-specific key validation and retention rules and MUST NOT import BLS uniqueness/reservation enforcement from a different ledger revision.
- **FR-017**: Operators MUST be able to prepare or import Leios voting-key material and authorize its registration and installation for a specific pool, network, and managed producer. Existing pool updates MUST preserve the key unless an explicit supported key change was requested.
- **FR-018**: The system MUST distinguish key preparation, submission, confirmation, scheduled activation, current eligibility, installation, expiration, and failure. Registration alone MUST NOT be displayed as readiness to vote or guaranteed committee membership.
- **FR-019**: Signing material MUST remain protected under existing credential safeguards and MUST NOT appear in diagnostics or ordinary query responses. State-changing operations MUST expose their target and consequences before explicit authorization.
- **FR-020**: Failed or uncertain registration, submission, or remote installation MUST NOT be reported as successful. Uncertain outcomes MUST be reconciled before repeat submission; rotation MUST NOT prematurely remove still-needed material.
- **FR-021**: The system MUST distinguish an announced endorser block from adopted certified content and MUST index committed Leios transaction effects exactly once, with complete parent/child and chain provenance.
- **FR-022**: Missing committed Leios content MUST block a claim of complete indexing at that chain point. A delivery representation MUST NOT be assumed identical to the on-chain representation without verified node-contract evidence.
- **FR-023**: Structurally recognized but baseline-disabled features MUST remain visibly unsupported for ledger acceptance; the existence of a field MUST NOT imply feature activation.
- **FR-024**: Existing send, reward withdrawal, stake registration/delegation, pool creation/reconfiguration/retirement, and transaction reconciliation workflows MUST operate under Dijkstra using correct fees, deposits, refunds, and authorization.
- **FR-025**: Required capabilities MUST be checked for the actual node/tool combination before enabling each affected operation. An era command or version label alone MUST NOT establish compatibility.
- **FR-026**: Confirmed, pending, rolled-back, failed, stale, and unavailable states MUST remain distinguishable in wallet, pool, and transaction views. Pending locally submitted effects MUST NOT be confused with confirmed funds.
- **FR-027**: Parent/child effects, account and governance changes, key-registration state, and Leios inclusion records MUST roll back atomically with their chain point, including an explicit rollback notification before any replacement block arrives.
- **FR-028**: Duplicate delivery and restart recovery MUST produce the same indexed state as uninterrupted ingestion. Ingestion progress MUST NOT record a block as complete before all required effects are durable.
- **FR-029**: A child MUST NOT be independently resubmitted as a complete transaction. Reconciliation and any existing authorized resubmission behavior MUST preserve the complete authorized batch and avoid duplicate spending or false confirmation.
- **FR-030**: Upgrade and replay MUST preserve historical identifiers and existing configuration, and MUST converge with a fresh index at the same chain point. Operators MUST receive prerequisite, backup, replay, remote-update, and recovery instructions before activation.
- **FR-031**: For every changed data family, acceptance evidence MUST include independently sourced valid and rejected examples, expected identities and values, and applicable boundary cases. The evidence MUST identify its ledger and tool revisions and distinguish decoding from ledger acceptance.
- **FR-032**: Release readiness MUST include safe end-to-end evidence for direct and nested indexing, Leios inclusion and key lifecycle, existing transaction workflows, rollback, and upgrade. Mocked boundaries, generated local expectations, or documentation validation MUST NOT be represented as that evidence.
- **FR-033**: JorManager MUST offer creation of new Musashi relay nodes and new stake pools through its existing managed-host and node/pool workflows, with explicit target, network, role, funding and disruption review before authorization.
- **FR-034**: The feature MUST publish the complete required Musashi network files on JorManager's remote configuration server in the catalog format consumed by the application, including Dijkstra genesis and all referenced configuration, topology, peer and earlier-era genesis assets; supplying upstream links or requiring manual file copying alone MUST NOT count as completion.
- **FR-035**: Downloaded Musashi files MUST form a validated, coherent bundle with recorded provenance, network incarnation and compatible tool requirements. Missing, corrupt, mixed-bundle or incompatible assets MUST prevent node/pool activation, while existing non-Musashi catalog entries and nodes remain unaffected.
- **FR-036**: Musashi reset/update handling MUST distinguish a new network incarnation from a configuration refresh, preserve existing node selections and keys, and require explicit authorization for node reconfiguration, database resync/replay or replacement. Publication and local database migration MUST NOT imply that any managed node was upgraded.
- **FR-037**: Previously supported networks and eras, including live Conway provisioning, monitoring, transactions and pool registration/retirement, MUST remain available without Musashi configuration, Dijkstra genesis or Leios voting credentials. Concurrent networks MUST use independent network identity, era, capability and state evidence; missing or mismatched evidence MUST block only dependent writes, preserving unrelated supported networks and readable history.
- **FR-038**: Dijkstra-specific commands, certificates, configuration and features MUST activate only for the target network's current node-derived Dijkstra era and qualified operation capabilities, never from an installed binary version or global switch. Era freshness MUST be `current`, `refresh_required` or `unavailable`; initial unknown or failed query is `unavailable`, and retained non-current values are display-only. Pending, failed, unknown or wrong-network query evidence MUST block dependent writes, not unrelated networks or history. Source, point and time are provenance, not expiry; there MUST be no era TTL, periodic polling or unconditional query per write. Backend enforcement MUST revalidate cache state and applicable capability, parameter, state and credential evidence before side effects. A confirmed changed era MUST invalidate dependent capability/parameter assumptions, previews and authorizations, requiring applicable qualification and renewed operator review; a same-era refresh of provenance alone MUST NOT invalidate an otherwise unchanged review.

### Key Entities *(include if feature involves data)*

- **Compatibility baseline**: The supported Musashi network bundle for Dijkstra qualification, its observed component/configuration identities, source/proposal distinctions and independent evidence; other supported networks retain their own applicable evidence and paths.
- **Chain inclusion**: The adopted block and chain point, transaction position, era, producer-recorded validity, and any Leios announcement/certification relationship.
- **Transaction batch**: One top-level transaction and its ordered, non-recursive children; member identities, inputs/outputs, auxiliary data, and collective outcome.
- **Output reference and value**: A member's ledger identity and local output number, its address, assets, associated datum/script information, and spending or collateral outcome.
- **Account and governance effect**: A member-owned deposit, withdrawal, balance constraint, certificate, vote, proposal, donation, or resulting state transition.
- **Leios key lifecycle**: Pool identity, public voting key and proof, protected signing material, registration and eligibility epochs, retention requirements, and installation observation.
- **Synchronization and recovery state**: Last complete chain point, pending authorized submissions, incomplete content, rollback history needed for recovery, and verified versus unavailable operator state.
- **Network configuration bundle**: The published catalog selection, coherent public files, content digests, reset identity, source provenance and per-node installed snapshot used to bootstrap a relay or producer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For 100% of the approved per-era acceptance corpus, block membership, transaction/member identities, output references, assets, and effective balances agree with the independent reference, with zero silently omitted children.
- **SC-002**: Every changed data family has at least one accepted case, one rejected or disabled case where rejection is defined, and its applicable boundary cases; there are zero unexplained acceptance or identity disagreements before release.
- **SC-003**: Across at least 100 reference batches covering successful, script-failed, and Leios-certified inclusion, there are zero duplicate effects, false confirmations, or lost child-to-parent relationships.
- **SC-004**: After duplicate delivery, ordinary rollback, and restart before, during, and after ingestion commitment, the recovered installation agrees exactly with an uninterrupted fresh index at the same chain point.
- **SC-005**: A representative pool operator completes registration, ordinary pool update, rotation, and retirement on an isolated network using the documented workflow without editing serialized transaction data, and correctly identifies whether a key is merely registered, eligible, and installed.
- **SC-006**: All existing funds- and certificate-changing workflows listed in FR-024 complete successfully on supported combinations; all deliberately incompatible combinations fail before an unauthorized side effect, with an actionable explanation.
- **SC-007**: In every missing-content, disabled-feature, failed-installation, and uncertain-submission acceptance scenario, the user sees the correct incomplete or failed state rather than a healthy balance, successful action, or voting-readiness claim.
- **SC-008**: Upgrade rehearsal preserves all reference historical identifiers and operator configuration, requires no unplanned manual data repair, and documents a successful recovery procedure with zero exposure of signing secrets in captured evidence.
- **SC-009**: From a clean installation and disposable hosts, a representative operator creates a synchronized Musashi relay and a newly registered/configured pool through JorManager using only the published remote configuration catalog, without manual network-file editing or importing; observed network, key and registration states match independent node/ledger observations.
- **SC-010**: Every missing/corrupt/mixed-reset/incompatible configuration case prevents false readiness or unauthorized effects; catalog refresh and Musashi reset rehearsal leave existing non-Musashi nodes and previously selected bundles unchanged until explicit operator action.
- **SC-011**: In isolated concurrent Conway and Dijkstra scenarios, every previously supported Conway workflow in FR-037 remains usable without Musashi assets or Leios credentials, while each network selects its own qualified rules; pending, unavailable, unknown or wrong-network era evidence blocks only dependent writes and preserves unrelated operation and readable history.
- **SC-012**: In standalone Conway and Dijkstra application scenarios, Dijkstra-capable tools never select Dijkstra while the target is Conway. Controlled block observations and node responses demonstrate initial query, matching-block reuse, mismatching-block refresh, pending/failure/wrong-network blocking, same-era review preservation and confirmed-change invalidation until applicable qualification and renewed review; historical decoding remains per-block. Upgraded Conway state and historical identities remain intact, with no synthetic Dijkstra keys or provenance. No hard-fork orchestration or cross-era rollback test is required.

## Assumptions

- Musashi Dōjō is the Dijkstra qualification baseline only. Its official supported release/configuration determines the Dijkstra component versions; other previously supported networks and eras retain their own applicable configurations and qualification. Runtime evidence determines each target network's current era, not a binary version or fixed activation date.
- In scope: full interpretation/indexing of Musashi Dijkstra data families, nested transactions and `directDeposits`, existing transaction workflows, Leios key lifecycle, new relay and pool provisioning, remote configuration-server publication, affected views, migration and recovery.
- No arbitrary nested transaction composer, swap marketplace, intent-matching service, independent consensus engine, new smart-contract execution engine, or transaction-inclusion-proof product is requested. Understanding and indexing an on-chain construct does not require adding an authoring interface for every possible construct.
- JorManager consumes the supported node's adopted chain; it is not a replacement full validator. Independent reference validation establishes acceptance expectations, while JorManager must still reject malformed/unsupported boundary data and avoid asserting ledger acceptance from decoding alone.
- Previously supported historical forms and live-era paths remain supported under their applicable rules. This does not promise unqualified new live-era support. Unsupported future extensions are not silently ignored or treated as active. Peras activation and proposal-only extensions are not implied by Dijkstra naming.
- Existing credential/authorization safeguards remain in force. Public Musashi integration uses disposable hosts, keys and test funds; controlled failure and epoch experiments use a private environment based on the same Musashi bundle. Production funds/nodes are never fixtures.
- The network baseline is selected and Musashi configuration assets are now published. Qualification must exercise the available upgraded CLI/bulk-capable node and real delivery, validated configuration selection, producer and new-pool workflows. Remaining runtime evidence and immutable provenance requirements are explicit delivery work, not a reason to restore former local pins or omit workflows.
- No application behavior is changed by this specification. Documentation checks establish planning readiness only; FR-031 and FR-032 remain required before claiming implemented compatibility.
- The 100-batch minimum exercises repeated processing across validity and inclusion outcomes; it is not a throughput benchmark or exhaustive-validation claim. Coverage of every changed data family and applicable boundary remains mandatory regardless of sample size.
