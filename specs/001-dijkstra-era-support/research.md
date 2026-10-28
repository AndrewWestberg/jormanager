# Dijkstra Compatibility Research

**Date**: 2026-10-27  
**Feature**: [Dijkstra Era Support](spec.md)  
**Evidence status**: Source investigation plus read-only installed-tool diagnostics. No application changes, ledger execution, network submission, key generation, or production-node operations were performed. Reference tests cited below were read, not run.

## 1. Baseline and authority

**Decision:** Musashi Dōjō's supported deployed bundle and configuration are the Dijkstra qualification target, not independently selected ledger, CLI or node commits or a replacement baseline for other supported networks/eras. Qualify the exact bundle used for each Dijkstra run, capturing artifacts, versions, hashes, network configuration and revisions; do not silently float existing nodes to new bundles. B1 and B2 are Dijkstra execution gates for that selected bundle. The earlier standalone development-ledger Dijkstra baseline is superseded.

**Rationale:** the [official node guide][musashi] calls this a continuously rebuilt prototype and warns of configuration re-pins and chain resets; its [pool guide][musashi-pool] describes the Dijkstra/BLS producer workflow. Public Musashi smoke and private same-bundle fault and epoch exercises are necessary; neither source reading nor a release tag proves deployed compatibility. No node was started, pool registered or remote file published in this planning work.

**Alternatives:** independently pinning a newer ledger, trusting an era name or automatically updating nodes would combine unqualified components or break reproducibility. Substituting Conway or removing nested transactions, direct deposits or Leios would not meet scope.

| Evidence snapshot, not a compatibility mandate | Observation |
| --- | --- |
| [Musashi rc-2026w43 release][release], 26 October | Candidate pre-release; its notes describe certifying-view forging and LeiosDb resync; deployment not verified |
| [Release flake.lock][lock] and [node cabal.project][project] | Node `65dd69d103e41a749d428ffd05de153ef7c2ab45`; ledger `9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`; consensus `a337d69f4946d5a1b20ecaaeae635dd47822e3d2`; network `f0243fe6d962e922ab2c8e131543eba3d78748cb`; CLI `05b6d649ec02b223651ad2470b82891b53eb89fe`; API `b8023269a78db9eb19aea8a6fd037280ee6fc6cd`; base `c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c`. Source provenance only, not proof of the running public binaries |
| [Published config][public-config] and [pin script][pin-script] | At research time config names Dijkstra genesis, sets `TestDijkstraHardForkAtEpoch` 0 and `MinNodeVersion` `11.1.0.164-prototype-2026w42`; the guide states magic 164. Not a claim that rc-w43 is deployed |
| Local tools, updated 2026-10-28 | User reports CLI 11.6.0.0 with Dijkstra commands and `cardano-node-leios` 11.2.0.164, linux-x86_64, ghc-9.12, revision `746f1b3cbdaada2c3ed2f702b0946643248dd30b`, built with bulk BLS support. Current read-only help confirms registration `--bls-signing-key-file` and node singleton/bulk startup flags; see section 7 for evidence limits |

Proposal documents describe intent; deployed-bundle source and runtime evidence govern actual compatibility. When the supported bundle changes, repeat the source delta review and run qualification before rollout.

Previously supported Conway provisioning, monitoring, transactions and pool operations remain independent of Musashi assets and Leios credentials. Supported older history dispatches by each block's era. Writes use the target network's node-derived current-era cache and operation-specific qualified capability; binaries, dates and global switches cannot select Dijkstra. Pending or unavailable refresh blocks dependent writes; only a confirmed changed era invalidates dependent previews and authorizations, not a same-era provenance refresh alone. This preserves supported paths without promising unqualified new live eras. Standalone application scenarios with controlled observations suffice; orchestrating a fork or cross-era rollback is not a qualification requirement.

## 2. Transaction and block format inventory

The following are **candidate release source observations**, not results from running its decoder or ledger. Sources: [CDDL][cddl], [transaction][tx], [transaction body][body], and [block body][blockbody].

| Context | Candidate source encoding |
| --- | --- |
| Submitted top transaction | `[body, witnesses, auxiliaryData / nil]`; legacy `[body, witnesses, true, auxiliaryData / nil]` accepted; `false` rejected |
| Block transaction | `[body, witnesses, auxiliaryData / nil, isValid]`; producer flag mandatory and may be false |
| Child | `[childBody, witnesses, auxiliaryData / nil]`; no child flag or recursively nested batch |
| Block and body | `[header, blockBody]`; body `[transactions, leiosCertificate / nil, perasCertificate / nil]` |
| Header | Header version pair `[highestSupportedMajor, softwareTag]`, certificate-present flag and optional `[ebHash, ebSize]`; operational certificate group expansion makes flat offsets non-obvious |

Body keys 0/1 are inputs/outputs at both levels; top also requires 2 (fee). Optional keys 3/8 validity, 4 certificates, 5 withdrawals, 7 auxiliary hash, 9 mint, 11 script integrity, 14 guards, 15 network, 18 reference inputs, 19 votes, 20 proposals, 21 treasury, 22 donation, 24 required top-level guards, **25 directDeposits**, and 26 account intervals are represented at both levels. Keys 13/16/17 collateral, 23 ordered children and 27 starting-account intervals are top-only. Key 25 maps account address to coin, not arbitrary assets; retain, index, query, attribute and reverse these effects, not just recognize the key. Unknown keys, missing fields, empty collections, duplicate keys and invalid boundaries require selected-bundle decoder outcomes rather than generic map overwrite.

