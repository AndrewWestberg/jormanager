# Dijkstra-aware upgrade and operator workflow

## Readiness and scope

This guide describes the implemented code paths, not a qualified release. Database migration/restore, real node delivery, signing/submission, remote provisioning and producer operation still require the user-coordinated manual qualification in [quickstart.md](../specs/001-dijkstra-era-support/quickstart.md). Local compilation and synthetic smoke checks do not replace that evidence.

Musashi Dōjō is the Dijkstra integration baseline only. Conway and earlier supported networks retain their own paths without Musashi files or BLS credentials. Supplied CLI/node builds are qualification inputs, not proof of compatibility.

Use the upstream [Musashi setup guide](https://leios.cardano-scaling.org/docs/testnet/getting-started/) and [pool guide](https://leios.cardano-scaling.org/docs/testnet/register-stake-pool/) for the selected network deployment. Do not replace a working Conway node/configuration merely because JorManager understands Dijkstra. No hard-fork transition or cross-era rollback exercise is required here; ordinary rollback/recovery and independent Conway/Dijkstra operation remain relevant.

### Application JSON runtime

The application uses Spring Boot-managed Jackson 3 for injected mappers, message JSON trees, streaming trace/genesis parsing and Kotlin binding. Shared `com.fasterxml.jackson.annotation` annotations remain intentional; Jackson 2 core/databind/Kotlin-module libraries are not required on the runtime classpath.

The subsequent compiler-warning cleanup uses Jackson 3's `asString()`, `isString`, `stringNode()` and `stringValue()` instead of their deprecated aliases. JSON coercion, validation, exact decimal-string amounts and wire formats are unchanged.

If startup reports that `BatchEffectProjector` needs `com.fasterxml.jackson.databind.ObjectMapper`, deploy a JAR built from the Jackson 3 migration rather than adding a Jackson 2 bean or changing Musashi configuration. This migration adds no database changesets and does not require resetting the database or node. The wider Dijkstra upgrade precautions below still apply when upgrading an older installation.

Earlier Jackson migration checks covered selected tests and isolated mapper/message behavior; they did not establish selected-file provisioning or release qualification.

Moshi remains in use for node/CLI models and registers `JodaDateTimeAdapter` explicitly for `DateTime`, not through annotated method discovery. This prevents a compiler-generated `Object` bridge from treating ordinary config keys as dates. A `ChainMonitor` error such as `Invalid format: "AlonzoGenesisFile"` requires the corrected application build, not changes to genesis dates or configuration keys. Local verification parsed the installed Musashi config and Shelley genesis through the application factory; date parsing, serialization and null handling are covered by focused regressions. This does not establish live chain synchronization.


## Before upgrading the application

1. Stop every JorManager instance that writes the database. Leave managed node processes unchanged unless a separate node maintenance operation has been approved.
2. Take a PostgreSQL backup and retain the matching old application binary, configuration, encryption/password recovery material and protected node/host configuration. The GUI key export is not a substitute for a complete database backup. Keep backups and private credentials encrypted and access-controlled.
3. Record the existing default node, host paths, network/genesis identity and remote service configuration. Preserve cold, KES, VRF and BLS material independently of chain registration state.
4. Arrange sufficient database/storage capacity and a node source that can supply the required historical blocks. The new occurrence and reversal history is retained; no pruning horizon is claimed to be qualified.
5. Rehearse migration and restoration on a disposable copy before using this build for real funds or production nodes. That rehearsal has **not** been performed by the implementation pass.

Liquibase registers these new changesets before application monitors start:

- `043-operational-schema.xml`: durable era/capability observations, reviewed operations and input reservations, with nullable Dijkstra-specific node fields.
- `044-dijkstra-ledger.xml`: network-scoped inclusion/member/output/account/governance history, reversal journal, protocol snapshots and Leios commitments; existing UTxO tables become maintained projections.
- `045-dijkstra-provisioning.xml`: historical provisioning tables, node snapshots/drafts, protected Leios key references and chain/installation observations. Historical data is retained.
- `046-node-selected-files.xml`: adds nullable topology and peer file references and renames historical `nodes.configuration_reset_id` to the hidden, read-only `network_key_discriminator`, retaining existing serialized network keys and indexed ledger/operations. New standalone selections use null (`-` in the key); a producer joining an existing network copies the observer's opaque discriminator. This is not a public reset API or reset workflow. Historical data and earlier migrations remain intact.

Numeric, duplicate-identity and legacy-data preconditions can halt migration. Do not delete historical rows, invent network identities, or disable checks to force it through. Diagnose the reported data against a backup first. The old `transactions` table is retained as history, but its transaction IDs do not establish durable authorization and are never automatically resubmitted.

There is no supported guessed down-conversion to the old schema. To return to the old application, stop JorManager and restore the matching pre-upgrade database, binary and configuration together. Restore remote files/services separately only if an explicitly authorized operation changed them. A local database migration does not upgrade or restart a remote node.

## Initial synchronization and current era

Legacy outputs without reliable network provenance remain historical/unassigned. They are not silently relabeled, deleted or treated as spendable. JorManager starts the network index from verified genesis/retained complete history and exposes replay coverage through `/jormanager/chainstatus`. A source unable to provide required content leaves the index unavailable or incomplete; it does not produce a successful empty balance.

Saved wallet entries remain visible when current index coverage is unavailable. Chain-derived balances, asset maps and staking registration are shown as unavailable, not zero or unregistered; a compact notice includes the matching network's reported reason. Spending and staking require matching-network `walletReady` coverage and fresh available balances, not exact newest-tip equality. A successful key import remains saved independently of its subsequent balance refresh; no re-import is needed.

Wallet retains its Filter and operation controls but no longer displays the index status panel or transaction finder; `/jormanager/transaction` has been removed without a compatibility endpoint. `/jormanager/chainstatus`, `/jormanager/node/chainstatus` and `/jormanager/account/history` remain available. Chain status distinguishes the observed tip from the last complete point; it describes index coverage, not proof that a node process is running or synchronized. After this process verifies exact tip equality, ordinary `syncing` in the same uninterrupted generation retains `walletReady=true`. Initial catch-up, restart, session replacement/end, rollback and explicit failures revoke readiness; a new verified tip match is required to recover. Reconciliation and account history retain their strict complete/point-matched checks. There is intentionally no stall timeout or blocks-behind threshold: a silently stalled open connection can retain its last usable view; fresh input checks and node submission remain authoritative.

The wallet-refresh flow starts at null. First verified readiness, each committed adoption while ready, and operation/reservation transitions initialize or increment its existing conflated counter after commit. A newer header alone publishes exact status without clearing balances or requesting another balance read. Wallet payloads carry `{items, networkKey, readState}` from one snapshot; `readState` includes required `walletReady` and decimal-string `generation`. The browser rejects older generations and keeps wallet-funded reviews usable during ordinary following. Deploy backend and frontend together; no database migration, rebuild or wallet re-import is needed.

Sync progress retains the pre-Dijkstra INFO messages: `ChainSync: Saved block: N of T, P% synced, poolId: ...` under `ChainSyncProtocol`, and `BlckFetch: Saved block: N of T, P% synced` under `LedgerDao`. These use block heights, not slots, with a two-decimal percentage truncated rather than rounded up. Every saved block is reported at tip; while catching up, each protocol connection reports at most once every ten seconds. ChainSync reports persisted default-network headers; BlckFetch reports complete block adoption only after the ledger transaction succeeds. The current fetch pipeline commits one block at a time, so it does not print the old batched block ranges. Routine progress no longer includes the long network key or coordinator status text. Existing failure diagnostics and Wallet completeness checks remain; raw ChainSync protocol traffic stays TRACE. No logging configuration change is needed.

For the Musashi disconnect reporting `ExnLeiosUnknownTxsRequested` on mini-protocol 19 at endorser-block slot 185565, affected JorManager builds encoded transaction-selection bits in reverse order. The corrected client uses the node's MSB-first bitmap contract; a 354-transaction request now selects exactly positions 0–353. This correction requires only deploying the updated JorManager JAR and restarting JorManager, not resetting either database or changing node configuration. Verify that the repeated rejection stops and `BlckFetch` progress advances past the previous stopping point. Local wire-format checks cover partial groups and split requests; live Musashi verification remains operator-owned.

### Bounded catch-up and measured local replay

Adoption still holds one network row lock and commits one block atomically. Transaction-local address/asset caches and block coordinates replace repeated identity/inclusion reads; independent input, data, asset-link and Leios-reference inserts use multi-row SQL in chunks of at most 256. Fresh inclusions buffer input-occurrence and member-data rows across endorsed/direct members, flushing each table at 256 rows and flushing the remainder before marking the block complete. Existing inclusions retain history lookups and flush missing rows per member. Live spends, output creation and duplicate-output checks remain immediate. Hot batch, member, output-occurrence and UTxO inserts remain individual but return only their ID; all row contents are still persisted. Explicit sequence expressions on ID-bearing batches avoid Exposed 1.6.1's named-sequence/multi-row SQL defect without changing the schema or assuming generated-key order.

Indexed ChainSync persistence runs in its existing transmit coroutine, not the shared Mux reader. Repeated tips are skipped only after a successful write for that generation and point; intersection/rollback force tip persistence. BlockFetch retains singleton requests and sequential adoption while prefetching at most one additional encoded block. Only one Leios closure is resolved at a time. Its worker belongs to the observer scope, so failures cancel the connection and replacement waits for owned work. Header capacity, receive/Leios limits, configuration, durability, earlier-era decoding and INFO progress wording/cadence are unchanged.

Local qualification on 2026-11-06 used Java `21.0.12.1+1-1-26.04.4-Ubuntu`, PostgreSQL 18.6, production Hikari settings, and a private loopback cluster with `fsync` and `synchronous_commit` enabled. Each workload had one warm-up and five measured replays in fresh UUID sibling databases using all 46 real migrations. Timing covers decoding, Leios resolution and committed adoption, not migration/genesis setup. Baseline and optimized fixture and normalized semantic digests matched. Fixture SHA-256: `d8cfc890cb8eca96fceee24d7ab3ab16cc510ebd0f457366b3438c9e2df02a93`.

| Workload | Build | Milliseconds, samples 1–5 | Transactions/s, samples 1–5 | JDBC executions/run |
|---|---|---|---|---:|
| 1,035 endorsed transactions, two Dijkstra blocks | Baseline | 3056.75, 2901.03, 3115.56, 2975.22, 3090.74 | 338.60, 356.77, 332.20, 347.87, 334.87 | 22794 |
| Same endorsed workload | Optimized | 2140.65, 1956.29, 1980.10, 1982.58, 1986.97 | 483.50, 529.06, 522.70, 522.05, 520.89 | 14521 |
| 100 Conway blocks, ten transactions each | Baseline | 2669.11, 2619.77, 2676.07, 2527.00, 2514.64 | 374.66, 381.71, 373.68, 395.73, 397.67 | 20000 |
| Same Conway workload | Optimized | 1952.99, 1996.38, 1933.16, 1952.76, 1861.66 | 512.03, 500.91, 517.29, 512.10, 537.15 | 15100 |
| 100 empty Conway blocks | Baseline | 121.96, 109.92, 102.60, 102.25, 122.66 | 0, 0, 0, 0, 0 | 900 |
| Same empty workload | Optimized | 101.42, 114.80, 120.80, 112.26, 105.40 | 0, 0, 0, 0, 0 | 900 |

The endorsed workload reduced median elapsed time by 35.1% and JDBC executions by 36.3%, passing the predeclared 20%/25% reduction gates. Conway median time improved 25.5%; empty-block median time increased 2.1%, below the 10% slowdown limit. Every sample spread remained below 20% of its median. A separate real ChainSync measurement reduced JDBC executions for 100 repeated tips from 400 to zero after initial tip persistence. JDBC batch calls/rows were zero in both reports: multi-row `VALUES` uses ordinary execution calls, not `executeBatch`.

The focused regression/build command passed 43 tests with no skips. It covers PostgreSQL rollback/re-adoption, retained identities, late missing-input atomicity, stale generations, assets, collateral, child ordering, deposits, readiness and eras 1–5; protocol checks cover reader responsiveness, real Leios exchange, bounded prefetch, shutdown ownership and worker-failure propagation. A separate CLI smoke ran actual Mux/BlockFetch/LeiosFetch against a scripted loopback peer and a fresh PostgreSQL sibling: Dijkstra committed 1,035 outputs totaling 10,143,000,000 lovelace at its certified successor; a separate three-block Conway stream committed six outputs totaling 58,800,000. Requests, exact current outputs and post-commit progress logs matched, with owned workers/sockets/pools/databases released.

To repeat the current harness, use Java 21, the existing libsodium setup and a disposable localhost `jormanager_test_*` administrative database with create/drop permission. Set `JORMANAGER_TEST_DATABASE_URL` privately. Capture baseline before production edits, then run optimized mode after them. The per-transaction SQL qualification below uses new report paths and stricter JDBC gates:

```sh
JORMANAGER_CATCHUP_BENCHMARK=baseline ./gradlew test --rerun \
  --tests '*ChainCatchupIntegrationTest.replayBenchmark' -x testVue -x buildVue --console=plain

JORMANAGER_CATCHUP_BENCHMARK=optimized ./gradlew test --rerun \
  --tests '*ChainCatchupIntegrationTest' --tests '*GenesisRecoveryIntegrationTest' \
  --tests '*ChainSyncResponsivenessTest' --tests '*BlockFetchPipelineTest' \
  --tests '*SyncLoggingTest' --tests '*LeiosFetchProtocolTest' \
  --tests '*GenesisLedgerDecoderTest' --tests '*HandshakeProtocolTest' \
  bootJar -x testVue -x buildVue --console=plain
```

The earlier qualification reports remain at `/tmp/jormanager-catchup-baseline.json` and `/tmp/jormanager-catchup-optimized.json`; the current harness writes the separate transaction-SQL reports below. Reports include individual samples, base source revision, runtime versions, JDBC counters and semantic digests. Missing/mismatched baselines or failed/inconclusive thresholds fail the benchmark. These synthetic, local results are not a production throughput promise: real-node latency, transaction distribution and hardware/cache variation remain unmeasured. Musashi JorManager was not started, restarted or redeployed; live elapsed-time verification remains operator-owned.

### Per-transaction SQL qualification

Only an inclusion inserted by the current locked adoption transaction permits skipping the five historical batch/member/input/output/data existence reads. Existing or retained inclusions keep their lookup/reuse path. Effective inputs use one network-qualified, unspent-only `UPDATE ... RETURNING output_occurrence_id`, consumed once; a returned null linkage is a valid genesis spend, while no row aborts adoption. Live-output duplicate checks, same-block visibility, atomicity, retained IDs, sequence allocation, schema and durability are unchanged.

The controlled comparison froze the actual pre-change checkout, including uncommitted sources and Git metadata, in private directory `/tmp/jormanager-transaction-sql-baseline-544a23b8-f53e-4fa3-b907-d95db45c21de`. Reports are `/tmp/jormanager-transaction-sql-baseline.json` and `/tmp/jormanager-transaction-sql-optimized.json`; earlier reports were not overwritten. Java, PostgreSQL, pool settings and fixture/semantic digests matched. The owned loopback PostgreSQL cluster used `fsync=on` and `synchronous_commit=on`, with one warm-up and five measured replays per workload.

| Workload/build | Milliseconds, samples 1–5 | Median ms | Transactions/s, samples 1–5 | Median transactions/s | JDBC executions, samples 1–5 |
|---|---|---:|---|---:|---|
| Leios baseline (1,035 transactions) | 1963.22, 1959.64, 1895.89, 1870.54, 1866.22 | 1895.89 | 527.20, 528.16, 545.92, 553.32, 554.60 | 545.92 | 14521, 14521, 14521, 14521, 14521 |
| Leios optimized | 1558.33, 1448.46, 1518.08, 1406.73, 1415.17 | 1448.46 | 664.17, 714.55, 681.78, 735.75, 731.36 | 714.55 | 8311, 8311, 8311, 8311, 8311 |
| Conway baseline (1,000 transactions) | 1880.44, 1849.72, 1901.83, 1860.00, 1848.63 | 1860.00 | 531.79, 540.62, 525.81, 537.63, 540.94 | 537.63 | 15100, 15100, 15100, 15100, 15100 |
| Conway optimized | 1579.36, 1473.43, 1400.44, 1406.68, 1474.68 | 1473.43 | 633.17, 678.69, 714.06, 710.89, 678.11 | 678.69 | 9100, 9100, 9100, 9100, 9100 |
| Empty baseline (100 blocks) | 120.53, 106.28, 120.34, 109.71, 106.57 | 109.71 | 0, 0, 0, 0, 0 | 0 | 900, 900, 900, 900, 900 |
| Empty optimized | 102.47, 106.34, 114.44, 111.10, 106.16 | 106.34 | 0, 0, 0, 0, 0 | 0 | 900, 900, 900, 900, 900 |

JDBC medians equal each row's repeated execution count: six calls removed per fresh transaction. Leios median time fell 23.6% and calls 42.8%; Conway time fell 20.8% and calls 39.7%; empty time fell 3.1% with unchanged calls. All gates passed: at least 35% fewer calls for both nonempty workloads, at least 20% lower Leios median time, and no more than 10% Conway/empty slowdown. Baseline/optimized timing spreads were 5.1%/10.5% for Leios, 2.9%/12.1% for Conway and 13.0%/11.3% for empty, below the 20% limit; no paired retry was needed. Repeated-tip executions remained zero. JDBC batch calls/rows remained zero because multi-row SQL uses ordinary executions.

The targeted `ChainCatchupIntegrationTest` and `GenesisRecoveryIntegrationTest` run passed 21 tests with no skips, and `bootJar` succeeded. Regressions include whole-block rollback on a cross-batch double spend, network isolation, nullable genesis linkage, non-null same-block linkage, duplicate delivery, retained history, collateral, deposits, earlier eras and asset attribution. A standalone `JavaExec` smoke outside JUnit committed two dependent Dijkstra blocks, checked exact live output/linkage, rolled back, and re-adopted with unchanged historical batch/member/input/output/data IDs and the correct complete point. Only messaging was mocked. Temporary smoke sources were removed and the owned cluster stopped; no production service or database was modified.

### ID-only inserts and block-local history buffering

The subsequent controlled comparison replayed real Musashi heights 80,142–80,165: 24 blocks containing 26,311 transaction batches, including 23,237 endorsed batches. It used the frozen pre-change checkout and the same decoded blocks, verified closures and prerequisite rows for both builds. Each build had one warm-up and three measured runs in fresh disposable PostgreSQL siblings; timing covered committed adoption only, excluding decoding, closure acquisition, database setup and assertions. Java 21/ZGC/8 GiB heap, PostgreSQL 18.6, Hikari settings, `fsync=on` and `synchronous_commit=on` matched.

| Build | Adoption milliseconds, samples 1–3 | Median ms | Sample spread / median |
|---|---|---:|---:|
| Before ID-only inserts and block-local history buffers | 33586.86, 34578.85, 35198.83 | 34578.85 | 4.66% |
| After both changes | 19756.87, 19888.14, 20356.98 | 19888.14 | 3.02% |

Median adoption time fell 42.5%. Every run matched exact live outputs, resolved input links and the final point; normalized batch/member/input/output/data digests matched across builds. The final sample of each build also rolled back and re-adopted the last nonempty block outside timing, preserving historical IDs and restoring the same state. Reports: `/tmp/adoption-batch-baseline.json`, `/tmp/adoption-batch-optimized.json`, and `/tmp/adoption-batch-comparison.json`. These runs did not collect new JDBC counts.

The targeted PostgreSQL regression run passed 21 tests and `bootJar`; the separate opt-in synthetic benchmark was intentionally skipped. Added coverage checks 259 dependent transactions across full and partial input/data buffers, exact member attribution/linkage, retained identities after rollback, and successful subsequent adoption after a late failure had flushed full chunks. No schema, durability, fetch-pipeline or duplicate-output-guard change was made. This warm subset does not reproduce production-sized indexes/cold-cache behavior; the real-block workload has no stateful ledger effects or native assets, which remain covered by focused regressions rather than this timing sample. No production service/database was modified or application deployed.


### Musashi handshake and initial funding

JorManager offers node-to-node versions 13–16. The Musashi build's pinned [network version contract](https://github.com/IntersectMBO/ouroboros-network/blob/2f1c154f248659b89523d5679ca532f442e7ed2a/cardano-diffusion/api/lib/Cardano/Network/NodeToNode/Version.hs) defines v15 (SRV support) and experimental v16 (a Peras-support field). Versions 13–15 use four version-data fields; v16 uses five. JorManager remains initiator-only, with peer sharing, query mode and Peras disabled. Invalid, unoffered and wrong-network acceptance messages fail negotiation. This does not activate Dijkstra on a Conway network.

For Shelley-at-genesis test networks, initial funding follows the pinned [ledger injection rules](https://github.com/IntersectMBO/cardano-ledger/blob/9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c/eras/shelley/impl/src/Cardano/Ledger/Shelley/Transition.hs): use legacy `initialFunds`, or embedded `extraConfig.initialFunds.data` when the legacy map is empty. Conflicting sources are rejected; external-file injection is explicitly unsupported. Do not move entries between genesis fields or change genesis bytes/hashes to work around an older application decoder.

An older build can mark a network seeded while missing the embedded allocations. On reconnect, the corrected build restores them only if the network's UTxO projection is entirely empty, there is no transaction-batch history (including rolled-back batches), and there are no incomplete block records. Registration is serialized with chain writers and commits all allocations together. Indexed empty blocks are preserved. Existing outputs, including spent genesis outputs, are not reseeded; partial projections are not automatically repaired. Unsafe recovery stops with a diagnostic rather than inventing balances. The observer establishes its fenced session before configuration/genesis validation so a registration failure is recorded as incomplete coverage.

For the reported slot-132 failure, stop JorManager, back up its database, deploy the corrected JAR and restart JorManager. Do not reset the managed node or manually insert/delete UTxOs. For the verified empty-index case, the log reports `Restored 2 genesis outputs ... before transaction ingestion`, then ingestion can continue from retained history.

The focused PostgreSQL regression suite is opt-in: set `JORMANAGER_TEST_DATABASE_URL` to a disposable local `jormanager_test_*` database and run `./gradlew test --tests '*GenesisRecoveryIntegrationTest' -x testVue -x buildVue`. Its role must be able to create/drop databases; the test creates a unique sibling database, applies the real migrations, and removes it afterward. Never point it at an application database.

On 2026-11-05, a read-only connection to the local Musashi node negotiated v16. A separate disposable PostgreSQL 18 instance applied all 46 migrations, ingested the actual empty blocks at slots 16 and 59, restored the two actual genesis allocations, and ingested the exact failing block at slot 132; that block spent one restored genesis output. Focused regressions passed (41 tests, one existing skipped test), including six real PostgreSQL recovery cases. This proves that boundary, not full tip synchronization or release qualification.


Current-era activation is separate from historical decoding:

- A verified node query initializes the current-era cache for each network/target context.
- A block whose era differs from the cached era triggers a fresh node query. The block's era is never assigned as the current era.
- Matching blocks reuse the cache. There is no TTL, periodic era polling, or unconditional current-era query for every write.
- Pending, failed, unknown or wrong-network evidence blocks dependent side effects.
- A successful same-era refresh preserves an otherwise unchanged review. A confirmed era change invalidates dependent qualification and review; changed funds, parameters, target or capability evidence also require a new review.

Historical blocks are decoded by their own eras, including when Dijkstra-capable tools are connected to a still-Conway network.

## Select a Musashi configuration

Select individual network files from the ordinary [catalog](https://bluecheesestakehouse.com/jormanager/index.html) or imports; no manifest or publication handoff is needed.

Before side effects, selected JSON, references, genesis hashes, network magic/system start and peer magic are validated. Missing or inconsistent files block provisioning. Operators own reset and resynchronization.

Generated configs retain the explicit era-specific genesis references and remove the legacy `GenesisFile` alias, including when it appears in an imported template. The node rejects `GenesisFile` together with `ByronGenesisFile`. For a config generated by an affected older build, manually remove only `GenesisFile`; retain the era-specific references and genesis files. Updating JorManager does not automatically rewrite deployed node configs.

For a new Musashi relay:

1. Select Byron, Shelley, Alonzo and Conway genesis, config and topology individually; select Dijkstra genesis and peer snapshot when referenced. Optional selections can be cleared for Conway.
2. Select the intended host, paths and ports. The first relay requires no existing observer or current-era query.
3. Review target and applicable host prerequisites. A binary or selected genesis does not establish current era.
4. Authorize provisioning only for the intended target. Selected files are installed with normalized local references.
5. Observe installation, process start and synchronization separately. A staged configuration or running process does not establish a complete index or voting readiness.

Producer registration and deployment require reviewed authorization, matching connected observer/current-era evidence, selected genesis identity and applicable capabilities. Pool children inherit parent configuration.

## New pools and reviewed operations

Pool creation now separates a persisted draft, on-chain registration and host deployment. Draft nodes do not start chain/monitoring sessions.

1. Prepare the draft with `/node/pool-draft` and inspect `/node/pool-draft-context`. The `/jormanager` prefix applies to these and the destinations below. Preserve explicit fee payer, owners, reward account, pledge, current minimum pool cost, metadata and parent/relay selection.
2. For Dijkstra, prepare/import the BLS key through the Leios panel and review registration against the selected network. Conway registration remains keyless and does not require Musashi evidence.
3. Use `/leios/registration/preview`, then explicitly approve `/leios/registration/submit`. Ordinary updates preserve a scheduled future key instead of replacing it with the current key. Cancellation uses reviewed re-registration; unsupported removal is not offered as a fabricated capability.
4. Wait for actual registration confirmation. `/pool/deployment/preview` and `/pool/deployment/install` separately review and authorize host changes. Bulk credentials use the supported per-pool embedded envelope representation from [PR #6749](https://github.com/IntersectMBO/cardano-node/pull/6749), not BLS filenames in the array.
5. Treat registration, effective key epoch, snapshot eligibility, installation and loaded state as independent observations.

Wallet sends/assets, rewards, stake registration/deregistration/delegation, governance and pool retirement use persisted review/approval paths. The UI shows exact lovelace amounts, target, fees/deposits/refunds and validity before approval. A governance rationale publication is a separate password-authorized public upload; a transaction preview alone does not publish it.

An interrupted or uncertain submission retains the exact reviewed bytes and reserved inputs but creates no spendable pending outputs. Use the password-authorized **Reconcile** action (`/walletreconcile`) to compare the saved whole batch with complete, point-matched chain/input/mempool/validity evidence. It never signs or automatically resubmits. Matching only the parent transaction ID is insufficient: witnesses, auxiliary data and child identities must match too. Producer-invalid inclusion is `phase2_failed`, not successful registration or deployment permission. Unresolved evidence remains uncertain; conclusive non-submission or expiry with absence evidence can release reservations. Exact confirmation releases only inputs the adopted branch did not consume (unused collateral on success; unused ordinary inputs on failure). Signed records retain rollback withholding evidence. Without a validity end, reservations can remain unresolved indefinitely. Changed consequences require a fresh review, not reopening an old signature.

Repeated approval of the same preview returns its durable state, not a new spend. A network-scoped available-wallet snapshot overlays only locally submitted transactions on indexed outputs, checks known parent IDs against the target mempool, and withholds unresolved normal/collateral inputs and reward withdrawals. Eligible pending change can fund the next reviewed transaction; exact confirmed-node outputs bridge the local indexing race. Confirmation deduplicates by member transaction/output reference, while a dropped or rolled-back ancestor suppresses unsupported descendants. These provisional effects never become adopted ledger facts. Reviewed key-only transactions use one raw builder with explicit fees, change, deposits/refunds and minimum-output/size checks; updates do not pay another pool-registration deposit. No full-mempool scan, optimistic certificate state, automatic resubmission or new coin-selection policy is added.

## Read transaction and account effects

Look up each parent or child by its own body transaction ID in Wallet. Children can be discovered even without payment outputs. A certified occurrence includes its announcing point, endorsement-block hash, distinct full-transaction commitment, reference position/size and certificate bytes; direct occurrences have no Leios provenance object.

`directDeposits` is body key **25**, an account-address-to-coin map. Parent and child deposits remain independently attributed. They are account credits, not payment outputs, and failed/rolled-back occurrences are not current credits. Account history is separate from reward balance: balance is returned only with a point-matched node snapshot, otherwise null with a reason. Transaction-body category totals are never presented as an invented reward balance.

Structured member CBOR is exposed as a flat typed node graph alongside its original raw bytes. This preserves exact integer strings, byte identities, structured map keys and deep nesting without recursive JSON truncation. See [operator-messages.md](../specs/001-dijkstra-era-support/contracts/operator-messages.md) for wire contracts.

## Observation limits that remain visible

The inspected pool-state response is a keyed **flat** record with `blsKey`, `futureStakePoolParams`, `deposit` and `retiring`, not a nested `poolParams` response. `blsKey.bksRegisteredIn` describes when the parameters take effect; the UI calls it `effectiveEpoch`, not registration epoch.

Pool status requires coherent before/after node tips and era/capability evidence. Fresh partial observations are `incomplete`; retained observations after a failed refresh are `stale`. The inspected queries do not expose the actual registration epoch or mark/set snapshot capture epochs. Committee seats do not identify their pool, so nonempty committee output does not establish pool-specific selection. These values remain null with reasons rather than guessed epochs/eligibility.

The inspected node source has no public loaded-BLS-key fingerprint readback. File contents/permissions and a successful restart establish neither loaded key identity nor voting readiness. A public-only node readback or possession proof is the missing upstream prerequisite for that verification. Installation therefore reports load as unverified; generic forging counters are not substituted for a key fingerprint.

## Local verification versus manual acceptance

The direct-file cutover has local backend/frontend regressions, production-browser Musashi/Conway selection checks, live catalog file validation and offline migration SQL checks recorded in the [delivery evidence](../specs/001-dijkstra-era-support/plan.md#direct-file-delivery-verification). These do not replace independent ledger, host, node or database qualification.

The local Musashi handshake and isolated early-block recovery above were exercised; the live application database and managed services were not modified. Existing-installation migration/restore, full chain catch-up, real signing/submission, SSH provisioning and producer restart remain unverified. Complete independent Conway/Dijkstra, delivery, recovery, selected-file provisioning and operator scenarios in [quickstart.md](../specs/001-dijkstra-era-support/quickstart.md) before release.
