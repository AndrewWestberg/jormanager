# Implementation Plan: Dijkstra Era Support

**Branch**: `001-dijkstra-era-support` (reported by setup-plan; no branch creation/switch performed here) | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: `specs/001-dijkstra-era-support/spec.md`

**Qualification status:** B1/B2 runtime integration and B3 selected-file provisioning remain open; no end-to-end JorManager qualification is claimed.

**Execution direction (2026-10-28):** The user requests code first, with no qualification environment provisioning or feature test authoring/execution in this pass. The user coordinates later manual integration testing, qualification, owners and reviewers. The parent integration owner may perform local compiler/smoke checks after integration; such checks are not runtime qualification. Code uses available authoritative source contracts and actual code-interface dependencies, not completion of B1–B3, G1–G6, independent corpus setup or a story's runtime checkpoint. All eight stories and their requirements remain in scope. Deferred evidence remains required for acceptance and full-feature release/runtime claims; it is not a constitution waiver.

Code and qualification status are tracked in [tasks.md](tasks.md). Local checks are not runtime qualification; the [operator guide](../../docs/dijkstra-upgrade-guide.md) states current limits.

## Summary

Support the complete Musashi Dijkstra data/transaction baseline, including nested members, directDeposits, certified inclusion and Leios keys. Create relays and pools using individually selected network files while retaining Conway and prior-era workflows.

Use the current Kotlin/Spring/Vue application, era/context-specific decoding with original byte spans, a durable inclusion/member/effect model, existing UTxO tables as its maintained projection, and a single atomic adoption/rollback boundary per network. Extend existing STOMP/GUI workflows for capabilities, exact amounts, chain freshness and separately observed key registration/eligibility/installation. Liquibase plus verified replay handles upgrades; remote binary/config/key deployment remains a separately authorized operation.

**Explicit directDeposits scope:** support transactions containing `directDeposits` (body key 25) at both top-level and child level, through decoding, exact coin-valued account-effect indexing, query/UI attribution, certified inclusion, atomic rollback and replay. This is an account-credit field, not a separate transaction envelope, UTxO output, stake-registration deposit or multiasset transfer. Validation scenario Q2a is mandatory; no payment-output-only filter may discard these transactions.

The Dijkstra qualification baseline is the official supported Musashi Dōjō network and its observed ledger/node/CLI behavior. Record exact source, binary and configuration identities as evidence, not independent pins. Operators own resets; existing nodes are not silently mutated.

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

**Scale/Scope**: Eight stories, 38 functional requirements, twelve success criteria: Dijkstra data families and directDeposits, Conway and Dijkstra operations, current-era authority, pool keys, direct file selection and provisioning, persistence, GUI and recovery.

## Constitution Check

Constitution: `.specify/memory/constitution.md`, version 1.1.2. Musashi is the Dijkstra qualification baseline; direct file selection supports provisioning while Conway and prior eras remain supported. No constitution gate is waived.

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
| B3 | Actual selected-file relay/pool provisioning and network identity remain unqualified | Validate selected files and demonstrate fresh relay and pool on disposable targets with independent observations | Before claiming Dijkstra provisioning support |

Existing catalog observations are inputs, not qualification. No publication, manifest or reset lifecycle is required; operators own resets.

### Observatory-informed execution gates

MCP calls, full-entry guidance, oracle independence and exclusions are in [research.md](research.md), section 12. The user coordinates gate owner/reviewer assignment during later qualification; missing owner/evidence is not a pass or a code-start blocker. Public-network Dijkstra checks use disposable Musashi resources; Conway compatibility uses isolated supported Conway targets. Disruptive experiments run only in private environments. Reuse the recorded sensor selection for these existing boundary/upgrade risks; this amendment does not claim a fresh MCP consultation or any runtime pass.

