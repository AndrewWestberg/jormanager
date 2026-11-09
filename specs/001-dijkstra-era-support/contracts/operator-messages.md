# Operator and query message contracts

**Status:** manual qualification remains pending. Musashi qualifies Dijkstra only; B1/B2/B3 require actual delivery, tooling and selected-file provisioning evidence.

## Existing transport and cutover

Reuse SockJS `/jormanager-websocket`, STOMP application prefix `/jormanager`, and `/topic/messages`. Existing `SocketResponse` success shape is `{type, data}`; errors currently contain `{type, exception}`. For changed paths, retain the envelope but serialize a sanitized exception DTO rather than an arbitrary Throwable: `{code, message, operation, target, retryDisposition}`. Never serialize command lines containing credentials, secret-file contents, passwords or stack-local key data. Correlation `requestId` belongs inside new response `data` or the sanitized `exception` object.

This shared topic is not a private per-user channel. Public lifecycle status may be broadcast; passwords, signing material and approval secrets must not be. Reuse the spending-password/credential authorization boundary for operations; request IDs are correlation only, not authentication. Preview IDs must be checked against the stored target, request contents and credential authorization before use. Do not broaden the application's deployment trust model: local-only binding or the documented protected access path remains required.

The operational surface retains SockJS/STOMP, but unsafe direct transaction senders are replaced rather than aliased. Wallet/governance use `/walletreview` + `/walletsubmit` and `/governancereview` + `/governancesubmit`; rationale publication is a separate `/governancepublish` authorization. Pool registration/update/cancellation, retirement and deployment use their reviewed destinations below. `/createnode` remains the relay path; producers/pool children use persisted drafts before registration/deployment. KES rotation remains independent and must not rotate a Leios key. All suffixes use `/jormanager`.

## Common types

- `NetworkKey`: network identity derived from verified magic, system start and applicable genesis, not magic or a mutable label alone.
- `ChainPoint`: `{slot: DecimalString, hash: Hex32}`; origin is explicit null only in fields documented as allowing origin.
- `Target`: Leios requests/responses use `{hostId, nodeId, poolId?, networkKey?}`; other reviewed requests resolve their target from persisted node/account/preview IDs. Validate associations server-side. A caller cannot redirect an installation with an arbitrary filesystem path.
- `DecimalString`: canonical integer string, no exponent or floating point. Coin/fee/deposit/refund values are nonnegative lovelace; mint/burn quantities may be signed. Browser code uses exact arithmetic and display-only ADA formatting.
- `ChainReadState`: `{networkKey, status: complete|syncing|incomplete|stale|unavailable, reason, observedTip, lastCompletePoint, walletReady: boolean, generation: DecimalString}`. Nullable points distinguish unavailable/origin from a fabricated point. Exact coverage and continuous wallet readiness are separate: ordinary `syncing` after this session's verified tip match can remain wallet-ready. Initial catch-up and continuity/failure boundaries cannot. Explicit `/chainstatus` replies add `requestId` and `replay`; committed-transition broadcasts carry the flat state without a request ID. Missing readiness fails closed; older generations are rejected without floating-point conversion.
- Leios `ReadState`: `{status: complete|syncing|incomplete|stale|unavailable, asOf, observedPoint, lastCompletePoint, reasonCode, affectedData}`. An unavailable value is null with an explanation, not zero or an empty successful collection.
- Reviewed wallet/governance results: `{requestId, previewId, txId, networkKey, era, fee, deposit, refund, validFrom, validUntil, state, effects}`. A transaction ID does not imply confirmation. Pool/provisioning results additionally expose their resolved target and operation-specific observations. Omitted key-change intent means preserve, not removal.
- `KeyPublicView`: `{keyId, publicKeyHex, proofOfPossessionHex, proofValidated, registration, eligibility, installation}`. The 96-byte public key and 48-byte proof are public; signing keys/encrypted signing blobs never appear. Do not expose a fictional BLS reservation state.
- `EraEvidence`: `{network, era, source, observedPoint, observedAt, freshness: current|refresh_required|unavailable}`. Era and provenance may be null when unknown; a retained non-current value is display-only. Source/point/time identify the node response, not an expiry. Only a successful matching-network authoritative node query updates the cached era; unknown, pending, failed or wrong-network evidence cannot authorize dependent writes. Installed tool version is not era evidence.

## Read and discovery destinations

All destinations below use the `/jormanager` prefix. Each response uses the corresponding type named in this table and includes `requestId`.

