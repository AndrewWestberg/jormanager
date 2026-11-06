# Compatibility and fixture evidence contract

**Status:** Dijkstra qualification evidence contract; B1/B2/B3 and G1–G6 remain open. Catalog observations do not establish runtime compatibility.

## Runtime manifest

Record each immutable Dijkstra qualification run and a separate supported Conway profile with actual network, genesis, configuration, node/CLI, observed era and applicable operation evidence; do not fabricate Dijkstra values.

| Field | Required content |
| --- | --- |
| `baselineId`, `ledgerRevision` | Selected network/configuration identity and observed upstream ledger revision, not former local pins |
| `components` | Official supported Musashi release and its resolved ledger/node/consensus/network/cardano-api/CLI source/build identities, binary hashes and dependency/toolchain provenance; do not mix individually selected versions |
| `network` | Magic, system start and genesis bytes/hashes, epoch/KES/protocol settings; identify private scenarios and overrides |
| `delivery` | Actual transport, negotiated version, era tags, exact framing/header commitment rules, direct-block acquisition, EB referenced-content acquisition and adoption point; immutable capture hashes and replay instructions |
| `operations` | Separate observed support for ordinary send/withdraw/register/delegate/pool update/retire, submission forms, BLS generate/import/proof validation/registration/rotation, pool-state/snapshot queries and producer install/reload |
| `nodeConfiguration` | Required genesis/configuration keys, managed producer and relay roles, secret-file type/path resolution, permission requirements, restart/reload procedure and observable loaded-key fingerprint contract |
| `selectedConfiguration` | Actual selected file origins and digests, node identity, effective role-specific config and independent relay/pool evidence |
| `evidence` | Actual commands/scenarios, sanitized stdout/stderr/results, timestamps, expected versus observed values, environment and reviewer; distinguish source reading, decoding, ledger acceptance, node acceptance and adopted confirmation |
| `unsupported` | Baseline-disabled Peras and proposal-only behavior, deliberately incompatible combinations, exact failure reasons; missing evidence is `unverified`, not `supported` |

A version label, CLI help, compile success, source golden file, empty response or mock transcript cannot alone mark an operation verified. A missing closure or unsupported operation blocks that operation before any spend or remote mutation. Remote/local target observations are distinct.

Era and capability evidence is per-network and current; initialization and event-driven refresh follow [operator-messages.md](operator-messages.md). Dijkstra qualification cannot authorize Conway or vice versa.

## Fixture record

Each approved fixture has an immutable ID and content digest. Store raw binary fixtures and machine-readable manifests in the backend test resources during implementation; do not put disposable signing secrets in them.

Required fields:

- source repository/revision or independent generator revision, exact generation command and license/provenance;
- runtime manifest ID, ledger/protocol version and transaction context (`submission`, `block`, `child`, `header`, `endorser`, `certificate`, `parameters`, `snapshot`);
- raw bytes and SHA-256 artifact checksum, with exact body/header/full-transaction/EB hashes identified by hash type;
- necessary pre-state/UTxO/accounts/protocol parameters/snapshot context and its digest;
- separate expected structural decode result and expected ledger acceptance/rejection with rule/failure identity; if phase-2-invalid adoption is legal, record that separately from phase-1 rejection;
- expected member IDs, parent relationships, ordered positions, own metadata, typed input references, output references/values, effective collateral, account/certificate/governance effects and resulting reference state;
- applicable boundary/rejection category, requirement IDs, inclusion mode and safe replay instructions;
- reference executable observation, JorManager observation and explained comparison outcome. JorManager must not generate its own expected identities or balances.

Use research section 8 as the changed-family inventory. Cover absent/null/empty, duplicates, tags, structured keys, binary names, both output forms, numeric boundaries, scripts/purposes, member ownership, `directDeposits` checkpoints, pool-key proof/age and disabled fields. Determine key reuse from Musashi's actual rules; do not invent BLS reservation enforcement. Schema-readable unsigned values are not automatically valid in every ledger state.

## Stateful scenarios

A scenario bundles fixtures into an ordered sequence of adopted delivery, explicit rollback, duplicate delivery, missing content, recovery and epoch transitions. Record independent expected state at every commit point. Include:

- Historical per-era replay and later spending of a child's output; parent and multiple children each have output zero under distinct own IDs.
- At least 100 reference batches in total, including successful and script-failed outcomes and certified inclusion. This fixed specification minimum exercises repetition; it is not a performance threshold or a substitute for changed-family coverage.
- Announcements without adoption, adopted certificate with missing closure, repeated certification/delivery and abandoned/replaced branches.
- Restart before, during and after commit; ordinary rollback before replacement arrives and across registration changes.
- Registration, retaining a key during ordinary pool update, rotation, mark/set activation, expiration and retirement/cancellation; bundle-specific proof validation, installation failure, unknown submission and rollback retention.
- Fresh and upgraded instances replayed to the identical point, with historical ID/configuration equality and a successfully restored backup.
- Individual selected files and clean relay sync and pool registration/producer startup through JorManager; missing/corrupt/mismatched files and incompatible tools must not cause false readiness or mutate existing nodes.
- Public Musashi traffic qualifies actual integration; controlled private scenarios supply deterministic rollback/epoch evidence. Standalone Conway and Dijkstra scenarios do not require an orchestrated hard fork.
- Standalone Conway with a Dijkstra-capable binary, and concurrent isolated Conway/Dijkstra targets: verify era-specific commands, capability isolation and retained existing Conway provisioning, monitoring and transaction/pool operations without Musashi assets or BLS. Use controlled block observations and node responses for startup query, matching reuse, differing-block refresh, pending/failed/unknown/wrong-network blocking, same-era review preservation and confirmed-change invalidation; preserve other networks and readable per-block-decoded history. No fixed activation date, binary-derived era, wall-clock expiry, hard-fork orchestration or cross-era rollback test.

## Pass/fail rules

Zero unexplained identity, effective-value, acceptance or ownership disagreements; zero duplicated effects, omitted children, false confirmations or secret disclosures. Every changed family has an accepted case and a rejected/disabled case where defined plus applicable boundaries. All named workflows must be exercised through real boundaries with disposable funds/keys. A required skipped, unavailable or flaky scenario fails its decision gate; rerunning until green or trimming the corpus is not remediation.

A separate engineer reviews provenance and divergent cases. Source citations and independent ledger output strengthen the oracle, but shared implementation assumptions and untested transport/installation behavior remain explicit blind spots. No ledger-execution, network, GUI or migration pass is claimed by the existence or validation of this document.
