# Task 101 Plan

## Summary

- Task ID: `task-101`
- Title: `Pin the automated tracing listener mechanism`
- Why now: next unblocked critical-path task after `task-100`; startup generation is still missing any verified tracing listener wiring
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Pin the exact `cardano-node 11.0.1` tracing-listener startup mechanism JorManager should generate for new core nodes, and codify that mechanism in the smallest reusable code/test seam so later startup wiring can be implemented without more runtime guesswork.

In scope:
- verify whether the accepted listener is config-driven or CLI-driven for `cardano-node 11.0.1`
- codify the verified listener mechanism as a small `NodeController` startup-fragment/helper seam or equivalent code-adjacent artifact
- add focused backend tests that pin the accepted listener fragment for core nodes and reject it for relay/pool nodes
- make the live startup-generation dependency on tracing-port availability explicit so `task-102` does not assume this is a pure string-splice change
- record the verified `11.0.1` syntax and the minimal follow-on implementation path for `task-102`

Out of scope:
- wiring the listener into generated systemd units or manual startup scripts (`task-102`)
- changing generated tracing config JSON beyond what `task-100` already pinned
- direct protocol client work, fixtures, or monitor migration
- frontend or request-model changes
- deployed template updates under `/home/westbam/bcsh/jormanager/`

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - `cardano-cli-doctor`
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-101-tracing-listener-mechanism.md`
- External/runtime verification consulted:
  - local `cardano-node 11.0.1` `run --help`
  - local `cardano-node --version`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Built and refreshed the deep index before code inspection
- Used code-index summaries and search first, then verified all important findings with live file reads
- Material indexed findings confirmed live:
  - `NodeController.createManualStartupScripts()` and `createSystemdFile()` still generate `cardano-node run` commands without any tracer listener flags
  - `NodeControllerTest` already provides focused controller helper coverage for tracing config and tracing-port allocation
  - no existing `src/test/resources` fixtures currently pin startup or listener shape
  - `createSystemdFile()` and `createManualStartupScripts()` are called before `allocateTracingPort()` and before the new `Node` is saved during core creation
  - the pool-node creation path also rewrites the parent core systemd unit before any new tracing-listener wiring exists, using persisted core-node data instead of freshly allocated inputs

## Interaction Mode And Checkpoints

- Interaction mode: `autonomous`
- User-owned checkpoints:
  - none required for planning or implementation of this task
  - live end-to-end startup validation against a real node remains optional follow-up verification, not a blocker for codifying the `11.0.1` mechanism
- Explicit rejection for this task:
  - do not widen into actually modifying generated `ExecStart` commands yet
  - do not leave config-only versus CLI-only listener wiring open after this task

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `createManualStartupScripts()` writes relay/core startup commands with `cardano-node run` plus topology/database/socket/host/port/config arguments, but no tracing-listener flags.
  - `createSystemdFile()` mirrors the same `ExecStart` omission for relay/core/pool service generation.
  - `createEnvFile()` does not expose any tracing-listener variables today.
  - `renderManagedConfig()` and `normalizeTracingConfig()` now pin dispatcher-era config shape from `task-100`, but they do not configure runtime listener startup.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - active focused tests currently cover `allocateTracingPort()` and `renderManagedConfig()` only.
  - there is no automated assertion for any startup fragment, `ExecStart` suffix, or listener flag.
  - the test file already uses inline fixtures, which is the smallest existing pattern for this task.
- Test-fixture surface
  - `src/test/resources` does not currently exist, so adding a whole fixture tree would be broader than the current test style unless implementation truth requires it.
- Verified `cardano-node 11.0.1` runtime fact
  - local `cardano-node run --help` exposes `--tracer-socket-network-accept HOST:PORT`, `--tracer-socket-network-connect HOST:PORT`, `--tracer-socket-path-accept FILEPATH`, and `--tracer-socket-path-connect FILEPATH`.
  - this confirms the accepted listener mechanism for JorManager is CLI startup wiring, not additional config JSON keys.
  - for the PRD's node-listener/JorManager-client model, `--tracer-socket-network-accept` is the relevant mode; `connect` and local-socket variants are out of scope.

## Planned Implementation Shape

- Keep the change local to `NodeController` and `NodeControllerTest`.
- Add one minimal internal helper seam that expresses the verified tracing-listener fragment for later reuse by both manual startup scripts and systemd generation.
- Pin that seam to the verified `cardano-node 11.0.1` CLI listener form:
  - core node with `tracingPort`: `--tracer-socket-network-accept 0.0.0.0:<tracingPort>`
  - relay and pool nodes: no tracing-listener fragment
- Prefer an inline test fixture in `NodeControllerTest` over a new `src/test/resources` tree, because no test resources exist yet and the current controller-test style already uses inline artifact snippets.
- Keep task-101 limited to codifying the fragment and its assertions.
- Record for `task-102` that wiring the fragment into startup artifacts will also require reconciling call ordering and helper inputs so `tracingPort` is available in both:
  - initial core-node creation, where startup files are currently written before `allocateTracingPort()` runs
  - pool-node creation, where the parent core systemd unit is regenerated from persisted core-node data

## Expected Change Set

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - add a small helper or equivalent reusable seam that returns the verified tracer listener CLI fragment for eligible core nodes
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - add focused tests that pin the helper output and, if useful, a tiny inline startup-snippet fixture showing the intended `cardano-node run` suffix
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`
  - canonical task plan

