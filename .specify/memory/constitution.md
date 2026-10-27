# JorManager Constitution

## Core Principles

### I. Operator-Friendly, Opinionated Workflows

JorManager MUST prioritize understandable GUI workflows for operating Cardano stakepools.
Changes to host management, node lifecycle, pool configuration, monitoring, and wallet operations
MUST identify the operator task they serve and expose the inputs, outcome, and failure state
needed to complete it. A supported default workflow MUST be documented; additional configuration
options MUST have a concrete operator use case rather than speculative flexibility.

Ease of use is an explicit project goal, not a claim of maximum security or configurability.
Any deliberate security-versus-convenience tradeoff MUST be recorded with its exposure and
required operator safeguards, consistent with the positioning in `docs/README.md`.

### II. Protect Funds, Credentials, and Operator Intent

Changes that handle signing material, SSH credentials, funds, or privileged host operations MUST
identify the sensitive data and external side effects involved. Secrets MUST NOT be committed,
emitted in diagnostics, or exposed through UI or API responses not required by the credential
workflow. New or changed state-changing workflows MUST validate their inputs and target host,
node, wallet, or network before carrying out the side effect.

An action that spends funds, changes credentials, or disrupts a managed node MUST require an
explicit operator request or a previously authorized automation policy. The workflow MUST expose
the affected target and consequence before authorization. Failed or uncertain external operations
MUST NOT be reported as successful; uncertain transaction outcomes MUST be reconciled before a
repeat submission is offered. These rules protect operators from unintended or duplicate effects.

### III. Preserve Operational and Upgrade Integrity

Changes to database schemas, persisted configuration, generated node files, or external-tool
contracts MUST describe their effect on existing installations as well as new installations.
Database schema changes MUST use the project's Liquibase migration mechanism. A database backfill
MUST NOT be represented as proof that remote configuration or running services were updated.

Breaking operational changes MUST include an upgrade guide identifying affected node roles,
prerequisites, automated versus manual steps, and observable post-upgrade checks. Changes that
can lose data or disrupt service MUST document backup and recovery requirements, including any
limits on rollback. Changes to node monitoring MUST distinguish unavailable or stale data from
current healthy state. This preserves operator trust during partial upgrades and remote failures.

### IV. Verify Observable Behavior

Every behavioral change MUST define acceptance criteria and provide verification evidence for
the affected path. Bug fixes MUST demonstrate the reported failure before the fix, using the
reported evidence when reproduction would be unsafe, and demonstrate the corrected outcome.
Regression tests MUST cover consumer-visible behavior, meaningful boundaries, or failure states
rather than copied implementation details.

Changes across the UI/backend, database, SSH, or Cardano-tool boundaries MUST exercise the affected
contract in an isolated environment. Changed user workflows MUST also receive a smoke run through
the actual UI, API, or command surface; passing unit tests alone is insufficient evidence. Review
notes MUST state commands or scenarios exercised, observed outcomes, and unverified assumptions.
Verification MUST NOT spend production funds, expose real credentials, or disrupt live pool nodes.
Documentation-only amendments MUST instead validate the affected document's structure, references,
and consistency; they do not require an application build or live-system exercise.

Quality gates MUST be selected using the integrated Software Observatory MCP server and the
risk-based procedure in Development Workflow. A sensor is an evidence-producing technique,
not a gate by itself; a gate applies an explicit acceptance rule to its observed result.
Catalog recommendations MUST inform, not replace, project-specific acceptance criteria or the
safety and verification requirements above. Multiple green checks sharing the same assumptions
MUST NOT be treated as independent proof of correctness.

### V. Keep Design Simple and Failures Diagnosable

Implementations MUST reuse existing project conventions and dependencies unless the change
records why they cannot meet the requirement. New abstractions or dependencies MUST solve a
current, identified problem. A replacement MUST migrate affected callers and remove obsolete
paths; any temporary coexistence MUST have a documented compatibility need and removal condition.

Failures at external boundaries MUST retain enough non-secret context to identify the operation,
affected resource, and cause. The UI MUST NOT present a failed or pending operation as completed.
Changes involving repeated monitoring, network connections, or background work MUST account for
resource ownership and cleanup. Complexity and diagnostic noise MUST NOT substitute for a clear
operator-visible outcome.

## Operational Constraints

- The supported baseline is a Linux deployment with a Kotlin/Spring Boot backend on Java 21,
  PostgreSQL with Liquibase migrations, and a Vue 3/TypeScript frontend built with Vite.
  Build files define exact dependency versions and frontend engine requirements.
- Changes to these platform assumptions MUST include a compatibility rationale, installation
  documentation updates, and verification against the proposed supported environment.
- Local and remote node operations MUST make their execution target explicit. SSH access and
  unattended credential use MUST document the required host and key protections; convenience
  MUST NOT be described as eliminating the security risk.
- Changes depending on Cardano node, CLI, or tracing behavior MUST identify and verify the supported
  external-tool contract. Unsupported combinations MUST produce a diagnosable failure rather than
  an invented successful result.
- `README.md`, `docs/README.md`, `docs/installation.md`, and applicable upgrade guides provide
  operator guidance. Changes affecting their instructions MUST update the relevant guidance in
  the same change. Documentation MUST distinguish supported behavior from unverified assumptions.

## Development Workflow

**Software Observatory and quality-gate selection**

The Software Observatory is available through an integrated MCP server that catalogs complementary
ways to examine software quality. Its families cover Structural, Behavioral, Test Effectiveness,
Invariants, Adversarial, Runtime, Change, Architecture, Evolution, and Human Comprehension evidence.
Entries explain the question a sensor answers, how to interpret its readings, how it can be
gamed, and what it cannot detect.

