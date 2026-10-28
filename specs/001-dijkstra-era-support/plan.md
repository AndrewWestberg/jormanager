# Implementation Plan: Dijkstra Era Support

**Branch**: `001-dijkstra-era-support` (reported by setup-plan; no branch creation/switch performed here) | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: `specs/001-dijkstra-era-support/spec.md`

**Status**: Musashi Dōjō remains the Dijkstra qualification baseline, not a replacement for supported Conway or earlier-era operation. As of 2026-10-28, all eight Musashi catalog assets are published and downloadable; the user supplies CLI 11.6.0.0 and bulk-capable `cardano-node-leios` 11.2.0.164 at `746f1b3cbdaada2c3ed2f702b0946643248dd30b`. Read-only help confirms the needed BLS registration/startup flags, and upstream PR #6749 documents per-pool bulk BLS. **B1/B2 runtime integration and B3 immutable-provenance/application provisioning remain open for Dijkstra/Musashi**; missing initial publication or missing bulk-loader implementation are no longer blockers. No end-to-end JorManager qualification is claimed.

**Execution direction (2026-10-28):** The user requests code first, with no qualification environment provisioning or feature test authoring/execution in this pass. The user coordinates later manual integration testing, qualification, owners and reviewers. The parent integration owner may perform local compiler/smoke checks after integration; such checks are not runtime qualification. Code uses available authoritative source contracts and actual code-interface dependencies, not completion of B1–B3, G1–G6, independent corpus setup or a story's runtime checkpoint. All eight stories and their requirements remain in scope. Deferred evidence remains required for acceptance and full-feature release/runtime claims; it is not a constitution waiver.