Both Alonzo array and Babbage map outputs are represented. Retain asset names as bytes, datum alternatives, scripts, tags, structured metadata and identity-sensitive original bytes. CDDL includes Plutus V4 and guard redeemer purpose; validate witness/redeemer duplicate, nonempty, tag, numeric and version behavior independently. Unsigned and signed quantities must not silently wrap; CBOR round trips alone do not establish ledger acceptance. The [Kotlin CBOR guide][kotlin] notes byte arrays require explicit byte-string handling, but no library migration is selected.

## 3. Nested semantics and identity

The [body source][body] represents children as ordered `OMap TxId (Tx SubTx era)` and memoizes bodies under the transaction-body identity; the [endorser source][endorser] separately represents EB references as an ordered map from full-transaction hash (`EraIndependentTx`) to `Word32` byte size, and memoizes a separate references hash. These identities are not interchangeable. Children retain body-derived IDs and local output indices; parent/position is provenance, not an invented output reference. Duplicate-child identity, including differing witnesses, must be checked against actual candidate decoder outcomes.

The candidate [LEDGER][ledger-rule], [SUBLEDGERS][subledgers] and [ENTITIES][entities] source should be used to construct independent fixtures for child order, original-versus-threaded state, phase-2 invalidity, aggregate value, fee/collateral, and member-owned certificates and metadata. ENTITIES validates field 26 against current accounts and field 27 against original accounts before withdrawals, certificates and direct deposits; child effects can therefore affect parent-entry current balances. Original-account withdrawal constraints prevent treating fresh deposits as freely withdrawable within a batch. Establish accepted/rejected same-batch spends, exact checkpoint boundaries, script sharing, child certificate combinations and legacy withdrawal behavior with the selected ledger; structure alone is not acceptance.

## 4. Leios key, block and parameter coverage

### Pool keys and lifecycle

The [pool guide][musashi-pool] requires a BLS signing key to construct a new Dijkstra pool registration; the CLI derives the possession proof. Candidate [CLI golden source][cli-pool] tests the mandatory option, but was not run. The CDDL allows a keyless pool representation, so historical decoding and new registration construction differ. A present public key and proof are 96 and 48 bytes respectively; they are distinct from KES/opcert and signing secrets.

Candidate [POOL][pool] verifies the proof and enforces minimum pool cost. Its uniqueness bookkeeping is for **VRF** hashes; do not assert BLS uniqueness or an aged-out BLS reservation rule based on later ledger sources. Re-registration updates future pool parameters, cancels retirement and retains the existing deposit. The [node dependency manifest][project] explicitly identifies the IETF `BLS_POP_` proof domain and contrasts an older `BLS_SIG_` domain: verify proof/tool compatibility with the selected bundle, never invent a cryptographic implementation.

Candidate [SNAP][snap] records a committee at mark and moves it to set; its maximum age is `ceil(maxKESEvo * slotsPerKESPeriod / slotsPerEpoch) + 2` epochs measured at mark creation. This is source behavior, not proof of pool selection, remote installation or active voting. Verify key rotation, future/current state, eligibility, expiration, rollback, and producer restart/reload at real boundaries.

### Delivery and certification

The [candidate body][blockbody] stores ordinary transactions and an optional certificate in separate positions. The [release notes][release] describe certifying blocks forged from a mempool view with the announced EB closure applied. Do **not** require certificate-only bodies or reject certificate-plus-ordinary-transactions. Certifying EB content and ordinary block transactions both need complete, ordered, exactly-once effects; actual cross-source order, certificate checks, announcement linkage, negotiated transport, closure availability and rollback need runtime and consensus-source qualification. The candidate [BBODY][bbody] does not contain the later ledger's LEIOSCERT transition; do not import that revision's signature, timing or empty-body rules.

In candidate consensus [CertifyingView][certifying-view], `buildCertifyingView` applies the announced closure before revalidating mempool transactions; [Shelley Leios integration][consensus-leios] resolves the entire closure from LeiosDB, fails on missing or undecodable content, and folds closure transactions into ledger state. Its `inlineLeiosClosure` replaces the body transaction sequence in a replay path; therefore preserve and test actual wire/replay delivery of both sources rather than assuming that one representation contains the other. These are source observations, not a captured node-to-client contract.

References are nonempty and distinct from EB transaction content; announcement or references alone cannot establish effects. Body hashing uses memoized encoded bytes in the candidate source, not a hypothetical extra MPT root. A Peras slot exists, but [validatePerasCert][blockbody] always returns false and [BBODY][bbody] rejects a non-null certificate under that validator: do not claim active Peras.

### Parameters and provisioning

Read full selected-bundle parameters and genesis rather than copying an unrelated revision's update-key table or guessed defaults. Preserve supported Leios/Peras parameters without inferring enabled Peras, and test fee, size, script and pool rules against the chosen bundle.

The [pin script][pin-script] inventories `alonzo-genesis.json`, `byron-genesis.json`, `conway-genesis.json`, `dijkstra-genesis.json`, `shelley-genesis.json`, `config.json`, `topology.json`, and `peer-snapshot.json`. New JorManager-managed Musashi nodes and pools are mandatory, including publication of all required downloadable files to the existing remote config server and verification of the actual server layout, names and served bytes. `EnvironmentMonitor.kt` fetches `https://bluecheesestakehouse.com/jormanager/index.html` every four hours, follows its links and overwrites DB files by basename; `NodeController.kt` renders DB-backed config and genesis files. The server owner, deployment location and publication access are not established by those observations. No external publication has occurred. Snapshot authoritative contents and hashes for each qualification, handle resets explicitly, and isolate existing node configurations from the monitor's floating updates.

## 5. Source conflicts and scope decisions