| Destination / response type | Request | Response data and guarantees |
| --- | --- | --- |
| `/capabilities` / `capabilities` | `requestId`, `hostId`, optional `nodeId`, `expectedNetworkKey`, or pre-creation `observerNodeId` + `configSha256` | Host/network/era-specific `eraEvidence`, baseline and per-operation `verified|unsupported|unverified|unavailable` with reasons/evidence; never infers current era or compatibility from era/version naming |
| `/chainstatus` / `chainstatus` | `requestId`, `networkKey` | Flat `ChainReadState` plus replay/legacy-coverage information; failure reason identifies requested block/endorsement commitments when known |
| `/node/chainstatus` / `node_chainstatus` | `requestId`, `nodeId` | Server-resolved node/network association, `readState` and replay coverage; network-index status is separate from process/sync metrics, including for drafts |
| `/transaction` / `transaction` | `requestId`, `networkKey`, `txId` | Own body ID, parent/ordered children, occurrence adoption history, validity/effect outcome, member-owned typed/raw data, inputs/outputs and `readState`; endorsed occurrences additionally expose `leios` provenance |
| `/leios/status` / `leios_status` | `requestId`, `target` | Public key views and point/time-scoped observations; nullable registration/snapshot/loaded facts remain independent of installed files |
| `/account/history` / `account_history` | `requestId`, `networkKey`, `accountAddressHex` | Member-attributed account effects plus exact balance only when a node snapshot matches the complete index point; otherwise null balance and `balanceReason` |

A first relay requires no observer. Producer and other current-era-dependent authorization require an already connected verified matching-network observer; configuration and binaries do not establish current era.

Wallet results are `{items, networkKey, readState}` from the same network/generation snapshot. Existing amount fields represent available indexed plus eligible locally submitted outputs, minus unresolved spent/reserved inputs and reward withdrawals; there is no separate numeric pending/confirmed UI split. All five derived fields remain null when coverage is unavailable, while saved identities/keys remain visible. Node responses carry the same readiness metadata, including explicitly queried non-default networks. History and authorized reconciliation still require exact complete coverage. A rolled-back occurrence remains identifiable and never appears as currently confirmed. Address-filtered wallet views do not omit underlying non-wallet member/effect history.

For every existing write workflow, the backend revalidates target-network cache state and the applicable operation capability, including at preview authorization and immediately before dependent external side effects. Initialize the cache by querying a verified node on startup and when re-establishing target context; a block with a different era marks `refresh_required` and triggers a fresh node current-era query, while a matching block reuses the cache. No era TTL, periodic polling or unconditional per-write era query applies. Decode historical blocks using their own era and never let their era overwrite the current cache. Pending or unavailable refresh and network mismatch block dependent side effects; a confirmed changed era invalidates dependent capability/parameter assumptions and previews/authorizations, requiring applicable qualification and renewed review. A successful same-era refresh of source/point/time alone does not invalidate an otherwise unchanged review. Bind preview and authorization to network identity, observed era, relevant protocol/state and capability evidence; changes in target, capability, parameters or relevant state still invalidate as applicable. Other freshness and validity rules, including UTxO/index state, credentials and key expiration, remain independent. UI disabling is not enforcement. Dijkstra-capable binaries alone do not enable Dijkstra commands, certificates, config or Leios actions on Conway; a separate network's failure does not disable unrelated supported operations or read-only history.

### directDeposits query and display contract

Every transaction member view exposes `directDeposits: {presence: absent|present, entries: [{accountAddressHex, amount: DecimalString, effective: boolean}]}` with member TxId and inclusion provenance supplied by the containing view. A present field has a nonempty entry collection; the accepted wire field is body key 25's account-address-to-coin map, not an ordered payment-output list. Query arrays preserve the observed association without inventing ledger semantics for map iteration order. Raw encoded bytes retain identity/presence evidence. Structurally rejected empty/null forms are inspection failures, not normalized accepted values.

Parent and child deposits remain separately attributable. Confirmed account balance/history shows `DIRECT_DEPOSIT` credits separately from rewards, withdrawals, registration deposits/refunds, fees and payment UTxOs. A member with only account effects remains discoverable even with no wallet payment output. Amounts are exact lovelace strings; binary account identity is not reconstructed from a display label. Script-failed or rolled-back occurrences never credit the current account balance; pending intent has a separate state. Both direct-block and certified inclusion obey the same query semantics.

