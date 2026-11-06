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

Wallet distinguishes the observed tip from the last complete point. The Nodes table stays compact; detailed network-index diagnostics are not a column there. `/jormanager/chainstatus` and `/jormanager/node/chainstatus` remain available, and node-index associations still support Leios status requests. These describe JorManager's index, not proof that a node process is running or synchronized. `syncing`, `incomplete`, `stale` and `unavailable` are not zero funds and cannot authorize dependent spending. Missing certified content reports the requested block/endorsement commitment where known. Complete adoption and ordinary rollback update durable projections together; discarded occurrences remain inspectable under their original transaction IDs.

Sync progress retains the pre-Dijkstra INFO messages: `ChainSync: Saved block: N of T, P% synced, poolId: ...` under `ChainSyncProtocol`, and `BlckFetch: Saved block: N of T, P% synced` under `LedgerDao`. These use block heights, not slots, with a two-decimal percentage truncated rather than rounded up. Every saved block is reported at tip; while catching up, each protocol connection reports at most once every ten seconds. ChainSync reports persisted default-network headers; BlckFetch reports complete block adoption only after the ledger transaction succeeds. The current fetch pipeline commits one block at a time, so it does not print the old batched block ranges. Routine progress no longer includes the long network key or coordinator status text. Existing failure diagnostics and Wallet completeness checks remain; raw ChainSync protocol traffic stays TRACE. No logging configuration change is needed.

For the Musashi disconnect reporting `ExnLeiosUnknownTxsRequested` on mini-protocol 19 at endorser-block slot 185565, affected JorManager builds encoded transaction-selection bits in reverse order. The corrected client uses the node's MSB-first bitmap contract; a 354-transaction request now selects exactly positions 0–353. This correction requires only deploying the updated JorManager JAR and restarting JorManager, not resetting either database or changing node configuration. Verify that the repeated rejection stops and `BlckFetch` progress advances past the previous stopping point. Local wire-format checks cover partial groups and split requests; live Musashi verification remains operator-owned.

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

An interrupted or uncertain submission retains the exact reviewed bytes and reserved inputs. Use the password-authorized **Reconcile** action (`/walletreconcile`) to compare the saved whole batch with complete, point-matched chain/input/mempool/validity evidence. It never signs or automatically resubmits. Matching only the parent transaction ID is insufficient: witnesses, auxiliary data and child identities must match too. Producer-invalid inclusion is `phase2_failed`, not successful registration or deployment permission. Unresolved evidence remains uncertain; only conclusive cancellation/non-submission or expiry with absence evidence releases reservations. Confirmation retains reservations for ordinary rollback. Changed consequences require a fresh review, not reopening an old signature.

Repeated approval of the same preview returns its durable state, not a new spend. Pending effects never become confirmed UTxOs. Ordinary rollback revokes confirmation without deleting installed keys or automatically resubmitting anything.

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