The earlier independent ledger research described LEIOSCERT, certificate-only bodies, BLS uniqueness/reservations and removed minimum-cost enforcement. Those assertions do not describe the candidate Musashi ledger and are withdrawn. CLI mandatory BLS is compatible with ledger decoding of historical keyless pools; schema and state-transition acceptance are separate questions. CIP examples are proposal intent, not authority to replace selected-bundle field numbers or transport contracts. Out of scope are an arbitrary nested composer, independent consensus/Plutus engine and active Peras unsupported by the qualified bundle; these exclusions do not reduce full decoding/indexing, direct deposits, new Musashi nodes and pools, or Leios operations.

## 6. Repository impact map

Paths below are relative to the repository root. This is an impact inventory, not a prescription to add a parallel framework.

| Surface | Observed implementation / risk | Required outcome / spec mapping |
| --- | --- | --- |
| `monitors/ChainMonitor.kt`; `nodeclient/protocols/handshake/{HandshakeProtocol,MsgProposeVersions}.kt` | Connects via TCP to managed node host/port; handshake offers versions 13/14 with initiator/peer-sharing data; runs KeepAlive, ChainSync and BlockFetch | Verify actual transport/era framing and EB data availability, not an assumed N2C adapter; FR-002,004,021–025 |
| `nodeclient/protocols/chainsync/{MsgRollForwardAdapter,ChainSyncProtocol}.kt` | Positional header parser groups all eras at/after Babbage; parser failure returns empty ('skipping'); rollback message sets protocol Idle without ledger rollback | Era-aware complete-header outcome; no silent gaps; immediate coherent rollback; FR-002,004,022,027 |
| `nodeclient/protocols/blockfetch/BlockFetchProtocol.kt` | Ignores outer era discriminator; old block element 1 is treated as body-map list, element 3 as metadata; only top-level inputs/outputs/mint traversed; parser failure exits process | Decode envelopes/nesting/validity and cert provenance with complete-state failure reporting; FR-003–014,021–023 |
| `utils/CardanoUtils.kt:calculateTransactionId` | Hashes `txBody.toCborByteArray()` after generic decoding | Prove original-byte fidelity for body/child IDs; do not use full-tx EB hashes as TxIds; FR-010–011 |
| `repositories/LedgerDao.kt:updateLiveLedgerState` | Reads first envelope element, top-level inputs/outputs only; combines transient pending UTxOs with chain UTxOs | Pending batch interpretation, distinct confirmation state and complete-batch reconciliation; FR-005–009,026,029 |
| `repositories/{LedgerDao,LedgerRepository}.kt`; `tables/LedgerUtxosTable.kt`; `utils/TransactionCache.kt` | UTxOs use `tx_id/tx_ix`; first fetched block triggers number-based rollback; rollback submission membership cache expires after one hour; pending data is in-memory | Existing output-reference shape can hold child IDs; add relationship/provenance and all required reversible state without inventing flattened IDs; restart/rollback evidence; FR-006,027–030 |
| Asset and metadata indexing within BlockFetch/LedgerRepository | Metadata selected by old block-level transaction index; token values/address filtering tailored to current wallet index | Child attribution, both output forms, exact binary asset identity and useful handling of supported addresses; FR-004–014 |
| `model/QueryTip.kt`; `controllers/BlockController.kt` | Era number stops at Conway; era-number comparisons also control operational calculations | Explicit Dijkstra recognition; preserve appropriate nonce/epoch/leader-log assumptions; FR-002,015,025 |
| `monitors/NodeMonitor.kt`; `moshi/adapters/PoolLedgerJsonAdapter.kt`; `model/{PoolLedger,PoolLedgerParams}.kt` | Hardcoded `conway query ledger-state`; pool parameter model only cost/margin/pledge | Current/future BLS registration and eligibility state, era-appropriate queries and failure reporting; FR-015–018,025–026 |
| `controllers/{NodeController,WalletController}.kt`; `controllers/utils/{WalletUtils,HostConnection}.kt` | CLI-driven construction/signing/submission; repeated certificate branches; Conway equality for pool-registration deposit option; Shelley registration conversion targets Conway | All create/update/retire/delegate/claim/send paths audited together; current fees/deposits and BLS policy; no secret leakage; FR-016–020,024–025,029 |
| `model/{CreateNodeRequest,ProtocolParameters}.kt`; `entities/Node.kt`; managed node configuration/files | Genesis slots currently through Conway; Leios signing material/configuration not represented by existing pool fields | Compatible node/genesis/key installation lifecycle, protected storage and observable remote outcome; FR-015–020,025,030 |
| `vue/src/views/Wallet.vue`, `vue/src/components/SendAdaModal.vue` and node forms/state consumers | Current balance/send/pool workflows lack Dijkstra batch and Leios lifecycle states | Expose provenance and pending/failed/stale/eligible/installed distinctions without claiming confirmation from submission; FR-005,018,026 |
| `src/main/resources/db/changelog`, operator `docs/` | Existing persistent index/configuration migrations and upgrade-guide conventions | Liquibase migration per constitution; replay/backfill/backup and remote steps must be separately documented; FR-027–030 |
| `build.gradle.kts`, existing backend/frontend tests | Google IoT CBOR is runtime; cardano-client-lib is test-only. Existing tracing test accepts a Dijkstra era string, not a Dijkstra ledger block | Reuse conventions, add independent fixture/contract evidence; an era string test is not compatibility proof; FR-031–032 |

Prefix for abbreviated backend paths is `src/main/kotlin/com/swiftmako/jormanager/`. No evidence establishes a separate external indexer: transaction ingestion and UTxO persistence are owned here. The feature cannot be completed solely by updating a dependency or a `QueryTip` constant.