## Implementation Notes

- Verified local version is `cardano-node 11.0.1`, so the plan should treat `--tracer-socket-network-accept HOST:PORT` as the pinned automation target for this task.
- The PRD requires node-as-listener and JorManager-as-client, so this task should explicitly reject `--tracer-socket-network-connect`.
- The PRD, not the CLI help output, fixes the bind host to all interfaces with firewall restriction outside JorManager, so the pinned host should be `0.0.0.0`, not `request.listen` or `127.0.0.1`.
- Do not add listener config into generated JSON; the live `11.0.1` help output already proves the accepted startup seam is CLI-based.
- Do not add env-file plumbing unless it materially reduces duplication without obscuring the exact pinned fragment; a direct helper returning the final argument string is the smallest truthful option.
- Keep the helper reusable by `createManualStartupScripts()` and `createSystemdFile()` so task-102 can stay mechanical.

## Verification Plan

- Static verification:
  - confirm the task produces one explicit reusable seam for tracing-listener args rather than leaving the `11.0.1` syntax implicit in docs only
  - confirm the pinned mechanism is CLI-based and does not broaden config generation
  - confirm relay and pool paths remain listener-free
- Automated verification:
  - add a focused `NodeControllerTest` for a core node with `tracingPort = 12790` asserting the exact fragment `--tracer-socket-network-accept 0.0.0.0:12790`
  - add focused tests asserting relay and pool return no fragment without introducing accidental listener wiring
  - if a startup-snippet helper is used instead of a bare arg helper, assert the exact `ExecStart` suffix contains the verified listener fragment once and only once
- Command target once implementation exists:
  - run `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"`
  - if unrelated workspace compile failures still block the target, record that truthfully and preserve the direct helper/test assertions in code
- Optional manual spot check:
  - rerun `cardano-node run --help` during implementation if needed, but no interactive runtime experimentation against a live node should be required

## Risks And Watchouts

- Accidentally wiring the helper into live startup generation during task-101 would blur the task boundary with `task-102`.
- Treating `task-102` as a pure string-insertion task would miss the real data-flow change needed to make `tracingPort` available to startup generation in both core creation and pool-triggered core rewrites.
- Using `request.listen` or loopback for the tracing listener would conflict with the PRD's bind-on-all-interfaces decision.
- Leaving unix-socket or `connect` variants half-supported would re-open a decision the local `11.0.1` help output already resolved for this deployment.
- Adding a whole fixture directory for one small startup fragment would be unnecessary surface area unless later protocol-fixture work reuses it.

## Planning Outcome

- Approved smallest truthful plan: pin the listener mechanism as a reusable CLI-fragment helper in `NodeController`, backed by focused `NodeControllerTest` assertions.
- Local `cardano-node 11.0.1` verification resolved the main open question from the PRD for this task: listener startup is CLI-based and should use `--tracer-socket-network-accept`, not a new config-only path.
- Existing inline-fixture controller tests are sufficient for this task; no new test-resource tree is planned.
- This task should remove the remaining ambiguity around automated listener syntax and host binding so `task-102` can focus on startup-generation data flow and artifact wiring instead of runtime discovery.

## Final Outcome

- Completed on 2026-05-12 after one implementation iteration and one approved review pass.
- Delivered result:
  - `NodeController.renderTracingListenerArgument(nodeType, tracingPort)` now pins the exact core-node listener CLI fragment for the verified `cardano-node 11.0.1` network-accept mode
  - focused `NodeControllerTest` coverage locks the `11.0.1` listener syntax, non-core exclusions, and null-port exclusion
  - startup-generation work remains deferred cleanly to `task-102`, with its required ordering/input reconciliation called out explicitly in this plan and the research note
- Verification completed:
  - `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed
  - in this workspace, that Gradle target also executed the existing frontend build/test tasks before backend test execution and still completed successfully
- Final review result:
  - implementation review approved in `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101-impl-review.md`

## Required Docs And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`.
- Mark `task-101` completed in the tracing tasks tracker once implementation, review, and documentation updates are finished.
- Do not change PRD design decisions from this planning pass; the local `11.0.1` help verification simply closes the config-vs-CLI ambiguity for implementation.
- Record the durable listener proof and the startup-ordering constraint in `.agent/plans/cardano-node-jormanager-tracing/research/task-101-tracing-listener-mechanism.md`.
- If implementation finds a mismatch between local help output and generated startup behavior, add one narrow `task-101` research note under `.agent/plans/cardano-node-jormanager-tracing/research/` rather than broadening the task silently.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101-impl-review.md`

## Self-Review

- Scope stays limited to verifying and codifying the `11.0.1` listener mechanism; actual startup wiring remains deferred to `task-102`.
- Workflow and verification text match current repo reality: no existing startup tests, no `src/test/resources`, and no claim of runtime wiring already being present.
- Missing-test risk is addressed directly with focused controller helper assertions.
- Docs/tracking/research updates are explicit and minimal, with no plan-review log written from this pass.