Before implementation, the change author MUST consult the integrated Software Observatory MCP
tools to determine quality gates and record the selection in the existing specification, plan,
or review record. The tool names below refer to that server's tools. Website browsing, web search,
and published web pages MUST NOT substitute for MCP consultation.

- Describe the change's failure modes and consequences, then use `suggest_sensors` to discover
  candidates and `list_families` to inspect the available evidence families. Suggestions are
  keyword matches, not authored recommendations or an assessment of this repository.
- Read each selected sensor's full entry with `get_sensor`. Evaluate its applicability, oracle
  (the basis for judging correctness), independence from the implementation, scope, latency,
  actionability, and blind spots. Record its ID, the MCP tool calls and relevant returned
  guidance, and the consultation date so reviewers can identify the evidence used.
- Run `stack_coverage` on the selected IDs to inspect family and confidence-stack coverage.
  Record uncovered families and unresolved doubts, with a risk-based explanation of exclusions.
  Family coverage counts representation, not independent evidence or correctness; suggested gap
  fillers are examples, not ranked choices. Neither full family coverage nor a fixed sensor
  count is required.
- For each gate, record the failure mode addressed, concrete command or review procedure, scope
  and safe environment, expected evidence, pass/fail criterion, responsible owner, and decision
  point (before merge, before release, or operational follow-up). Numerical thresholds MUST have
  a risk-based rationale established before observing results, not be adjusted to turn red green.
- Select complementary evidence for the actual risks. For example, a database migration needs
  schema/data-integrity and upgrade-path evidence; an SSH or Cardano-tool boundary needs real
  integration or contract evidence and failure-path checks; a funds or credential change needs
  invariant/security evidence and independent review; a UI workflow needs operator-visible
  behavior evidence. The relevant full entries, rather than these examples alone, MUST guide
  the selection. Existing checks MAY satisfy a gate when they address its specific failure mode.

For example, `get_sensor` with `query: "SO-002c"` returns Integration Tests guidance on why mocked
dependencies cannot establish real-boundary correctness. `get_sensor` with `query: "SO-010"`
returns Independent Review guidance on specific findings and evidence rather than mere approval.
These are reference examples, not a fixed mandatory sensor set for every change.

At the gate's decision point, reviewers MUST inspect actual readings and unresolved blind spots,
not just tool exit codes, coverage percentages, or approval counts. A failed, skipped, flaky, or
unavailable required check MUST NOT be reported as passed. Blocking gates MUST prevent the
associated merge or release unless a Governance exception is explicitly approved. Operational
follow-up MUST NOT substitute for evidence required before merge or release.

If the integrated MCP server or a required tool is unavailable, record the failed or unavailable
tool and the resulting consultation gap as a blocker. Previously recorded MCP evidence MAY
inform the analysis but MUST NOT be represented as a fresh consultation or silently satisfy a
missing required assessment. The gap MUST remain unresolved until MCP access is restored and
the assessment completed, or a Governance exception is approved. MCP unavailability MUST NOT
silently waive existing gates or trigger a website fallback.
Documentation-only changes retain the document-focused validation allowance in Principle IV;
MCP consultation MUST NOT impose unrelated application builds or production experiments.

**Change execution and review**

1. Scope the operator need, affected boundaries, acceptance criteria, and operational risks before
   implementation. Determine Observatory-informed gates and identify applicable principles and
   any proposed exception in the change record using the procedure above.
2. Inspect existing implementation and documentation before introducing a new pattern. Include
   callers, persisted state, remote files, and operator instructions in impact analysis when affected.
3. Implement the smallest complete change, including required migrations and caller updates.
   Keep unrelated refactors and speculative features outside the change.
4. Execute the selected gates, relevant existing checks, and Principle IV verification.
   Use the repository Gradle wrapper for backend checks and the scripts in `vue/package.json`
   for frontend checks. Record missing prerequisites instead of claiming unexecuted checks passed.
5. Before merge, review correctness, secret handling, failure behavior, upgrade compatibility,
   gate results, residual risks, and documentation. Reassess sensor selection when scope or
   evidence reveals a new failure mode. Unresolved violations MUST block merge unless an
   exception is explicitly approved under Governance.

## Governance

This constitution governs project design and development decisions. Repository guidance that
conflicts with it MUST be reconciled through an explicit amendment or a documented exception,
not silently ignored. RFC 2119 MUST, MUST NOT, and SHOULD carry their normative meanings; any
use of SHOULD MUST state the rationale for permitted deviation.

An amendment MUST record the proposed rule changes, rationale, impact on existing practices,
version classification, and any required transition work. A maintainer MUST approve it before
merge. Reviews of specifications, plans, and implementation changes MUST check the applicable
principles and record unresolved compliance issues. Exceptions MUST identify the exact rule,
reason, risk, mitigation, approving maintainer, and expiry date or removal condition.

Constitution versions are independent of application release versions and follow semantic
versioning: MAJOR for incompatible principle removals or redefinitions; MINOR for new principles,
new sections, or materially expanded guidance; PATCH for clarifications and non-semantic wording
corrections. The original ratification date MUST be retained once confirmed; the last-amended
date MUST change only when the constitution changes. Confirmed dates MUST use YYYY-MM-DD format.

Amendments MUST include a temporary Sync Impact Report for review. That report is not governance
content and MUST be removed before the amended constitution is committed. Constitution updates
MUST NOT rewrite template layers or dependent commands; those consumers read the constitution
at runtime. Any discovered consumer incompatibility MUST be recorded for a separately scoped
follow-up rather than silently expanding the amendment.

**Version**: 1.1.2 | **Ratified**: 2026-10-27 | **Last Amended**: 2026-10-27
