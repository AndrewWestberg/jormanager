# Dijkstra validation quickstart

**Status:** validation plan, not a report that Musashi or Dijkstra works. New feature messages and scenarios become runnable after implementation and qualification. Current repository build/start commands below are real entry points; there is no checked-in Dijkstra replay harness or independent corpus yet. Do not substitute a CLI era label, mock node or local round trips for qualified deployed-bundle and independent-reference evidence.

**Scheduling:** This is a guide for later user-coordinated manual integration qualification, not a prerequisite to start implementation. Do not provision these environments or author/run the feature tests in the current code pass. The parent may run local compiler/smoke checks after integration, but those do not satisfy Q0–Q8 or G1–G6. The user coordinates later owners, reviewers, test execution and qualification; all evidence tasks remain pending, with no waiver of release/runtime requirements.

## Later qualification prerequisites and safety

Musashi-specific prerequisites below apply to Dijkstra scenarios, not to existing Conway operation. For Q8, also prepare a supported standalone Conway node/CLI/network profile with independently recorded identity and current-era evidence, disposable hosts/funds/keys and sanitized existing-installation data. It must provision and operate without Musashi assets, Dijkstra genesis or Leios credentials. Run the Conway and Dijkstra targets independently and concurrently; do not orchestrate a hard fork or cross-era rollback.