**Code-pass status:** shared persistence, era/capability gating, raw historical/Dijkstra decoding, nested/direct-deposit effects, certified-content ingestion, reviewed operations/reconciliation, provisioning and operator surfaces are implemented. Local compiler/build and disposable structural/browser checks are recorded in [quickstart.md](quickstart.md#evidence-and-completion); they do not qualify the release. [tasks.md](tasks.md) distinguishes completed code tasks from mixed tasks whose runtime/publication/observation requirements remain open. The [operator guide](../../docs/dijkstra-upgrade-guide.md) describes the current entry points and limits.

## Summary

Support the complete Musashi Dijkstra data/transaction baseline, including own-ID nested members, aggregate successful/collateral-only effects, all changed authorization/account/governance families, certified Leios inclusion, and the operator lifecycle for Leios voting keys. Users must be able to create new Musashi relays and pools using downloaded configuration from JorManager's existing remote server. The same application release must be deployable during Conway, retaining existing funds/certificate workflows, provisioning, monitoring, historical identities and unrelated node configuration. Preserve all previously supported era paths; Dijkstra support is additive, not a replacement for them.

Use the current Kotlin/Spring/Vue application, era/context-specific decoding with original byte spans, a durable inclusion/member/effect model, existing UTxO tables as its maintained projection, and a single atomic adoption/rollback boundary per network. Extend existing STOMP/GUI workflows for capabilities, exact amounts, chain freshness and separately observed key registration/eligibility/installation. Liquibase plus verified replay handles upgrades; remote binary/config/key deployment remains a separately authorized operation.

**Explicit directDeposits scope:** support transactions containing `directDeposits` (body key 25) at both top-level and child level, through decoding, exact coin-valued account-effect indexing, query/UI attribution, certified inclusion, atomic rollback and replay. This is an account-credit field, not a separate transaction envelope, UTxO output, stake-registration deposit or multiasset transfer. Validation scenario Q2a is mandatory; no payment-output-only filter may discard these transactions.

The Dijkstra baseline authority is the official supported **Musashi Dōjō network bundle**, not locally installed CLI versions or independently selected ledger/node/CLI commits. Record its exact upstream component revisions, binary hashes and network/configuration incarnation for reproducible qualification; these are provenance snapshots, not separate compatibility pins. Follow upstream Musashi updates with explicit review, never silent mutation of existing nodes. Supported non-Musashi networks retain their own identity, tools and configuration; Musashi qualification does not become a prerequisite for Conway operation. Proposed CIPs do not override the selected bundle. No new consensus/Plutus engine, arbitrary nested composer, marketplace, inclusion-proof product or active Peras implementation is in scope.

### Dual-era activation and compatibility boundary

- Decode historical/adopted data using each block's actual era. Select transaction construction, CLI commands, certificates, parameter interpretation and exposed operations using the target network's node-derived current-era cache plus relevant qualified capabilities. Initialize the cache by querying the node. If an observed block's era differs from the cached era, mark the cache `refresh_required` and query the node again; only that response updates the cached current era. Matching-era blocks reuse the cache. Neither the mismatching block, an installed binary, a downloaded bundle, a global flag nor a predicted activation date establishes the current era.
- Conway remains a supported live operating profile in this release: existing synchronization, wallet/staking/pool workflows, monitoring and node/pool provisioning do not require Musashi configuration, a Dijkstra genesis file or Leios voting credentials. Keep prior-era dispatch and callers as intentional supported behavior, not obsolete compatibility shims.
- Gate Dijkstra-only operational behavior in the backend as well as the GUI. Reject forged Dijkstra requests on Conway before signing, submission or remote mutation. Preserve valid Conway actions when Dijkstra qualification is absent. Era-evidence status is `current`, `refresh_required` or `unavailable`; initialization, pending refresh, failed/unknown responses and wrong-network evidence cannot enable dependent writes. Other networks and historical inspection remain unaffected.
- This era cache is event-driven, not time-expiring: source/point/time record provenance, not a TTL. No periodic polling or unconditional per-write era query is required. Query on initialization or re-established target context and on a detected block/cache era mismatch. A node queried during historical ingestion can legitimately report a different current era from the triggering block; preserve per-block decoding without replacing the node's answer with the block era.
- Bind reviewed operations and protocol snapshots to the target network and node-confirmed era, and revalidate cache status and relevant fingerprints before side effects. Pending/failed refresh blocks dependent writes. A successful same-era refresh does not invalidate an otherwise unchanged review merely because query point/time changed; a node-confirmed era change invalidates affected capability/parameter assumptions and previews and requires applicable qualification and renewed review. Separate index/funds freshness, key expiry and other consequence checks remain in force.
- Validate standalone Conway and standalone Dijkstra behavior, Dijkstra-capable tools on a Conway chain, mixed-network isolation and existing-installation upgrade integrity through Q8. Deterministic application/contract cases cover cache initialization/reuse, block-mismatch refresh and authoritative node responses; they do not require a live fork. Hard-fork transition orchestration and cross-era rollback tests remain explicitly out of scope; ordinary chain rollback and historical replay requirements are unchanged.

## Technical Context

**Language/Version**: Kotlin 2.4.20 on Java 21; TypeScript ~5.7.2 and Vue ^3.5.13; Node.js >=24. Exact application dependency versions are in `buildSrc/src/main/kotlin/Versions.kt` and `vue/package.json`/lockfile.

**Primary Dependencies**: Spring Boot 4.1.1 Web/WebSocket/JPA, Kotlin coroutines 1.11.0, Exposed 1.5.0, Liquibase 5.0.4, PostgreSQL JDBC 42.7.13, Google IoT CBOR 0.5.0-NEWM, Moshi 1.15.2, SSHJ 0.41.1; Pinia/SockJS/STOMP/Vite ^6.0.7. Application dependency versions are unaffected by discarding the local Cardano baseline. Dijkstra qualification uses the supported Musashi bundle; existing supported networks retain their qualified tool contracts. B1/B2 verify actual Dijkstra integration, not a global binary replacement or era-selection mechanism.

**Storage**: PostgreSQL with Liquibase-managed schema; byte-preserving raw records plus exact numeric and typed projections, chain-point-scoped reversible changes, durable pending authorizations. Signing keys remain encrypted through the existing File/WalletUtils credential mechanism. No secret values in event/status payloads or evidence artifacts.

**Testing**: JUnit 6.1.3 via JUnit Platform, Spring Boot tests, MockK 1.14.11, Truth 1.4.5; frontend Vitest ^2.1.8. Cardano client library 0.7.3 is test-only and not assumed Dijkstra-capable. Independent pinned-ledger fixtures, disposable PostgreSQL, real node/CLI/SSH/browser scenarios are required in addition to existing suites.

**Target Platform**: Linux server/managed Linux hosts, browser GUI, PostgreSQL and required native Cardano libraries from `docs/installation.md`. Public Musashi integration uses disposable hosts/keys/test funds; isolated supported Conway targets verify current-era behavior, and private same-bundle environments provide controlled Dijkstra faults and key-lifecycle epoch observations. Historical per-era replay remains required, but no hard-fork transition or cross-era rollback experiment is required. Never use production hosts or credentials as fixtures.

**Project Type**: Existing full-stack stakepool management web application with backend-owned chain ingestion and remote-node operations; STOMP messages, not a new REST service.

**Performance Goals**: Correct bounded processing of legal maximum-size batches/EB content and ongoing chain following. No new throughput/latency SLA is specified. The 100-batch minimum is correctness coverage, not a benchmark. Avoid per-member whole-block copying, unnecessary re-hashing and unbounded acquisition queues.

**Constraints**: Original hash inputs, exact unsigned/signed quantities and binary names; no silent incomplete index, false confirmation or unsafe automatic resubmission; one PostgreSQL commit for all adopted effects/progress; immediate rollback without replacement; protected credentials and explicit spend/deployment approval. Use protocol/negotiated limits rather than invented format caps.

**Scale/Scope**: Eight user stories, 38 functional requirements, twelve success criteria. All permitted transaction levels/data families, direct/certified inclusion, existing funds/certificate workflows in Conway and Dijkstra, per-network era activation, pool-key lifecycle, new relay/pool creation, public config catalog publication, persistence, GUI, remote operations and upgrade/recovery are included. JorManager follows the adopted chain rather than independently executing consensus or scripts; fork mechanics are the node's responsibility.

## Constitution Check

Constitution: `.specify/memory/constitution.md`, version 1.1.2. The user has selected Musashi as the Dijkstra qualification baseline and explicitly expanded provisioning/publication scope while requiring continued Conway and previously supported era operation. This replaces the old independently selected Dijkstra sources, not supported network profiles, platform or safety requirements. Existing Observatory selection covers source drift, real external boundaries, persistence, failure and operator safety; B3/G6 apply those sensors to configuration publication and provisioning, while G1/G3/G4 cover Q8's standalone dual-era and upgrade checks. No new transition-testing gate is introduced.

| Principle / gate | Pre-research assessment | Post-design assessment |
| --- | --- | --- |
| I — operator-friendly workflows | Existing producer/wallet tasks define scope; no separate product | Design conforms: existing views plus reviewable Leios actions, target/consequence/failure and documented default workflow |
| II — protect funds/credentials/intent | Secrets and CLI/SSH/funds side effects identified | Design conforms: encrypted storage, public-only shared topic, exact authorization, uncertain-outcome reconciliation, old-key retention; runtime/security proof required by G3/G5 |
| III — operational/upgrade integrity | Legacy persistence cannot reconstruct complete new records; deployment must not disable Conway | Design conforms: additive Liquibase changes, era-appropriate nullable/not-applicable fields, verified replay, historical-ID and Conway configuration preservation, backup/restore and separately authorized remote upgrades; G2/G4 remain unexecuted |
| IV — observable evidence and Observatory | Fresh MCP consultation performed during Phase 0 | Selection recorded in research section 12; G6 applies it to new provisioning risks. Document validation applies now; G1–G6 remain required execution, not claimed passed |
| V — simple design/diagnosable failures | Retain application/persistence/transport conventions and supported era dispatch | Design conforms: replace obsolete duplicate parser/writer paths, not valid Conway/earlier-era branches; no parallel service/framework; explicit incomplete/error state and owned/cancelled in-flight work |
| External-tool operational constraint / FR-001, FR-025, FR-033–036 | Published assets, upgraded CLI and bulk-capable node now available | B1/B2 require actual delivery/operator execution; B3 retains immutable provenance, application selection and clean relay/pool qualification. Download/help observations do not pass the end-to-end gates |

### Technical evidence prerequisites (release and affected operations, not code start)

| ID | Missing evidence and investigation | Owner and clearing condition | Decision point |
| --- | --- | --- | --- |
| B1 | Real adopted direct/certified content, complete EB closure, framing/order and rollback from the selected Musashi bundle have not been exercised by JorManager | Integration maintainer records actual release/source/binary/genesis/config identities, captures public Musashi delivery, and exercises isolated same-era fault scenarios plus historical-era fixtures; no hard-fork transition experiment | Before claiming implemented Dijkstra indexing support |
| B2 | CLI 11.6.0.0 and the supplied bulk-capable node build are available; help confirms BLS registration and singleton/bulk startup flags. PR #6749 supplies the per-pool bulk format; JorManager still needs generator/startup/lifecycle integration | Operator/tooling maintainer exercises the supplied executables on disposable targets, including embedded per-pool keys/order, proof domain, registration/rotation, snapshots and actual loaded-key observations | Before enabling/claiming the affected operational workflows |
| B3 | Eight Musashi catalog assets now download successfully and their file references resolve. Stable `musashi-*` names have no linked manifest/versioned publication; the application still needs coherent snapshots and fifth-genesis/peer support | Complete planned immutable provenance with the publisher; implement validated discovery/selection and demonstrate a fresh relay and pool through JorManager. Publication access is needed only for remaining server changes, not to discover/download existing assets | Before releasing Musashi provisioning; asset availability alone does not prove new-node/new-pool success |

The baseline and initial asset publication are established. [Research section 7](research.md#qualification-update--2026-10-28) separates user-supplied build facts, observed downloads/help and the open upstream PR from remaining runtime evidence. The [network configuration contract](contracts/network-configuration.md) distinguishes today's catalog aliases from planned immutable publication. No remote upload, node start or pool registration was performed by this assistant update.

### Observatory-informed execution gates

MCP calls, full-entry guidance, oracle independence and exclusions are in [research.md](research.md), section 12. The user coordinates gate owner/reviewer assignment during later qualification; missing owner/evidence is not a pass or a code-start blocker. Public-network Dijkstra checks use disposable Musashi resources; Conway compatibility uses isolated supported Conway targets. Disruptive experiments run only in private environments. Reuse the recorded sensor selection for these existing boundary/upgrade risks; this amendment does not claim a fresh MCP consultation or any runtime pass.

| Gate / sensors | Failure mode, scope and concrete procedure | Expected evidence and pass/fail rule | Owner / decision |
| --- | --- | --- | --- |
| G0 — SO-010b | Design/source drift: validate document structure/local links, completed templates, all FR/SC mappings, task dependency order and normative manifest schema/vector; check Musashi authority is Dijkstra-scoped and Conway operation is retained | No broken local link/unfilled template, omitted requirement, obsolete local-pin requirement, global Dijkstra-only prerequisite or unsupported success claim; documents do not clear B1/B2/B3 | Plan author / before delivery of planning artifacts |
| G1 — SO-005c, SO-004b | Wrong bytes/IDs, lost children, rounded values, wrong batch/account/collateral effects or era selection: independent corpus per evidence manifest through actual codec/projections, including supported historical formats and separate Conway/Dijkstra cases; run focused JUnit scenarios and full `./gradlew test` | Every changed family/boundary, accepted/rejected results and exact IDs/effects agree; 100 approved batches include successful/script-failed/certified cases; zero unexplained mismatches/duplicates/omissions; no self-generated expectations or implicit unknown-era fallback | Ledger implementer plus independent ledger reviewer / before merge of relevant behavior and release |
| G2 — SO-004b, SO-005d, SO-002c | Incomplete adoption, restart/rollback corruption: disposable PostgreSQL with real Liquibase; replay, repeated delivery, kill/restart before/during/after commit, explicit rollback before replacement, missing closure, old in-flight branch completion | Named constraints hold; durable point never passes a gap; all state equals uninterrupted reference; no stale effects after rollback; record fault point and SQL/state comparison, not only test exit | Persistence maintainer / before merge and release |
| G3 — SO-002c, SO-010 | External/API/UI mismatch, unsafe spend/key handling or Dijkstra behavior on Conway: real node/CLI/SSH plus actual browser using quickstart Q1–Q8, independently established Conway/Dijkstra targets, mixed-network isolation, incompatible combinations, uncertain submission and failed installation | Correct target/fee/deposit/review and era-specific CLI/certificates; Conway works without Dijkstra prerequisites and rejects Dijkstra-only actions; Dijkstra operations require observed Dijkstra plus qualified capabilities; chain IDs, retained key on ordinary update, independent confirmation/eligibility/installation and zero secret disclosure; mocks do not qualify, and fork-transition testing is not required | Integration maintainer and independent operator/security reviewer / before merge of affected workflows and release |
| G4 — SO-004b, SO-002c, SO-005d | Upgrade loss or unrecoverable rollout: restore sanitized historical database/config backup in isolation, including a current Conway installation; Liquibase+era-appropriate replay, compare fresh/upgraded at the same point, rehearse recovery, independently verify remote config | Exact reference historical IDs and operator configuration retained, equivalent indexed state and usable Conway workflows without fabricated Musashi/Dijkstra assignments; documented restore works; migration never claims or forces remote binary/config/key upgrades | Release/persistence maintainer / before release |
| G5 — SO-010, SO-010b | Unreadable operator workflow or unjustified safety claim: independent engineer reviews callers, schema, secrets, scripts, stale state and runbook; representative operator explains statuses after real workflow | All substantive objections resolved with evidence; operator distinguishes submitted/confirmed/eligible/installed; README, installation and applicable upgrade guidance match exercised behavior | Independent reviewer and release maintainer / before merge and release |
| G6 — SO-002c, SO-005d, SO-010b | Broken/mixed config download or unusable new-node/pool workflow: validate the shared manifest schema/vector and published catalog/assets, then execute Q0's Musashi provisioning and Q8's existing Conway provisioning through the real GUI/backend/managed host; withhold/corrupt an asset and rehearse catalog update/reset privately | Complete coherent Musashi downloads; new relay syncs and new pool registers/runs with correct era/role configuration; Conway provisioning retains its supported files and credentials without Dijkstra-only requirements; failures block false readiness and existing networks/nodes do not change. Record remote URLs/hashes and actual status, not just fixture parsing | Config-server and application maintainers / before provisioning merge/release |

Zero wrong-money/identity/secret-leak results is required because one error violates the feature's safety contract. The 100-batch threshold comes from SC-003 before execution; do not reduce it or change thresholds after seeing results. No production canary, live chaos or production fund/key use is required or permitted by this plan.

## Project Structure

### Documentation (this feature)

```text
specs/001-dijkstra-era-support/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
├── contracts/
│   ├── chain-delivery.md
│   ├── operator-messages.md
│   ├── evidence-manifest.md
│   ├── network-configuration.md
│   └── network-configuration.schema.json
└── checklists/requirements.md
```

The implementation breakdown is now in [tasks.md](tasks.md): eight story phases, explicit prerequisite edges, independent acceptance criteria and full-scope release gates. Generating tasks does not clear B1/B2/B3.

### Source Code (repository root)

```text
build.gradle.kts
buildSrc/src/main/kotlin/Versions.kt
src/main/kotlin/com/swiftmako/jormanager/
├── nodeclient/protocols/       # handshake, ChainSync, BlockFetch
├── monitors/                  # EnvironmentMonitor, ChainMonitor, NodeMonitor, WalletMonitor
├── repositories/              # LedgerDao, LedgerRepository, File/Node repositories
├── tables/                    # ledger projections and new inclusion/member state
├── entities/                  # persistent configuration/key/operation records
├── model/                     # typed messages, era/parameter/lifecycle models
├── controllers/               # node/wallet operations and queries
├── controllers/utils/         # credential, CLI and HostConnection boundaries
├── moshi/adapters/            # exact current/future pool and parameter queries
└── utils/                     # identity, era, exact-value and cache handling
src/main/resources/db/
├── liquibase-changelog.xml
└── changelog/                 # next available changesets; old migrations unchanged
src/test/kotlin/com/swiftmako/jormanager/
src/test/resources/            # shared manifest vector; independent era corpora added during implementation
vue/src/
├── stores/jormanager.ts
├── types/
├── views/                     # Wallet.vue, Nodes.vue
├── components/                # AddNodeWizard + existing send/staking/pool/node/key forms
└── utils/                     # exact-value display
vue/tests/
docs/                         # installation and versioned upgrade guidance
```

**Structure Decision**: extend the existing monolith and its tests. Research section 6 inventories affected concrete files. Do not create a second indexer, transport convention or migration subsystem. Feature docs describe planned behavior; current operator docs are updated only alongside implemented, exercised behavior.

The existing server at `https://bluecheesestakehouse.com/jormanager/index.html` now publishes Musashi; reuse it rather than adding another service. Remaining publisher coordination covers the planned manifest/versioned assets, not first publication. Application changes include Dijkstra genesis and coherent bundle identity through File options, CreateNodeRequest, Node persistence, AddNodeWizard and NodeController only where required by the selected network profile. Preserve existing Conway configuration/provisioning without forcing Musashi fields. Normalize both prefixed genesis paths and `musashi-peer-snapshot.json` consistently when installing unprefixed local filenames. Mutable basename caching must not replace an existing node's selected incarnation.

## Phase 0 — Research outcomes

[research.md](research.md) re-evaluates protocol/source claims against Musashi, preserves explicit `directDeposits` coverage and records source provenance separately from runtime evidence. The selected prototype permits direct transactions with certified content and does not establish the newer ledger's BLS reservation rules; those former assumptions are removed. Exact upstream source snapshots explain the observed network implementation but are not new local version constraints. B1/B2/B3 now concern execution and publication of the selected Musashi workflow.

## Phase 1 — Design and contracts

- [data-model.md](data-model.md): inclusion/member/effect relations, exact representations, state transitions, current projections, authorization/key dimensions, Liquibase/replay/restore design.
- [contracts/chain-delivery.md](contracts/chain-delivery.md): transport requirements without fictional wire methods, typed adopted/rollback/failure boundary, envelope/closure/completeness semantics.
- [contracts/operator-messages.md](contracts/operator-messages.md): existing STOMP cutover and proposed messages, exact public values, credentials, review/authorization, reconciliation and UI states.
- [contracts/evidence-manifest.md](contracts/evidence-manifest.md): runtime/fixture provenance and independent acceptance contract.
- [contracts/network-configuration.md](contracts/network-configuration.md) and [normative schema](contracts/network-configuration.schema.json): exact manifest payload/identity/hash rules, existing remote catalog publication, coherent Musashi assets/reset identity, new relay/pool provisioning and role-specific configuration, without changing legacy-network requirements. The shared [synthetic vector](../../src/test/resources/dijkstra/network-manifest-v1-vector.json) checks serialization only, not genesis validity or qualification.
- [quickstart.md](quickstart.md): isolated prerequisites, current runnable build/start commands and end-to-end validation scenarios; not implementation bodies or a claimed existing acceptance harness.

### Implementation dependency order

1. Use available provenance and the normative manifest schema/vector as design inputs; retain T001's partial observations without fabricating a complete runtime manifest. Independent corpus, disposable targets and external publisher work are deferred and do not block coding.
2. Implement shared schema, exact representations and ordinary transaction/member/metadata mappings before their actual consumers. Implement per-network node-derived era cache, mismatch-triggered refresh, capability observations and current-era protocol snapshots/economic calculations before dependent authorization and caller integration. Preserve Conway configuration and era-appropriate optional fields.
3. Implement byte-preserving per-era/context decoding, one inclusion/member/effect commit authority and direct-chain queries; US2 adds nested identity/application semantics. Independent fixture authoring and comparisons remain pending rather than blocking this work.
4. Implement pool-certificate decoding, deposit/refund effects, reversible pool state and operation confirmation before dependent lifecycle consumers. US7 account/certificate/projection extensions depend on these US3 mappings, including T073 on T034. Full lifecycle qualification remains deferred.
5. Implement certified inclusion/recovery and B3 publisher/downloader/bundle selection against the schema/vector, retaining Conway provisioning. Actual publication and clean relay/pool qualification remain later user-coordinated evidence.
6. Migrate US6 transaction/UI callers and complete US7 interpretation and queries, including parent/child `directDeposits`, withdrawals and stake/governance effects. Full US6 Q5 acceptance follows both caller implementation and US7 acceptance. Retain supported earlier-era paths.
7. The user coordinates later test authoring and execution, full Q5, all-story recovery and G1–G6, including Q8's separate Conway/Dijkstra scenarios, clean provisioning and private fault/upgrade/restore rehearsals. T063 covers only partial Q8; T081 covers full Q8. Operator guidance must reflect actually exercised behavior. No hard-fork transition or cross-era rollback testing is required; no release gate is waived.

### Requirement and outcome traceability

| Requirements | Design coverage | Acceptance outcomes / gates |
| --- | --- | --- |
| FR-001, FR-002, FR-025 | Baseline/capability manifest, era-specific decoding, no unknown-era fallback | SC-006, SC-007; B1/B2, G1/G3 |
| FR-003, FR-004, FR-010, FR-011 | Explicit contexts, original bytes, distinct hashes, complete headers/bodies | SC-001, SC-002; G1/G3 |
| FR-005, FR-006, FR-007, FR-008, FR-009 | Own-ID ordered children, aggregate accounting, typed inputs, collateral-only invalid batches | SC-001, SC-003; G1/G2 |
| FR-012, FR-013, FR-014, FR-015 | Member-owned scripts/guards, explicit `directDeposits` account effects, certificates/governance and exact parameters; Q2a covers account-credit semantics | SC-001, SC-002, SC-006; G1/G3 |
| FR-016, FR-017, FR-018, FR-019, FR-020 | Reviewed encrypted-key workflow, pool registration lifecycle and independent installation/eligibility | SC-005, SC-006, SC-007, SC-008; B2, G3/G5 |
| FR-021, FR-022, FR-023 | EB closure/provenance, adoption versus announcement, disabled Peras | SC-002, SC-003, SC-007; B1, G1/G2/G3 |
| FR-024, FR-026, FR-029 | Existing workflow cutover, exact amounts, separate pending/confirmed/stale and whole-batch reconciliation; full Q5 acceptance after required US7 ingestion/query integration | SC-006, SC-007; G1/G3/G5 |
| FR-027, FR-028, FR-030 | Atomic immediate rollback, durable progress, verified historical replay and restore | SC-004, SC-008; G2/G4 |
| FR-031, FR-032 | Independent changed-family corpus plus real direct/nested/Leios/key/workflow/upgrade execution | SC-001, SC-002, SC-003, SC-004, SC-005, SC-006, SC-007, SC-008; G1–G5 |
| FR-033, FR-034, FR-035, FR-036 | Musashi remote catalog publication, complete coherent download, new relay/pool creation, immutable node selection and explicit reset/update | SC-009, SC-010; B3, G3/G4/G6 |
| FR-037, FR-038 | Supported Conway/prior-era paths, additive configuration/schema migration, per-network node-derived era cache with block-mismatch refresh and backend/UI enforcement; no era TTL or version inference | SC-011, SC-012; Q8, G1/G3/G4/G6; separate-era/cache checks, not fork-transition testing |

## Complexity Tracking

No constitution exception or alternate platform is proposed. Raw bytes plus typed projections preserve identity and query semantics; reversible changes enable immediate rollback; durable authorization handles uncertain submissions. Network-bundle snapshots prevent mutable catalog updates or repeated magic 164 from silently changing existing nodes. Supported Conway/earlier-era dispatch remains intentionally maintained; only obsolete duplicate paths are removed. The old local Dijkstra Cardano pins are removed, while evidence records remain exact. B1/B2/B3 are Dijkstra/Musashi execution/publication prerequisites, not global requirements for Conway operations or waived gates.

## Planning validation record

- Setup executed successfully through `.specify/scripts/bash/setup-plan.sh --json`; returned this feature directory, spec, plan and branch label.
- Initial Musashi-cutover document smoke passed: ten Markdown documents, 42 local links, two shell snippets parsed with `bash -n`, eight stories, sequential 36 FR and ten SC, complete plan traceability, explicit directDeposits coverage, and all eight public configuration assets plus manifest. Superseded local Cardano revision pins and unresolved template markers were absent. Shell snippets were not executed.
- Qualification update 2026-10-28: publication availability is observed, the upgraded CLI exposes BLS registration and the supplied node has documented bulk support. B1/B2 runtime scenarios and B3 immutable-provenance/application provisioning remain unexecuted, not passed. Earlier document-validation counts below/above describe their dated runs.
- No application code, database schema or remote configuration changed during planning/task generation. No application build/test, ledger execution, network submission, key generation, GUI runtime, migration or SSH operation was exercised.
- Task-generation validation passed for `tasks.md`: 80 sequential unchecked tasks across 11 phases, correct story/parallel labels and file paths, acyclic prerequisite edges, all 25 entity constraints quoted, all 36 FR and ten SC mapped, eight independent story criteria/parallel examples, 20 parallel-marked tasks in ten disjoint-file pairs, seven valid local links and balanced fences. These are document checks, not implementation or runtime evidence.
- Evidence-update validation 2026-10-28 passed: 11 documents, 53 local links including anchors, balanced fences, 80 sequential tasks with acyclic dependencies, 36 FR/ten SC and 25 verbatim entity contracts retained. All eight asset SHA-256 fingerprints in research match this run's downloads. Read-only checks were catalog/asset retrieval, CLI registration help, node run help and PR discovery; no application/runtime acceptance or remote mutation was exercised.
- Approved dependency/dual-era amendment: shared member/ordinary-metadata mappings and protocol economics now precede consumers; pool-effect ingestion and retirement/cancellation precede lifecycle/provisioning acceptance. FR-037/FR-038, SC-011/SC-012 and Q8 require standalone Conway/Dijkstra operation and per-network observed-era gating. No hard-fork transition or cross-era rollback test is required; full directDeposits and ordinary rollback scope remains.
- Amendment document validation passed: 84 sequential tasks, acyclic earlier-task dependencies and repaired semantic prerequisite edges, correct checklist/story labels/file paths, 38 FR and twelve SC mapped in plan/tasks, all 25 entity field/constraint contracts quoted, 11 Markdown documents with 58 valid local links/anchors, balanced fences and two shell snippets passing syntax-only `bash -n`. There are 20 parallel-marked tasks with disjoint paired file ownership.
- Manifest smoke passed against the added draft 2020-12 schema and shared synthetic vector: 36 invalid cases rejected with active RFC3339/RFC3986 assertions, positive integer/leap-day boundaries, duplicate-key parser checks and reset-versus-bundle mutation checks. Validation exposed a trailing-newline timestamp acceptance gap; exact length constraints fixed it. Stored vector hashes agree independently in Python and Node for its ASCII/integer/null values; this is not a general RFC 8785 conformance or real network test. Temporary validation dependencies were removed; no project dependency changes or runtime qualification were performed.
- Approved I5/I6/A1 amendment: full US6 Q5 acceptance is now T078 after US7 decoding/projection/query acceptance T077 and US6 caller integration T069; the US7 AccountEffect extension T073 explicitly depends on shared mapping T034. Era evidence now uses per-network node-query initialization and observed block/cache mismatch refresh, with no TTL or unconditional per-write query; only the node response updates current era. Pending/unavailable evidence blocks dependent writes, same-era provenance refresh preserves otherwise unchanged review, and confirmed changed era requires applicable qualification and renewed review. Conway/previous-era support, full directDeposits scope and ordinary rollback remain; no live hard-fork or cross-era rollback experiment was added.
- Amendment validation passed: 84 sequential tasks across 12 phases; correct checklist/story labels and file paths; acyclic task/story dependency graphs and repaired I5/I6 prerequisite paths; all 38 FR and twelve SC mapped; all 25 entity field/constraint quotations retained verbatim; 11 Markdown documents with 62 valid local links/anchors and balanced fences; two shell snippets passed syntax-only `bash -n`; eight story parallel examples and 20 parallel-marked tasks in ten independent disjoint-file pairs. These checks validate the documentation, not application behavior; no application build/test, node/CLI qualification, network submission or remote mutation was run for this amendment.

## Implementation setup observations — 2026-10-28

- At initialization, the implementation prerequisite command resolved this feature. The reviewer-owned checklist contained 16 checked and zero unchecked items; its markers were not changed. No T001–T084 task was complete at that checkpoint; current implementation status is recorded separately above and in tasks.md.
- Setup verification added missing root ignore rules for local environment files, logs, compiled artifacts, dependency/output directories and editor temporary files. `git check-ignore --no-index -v` exercised root/frontend/resource-directory environment files, logs, class/JAR files and dependency/build outputs; the Gradle wrapper JAR and Kotlin source remain eligible for tracking. The frontend package is private; no npm publishing ignore file is needed. No Docker, ESLint configuration, Prettier configuration, Terraform or Helm configuration was discovered in the project.
- Installed `/home/westbam/.local/bin/cardano-cli` has SHA-256 `2373b0f607f49d0d452e5bcbf5dbdadeacce13f885c4c8846c0a31d84617cfc8`; installed `/home/westbam/.local/bin/cardano-node-leios` has SHA-256 `196c0885dc1c87edecad2931aa3ba9ad5f19a6cdbe83a45dab443f38e1f7cf4a`. Each matches its discovered local optimized build artifact. Source checkout HEADs are CLI `90cc2b9ae147abacf8169c848ec10dc4296796f6` and node `746f1b3cbdaada2c3ed2f702b0946643248dd30b`. Supplied versions remain CLI 11.6.0.0 and node 11.2.0.164; their version commands were not rerun.
- Local Cabal plans report GHC 9.12.4 and Cabal 3.16.1.0. The node resolves Dijkstra ledger 0.5.0.0 at `9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`, API 11.9.0.0 at `b8023269a78db9eb19aea8a6fd037280ee6fc6cd`, consensus 5.1.0.1 at `a337d69f4946d5a1b20ecaaeae635dd47822e3d2`, and network 1.3.0.0 at `f0243fe6d962e922ab2c8e131543eba3d78748cb`. The CLI resolves CHaP packages Dijkstra ledger 0.4.0.0, API 11.11.0.0, consensus 5.2.0.0 and network 2.0.0.0. This difference requires operation-specific qualification; it is not proof of either compatibility or incompatibility and does not establish new local pins. The node's source-declared CLI revision is not the independently installed CLI checkout.
- A read-only `cardano-cli conway query tip --testnet-magic 1 --socket-path /home/westbam/haskell/pprod/db/socket --output-json` succeeded: era Conway, block 5346497, epoch 322, slot 137533138, hash `017da84de19701bb2bec2b75435373982dbf92ae5235759c84b83c265bed0c03`, reported sync progress 100.00. The selected configuration has four genesis files and no Dijkstra genesis; Shelley genesis identifies magic 1 and system start `2022-06-01T00:00:00Z`. This establishes a node-derived era observation, not full Conway workflow qualification or permission to modify that existing node.
- Public Musashi snapshot retrieval at `2026-10-28T19:40:58Z` parsed all eight catalog assets and checked the five config/genesis filename references. Shelley genesis identifies magic 164, system start `2026-10-18T00:00:00Z`, epoch length 21600, slots per KES period 129600 and maximum KES evolutions 62; configuration schedules Dijkstra at epoch zero. These are configuration observations, not a live Musashi era query, node-required genesis-hash validation or a publication/provisioning pass.
- T001 remains open pending complete qualification-input/owner assignment. No independent corpus or qualification environment record was found in the feature/test-resource locations. The test-database environment variables are unset; existing node processes are not designated disposable qualification targets. Do not import existing operator credentials, databases or node directories into fault/upgrade scenarios. Obtain explicit disposable-target and operator/reviewer ownership decisions before state-changing qualification. At this initial setup checkpoint T002–T084 were unchecked; their deferred evidence dependencies did not block subsequent code work. No signing, submission, node restart, database mutation, application build/test or SSH deployment occurred during these setup observations.

## Implemented physical layout and remaining source limits

- The logical schema is split across `043-operational-schema.xml`, `044-dijkstra-ledger.xml` and `045-dijkstra-provisioning.xml` under `src/main/resources/db/changelog/`, registered by `src/main/resources/db/liquibase-changelog.xml`. A database migration/restore rehearsal is still required.
- Reviewed wallet/governance/pool paths use `ReviewedWalletController`, `GovernanceReviewController`, `ReviewedPoolRegistrationController`, `ReviewedPoolRetirementController` and `PoolDeploymentService`, rather than adding a second transaction authority to the older controllers. `ReviewedTransactionIdentity` compares the exact saved parent/child batch before confirming an operation. Explicit `/walletreconcile` never automatically signs or resubmits.
- Transaction detail is `vue/src/components/WalletTransactionDetail.vue`; node-index discovery is `/node/chainstatus` in `TransactionController`. Index freshness is separate from node-process state.
- The obsolete `TransactionCache`, pending `Transaction` entity and `TransactionRepository` were removed; the historical SQL table is retained without automatic resubmission authority.
- T036/T037/T061 retain real upstream observation prerequisites: actual registration/snapshot capture epochs, unambiguous pool-specific committee attribution, and public-only loaded-key readback/possession proof. Unknown values remain null/unverified, not inferred from effective epoch, generic forging metrics or successful file installation. See [research section 13](research.md#13-implementation-source-observation-limits).

