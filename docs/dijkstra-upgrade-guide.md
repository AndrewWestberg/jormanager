# Dijkstra-aware upgrade and operator workflow

## Readiness and scope

This guide describes the implemented code paths, not a qualified release. Database migration/restore, real node delivery, signing/submission, remote provisioning and producer operation still require the user-coordinated manual qualification in [quickstart.md](../specs/001-dijkstra-era-support/quickstart.md). Local compilation and synthetic smoke checks do not replace that evidence.

Musashi Dōjō is the Dijkstra integration baseline only. Existing supported Conway and earlier-era networks keep their own configuration and operation paths; they do not need a Musashi bundle or BLS credentials. The supplied CLI `11.6.0.0` and `cardano-node-leios` `11.2.0.164`, revision `746f1b3cbdaada2c3ed2f702b0946643248dd30b`, are qualification inputs, not independent permanent version pins or proof of compatibility.

Use the upstream [Musashi setup guide](https://leios.cardano-scaling.org/docs/testnet/getting-started/) and [pool guide](https://leios.cardano-scaling.org/docs/testnet/register-stake-pool/) for the selected network deployment. Do not replace a working Conway node/configuration merely because JorManager understands Dijkstra. No hard-fork transition or cross-era rollback exercise is required here; ordinary rollback/recovery and independent Conway/Dijkstra operation remain relevant.

## Before upgrading the application

1. Stop every JorManager instance that writes the database. Leave managed node processes unchanged unless a separate node maintenance operation has been approved.
2. Take a PostgreSQL backup and retain the matching old application binary, configuration, encryption/password recovery material and protected node/host configuration. The GUI key export is not a substitute for a complete database backup. Keep backups and private credentials encrypted and access-controlled.
3. Record the existing default node, host paths, network/genesis identity and remote service configuration. Preserve cold, KES, VRF and BLS material independently of chain registration state.
4. Arrange sufficient database/storage capacity and a node source that can supply the required historical blocks. The new occurrence and reversal history is retained; no pruning horizon is claimed to be qualified.
5. Rehearse migration and restoration on a disposable copy before using this build for real funds or production nodes. That rehearsal has **not** been performed by the implementation pass.

Liquibase registers these new changesets before application monitors start:

- `043-operational-schema.xml`: durable era/capability observations, reviewed operations and input reservations, with nullable Dijkstra-specific node fields.
- `044-dijkstra-ledger.xml`: network-scoped inclusion/member/output/account/governance history, reversal journal, protocol snapshots and Leios commitments; existing UTxO tables become maintained projections.
- `045-dijkstra-provisioning.xml`: immutable network bundles, node snapshots/drafts, protected Leios key references and independent chain/installation observations.

Numeric, duplicate-identity and legacy-data preconditions can halt migration. Do not delete historical rows, invent network identities, or disable checks to force it through. Diagnose the reported data against a backup first. The old `transactions` table is retained as history, but its transaction IDs do not establish durable authorization and are never automatically resubmitted.

There is no supported guessed down-conversion to the old schema. To return to the old application, stop JorManager and restore the matching pre-upgrade database, binary and configuration together. Restore remote files/services separately only if an explicitly authorized operation changed them. A local database migration does not upgrade or restart a remote node.

## Initial synchronization and current era

Legacy outputs without reliable network provenance remain historical/unassigned. They are not silently relabeled, deleted or treated as spendable. JorManager starts the network index from verified genesis/retained complete history and exposes replay coverage through `/jormanager/chainstatus`. A source unable to provide required content leaves the index unavailable or incomplete; it does not produce a successful empty balance.

Wallet and Nodes distinguish the observed tip from the last complete point. Nodes requests `/jormanager/node/chainstatus` using the persisted node ID and labels the result as the JorManager network index, separately from process/sync metrics; a draft's network index is not proof that its process runs. `syncing`, `incomplete`, `stale` and `unavailable` are not zero funds and cannot authorize dependent spending. Missing certified content reports the requested block/endorsement commitment where known. Complete adoption and ordinary rollback update durable projections together; discarded occurrences remain inspectable under their original transaction IDs.

Current-era activation is separate from historical decoding:

- A verified node query initializes the current-era cache for each network/target context.
- A block whose era differs from the cached era triggers a fresh node query. The block's era is never assigned as the current era.
- Matching blocks reuse the cache. There is no TTL, periodic era polling, or unconditional current-era query for every write.
- Pending, failed, unknown or wrong-network evidence blocks dependent side effects.
- A successful same-era refresh preserves an otherwise unchanged review. A confirmed era change invalidates dependent qualification and review; changed funds, parameters, target or capability evidence also require a new review.

Historical blocks are decoded by their own eras, including when Dijkstra-capable tools are connected to a still-Conway network.

## Select a Musashi configuration

The [remote catalog](https://bluecheesestakehouse.com/jormanager/index.html) already publishes the eight stable `musashi-*` assets. Stable aliases alone are not an immutable selectable bundle. The remaining publisher handoff is defined in [network-configuration.md](../specs/001-dijkstra-era-support/contracts/network-configuration.md): publish the ManifestV1 record and all eight `musashi-<resetId>-<bundleId>-<asset>` files, then link the complete set.

JorManager validates the packaged JSON Schema, duplicate keys, RFC 8785 identities, HTTPS catalog origin, raw asset digests, all five node-required genesis hashes, magic/system start and internal file references before promoting a bundle. Source URLs are provenance, not permission to fetch arbitrary URLs. A bad or partial refresh retains the previous validated selections. No publisher upload or immutable-manifest publication was performed in this implementation pass.

For a new Musashi relay:

1. Select one validated bundle and its reset in the existing node wizard; do not mix individual genesis selectors.
2. Select the persisted host, paths, ports and reviewed local overrides. Establish current-era evidence using an already connected, verified observer on that same network before the new node exists.
3. Review host provisioning capabilities. Dijkstra qualification records bind the actual CLI/node/configuration hashes, evidence digest, expected era and generation. Recording a qualification is an attestation of independently obtained evidence, not an automatic compatibility test or permission to invent a pass.
4. Authorize provisioning only after the target and prerequisites are correct. The staging path installs all five genesis files, config, topology and peer snapshot with coherent local references.
5. Observe installation, process start and synchronization separately. A staged configuration or running process does not establish a complete index or voting readiness.

Pre-creation capability discovery uses `/jormanager/capabilities` with the target host, verified observer and effective configuration digest. Reviewed preflight is `/jormanager/node/provisioning-preview`. Applicable manual evidence is recorded through `/jormanager/capabilities/qualification` or `/jormanager/capabilities/host-qualification`; both require the expected current era/generation and matching artifacts. These are protected-deployment STOMP interfaces, not public Internet administration APIs.

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

The implementation pass exercised Kotlin main and existing test-source compilation, the production Vue build, disposable structural codec/framing/account-checkpoint checks, exact whole-batch identity checks and ManifestV1 serialization checks. A real browser rendered synthetic unavailable-index Wallet state, a certified child with exact `directDeposits` and ineffective phase-2-failed intent, immediate historical labeling after network invalidation, and a draft's network-index/recovery status separate from process readiness. These inputs were not independent ledger qualification fixtures or backend integration proof. An implicit Gradle dependency also ran the existing 74 Vitest tests once; subsequent compiler/smoke commands exclude that task.

No qualification network, application database migration/restore, real signing/submission, SSH provisioning or producer restart was exercised. Before release, complete the independent Conway/Dijkstra, direct/certified content, recovery, bundle-publication and operator scenarios in [quickstart.md](../specs/001-dijkstra-era-support/quickstart.md). Keep the corresponding tasks and release gates pending until actual results exist.