1. Linux, Java 21, Node.js >=24/npm, PostgreSQL, and native libraries described in [installation.md](../../docs/installation.md). Use the repository Gradle wrapper; exact dependencies remain in existing build manifests.
2. A disposable PostgreSQL database and role with schema-management permission, an isolated application configuration and test-only spending password. Never point the test application at an existing operator database or import its real host configuration. PostgreSQL server version must be recorded in evidence; the JDBC version is not a server-version declaration.
3. Use the supplied `cardano-cli` 11.6.0.0 and `cardano-node-leios` 11.2.0.164 revision `746f1b3cbdaada2c3ed2f702b0946643248dd30b` as the available qualification inputs, recording actual executable paths, dependency/binary/configuration provenance and negotiated delivery. These are supplied build facts, not replacement local compatibility pins. Current help exposes BLS registration and singleton/bulk startup; [research section 7](research.md#qualification-update--2026-10-28) identifies PR #6749 and evidence limits. The older rc-2026w43 snapshot does not automatically describe this build's dependency set.
4. All eight Musashi files are now available from the [JorManager catalog](https://bluecheesestakehouse.com/jormanager/index.html). Complete the planned immutable provenance/manifest and validated application snapshots in [network-configuration.md](contracts/network-configuration.md); the observed stable-basename row does not yet expose that manifest. Verify node-specific genesis hashes and coherent reset, not only successful JSON download. Use a private same-bundle network for deterministic Dijkstra replay/fault/epoch cases; all hosts, funds and keys remain disposable.
5. Disposable managed relay and producer hosts reachable through the same local/SSH mechanism used by JorManager. Use dedicated SSH credentials, verified targets, appropriate permissions and an approved restart/reload contract. Registration testing does not authorize actions against another host.
6. Sanitized historical database/configuration fixtures for upgrade rehearsal, safe backup storage, and a tested restore destination. Do not capture secret key material in logs/screenshots/fixture manifests. Existing backup export can contain decrypted signing material; handle it as a secret, not an ordinary evidence artifact.

If any prerequisite is absent, record the exact blocked scenario, owner and missing artifact. A missing fixture, external publication or tool is not a skipped passing test. Musashi resets are expected: network magic 164 alone is not reset identity. This guide does not invent new node-control APIs or claim current JorManager already creates Musashi nodes.

## Build and start the isolated application

Run from the repository root. These commands validate/build the application; they do not qualify the external Cardano bundle:

```sh
java -version
node --version
npm --version
npm --prefix vue ci
./gradlew test
./gradlew ktlintCheck
npm --prefix vue run test
npm --prefix vue run build
```

`processResources` already depends on the project's frontend test/build tasks. In the later qualification run, record real outcomes, including environment failures; do not retry or weaken checks to claim a pass. Add the listed focused consumer-visible codec, ingestion/recovery, key lifecycle and exact-value regressions during later verification, following existing JUnit/Vitest conventions. The full commands above must include those tests once added; no invented test-class selector is claimed to exist here.

Provision an empty disposable database using normal PostgreSQL administration. Set the following variables to that database only; the shell checks prevent falling back to repository defaults:

```sh
: "${JORMANAGER_TEST_DB_URL:?Set the disposable JDBC PostgreSQL URL}"
: "${JORMANAGER_TEST_DB_USER:?Set the disposable database role}"
: "${JORMANAGER_TEST_DB_PASSWORD:?Set its test-only password without recording it}"
export SPRING_DATASOURCE_URL="$JORMANAGER_TEST_DB_URL"
export SPRING_DATASOURCE_USERNAME="$JORMANAGER_TEST_DB_USER"
export SPRING_DATASOURCE_PASSWORD="$JORMANAGER_TEST_DB_PASSWORD"
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=8787'
```

Open `http://127.0.0.1:8787/` and use the built, same-origin GUI. Confirm startup/Liquibase output names only the disposable database. Create a test-only spending password and add only the isolated host/node. Choose the isolated network as the default node before wallet operations; independently check its genesis/magic against the runtime manifest.

Do not use `npm run dev` unchanged for this guide: the current Vite port is 8080, the debug store points to backend 9797, and WebSocketConfig allows a different development origin. Serving the built UI from Spring avoids relying on that unrelated dev-proxy mismatch. No dev-server configuration change is part of this feature plan.

After implementation, inspect STOMP traffic through browser developer tools: endpoint `/jormanager-websocket`, commands `/jormanager/...`, responses `/topic/messages`. Use the actual GUI to authorize state-changing scenarios, not hand-crafted messages that bypass review. Read destinations and exact payload rules are in [operator-messages.md](contracts/operator-messages.md).

## Validation scenarios

Each later run records runtime/fixture manifest IDs, actual steps/commands, expected and observed point/IDs/values, sanitized UI evidence and pass/fail findings. Reference expectations must exist before JorManager is run for qualification; obtaining them is not a prerequisite to writing code. For storage assertions and transitions use [data-model.md](data-model.md); for delivery semantics use [chain-delivery.md](contracts/chain-delivery.md). The fixture provider must supply reproducible reference replay/epoch/rollback commands in its manifest; this guide does not fabricate a node-control CLI.

### Q0 — New Musashi relay and pool through JorManager

- Use the now-published Musashi row as the source. Before node creation verify complete application download, immutable selected snapshot, provenance, node-specific genesis hashes, system start and magic. Normalize all five prefixed genesis paths and the prefixed peer snapshot path consistently with installed filenames. Asset availability is already observed; manifest/versioned publication and application qualification remain outstanding.
- On each disposable target record the actual configured node executable, including `cardano-node-leios` for the supplied build, and its CLI/build/capability evidence; do not accidentally test a different `cardano-node` installation. Before the new node exists, obtain fresh authoritative era evidence from the verified observer/reference node for that same selected network; record its source and identity, not a version-based inference or requirement that the new node already be running. Inspect architecture, disk, paths, permissions, clock, connectivity, effective flags/socket and BLS operation support. Version/help observations do not establish successful registration, synchronization or producer key loading; missing prerequisites block dependent writes/signing.
- In the GUI choose that verified bundle, create a **new relay**, and authorize only the intended isolated host. Compare effective genesis, config, topology and peer files with the snapshot plus reviewed role-specific overrides; check service/manual startup and sync/tip/network identity. Obtain test ada only from the [Musashi faucet](https://faucet.leios.play.dev.cardano.org/basic-faucet) for disposable accounts.
- Create a **new producer/core and pool** through the GUI with the relay's bundle/reset and reviewed relay topology. Review live minPoolCost, fees, deposits, pledge, reward/owners; prepare distinct disposable cold/KES/VRF/BLS material and authorize registration. Observe submission, confirmation, scheduling, eligibility and loaded public fingerprint separately. For pool children exercise PR #6749's per-pool fourth-element embedded BLS envelope/array, including mixed legacy entries, ordered rotation keys and invalid-key rejection; JorManager's generator must emit the extension. Verify both authorized manual/systemd startup paths and correct per-pool key association, without capturing secrets.
- Negatively test missing/mixed/tampered assets, partial publication, unreachable server, incompatible host/tool, missing BLS support and stale reset: actionable failure, no partial bundle promotion, no unauthorized remote writes/signing and no false readiness. On a changed genesis/system-start reset, require explicit new bundle and clean chain database/replay; do not reuse old balances, pool state or pending authorizations just because magic is unchanged. Topology-only changes also require explicit review, not automatic mutation.
- Expected: FR-033–FR-036 and SC-009/SC-010, verified by G6: a fresh relay synchronizes and a fresh pool registers and its producer runs via JorManager using published assets, with actual outcomes recorded. Registration alone does not prove activation, forging or voting.

### Q1 — Compatibility and per-era ingestion

- Use independently sourced static reference blocks for the already supported eras and a qualified Dijkstra network/fixture. Compare block IDs, transaction membership, values, per-block era and last complete point with independent reference output. Existing historical identifiers remain unchanged; no hard-fork orchestration is required.
- Select an explicitly incompatible tool combination and an unsupported future-era fixture. Starting the affected operation must show the specific missing capability before signing, spending, writing remote config or healthy-balance claims. Do not modify a production tool installation to create this case.
- Expected: SC-001/SC-006/SC-007; no unknown-era-to-Conway fallback and no silent parser skip.

### Q2 — Nested identity, full data fidelity and aggregate effects

- Replay the accepted parent/two-child scenario where all members create output zero, then its later transaction spending a child's output. Query parent and each child through `/jormanager/transaction` and inspect the wallet.
- Replay independently approved individually-unbalanced members whose aggregate balances, reference-input and child metadata cases, then script-failed/collateral-return cases. Verify only reference-prescribed effects, one fee/collateral accounting point and actual owner attribution.
- Run the complete changed-family corpus: guards/required guards, script versions and structured redeemers, tags and original-byte hashes, accounts/interval checkpoints, certificates/votes/proposals, parameter families, binary/empty asset names, numeric boundaries and legacy output forms. Compare exact decimal strings and displayed values, not floating-point approximations.
- Exercise rejected duplicate children, extra nesting, child-only forbidden fields, wrong submission flags, malformed duplicates/empty values and same-batch-spend outcomes. Rejection leaves no partial confirmed effects. Ledger-invalid yet decodable cases never appear ledger-accepted merely because inspection succeeds.
- Expected: SC-001/SC-002/SC-003; every approved member independently discoverable, including non-payment members, with zero identity/value/ownership mismatches.

### Q2a — directDeposits transactions (required)

- Replay independent accepted transactions containing `directDeposits` at body key 25: a top-level deposit, a child-owned deposit, multiple account recipients and parent/child deposits to the same account. Include a permitted member with no payment output and a certified inclusion case. Query each member and inspect the account history/balance; every exact credit appears once under its actual owner, with no synthetic UTxO or registration-deposit entry.
- Exercise account existence and ordering: accepted registration-before-deposit where the baseline permits it, child credit visible at the subsequent parent's current-balance checkpoint, starting-balance checks against original state, and rejected attempts to withdraw newly deposited funds beyond the original-balance allowance. Compare all results to independently executed ledger expectations.
- Cover absent versus prohibited present-empty/null maps, incorrect-network/nonexistent account rejection, exact ledger-approved large amounts and out-of-range/multiasset values. Do not invent whether zero or an unsigned maximum is valid for a particular state; pin the reference outcome.
- Replay a script-failed batch with deposit intent: `directDeposits` remains inspectable but ineffective and the account receives no credit. Repeat successful delivery, restart at commit boundaries, and explicitly roll back before replacement; credits neither duplicate nor remain on the discarded branch.
- Expected: FR-005/FR-007/FR-008/FR-011/FR-013/FR-021/FR-027/FR-028; SC-001/SC-002/SC-004/SC-007. These cases are mandatory G1/G2/G3 evidence, not covered merely by parsing key 25.

### Q3 — Leios announcement, adoption and completeness

- Use the recorded node scenario to announce an EB without adoption; observe no confirmed payments. Then deliver adopted certified closure containing ordinary/nested batches, including a certifying block that also carries direct body transactions. Compare both content sources, their actual application order and provenance with independent reference output; inlined closure must neither be lost nor applied twice.
- Withhold one committed referenced transaction using the isolated fixture/delivery environment. Observe `incomplete`, missing identity and last complete point; dependent funds are stale/unavailable and spending is disabled. Restore content and observe one complete application, not duplicate effects.
- Deliver duplicates, abandoned announcements, branch replacement and ledger-rejected certificate cases using the corresponding reference fixtures. Non-null Peras is visibly baseline-disabled; its field is not evidence of activation.
- Across Q2/Q3 run at least 100 reference batches with successful, script-failed and certified inclusion represented. Record exact sample distribution and retain all discovered divergences.
- Expected: SC-002/SC-003/SC-007; no announcement-only confirmation, lost nested member, duplicate effect or completeness advance past a gap.

### Q4 — Registration, retained key, rotation and installation

- In the producer's Leios workflow, prepare/import disposable key material; verify public key/proof relation through qualified tooling. Try mismatched key material and an invalid proof; both fail actionably without claimed registration. Establish key acceptance and lifecycle from the actual qualified bundle rather than assuming cross-pool BLS reservation rules.
- Review pool/network/host, fee, deposit/refund consequences and activation implications; authorize registration. Observe submitted versus confirmed versus scheduled separately. Compare pool current/future state and mark/set snapshot observations with the reference.
- Update an unrelated pool parameter using the existing pool configuration workflow. Confirm the intended current/future key is retained and no new pool deposit is invented.
- Preview and authorize installation to the disposable managed producer. Compare public fingerprint, permissions and actual node-loaded observation. Inject a remote write/reload failure; registration remains independently reported and operational readiness stays false/unknown.
- Rotate through the same review/submission flow; observe old/new key state, snapshot activation, expiration boundary, retirement and canceled retirement scenarios. Keep old material when needed for active/future state or rollback. Verify KES/VRF/cold keys are not replaced.
- A representative operator must explain which key is registered, eligible, selected and installed without editing CBOR or assuming immediate activation. Capture only public observations.
- Expected: SC-005/SC-006/SC-007/SC-008, including zero secret disclosure and no registration-equals-readiness claim.

### Q5 — Existing workflows and uncertain submission

- With disposable funded accounts, execute send (including assets), reward withdrawal, stake registration/deregistration/delegation, pool creation/reconfiguration and retirement through the existing GUI. Include supported governance actions touched by the shared transaction builder. Reconcile submitted body IDs, actual fees/deposits/refunds and confirmed state independently.
- Inspect both permitted submitted representations and their identical body identity. Reject `false` transitional validity and standalone incomplete child submission without silently rewriting authorized content.
- Induce an uncertain submission by losing the response at the real isolated boundary. Restart/revisit the workflow; confirm it reconciles existing chain state before offering any repeat. Changes to target, body, validity or fee require a new review/authorization. No child may be resubmitted independently.
- Expected: SC-006/SC-007; no duplicate spend or success reported for an uncertain/failed operation.

### Q6 — Immediate rollback, restart and duplicate delivery

- Capture expected reference state before a branch containing nested effects, account/governance changes and a pool-key registration. Deliver an explicit rollback and withhold replacement blocks.
- Verify all dependent state and complete point reverse immediately, current confirmation/eligibility changes and installed-key state remains independently observed. Keep required secret material. Then replace the branch and compare again.
- Repeat delivery and restart before acquisition, after header/closure acquisition, during the database write and after commit before notification. Use external process termination/fault control in the disposable environment; record the exact point and expected invariant. Never kill the operator's running instance.
- Reconnect the GUI and confirm it re-queries durable state rather than retaining missed confirmations. Also test an obsolete in-flight fetch completing after a rollback.
- Expected: SC-004/SC-007; state equals uninterrupted replay, zero partially committed batches, no point ahead of durable effects and no duplicate confirmations.

### Q7 — Upgrade, replay, backup and recovery

- Restore the sanitized historical fixture to one disposable instance; create a second empty instance. Capture historical output IDs and operator configuration from the fixture before migration.
- Apply the new Liquibase/application version to the first, with ingestion paused during ownership cutover. Replay the required verified history; the second performs a fresh replay of the same source.
- Compare both at the identical complete point, including child ownership, exact UTxOs, account/governance effects, lifecycle observations and pending authorizations. Missing historical bytes cannot be invented by a metadata backfill. Display replay-required/stale until the required content is available.
- Perform documented remote prerequisites separately for relay/producer roles and verify running configuration; a local schema migration must not claim them complete.
- Rehearse recovery by stopping the isolated service and restoring the pre-upgrade database/configuration with its matching application version and retained key backups. Verify historical identities and operator access. Do not run old binaries against the new schema without proven compatibility.
- Expected: SC-004/SC-008; no unplanned manual repair, documented restore works and no production credentials/funds touched.

### Q8 — Standalone Conway and Dijkstra coexistence

- In isolated environments with disposable hosts, funds and keys, retain an existing supported Conway configuration without Musashi files, Dijkstra genesis or BLS credentials. Provision and monitor a relay and pool; run send, reward withdrawal, stake registration/delegation, pool registration, update, retirement and supported cancellation. Expected: existing Conway operations remain usable, costs and confirmation match the applicable reference, and no Dijkstra keys or state are invented.
- Install or select a Dijkstra-capable binary while authoritative target-network evidence still says Conway. Expected: Conway commands, certificates and configuration remain selected; the executable version alone does not activate Dijkstra or require Leios. Separately exercise a qualified standalone Dijkstra target through Q0/Q5.
- Run the two targets concurrently. In deterministic application/contract cases control block observations and node responses: on startup and re-established target context require a matching-network node query, not a persisted-era inference; observe a matching-era block and assert cache reuse without another query. Observe a differing-era block and assert `refresh_required`, one triggered fresh node query and no block-derived cache overwrite; decode historical blocks by their own era. Assert no elapsed-time expiry, periodic era polling or unconditional per-write query.
- Hold the refresh pending, then exercise failed, unknown and wrong-network responses. Expected: dependent writes are blocked before signing, submission or remote mutation, with a reason and retained non-current era display-only; readable history and the other network's qualified operations remain available. Initial unknown/query-failed state is `unavailable`.
- Obtain a preview before a differing block; exercise UI and backend authorization while refresh is pending and after a same-era node response with changed provenance. Expected: pending blocks the write, then the same-era response restores `current` without invalidating an otherwise unchanged review. Separately return a confirmed changed era: dependent capability/parameter assumptions and the old preview/authorization are invalidated; applicable qualification, renewed review and explicit authorization are required before side effects. Revalidate cache state at the write boundary, retaining independent state, credential and key checks.
- Upgrade a sanitized existing Conway database with Liquibase. Expected: configuration, keys and historical identities survive; no synthetic Dijkstra state, provenance or credentials appear; after any required verified replay existing supported Conway workflows continue. Missing evidence blocks only dependent actions. These scenarios address FR-037/FR-038 and SC-011/SC-012; they do not require a hard-fork or cross-era rollback experiment.

## Evidence and completion

Before feature merge/release, execute the applicable gates in plan G1–G6, review failures and record commands/scenarios actually run. Update `README.md`, `docs/README.md`, `docs/installation.md` and the release's applicable upgrade guide with verified prerequisites, new-relay/new-pool catalog workflow, role-specific manual/automatic steps, backup/replay/remote deployment and recovery limits. Do not publish hypothetical commands as tested support.

Observed so far: published eight-file HTTP/JSON/reference checks, user-supplied upgraded builds, local help capabilities and upstream PR documentation. None is a Q0–Q8 application pass. No application startup/tests, ledger execution, signing, submission, key generation, migration or SSH deployment occurred in this documentation update; remaining immutable-provenance and runtime gates stay open.