## 7. Executed read-only diagnostics and evidence gaps

Historical local diagnostics from 2026-10-27, superseded by the update below (not the current installed CLI):

| Command | Observed result | Limit |
| --- | --- | --- |
| `cardano-cli version` | 11.2.3.1, git 80d559500fc2d71b457656e7f77a270314740500 | Not the candidate Musashi CLI |
| `cardano-cli dijkstra --help` | Dijkstra command group succeeds | Does not establish current ledger format support |
| `cardano-cli dijkstra node key-gen-BLS --help` | BLS key generation command advertised | No keys generated; does not prove registration/install capability |
| `cardano-cli dijkstra stake-pool registration-certificate --help` | No BLS signing/public-key registration option advertised | Insufficient for the required BLS registration workflow |

### Qualification update — 2026-10-28

- **User-supplied build facts:** local `cardano-cli` is now 11.6.0.0 with Dijkstra commands. `cardano-node-leios --version` was supplied as `cardano-node 11.2.0.164 - linux-x86_64 - ghc-9.12`, revision `746f1b3cbdaada2c3ed2f702b0946643248dd30b`, built with added bulk-credential support. These version/build facts are accepted from the user, not represented as version commands rerun here. Record this actual executable and its resolved dependencies in qualification; do not assume the older rc-2026w43 source table describes every component of this build.
- **Observed local capability:** `cardano-cli dijkstra stake-pool registration-certificate --help` now requires `--bls-signing-key-file` and advertises `--testnet-magic`. The old missing-registration-flag finding is resolved. `cardano-node-leios run --help` exposes `--shelley-bls-key` (single envelope or envelope array) and `--bulk-credentials-file`. Both commands were help-only: no keys, signing, registration or node startup occurred.
- **Located upstream work:** [cardano-node PR #6749](https://github.com/IntersectMBO/cardano-node/pull/6749), “Support optional per-pool BLS keys in bulk credentials,” targets `leios-prototype` and was open when read. Its documented loader contract accepts `[opcert, vrf, kes]` and `[opcert, vrf, kes, bls]`; the fourth element is an embedded signing-key text envelope or array, not a filename. Omitted/null/empty BLS means no keys. Pool association and key order are preserved; malformed entries/invalid keys fail rather than returning partial credentials. The PR reports library/test/production-loader smoke success; those are author-reported upstream results, not JorManager qualification or tests executed here. Existing bulk generators are unchanged, so JorManager must emit the extension. No claim is made that this PR has merged.
- **Observed publication:** the [JorManager catalog](https://bluecheesestakehouse.com/jormanager/index.html) now lists Musashi alongside the existing networks. All eight linked `musashi-*.json` assets returned HTTP 200 and parsed as JSON. Five config genesis references and topology `peerSnapshotFile` resolve to those published names. Shelley magic is 164 and system start is `2026-10-18T00:00:00Z`; config still starts Dijkstra at epoch zero. Published genesis paths and the peer snapshot path are prefixed; installation must either retain those names or normalize every reference consistently.
- **Publication limits:** the observed row uses stable `musashi-*` basenames and has no linked manifest or content-versioned filenames. Publication/download availability is established; the planned immutable bundle/provenance contract and application selection still need completion. SHA-256 below fingerprints downloaded bytes only; node-specific genesis hash validation, upstream equivalence, reset safety and actual JorManager provisioning have not been exercised.

| Downloaded catalog asset | SHA-256, observed 2026-10-28 |
| --- | --- |
| `musashi-config.json` | `351503f8163d77aa2c81f51ec9229a5ad44e14af8203189e8b62c5dc6eda5621` |
| `musashi-byron-genesis.json` | `e446be2aa24aa327b0cbbff57b3ab3f8dfe793959809f3b31973c7945bc64e4b` |
| `musashi-shelley-genesis.json` | `0c7365b3e74a52a09d1ad169438b714919dd36710c9feed8e49be65e73990ab7` |
| `musashi-alonzo-genesis.json` | `5e5b1f0776c6d84bcd15bc7df4f9368884b7c433296657e54722175ce71f1318` |
| `musashi-conway-genesis.json` | `9a7bdb6f68c6672a8afa202e8cfc6fdf40b00de9cb9c27cbcec784cdc6deae0e` |
| `musashi-dijkstra-genesis.json` | `9895c096a45225e99630b3fb866e5b115ce3d28c6ecd45ceda8feb0e29306127` |
| `musashi-topology.json` | `1420c577beea1ab8889deec1cbe607820cba13b12aff5f5db2ea3420aa1e7678` |
| `musashi-peer-snapshot.json` | `a4aa1b9fbeb34725b0ededd9f9703ca011341b9f62c30a435ac3f8f3f0133672` |


**Compatibility recommendation:** use the upgraded CLI's `dijkstra` command family and the supplied `cardano-node-leios` build as Dijkstra qualification inputs only when the target's authoritative observed era is Dijkstra. Missing CLI registration syntax and missing node bulk-loader implementation are no longer the blockers. Actual proof/registration, pool-specific bulk generation/loading, sync and lifecycle integration remain to be exercised; never substitute Conway for Dijkstra or infer an era or compatibility from a version alone.

Remaining prerequisites for implementation/release evidence, with the work needed to clear them:

1. **B1: selected-bundle delivery** — capture bundle/build/config provenance and public Musashi smoke; on a private same-bundle network establish negotiated era tags, header hashing, transport, EB closure availability and adoption order including cert plus ordinary transactions. Do not assume N2C inlining from proposal prose.
2. **B2: operator boundary** — exercise the available upgraded CLI and bulk-capable node through JorManager on disposable infrastructure, including proof domain, per-pool embedded bulk keys/order, rotation, snapshot eligibility and loaded producer state. The relevant help/PR contracts are available; end-to-end application evidence is still missing.
3. **Independent conformance corpus** — obtain bytes, hashes and acceptance/rejection outcomes from the selected bundle's ledger and CLI; candidate [pool registration][cli-pool] source is a fixture lead, not an executed result.
4. **B3: provisioning/publication completion** — use the now-published eight-file Musashi catalog as the input; complete the planned immutable provenance/manifest publication and validated application snapshots, then create a new relay and pool through JorManager. Obtain publisher coordination only for remaining publication changes. Test unavailable, stale, reset and partial-publication paths without silently updating existing nodes. See [network configuration contract](contracts/network-configuration.md).
5. **End-to-end index and upgrade** — exercise real consumers, migration/replay and restart/rollback, plus private epoch and fault scenarios with the same bundle. Exercise standalone Conway and Dijkstra application paths and mixed-network isolation. No application runtime compatibility is claimed here.

These are named technical evidence dependencies with a conservative support policy, not deferred optional feature scope. Planning can proceed; production readiness cannot be asserted until they are cleared.

## 8. Required future verification inventory

The implementation plan must turn these into selected-bundle revision-snapshotted inputs and observable expected outputs. This table is an evidence inventory, not a report of passing tests.

| Family | Accepted/boundary evidence | Rejected/failure evidence | Requirements |
| --- | --- | --- | --- |
| Envelope / era | New and transitional submission, block valid/invalid, child, historical per-era decoding, standalone Conway and Dijkstra profiles | Wrong flag value/position, arity, missing required body fields, wrong/unknown era | FR-001–004,025,037–038 |
| Nested identity / ordering | Parent and multiple child output-zero references; later spending of child output; child-owned metadata; ordered effects | Duplicate child IDs with differing witnesses; nested child; child fee/collateral/starting interval; same-batch spend attempts and double spends | FR-005–011 |
| Value / validity | Individually unbalanced members with balanced batch; asset mint/burn; reference inputs; correct collateral return | Batch imbalance, size/limit violations, script failure without ordinary effects, invalid reference/spend overlap | FR-007–009,015 |
| CBOR fidelity | Tags 24/258/259/30, structured keys, unsigned maxima, binary/empty asset names, both output styles, definite/indefinite forms | Duplicate sparse/redeemer/witness keys, prohibited empty collections, bad tag/type/length, overflow, truncation and trailing data per reference decoder | FR-010–012,031 |
| Scripts / guards | Purpose 6, V4 witness/reference/aux forms, shared scripts with distinct member datums, allowed legacy mode | Flat/empty redeemers, missing/extra pointers, invalid guard datum presence, incompatible old-script contexts, same-batch output script assumptions | FR-011–012,031 |
| Accounts / governance | Partial withdrawals where permitted, direct deposits after registration, exact/lower/upper interval boundaries, child votes/proposals/certificates | Wrong-network or missing accounts, withdrawing fresh same-batch deposits, legacy withdrawal mismatch, forbidden certificate/rule combinations | FR-013–015 |
| BLS pool lifecycle | No-key historical decode; valid key/proof; re-registration; scheduled rotation; retirement/cancellation; age boundary | Invalid proof/length or incompatible proof domain, VRF collision, unsupported CLI, failed install, uncertain outcome, rollback; determine BLS reuse outcomes from selected bundle rather than assume uniqueness | FR-016–020,024–025 |
| Leios body / EB closure | Direct block; certificate with ordinary transactions and EB closure; ordered references and full content; exact-once adoption and announcement replacement | Missing/duplicate references, unavailable closure and malformed certificate; establish selected-bundle actual timing, signer, announcement and signature rejection outcomes | FR-004,021–023 |
| Disabled/proposed extensions | Null Peras slot and preserved parameter values | Non-null Peras ledger acceptance; proposal-only fields or MPT assumptions | FR-001,023 |
| Recovery / operators | Fresh versus upgraded state; at least 100 reference batches; restart at commit boundaries; complete existing transaction workflows | Rollback with no replacement, duplicate delivery, missing committed EB content, stale view, repeated uncertain submission, secret disclosure | FR-026–032 |
| New Musashi provisioning | Publish/fetch five genesis assets plus config/topology/peer data and provenance, then create new relay and pool; public smoke and private same-bundle reset/epoch exercises | Missing, partial, stale or hash-mismatched files; mixed-reset assets, unavailable publication, unsupported producer startup, false registration/readiness | FR-033–036; SC-009, SC-010 |
| Coexisting networks | Standalone Conway workflows and Dijkstra qualified workflows; Dijkstra-capable tools on Conway; independent network configurations and historical decoding; deterministic node-query cache and refresh boundaries | Pending/unavailable/wrong-network evidence blocks dependent writes only; confirmed changed era invalidates dependent assumptions and previews, same-era provenance refresh alone does not; no synthetic Dijkstra migration state | FR-037–038; SC-011, SC-012 |

For all future gates, capture actual commands, fixture provenance, observed outputs, safe environment, owner, decision point, and unresolved blind spots. Ledger decode/encode agreement and ledger-state acceptance are separate evidence. Local round trips, copied expectations and mock echoes are not independent evidence. Final runtime checks must include the actual operator surface and real node/CLI boundary without production funds.

## 9. Documentation quality gates and Observatory consultation

Consulted the mounted Software Observatory MCP tools on 2026-10-27:

- `suggest_sensors` with documentation/specification completeness, source-provenance, nested-indexing and credential risks; six keyword-ranked results. Suggestions were treated as candidates, not an authored gate selection.
- `list_families` returned Structural, Behavioral, Test Effectiveness, Invariants, Adversarial, Runtime, Change, Architecture, Evolution and Human Comprehension.
- Full `get_sensor` entries read for **SO-010b Documentation Drift** and **SO-010d Decision Provenance**.
- `stack_coverage` on those IDs returned Human Comprehension only; two low-oracle retrospective sensors, with static-analysis and production-behavior stack labels. Those catalog labels are not claims that production behavior was exercised here.

| Selected sensor / gate | Guidance applied, oracle and independence | Procedure, pass rule, owner and decision |
| --- | --- | --- |
| SO-010b / source-claim consistency | Date claims; compare prose against reality; prioritize consequential drift; do not merely refresh dates. External pinned sources and actual CLI help are independent of this draft, but static reading does not prove runtime compatibility | Author compares every protocol family and conflict table with cited source sections; verifies local paths and document structure. Pass before specification completion: no unsupported behavior stated as observed, no contradiction hidden, no implementation success claimed |
| SO-010d / decision provenance | Record why, alternatives and limitations; template filling is not provenance. Source history/revisions explain the compatibility choice, but cannot prove that the chosen runtime works | Author traces baseline, exclusions, boundaries and acceptance dependencies back to evidence. Pass before specification completion: per-run immutable bundle snapshot, source precedence, rejected proposal assumptions, and exact remaining prerequisites are recorded |

Uncovered catalog families: Structural, Behavioral, Test Effectiveness, Invariants, Adversarial, Runtime, Change, Architecture and Evolution. They are not waived for implementation; they are excluded from this documentation-only gate selection because no executable behavior changes. A lightweight structural/link check supplements the two selected sensors without being misrepresented as independent functional proof. Before implementation, repeat MCP selection for codec differential/invariant/adversarial risks, real node integration, database upgrade/rollback, and credential/operator workflows. No fixed number of green checks establishes correctness.

## 10. Specification workflow record

- No `.specify/extensions.yml` was present during the initial hook check; no pre-hook was dispatched.
- `.specify/init-options.json` uses `feature_numbering: sequential`; no existing `specs/` directories or explicit feature-directory override were present. Selected `specs/001-dijkstra-era-support`.
- Active template resolution executed through `.specify/scripts/bash/resolve-template.sh spec-template --json`; resolved the core `.specify/templates/spec-template.md`, copied before drafting. Constitution 1.1.2 was read.
- `.specify/feature.json` records the actual directory. `check-prerequisites.sh --paths-only --json` successfully returned that feature directory and spec path independently of any new branch creation.
- The separate [requirements checklist](checklists/requirements.md) records document quality validation. Runtime conformance remains future implementation evidence, not a condition already met by this research.

## 11. Phase 0 planning decisions

Planning consultation date: 2026-10-27. The prior source investigation above is retained, not represented as newly executed ledger evidence. Two read-only research slices inspected (a) ingestion/persistence/recovery and (b) operator messages/credentials/deployment. No application builds, tests, node operations, signing or submission were run. The decisions below resolve application design choices; B1/B2 remain explicit external qualification blockers, not optional scope reductions.

### R1 — Keep the existing platform and message transport

- **Decision:** Kotlin 2.4.20 / Java 21, Spring Boot 4.1.1, PostgreSQL, Liquibase 5.0.4, Exposed 1.5.0/JPA, Google IoT CBOR 0.5.0-NEWM, and Vue 3/TypeScript/Vite remain the application stack. Preserve SockJS/STOMP commands and the existing shared response topic; use decimal strings for changed exact-value contracts.
- **Rationale:** `buildSrc/src/main/kotlin/Versions.kt`, `build.gradle.kts`, `vue/package.json` and `spring/config/WebSocketConfig.kt` establish the conventions. Node.js >=24 is required by the frontend; JUnit 6.1.3 runs through JUnit Platform, with MockK/Truth and Vitest already present. Current operational APIs are not REST.
- **Alternatives considered:** a new REST service, external indexer, Kotlin serialization replacement or additional database would introduce unneeded boundaries. None is justified by this feature. Existing CBOR use does not prove fidelity: raw-slice/token validation must establish duplicate/presence/tag/structured-key behavior before generic object conversion.

### R2 — Preserve raw identity bytes and complete member semantics

- **Decision:** use era/context-specific typed decoding with retained byte spans, body-ID/full-transaction-hash type separation, top/member records and typed member-owned effects. Use the current CBOR dependency for supported value decoding and a bounded token/span boundary for identity-sensitive parsing; do not use object re-encoding as the identity oracle.
- **Rationale:** the present BlockFetch parser flattens top-level UTxOs and `calculateTransactionId` hashes re-encoded maps. Current tables cannot represent child ownership, certificate outcomes or full inclusion provenance. Original bytes and explicit semantic interpretation are both required.
- **Alternatives considered:** flattening children into the parent breaks output references; raw-only storage fails query/state requirements; annotations or local round trips fail to establish compatibility. A codec dependency replacement is not selected; any necessary change must first demonstrate an actual unsupported primitive and be reviewed against the same corpus.

### R3 — One durable adoption/rollback boundary per network

- **Decision:** separate observed headers from complete indexing; acquire closure before commit; serialize adoption/rollback with a delivery generation; commit effects, reversible changes and progress together; publish/cache only after commit. Reuse existing UTxO tables as the maintained projection and remove the obsolete writer on cutover.
- **Rationale:** ChainSync currently persists headers independently; its `MsgRollBackward` branch does not reverse the ledger. `LedgerDao.commitBlocks` wraps database work but also mutates process state and triggers external work. Block-number rollback and a one-hour transaction cache cannot establish branch identity or restart safety.
- **Alternatives considered:** rollback on first replacement block misses explicit rollbacks without replacement; keeping both old/new ingestion writers introduces divergent authority; synthetic historical completeness invents absent bytes/validity/order. Liquibase plus verified replay is required, retaining existing IDs and configuration.

### R4 — Separate key lifecycle dimensions and authorized operations

- **Decision:** encrypted signing-file storage plus a public key/lifecycle record; durable reviewed operation/whole-batch submission record; independent on-chain registration, snapshot eligibility and installed/loaded observations. Reuse existing producer/wallet GUI workflows and add focused Leios actions; do not invent a BLS reservation state.
- **Rationale:** `WalletUtils` encrypts secrets using the spending password; NodeController/HostConnection already own CLI/SSH side effects. The response topic is shared, so no key or credential may be echoed. Ordinary pool updates must preserve future as well as active key intent; KES and Leios keys are unrelated. A preview must bind the exact target and economic/disruption consequences before authorization.
- **Alternatives considered:** a nullable key on Node loses rotation history; marking registration as readiness ignores snapshot and host state; a new crypto implementation is unnecessary. Proof/secret relation checking and certificate construction must use qualified tooling, and producer load checks require a verified node contract.

### R5 — Bound resource ownership without inventing a benchmark

- **Decision:** retain bounded acquisition/decoding, one network commit authority, protocol-parameter limits and cancellable owned connections/jobs. Store byte spans without avoidable copying and only widen numeric fields that need it. Measure legal maximum batches/EB closure during qualification; no throughput SLA is added.
- **Rationale:** the specification requires complete indexing and a 100-batch correctness sample, not an arbitrary transactions-per-second objective. Size/resource rejection must use actual negotiated/protocol limits, not silently truncate content.
- **Alternatives considered:** unbounded buffering risks availability; a new benchmark target or unrelated profiling framework does not resolve the compatibility risks.

### R5a — Event-driven node-derived current-era cache

- **Decision:** Initialize each network's cache from a verified node query at startup and re-established target context. Matching-era blocks reuse it; a differing block marks `refresh_required` and triggers a fresh node current-era query. Only a successful matching-network node response updates it. `current`, `refresh_required` and `unavailable` express authorization freshness; initial unknown or failed query is unavailable and retained non-current values are display-only. Source, point and time are provenance, not expiry. Revalidate cache state before dependent side effects, retaining all separate capability, parameter, UTxO/index, credential and key validity checks.
- **Rationale:** A block can be historical and is not authoritative evidence of the node's current era. The node response resolves a noticed difference; pending, unknown, failed or wrong-network responses cannot authorize writes. Confirmed changed era invalidates dependent capability/parameter assumptions and previews until applicable qualification and renewed review. A same-era response changes provenance alone and preserves an otherwise unchanged review. Other networks and historical decoding remain independent.
- **Alternatives considered:** Treating block era as current would confuse historical replay with current rules; wall-clock TTL or periodic polling would replace the specified observation trigger with time; querying unconditionally for every write adds needless repeated external calls. Persisted provenance alone cannot initialize a live target context. This decision specifies behavior, not a claim of runtime qualification.

### R6 — External bundle qualification remains blocked, not guessed

- **Decision:** qualify the supported deployed Musashi bundle as a coherent set. B1 proves its delivery and B2 its operational boundary; neither seeks a release matching an independently selected old ledger commit. Record per-run source and artifact snapshots, but do not float existing nodes.
- **Rationale:** the [release manifest][project] and [release notes][release] provide an identifiable candidate, not evidence of the deployed binary, EB delivery, operator key loading or remote publication.
- **Alternatives considered:** Conway/latest substitution, floating configuration or adopting source claims without runtime tests would fabricate support. Source compatibility alone cannot prove EB closure.
- **Required evidence:** resolved bundle and config hashes, public smoke, private same-bundle delivery/fault/epoch runs, conformance fixtures, real key/query/deploy observations, and publication plus retrieval of remote config artifacts. None was executed during planning.

### R7 — Publish Musashi through the existing configuration catalog

- **Decision:** use `https://bluecheesestakehouse.com/jormanager/index.html`, publish a coherent immutable bundle with all referenced assets, and extend current file-options/creation flows for Dijkstra genesis and node-selected snapshots. New relay and pool creation are required acceptance paths, not optional manual setup.
- **Rationale:** as of 2026-10-28, the public catalog supplies all eight Musashi assets under stable basenames. Current file caching overwrites by basename and creation derives config/topology from the chosen Byron filename. A bundle-level selection and immutable installed snapshot still prevent mixed-reset genesis or automatic changes to existing nodes; planned manifest/versioned publication remains distinct from already completed asset availability.
- **Alternatives considered:** direct upstream URLs or manual imports alone fail the requested remote-server workflow; a second configuration service duplicates the existing catalog; mutable shared genesis/config rows would make refresh an implicit node update.
- **Verification:** publication availability and eight-file JSON/reference checks are now observed in section 7. B3/G6 still require immutable provenance, node-hash validation, application retrieval/selection, new relay sync, new pool registration/producer startup and safe failure/reset observations. SO-002c, SO-005d and SO-010b from the dated MCP consultation below remain applicable; no new MCP execution, upstream test rerun or JorManager deployment is claimed.

## 12. Planning quality-gate consultation

Fresh mounted MCP calls on 2026-10-27:

1. `suggest_sensors` described funds/credential safety, independent ledger comparison, nested accounting, node/CLI/SSH contracts, atomic rollback/restart, Liquibase upgrades and GUI understanding; limit 8. Candidates included SO-004b, SO-010, SO-007b, SO-010b, SO-011d, SO-008c, SO-014b and SO-004. Rankings were keyword-based, not repository-specific recommendations.
2. `list_families` returned all ten evidence families.
3. Full `get_sensor` entries read for SO-004b, SO-002c, SO-010, SO-010b, SO-005c and SO-005d. Integration/differential/fault sensors were deliberately selected for the concrete boundaries rather than selecting only top keyword matches.
4. `stack_coverage` with those six IDs returned Invariants, Behavioral, Human Comprehension and Adversarial; no unknown IDs. Stack labels were production-behavior (2), integration-tests (1), behavioral-tests (1), property-metamorphic (1), canary-shadow (1). These catalog labels are not runtime evidence from this planning run.

| Sensor | Guidance applied; oracle, independence, scope and latency | Actionability and blind spot |
| --- | --- | --- |
| [SO-004b](https://softwareobservatory.com/sensors/database-invariants/) | Named FK/unique/check constraints give high-strength database-enforced readings, independent of application checks; system scope, minutes | Roll back impossible writes and fix the cause; do not disable constraints to pass migration. Only expressible structural properties are covered, not ledger acceptance |
| [SO-002c](https://softwareobservatory.com/sensors/integration-tests/) | Real dependency boundaries, including browser E2E; high oracle for a concrete mismatch but low independence when the same team writes expectations; service scope, minutes | Classify environment versus boundary failure; mocks do not prove node/CLI/SSH/database integration, and retries cannot turn flaky evidence into a pass |
| [SO-005c](https://softwareobservatory.com/sensors/differential-testing/) | Independent implementation comparison; high oracle for disagreement, not automatic proof the reference is right; function scope, minutes–hours | Retain divergent bytes and judge against the pinned baseline; do not narrow/exempt the corpus after failures. Shared bugs remain invisible |
| [SO-005d](https://softwareobservatory.com/sensors/fault-injection/) | Externally induced restart/disconnection/failure against explicit invariants; high independence, medium oracle; system scope, minutes | Record exact fault and broken invariant; test commit boundaries and meaningful unavailable paths. Unknown failure modes remain outside the experiment |
| [SO-010](https://softwareobservatory.com/sensors/independent-review/) | Independent human review of callers/schema/spec/security and operator interpretation; medium oracle, high independence; module scope, hours | Require concrete objections and evidence-backed resolutions, not approval counts. Expertise and uninspected paths limit confidence |
| [SO-010b](https://softwareobservatory.com/sensors/documentation-drift/) | Compare dated claims with source/runtime and keep proposed versus observed separate; low oracle, independent artifact comparison; module scope, days | Fix consequential runbook drift; matching prose and templates cannot establish functional compatibility |

Uncovered families: Structural, Test Effectiveness, Runtime, Change, Architecture, Evolution. Risk-based exclusions: existing builds/typechecks and document checks still run where applicable but are not independent protocol evidence; no coverage percentage or mutation score is substituted for external fixtures; operational freshness is checked in the actual UI scenarios rather than adding a new telemetry project; isolated fresh/upgrade comparison covers this cutover without production canaries; existing architecture is retained and reviewed without a new architecture metric; no historical defect dataset supports an evolution gate. These exclusions do not waive constitution requirements. Reassess selection if implementation exposes new failure modes.

The selected sensors become concrete owner/decision/pass-fail gates in [plan.md](plan.md). Required missing evidence stays blocked, never green. Source review, mocks, local encode/decode loops and multiple checks sharing JorManager-generated expectations cannot substitute for independently sourced conformance and actual operator-boundary evidence.

[musashi]: https://leios.cardano-scaling.org/docs/testnet/getting-started/
[musashi-pool]: https://leios.cardano-scaling.org/docs/testnet/register-stake-pool/
[release]: https://github.com/input-output-hk/ouroboros-leios/releases/tag/rc-2026w43
[lock]: https://github.com/input-output-hk/ouroboros-leios/blob/rc-2026w43/flake.lock
[project]: https://github.com/IntersectMBO/cardano-node/blob/65dd69d103e41a749d428ffd05de153ef7c2ab45/cabal.project
[public-config]: https://book.play.dev.cardano.org/environments-pre/leios/config.json
[pin-script]: https://github.com/input-output-hk/ouroboros-leios/blob/rc-2026w43/testnet/pin-config.sh
[cddl]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/cddl/data/dijkstra.cddl
[tx]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs
[body]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs
[blockbody]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/BlockBody/Internal.hs
[ledger-rule]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Ledger.hs
[subledgers]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubLedgers.hs
[entities]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Entities.hs
[pool]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Pool.hs
[snap]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Snap.hs
[bbody]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Bbody.hs
[endorser]: https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Block/Endorser.hs
[certifying-view]: https://github.com/IntersectMBO/ouroboros-consensus/blob/a337d69f4946d5a1b20ecaaeae635dd47822e3d2/ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/CertifyingView.hs
[consensus-leios]: https://github.com/IntersectMBO/ouroboros-consensus/blob/a337d69f4946d5a1b20ecaaeae635dd47822e3d2/ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Leios.hs
[cli-pool]: https://github.com/IntersectMBO/cardano-cli/blob/05b6d649ec02b223651ad2470b82891b53eb89fe/cardano-cli/test/cardano-cli-golden/Test/Golden/Dijkstra/StakePool/RegistrationCertificate.hs
[kotlin]: https://kotlinlang.org/docs/serialization-cbor.html