An endorsed occurrence's `leios` object is `{announcingPoint, ebHash, fullTransactionHash, referencePosition, transactionSize, certificateHex}`; direct occurrences return null. `fullTransactionHash` commits to the included batch and must not be substituted for a parent or child body ID. Provenance remains available for discarded branches.

`memberData` entries carry `{family, position, rawHex, typed}`. Structured CBOR inside `typed` uses `{root, nodes}`: integer values are decimal strings, bytes are hex, arrays contain child indices, maps contain ordered key/value index pairs, and tags reference a child index. Text, null, boolean and simple/raw forms remain distinct. The flat graph avoids imposing a recursive JSON depth limit or stringifying structured keys.

## Key and registration destinations

State-changing requests contain `requestId`, `target`, spending-password authorization and an explicit operation intent. Never log the request's sensitive fields; request `toString` and diagnostic serializers must redact them. Credential ingestion is confined to the existing protected credential channel and discarded from browser state after completion.

| Destination / response type | Additional request fields | Observable result |
| --- | --- | --- |
| `/leios/key/prepare` / `leios_key_prepared` | `intent=prepare`, explicit confirmation of target | Generate using verified tooling, validate derived public key/proof, persist encrypted secret; return only key ID/public view |
| `/leios/key/import` / `leios_key_imported` | `intent=import`, secret material through credential input, optional claimed public key/proof | Validate format and cryptographic relation with the supported tool before persistence; reject mismatches; no echo of secret |
| `/leios/registration/preview` / `leios_registration_preview` | `requestId`, `nodeId`, `feePayerAccountId`, nullable `keyId`, `action=register|rotate|cancel|update`, spending password and optional pool changes | Persist reviewed target, exact body/TxId and economic/state consequences; no submission or installation. Conway uses no BLS key |
| `/leios/registration/submit` / `leios_registration_submitted` | `requestId`, `previewId`, spending password, `approved=true` | Revalidate target, capabilities, key rules, state/parameters and authorization. Persist whole-batch bytes before external submission; never imply confirmation |
| `/leios/key/install` / `leios_key_installation` | `keyId`, reviewed installation preview ID, explicit approval, sudo credential only if needed | Deploy only to persisted managed producer target; verify ownership/mode and public fingerprint, reload/restart only when reviewed and authorized; return actual installation/loaded observations |
| `/leios/key/install-preview` / `leios_key_install_preview` | `keyId` | Target host/node/configuration location, selected public fingerprint, disruption/restart implications, prior recoverable configuration and capability result; no remote mutation |

Rotation uses the same registration preview/submit flow with `action=rotate`, not a second submission implementation. Removal is accepted only if the selected operational contract supports it; optional/null ledger decoding alone is insufficient to offer removal. Existing pool updates must preserve the registered or scheduled key according to the reference lifecycle unless an explicit supported change is approved. They must not overwrite a future rotation with the active key merely because it was queried first.

Generation/import/preview must fail before spending or remote installation if Dijkstra-era evidence or capabilities are insufficient. Re-registration extends existing pool certificate tag 3; do not invent a standalone BLS certificate or pool deposit. Use current protocol parameters and registered state to compute economic consequences. Retirement and cancellation remain available in the supported Conway workflow; Dijkstra cancellation uses qualified re-registration, preserves the existing deposit and respects current/future keys and retirement timing. The supported CLI command binding must be validated against B2, not synthesized here.

### Implemented observation limits

Pool-state queries return keyed flat records with `blsKey`, `futureStakePoolParams`, `deposit` and `retiring`. `blsKey.bksRegisteredIn` is exposed as `registration.effectiveEpoch`; it is not the actual registration epoch. `registrationEpoch` is null with `registrationEpochReason` when unavailable. Before/after node tips and era/capability evidence must match for a fresh observation; fresh partial data is `incomplete`, retained data after failed refresh is `stale`.

The inspected snapshot/committee queries do not expose mark/set capture epochs or an unambiguous pool identity for nonempty committee seats. Unknown age/eligibility/selection remains null; voting epochs are not relabeled as snapshot epochs. The inspected node exposes no loaded-BLS-key fingerprint readback, so installation cannot transition to a verified loaded state from a file or restart alone. A public-only node readback/possession proof is the missing prerequisite.

### Wallet, governance and provisioning destinations

Wallet review accepts `{requestId, kind, request}` and returns the reviewed result above. Submission accepts `{requestId, previewId, spendingPassword}`: invoking it is explicit approval of that saved review, not permission to rebuild a different body. Governance follows the same pattern; `/governancepublish` separately accepts `{requestId, rationale, spendingPassword}` and returns the public anchor to bind into the subsequent review.

