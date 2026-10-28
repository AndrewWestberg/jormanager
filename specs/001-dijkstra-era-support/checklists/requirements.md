# Specification Quality Checklist: Dijkstra Era Support

**Purpose**: Validate specification completeness and quality before proceeding to planning  
**Created**: 2026-10-27  
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **Historical review result: PASS — 16/16 specification-quality items** before the coexistence amendment; current document consistency requires a new review. “Feature meets measurable outcomes” means the specification defines complete, verifiable outcomes; it does not mean the unimplemented feature has passed runtime acceptance.
- **Iteration 1:** The account scenario said “starting or resulting balance constraints,” which could incorrectly imply a post-effect check. Replaced it with “balance constraints at their ledger-defined checkpoints.” Ordered all P1 stories before P2 stories and added the rationale and limits of the 100-batch acceptance minimum. Removed unneeded diagnostic details whose exact output was not retained in the evidence record.
- **Iteration 2:** Manual review passes all items. No unresolved product-scope questions or clarification markers remain in the specification. Technical source inventories, numeric wire fields, repository paths, codec choices and commands are confined to [research.md](../research.md), not prescribed as implementation design in the stakeholder specification.
- **Mandatory sections:** Eight independently testable user journeys; Given/When/Then acceptance scenarios; explicit edge cases; 38 functional requirements; eight entity groups; twelve measurable success criteria; assumptions and bounded exclusions.
- **Value and observability:** Users can create new Musashi relays/pools from the remote catalog, trust complete balances, find child transactions and directDeposits, distinguish submitted/confirmed/failed/stale states, manage Leios keys safely and recover after upgrade/reset/rollback.
- **Source/authority gate:** Musashi Dōjō replaces independently selected local Cardano pins for Dijkstra qualification only, not for other supported networks/eras. Exact source/binary/configuration identities remain recorded evidence. Research distinguishes candidate-source facts, deployed runtime qualification, proposals and disabled Peras; former certificate-only, BLS-reservation and removed-minPoolCost assumptions are withdrawn.
- **Remaining implementation evidence dependencies:** As of 2026-10-28, eight Musashi catalog assets are downloadable and the upgraded CLI/bulk-capable node are available; read-only help and upstream PR #6749 provide concrete contracts. Remaining work is immutable provenance/application download and selection, actual delivery/key/tool/GUI/SSH/database/upgrade scenarios and new relay/pool qualification. These are not passed by publication or document validation; research section 7 records the evidence split.
- **Historical document validation record:** Prior Musashi-cutover checks reported 42 local links, balanced code fences, two shell snippets syntax-checked, eight stories, sequential FR-001–FR-036 and SC-001–SC-010 with plan mappings, retained explicit directDeposits, all eight public assets plus manifest and no superseded local Cardano revision pins. Those checks predate the present coexistence amendment and are not validation of it or runtime/publication evidence.
- **Discovery smoke:** `bash .specify/scripts/bash/check-prerequisites.sh --paths-only --json` resolved `specs/001-dijkstra-era-support/spec.md` through the persisted feature path.
- **Post-specification hooks:** `.specify/extensions.yml` was checked again after validation and does not exist. No post-hook was registered or dispatched.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`. There are no incomplete specification-quality items.

### Requirement-to-acceptance traceability

| Requirement group | Acceptance scenarios / evidence obligation | Measurable outcomes |
| --- | --- | --- |
| FR-001 | Musashi authority and per-run provenance; reviewed bundle/reset updates; no independently selected local pins | SC-002, SC-010 |
| FR-002–004 | Stories 1.1–1.5, 4.1–4.4, 6.2–6.4 and 6.7; per-era/envelope reference cases and node-derived cache refresh | SC-001, SC-002, SC-007, SC-012 |
| FR-005–009 | Stories 2.1–2.5; invalid-batch collateral, local output references, member order and input-role edge cases | SC-001, SC-003, SC-004 |
| FR-010–012 | Stories 2.1, 2.4, 6.2, 7.1, 7.4; independently derived identity/serialization/script cases | SC-001, SC-002 |
| FR-013–015 | Stories 6.1, 7.2–7.4; account boundary, governance ownership and current cost/limit cases | SC-001, SC-002, SC-006 |
| FR-016–020 | Stories 3.1–3.6; invalid/mismatched/expired key material, actual Musashi lifecycle, uncertainty and remote-install failure | SC-005, SC-007, SC-008 |
| FR-021–023 | Stories 1.3–1.4 and 4.1–4.4; Leios adoption/completeness and disabled-feature cases | SC-002, SC-003, SC-007 |
| FR-024–026 | Stories 1.3, 3.2, 5.4, 6.1–6.4; existing workflow matrix and pending/confirmed distinction | SC-006, SC-007 |
| FR-027–030 | Stories 3.6, 5.1–5.4, 6.3; no-replacement rollback, restart/replay and complete-batch reconciliation | SC-003, SC-004, SC-008 |
| FR-031–032 | Independent Test clauses, research verification inventory and release evidence gates | SC-001–SC-008 |
| FR-033–036 | Stories 8.1–8.5; remote catalog publication, clean relay/pool creation, incomplete/mixed bundles and reset/update isolation; quickstart Q0 | SC-009, SC-010 |
| FR-037–038 | Stories 1.5, 3.7, 5.5, 6.5–6.7, 8.6; standalone Conway/Dijkstra, per-network node-query initialization, matching reuse, differing-block refresh, pending/failed isolation, same-era review preservation and confirmed-change invalidation; migration isolation; quickstart Q8 | SC-011, SC-012 |

This traceability is a review aid, not evidence that the future runtime scenarios have already executed.