| Gate / sensors | Failure mode, scope and concrete procedure | Expected evidence and pass/fail rule | Owner / decision |
| --- | --- | --- | --- |
| G0 — SO-010b | Review document links, FR/SC mappings, task ordering and direct-file contract; preserve Musashi authority and Conway operation | No omitted requirement, unsupported success claim or global Dijkstra prerequisite; does not clear B1/B2/B3 | Plan author / before planning delivery |
| G1 — SO-005c, SO-004b | Wrong bytes/IDs, lost children, rounded values, wrong batch/account/collateral effects or era selection: independent corpus per evidence manifest through actual codec/projections, including supported historical formats and separate Conway/Dijkstra cases; run focused JUnit scenarios and full `./gradlew test` | Every changed family/boundary, accepted/rejected results and exact IDs/effects agree; 100 approved batches include successful/script-failed/certified cases; zero unexplained mismatches/duplicates/omissions; no self-generated expectations or implicit unknown-era fallback | Ledger implementer plus independent ledger reviewer / before merge of relevant behavior and release |
| G2 — SO-004b, SO-005d, SO-002c | Incomplete adoption, restart/rollback corruption: disposable PostgreSQL with real Liquibase; replay, repeated delivery, kill/restart before/during/after commit, explicit rollback before replacement, missing closure, old in-flight branch completion | Named constraints hold; durable point never passes a gap; all state equals uninterrupted reference; no stale effects after rollback; record fault point and SQL/state comparison, not only test exit | Persistence maintainer / before merge and release |
| G3 — SO-002c, SO-010 | External/API/UI mismatch, unsafe spend/key handling or Dijkstra behavior on Conway: real node/CLI/SSH plus actual browser using quickstart Q1–Q8, independently established Conway/Dijkstra targets, mixed-network isolation, incompatible combinations, uncertain submission and failed installation | Correct target/fee/deposit/review and era-specific CLI/certificates; Conway works without Dijkstra prerequisites and rejects Dijkstra-only actions; Dijkstra operations require observed Dijkstra plus qualified capabilities; chain IDs, retained key on ordinary update, independent confirmation/eligibility/installation and zero secret disclosure; mocks do not qualify, and fork-transition testing is not required | Integration maintainer and independent operator/security reviewer / before merge of affected workflows and release |
| G4 — SO-004b, SO-002c, SO-005d | Upgrade loss or unrecoverable rollout: restore sanitized historical database/config backup in isolation, including a current Conway installation; Liquibase+era-appropriate replay, compare fresh/upgraded at the same point, rehearse recovery, independently verify remote config | Exact reference historical IDs and operator configuration retained, equivalent indexed state and usable Conway workflows without fabricated Musashi/Dijkstra assignments; documented restore works; migration never claims or forces remote binary/config/key upgrades | Release/persistence maintainer / before release |
| G5 — SO-010, SO-010b | Unreadable operator workflow or unjustified safety claim: independent engineer reviews callers, schema, secrets, scripts, stale state and runbook; representative operator explains statuses after real workflow | All substantive objections resolved with evidence; operator distinguishes submitted/confirmed/eligible/installed; README, installation and applicable upgrade guidance match exercised behavior | Independent reviewer and release maintainer / before merge and release |
| G6 — SO-002c, SO-005d, SO-012b | Exercise selected-file validation and Q0/Q8 through actual UI/backend/managed host; inject missing and hash-mismatched files before writes | First relay works without an observer; coherent files, pool creation and Conway isolation are independently observed; failed validation produces no side effects | Provisioning maintainer / before qualification |