`/walletreconcile` accepts `{requestId, previewId, spendingPassword}` for any persisted reviewed wallet, governance or pool transaction and returns `{requestId, previewId, txId, networkKey, state, reason}`. It checks saved whole-batch identity, point-matched complete indexing and node/input/mempool/validity evidence. It never signs or automatically resubmits. Unresolved evidence remains uncertain; only conclusive pre-broadcast cancellation/non-submission or expiry with complete absence evidence releases reserved inputs. Confirmation retains reservations for ordinary rollback.

Pool drafts use `/node/pool-draft` and `/node/pool-draft-context`; reviewed deployment uses `/pool/deployment/preview` and `/pool/deployment/install` only after actual registration confirmation. `/node/provisioning-preview` supplies pre-creation checks. Qualification attestations use `/capabilities/qualification` or `/capabilities/host-qualification`, binding operation/status, evidence ID/SHA-256, CLI/node/configuration hashes and `expectedEra`/`expectedGeneration`. The server checks the current observer and actual artifacts; the caller must supply genuine independent evidence.

## State transitions and reconciliation

Reviewed transactions progress from preview through authorization/signing and submitted or uncertain states. An adopted occurrence confirms only when the saved parent-and-child bodies, witnesses and auxiliary data match, not merely the parent TxId. Producer-invalid inclusion records `phase2_failed`, never successful registration or deployment permission. Rollback revokes either inclusion outcome and requires reconciliation. Scheduled registration, eligibility, selection and installation remain separate observations.

Installation: `not_installed → authorized → installing → installed → loaded`, with `failed|unknown|stale` whenever verification fails or observations expire. `installed` requires the expected public fingerprint and permissions on the target; `loaded` requires a real supported node observation. Neither is inferred from a database migration, SSH exit alone or file existence. A failed install leaves chain registration as observed and reports the operational gap.

Eligibility comes from the selected Musashi mark/set snapshots and age rules, not a fixed wall-clock timer. Scheduled activation is a projection until observed; eligibility differs from committee selection. On rotation retain prior material while needed by active/future state or rollback recovery; no destructive cleanup during uncertain submission or rollback. Verify bundle-specific proof compatibility, including the candidate IETF possession-proof domain, through qualified tooling.

A repeated preview submission returns its durable operation state, not a fresh signature or spend. The explicit reconciliation action resolves interrupted/uncertain operations using the whole authorized batch; children are never independently resubmitted. A changed fee, body, target, validity, confirmed current era or other reviewed consequence requires a new preview and authorization, never reopening the old review. Pending or unavailable era refresh blocks dependent side effects, but a successful same-era provenance refresh alone does not require a new review. Independent funds/state/capability safety checks still apply.

## Failure codes and UI obligations

Failures carry sanitized resource context and a concrete next action. An exception must not be followed by success for the same failed effect.

Nodes/Wallet views and their existing creation/update/send/staking modals must expose the affected target, amount consequences and failure state before approval. Add a Leios key panel to the producer workflow, not a separate application. Disable actions dependent on stale funds/capabilities; preserve viewable historical state with its point/time. After a rollback notification, invalidate displayed confirmation and dependent eligibility immediately, even without a replacement block. Reconnection re-queries durable status rather than trusting missed topic events.

Validation: actual Vue/SockJS/backend/PostgreSQL/CLI/SSH boundaries in the isolated scenarios in [quickstart.md](../quickstart.md), with independent ledger expectations from [evidence-manifest.md](evidence-manifest.md). Existing mocked controller tests cannot establish these contracts.

## New Musashi node and pool creation

Existing `/jormanager/file_options`, `/jormanager/createnode` and `/jormanager/nodes` use individual file selections; no bundle/reset selection or manifest API.

Relay requests carry genesis, config, topology and optional Dijkstra/peer file IDs. Validate files before side effects. Producer/pool children use reviewed draft, registration and deployment, with matching observer and genesis; children inherit parent files.

Existing supported Conway node and pool creation continue through their existing configuration path without Musashi assets, Dijkstra genesis or BLS material. A selected Dijkstra-capable binary alone must not change that path.

Creation distinguishes installed, started, synchronized, submitted, confirmed, eligible and loaded observations; no private material appears in status.

Catalog refresh does not mutate existing nodes. Operators own reset and resynchronization. Missing/corrupt/mismatched files block activation. See [network-configuration.md](network-configuration.md); actual clean provisioning is quickstart Q0.