**Direct-file delivery gates — 2026-11-05:** Observatory MCP consultation selected SO-002c integration tests, SO-012b smoke tests and SO-005d fault injection. Stack coverage is behavioral/adversarial only; other families are uncovered for this narrow change, supplemented by compiler/type checks, not waived release gates. Backend compilation/focused regressions, Vue type/build/tests, rendered Musashi/Conway wizard smoke and isolated live-catalog selection validation passed as recorded [below](#direct-file-delivery-verification). Missing/hash-mismatched inputs were rejected before installation; controller tests cover rejection before host access. Mocks do not prove real integration, and a passed smoke does not qualify a release. Actual DB/SSH/node-start gaps remain explicit; no production chaos or load experiment.

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
│   └── network-configuration.md
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
src/test/resources/            # independent era corpora and evidence manifests
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

Use the existing ordinary file catalog and imports. Select genesis, config, topology and optional peer files individually; validate their contents before side effects. No manifest or reset workflow. Preserve Conway provisioning.

## Phase 0 — Research outcomes

[research.md](research.md) retains protocol/source claims, `directDeposits` coverage and independent runtime evidence. B1/B2/B3 remain open qualification obligations.

## Phase 1 — Design and contracts

- [data-model.md](data-model.md): inclusion/member/effect relations, exact representations, state transitions, current projections, authorization/key dimensions, Liquibase/replay/restore design.
- [contracts/chain-delivery.md](contracts/chain-delivery.md): transport requirements without fictional wire methods, typed adopted/rollback/failure boundary, envelope/closure/completeness semantics.
- [contracts/operator-messages.md](contracts/operator-messages.md): existing STOMP cutover and proposed messages, exact public values, credentials, review/authorization, reconciliation and UI states.
- [contracts/evidence-manifest.md](contracts/evidence-manifest.md): runtime/fixture provenance and independent acceptance contract.
- [contracts/network-configuration.md](contracts/network-configuration.md): individual file selection, validation, relay bootstrap and reviewed producer deployment.
- [quickstart.md](quickstart.md): isolated prerequisites, current runnable build/start commands and end-to-end validation scenarios; not implementation bodies or a claimed existing acceptance harness.

### Implementation dependency order

1. Use available provenance as evidence inputs; retain T001 partial observations without fabricating a runtime manifest. Independent corpus and disposable targets are deferred.
2. Implement shared schema, exact representations and ordinary transaction/member/metadata mappings before their actual consumers. Implement per-network node-derived era cache, mismatch-triggered refresh, capability observations and current-era protocol snapshots/economic calculations before dependent authorization and caller integration. Preserve Conway configuration and era-appropriate optional fields.
3. Implement byte-preserving per-era/context decoding, one inclusion/member/effect commit authority and direct-chain queries; US2 adds nested identity/application semantics. Independent fixture authoring and comparisons remain pending rather than blocking this work.
4. Implement pool-certificate decoding, deposit/refund effects, reversible pool state and operation confirmation before dependent lifecycle consumers. US7 account/certificate/projection extensions depend on these US3 mappings, including T073 on T034. Full lifecycle qualification remains deferred.
5. Implement certified inclusion/recovery and validated individual file selection, retaining Conway provisioning. Clean relay/pool qualification remains deferred.
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
| FR-033, FR-034, FR-035, FR-036 | Individual selected files, validation before side effects, relay/pool creation and operator-owned resets | SC-009, SC-010; B3, G3/G4/G6 |
| FR-037, FR-038 | Supported Conway/prior-era paths, additive configuration/schema migration, per-network node-derived era cache with block-mismatch refresh and backend/UI enforcement; no era TTL or version inference | SC-011, SC-012; Q8, G1/G3/G4/G6; separate-era/cache checks, not fork-transition testing |

## Complexity Tracking

No constitution exception is proposed. Raw bytes, reversible changes and durable authorization preserve correctness. Selected files and local producer snapshots do not imply a publication or reset protocol. Conway/earlier-era dispatch remains supported; B1/B2/B3 remain open.

## Planning validation record

- Setup executed successfully through `.specify/scripts/bash/setup-plan.sh --json`; returned this feature directory, spec, plan and branch label.
- Historical document smoke for the superseded manifest design checked ten Markdown documents, 42 local links, two syntax-only shell snippets, eight stories, 36 FR and ten SC. This is not evidence of an upstream manifest or current selected-file provisioning; the local manifest contract has been removed.
- Qualification update 2026-10-28: catalog availability and CLI help were observed; B1/B2 runtime scenarios and selected-file B3 provisioning remain unexecuted. Earlier document-validation counts describe their dated runs.
- No application code, database schema or remote configuration changed during planning/task generation. No application build/test, ledger execution, network submission, key generation, GUI runtime, migration or SSH operation was exercised.
- Task-generation validation passed for `tasks.md`: 80 sequential unchecked tasks across 11 phases, correct story/parallel labels and file paths, acyclic prerequisite edges, all 25 entity constraints quoted, all 36 FR and ten SC mapped, eight independent story criteria/parallel examples, 20 parallel-marked tasks in ten disjoint-file pairs, seven valid local links and balanced fences. These are document checks, not implementation or runtime evidence.
- Evidence-update validation 2026-10-28 passed: 11 documents, 53 local links including anchors, balanced fences, 80 sequential tasks with acyclic dependencies, 36 FR/ten SC and 25 verbatim entity contracts retained. All eight asset SHA-256 fingerprints in research match this run's downloads. Read-only checks were catalog/asset retrieval, CLI registration help, node run help and PR discovery; no application/runtime acceptance or remote mutation was exercised.
- Approved dependency/dual-era amendment: shared member/ordinary-metadata mappings and protocol economics now precede consumers; pool-effect ingestion and retirement/cancellation precede lifecycle/provisioning acceptance. FR-037/FR-038, SC-011/SC-012 and Q8 require standalone Conway/Dijkstra operation and per-network observed-era gating. No hard-fork transition or cross-era rollback test is required; full directDeposits and ordinary rollback scope remains.
- Amendment document validation passed: 84 sequential tasks, acyclic earlier-task dependencies and repaired semantic prerequisite edges, correct checklist/story labels/file paths, 38 FR and twelve SC mapped in plan/tasks, all 25 entity field/constraint contracts quoted, 11 Markdown documents with 58 valid local links/anchors, balanced fences and two shell snippets passing syntax-only `bash -n`. There are 20 parallel-marked tasks with disjoint paired file ownership.
- Historical manifest smoke exercised an obsolete configuration schema/vector, since removed; it is not selected-file validation or runtime qualification.
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

- The logical schema is split across `043-operational-schema.xml`, `044-dijkstra-ledger.xml`, `045-dijkstra-provisioning.xml` and `046-node-selected-files.xml` under `src/main/resources/db/changelog/`, registered by `src/main/resources/db/liquibase-changelog.xml`. Changeset 046 adds selected-file references and preserves existing network-key discrimination. A database migration/restore rehearsal is still required.
- Reviewed wallet/governance/pool paths use `ReviewedWalletController`, `GovernanceReviewController`, `ReviewedPoolRegistrationController`, `ReviewedPoolRetirementController` and `PoolDeploymentService`, rather than adding a second transaction authority to the older controllers. `ReviewedTransactionIdentity` compares the exact saved parent/child batch before confirming an operation. Explicit `/walletreconcile` never automatically signs or resubmits.
- Transaction detail is `vue/src/components/WalletTransactionDetail.vue`; node-index discovery is `/node/chainstatus` in `TransactionController`. Index freshness is separate from node-process state.
- The obsolete `TransactionCache`, pending `Transaction` entity and `TransactionRepository` were removed; the historical SQL table is retained without automatic resubmission authority.
- T036/T037/T061 retain real upstream observation prerequisites: actual registration/snapshot capture epochs, unambiguous pool-specific committee attribution, and public-only loaded-key readback/possession proof. Unknown values remain null/unverified, not inferred from effective epoch, generic forging metrics or successful file installation. See [research section 13](research.md#13-implementation-source-observation-limits).


## Direct-file delivery verification

Local checks on 2026-11-05:

- Clean Kotlin build and focused `NodeControllerTest`, `NodeConfigurationFilesTest` and `JsonMessageContractTest`: 35 passed, one existing disabled test, no failures. The first-relay controller case has no observer; missing selections and existing targets reject before writes.
- All five Vue test files: 76 passed. `vue-tsc --noEmit`, Vite production build and `./gradlew bootJar` succeeded. Frontend build/test shell scripts now stop on failure rather than packaging stale assets after an error.
- Actual production UI served in an isolated browser with synthetic host/catalog state and backend access disabled: eight ordinary Musashi selections reached review and produced a first/default-relay request; a separate Conway selection reached review with Dijkstra and peer fields empty. No manifest or reset fields were present. This is surface proof, not a live server/session check.
- Captured Musashi browser JSON bound to the real `CreateNodeRequest`. The real `NodeConfigurationFiles` resolver/validator used all eight current files fetched read-only from the public catalog, preserved genesis bytes and bootstrap peers, and normalized `peerSnapshotFile`. Removing the referenced Dijkstra genesis or replacing Conway genesis with `{}` failed validation. No database, SSH, node process or transaction was used.
- Liquibase 5.0.4 rendered changeset 046 against offline PostgreSQL 18: two nullable file-ID columns added and the old network-key discriminator column renamed, with no data-drop statements. This is parser/SQL-generation proof, not migration or restore execution.

Independent real relay synchronization, producer registration/startup/key observation, PostgreSQL migration/restore and the full feature qualification suite remain pending. Temporary smoke sources/services were removed; no qualification target or remote configuration was changed.

## Handshake and genesis recovery correction

2026-11-05: the operator reports accepted node-to-node v14 but missing-input failure at slot 132. Read-only inspection found empty legacy Shelley `initialFunds`, two allocations in `extraConfig.initialFunds.data`, and an already-seeded JorManager network with zero UTxOs and two indexed empty blocks. The correction must investigate newer supported handshake versions independently of this ledger failure, preserve legacy networks, and never recreate spent funds or reset the managed node.

Fresh Software Observatory MCP consultation used `suggest_sensors`, `list_families`, full `get_sensor` entries for SO-002c, SO-013b and SO-007c, then `stack_coverage`. Selected guidance: exercise real dependencies rather than treating mocks as integration proof; declare recovery invariants before promotion and do not weaken them to pass; compare external version semantics and actual consumers, not only message shape. Coverage spans behavioral, invariants and change; structural, test-effectiveness, adversarial, runtime, architecture, evolution and comprehension families are not represented by selected sensors. Compilation supplements structure; focused failure cases and read-only node smoke address immediate boundary risks. No live chaos/load experiment or full Leios qualification is claimed.

The integration owner must pass these delivery gates before reporting completion:

- Protocol: source-derived proposal/acceptance vectors for newer compatible versions, retained 13/14 negotiation, and rejection of malformed/unoffered/wrong-network responses; exercise the actual handshake implementation against the local Musashi node without starting live application ingestion or changing node state. Do not advertise capabilities whose changed wire semantics remain unsupported.
- Genesis: independent expected IDs/amounts for legacy and embedded extra-config funds, upstream conflict/null rules, and explicit rejection of unsupported external data. Preserve existing strict JSON and coin boundaries.
- Recovery: real isolated PostgreSQL exercises the production registration path. Fresh initialization and repeat registration are correct; safe recovery preserves empty-block history and other networks; spent projections are not recreated; any existing transaction history prevents automatic restoration of missing genesis rows; concurrent registration cannot duplicate funds and failed recovery is atomic. No live application database mutation is part of verification.

Recovery is limited to proven-safe initialization repair under the existing per-network writer lock, not a generic reset/replay service. Ambiguous historical state must remain unavailable with a diagnostic requiring controlled recovery. Deployed files, credentials and managed services remain unchanged.

Verification completed for this correction on 2026-11-05:

- Pinned network `2f1c154f248659b89523d5679ca532f442e7ed2a` defines v14–16; pinned consensus `2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b` calls v15 latest released and maps v14–16 to the same Cardano block codec. JorManager retains v13 compatibility, adds v15/v16, and sends the v16 Peras field as false. No additional Peras mini-protocol is enabled.
- An independent review of the recovery guard identified that pre-session failure could retain an old complete read state. The observer now begins its fenced session before config/genesis validation and registration, allowing the existing failure handler to commit incomplete coverage. Reviewed guard conditions cover all retained transaction batches, not just adopted ones, and existing spent rows prevent reseeding.
- Focused `HandshakeProtocolTest`, `GenesisLedgerDecoderTest`, `GenesisRecoveryIntegrationTest`, `MoshiConfigurationTest` and `NodeControllerTest`: 41 passed, one existing skipped, no failures. Six opt-in PostgreSQL tests use unique disposable databases and actual Liquibase migrations to cover idempotence, network isolation, preserved history/spent rows, unsafe-history refusal, database-failure atomicity and concurrent registration.
- A throwaway runtime smoke used the production handshake, genesis decoder, block decoder, projector and coordinator, reading the actual local Musashi node and writing only a separate PostgreSQL 18 cluster. Acceptance was `8301108518a4f500f4f4`: v16, magic 164, initiator-only, peer sharing/query/Peras disabled.
- All 46 migrations initialized the smoke database. Actual slots 16 and 59 had no transaction batches. After bounded restoration of the two independently checked genesis allocations, actual slot 132 (`6ec6eb0580774046d3eb1015fea8cc9c72edca3e8a66aff9cbba0634673a3b82`) committed one batch and spent one restored genesis output. Repeat registration did not resurrect it. `bootJar` succeeded.

These checks do not complete T019's broader delivery/era/Leios obligations or full qualification. No live application database, deployed file or managed service was changed; no transaction was signed/submitted. Full catch-up and existing-installation migration/restore remain unverified.

## Restore operator progress logging and simplify Nodes

Historical record: the logging behavior below is superseded by **Restore the pre-Dijkstra sync log contract**. The Nodes-column removal and preserved Wallet/API safeguards still apply.

2026-11-05: the operator approved restoring INFO index progress and caught-up/failure events and removing the verbose Network index column. Historical evidence: `10b1e51` removed the previous ten-second INFO progress messages and added the column; raw ChainSync messages were already TRACE. Scope is coordinator diagnostics and the Nodes presentation/helper only. Node-index store associations remain consumed by Leios status requests; Wallet readiness, status APIs, persistence, era activation and spending gates must remain unchanged.

Fresh MCP calls: `suggest_sensors`, `list_families`, full `get_sensor` for SO-002c and SO-007c, and `stack_coverage`. Guidance applied: real boundary checks are not replaced by mocks; inspect actual API consumers before removal, and verify behavior as well as shape. Selected families are behavioral/change; structural, test-effectiveness, invariants, adversarial, runtime, architecture, evolution and comprehension are uncovered by the selected catalog entries. Compiler/type checking, focused transition tests, real log capture and browser inspection address the immediate risks; no schema, credential or funds logic changes justify production experiments or broad new qualification.

Before delivery, the implementation owner must pass: (1) deterministic logging regressions for the existing ten-second operator cadence, per-network throttling, immediate caught-up/failure transitions, deduplicated failures and no catch-up claim for incomplete state; (2) an isolated PostgreSQL runtime smoke demonstrating logs use committed last-complete points and Wallet reads still reject incomplete coverage; (3) a real browser smoke of Nodes without the verbose column and Wallet retaining syncing/unavailable indications, with live backend connections blocked. Build/type checks must pass. No node, live database, signing or remote operation is authorized. Browser synthetic state verifies presentation, not backend integration; database smoke supplies separate boundary evidence.

Delivery evidence: all five deterministic logging tests and seven real PostgreSQL integration tests passed. The added database case checks committed-point logging, caught-up eligibility and rejection of Wallet reads after incomplete coverage. A separate throwaway runtime using the production coordinator/projector and an isolated PostgreSQL 18 cluster emitted INFO progress at committed slot 42 versus observed tip 1020 after ten seconds, an immediate caught-up event, and one WARN for two identical failure reports; assertions confirmed Wallet reads reject incomplete coverage. Synthetic blocks/observations exercise logging and readiness, not a real network catch-up.

The production Vue build passed type checking, and `bootJar` succeeded. A browser smoke of the static production preview rendered dojo/brainy without the Network index column and preserved Wallet's syncing/unavailable states and stale-balance warning. Browser state and transport were synthetic, with no live application backend; the expected offline connection notification is not evidence of a live-service failure. No live node, application database, signing or remote operation was exercised. The full frontend test suite and full chain synchronization were not re-run for this presentation/logging change.

## Restore the pre-Dijkstra sync log contract

2026-11-06: operator-provided before/after logs establish the regression. Restore `ChainSync: Saved block` (including the nine-character pool-ID prefix) and `BlckFetch: Saved block` with block heights and a two-decimal, truncated synchronization percentage. Restore INFO logger names `ChainSyncProtocol` and `LedgerDao`, every saved block at tip and ten-second throttling while behind. Remove the replacement coordinator progress/state logger. Existing failure reporting, Wallet/status APIs and ingestion safeguards remain unchanged. The new single-block fetch pipeline no longer commits ranges; do not restore the old batching architecture just to print ranges.

Fresh MCP consultation: `suggest_sensors`, `list_families`, full SO-002c `get_sensor`, and `stack_coverage`. SO-002c requires actual dependency boundaries rather than mocked persistence as proof. Behavioral coverage selected; structural, test-effectiveness, invariants, adversarial, runtime, change, architecture, evolution and comprehension families remain uncovered by catalog selection. This logging-only change does not alter schemas, node protocols or funds logic; targeted compiler/regression checks and isolated runtime capture are proportionate, not full network qualification.

Implementation owner gates before delivery: deterministic tests must prove ten-second catch-up throttling, every-block tip logging and per-connection independence; a disposable PostgreSQL smoke must exercise successful header/block commits and a failed block save, show the requested concise output and no false saved-block success on failure. Run focused Gradle tests and package the JAR. Use only synthetic chain input and a separate local database; no live node or application database changes. Preserve the existing Wallet incomplete-coverage regression while deleting assertions tied to the superseded logger.

Delivery evidence (2026-11-06): `JORMANAGER_TEST_DATABASE_URL=<disposable local database> ./gradlew test --tests '*SyncLoggingTest' --tests '*GenesisRecoveryIntegrationTest' bootJar -x testVue -x buildVue --console=plain` passed all nine tests, with no skips. Cadence regressions cover catch-up throttling, consecutive tip blocks, headers ahead of the advertised tip, and independent connections; the seven PostgreSQL cases retain Wallet readiness and recovery protections.

A throwaway `syncLogsSmoke` Gradle task initialized all 46 migrations in a separate PostgreSQL 18 cluster and used the real Spring Data JPA `ChainRepository`, Exposed coordinator/projector and both protocol receive paths with synthetic Dijkstra wire input. Observed INFO output was `ChainSync: Saved block: 1 of 1, 100.00% synced, poolId: f9dca21a6...` and `BlckFetch: Saved block: 1 of 1, 100.00% synced`, then both messages for block 2 immediately afterward. A malformed block 3 saved its valid header but produced no BlckFetch success message, retained complete ledger point 2 and marked coverage incomplete. No `Chain index` progress messages remained. This proves the logging/persistence boundary, not live chain synchronization; no live node or application database was used. Frontend files were unchanged and their build/tests were not repeated.
