# Task 203 Plan

## Summary

- Task ID: `task-203`
- Title: `Add automated protocol fixtures for tracing implementation`
- Why now: next unblocked foundation task for phase-2 because `task-200`, `task-201`, and `task-204` all need a reproducible non-live protocol harness before direct tracing client work starts
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Create the smallest reusable automated fixture layer for the new Hermod-compatible tracing work so lifecycle, reconnect, and payload-decode behavior can be implemented and tested locally without live node experimentation.

In scope:
- add test-only tracing protocol fixtures reusable by `task-200`, `task-201`, and `task-204`
- support scripted connection lifecycle cases: accept, handle the minimum evidence-backed trace session exchange, send scripted replies, close, and allow reconnect testing
- provide reproducible forwarded payload fixtures for adopted-block decode work, while keeping the harness generic enough for later node-state reuse without guessing node-state messages yet
- align the fixture approach with existing long-lived outbound socket-monitor patterns already used by `ChainMonitor` and `PooltoolMonitor`
- keep fixture logic out of production runtime code except for the smallest testability seam if implementation truth requires one later

Out of scope:
- production tracing client implementation (`task-200`)
- adopted-block decoder implementation (`task-201`)
- block persistence bridging (`task-202`)
- node-state family selection or `NodeStats` mapping (`task-204`)
- live validation against running core nodes, SSH hosts, journald, or firewall state
- introducing `cardano-tracer`, `hermod-rust`, or any sidecar/runtime dependency

## Dependencies

- Required completed upstream task:
  - `task-102`
- Research and design dependencies already pinned:
  - core nodes expose the listener via `--tracer-socket-network-accept 0.0.0.0:<tracingPort>`
  - JorManager is always the client and the node is always the listener
  - live boundary is direct Hermod-compatible protocols only
- Downstream tasks unblocked by this work:
  - `task-200`
  - `task-201`
  - `task-204`

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
- Skills consulted:
  - none needed for this task
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-102.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-104.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-101-tracing-listener-mechanism.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-102-startup-listener-wiring.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-100-config-generation-baseline.md`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-104-request-plumbing-cleanup.md`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Refreshed the index and rebuilt the deep index before planning
- Used code-index search first, then verified important findings against live files
- Material indexed findings confirmed live:
  - `ChainMonitor` and `PooltoolMonitor` already provide the repo's main outbound TCP lifecycle pattern: connect with Ktor sockets, run long-lived coroutines, and reconnect after disconnects
  - `Mux`, `HandshakeProtocol`, and `KeepAliveProtocol` show existing reusable framing/state-machine style, but they are specific to the node mini-protocol stack and should not be treated as the tracing protocol itself
  - `BlockMonitor` still depends on local/remote `cat`, `tail -Fn0`, `grep`, and scraped `TraceAdoptedBlock` log lines
  - `NodeMonitor` still depends on Retrofit `EkgService` plus SSH port forwarding for remote nodes
  - there is currently no `src/test/resources` tree and no existing fake tracing server/test harness to reuse

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/monitors/ChainMonitor.kt`
  - proves the preferred lifecycle shape for future tracing connections: one long-lived outbound socket, coroutine-owned reconnect loop, and `SmartLifecycle` integration
- `src/main/kotlin/com/swiftmako/jormanager/monitors/PooltoolMonitor.kt`
  - shows an alternate socket-monitor pattern with per-node job management and reconnect behavior, useful for core-node scoped tracing later
- `src/main/kotlin/com/swiftmako/jormanager/nodeclient/protocols/mux/Mux.kt`
  - already owns low-level framed socket send/receive logic for the existing Cardano node client stack
  - useful as a style reference for buffered framed I/O, but this task should avoid forcing Hermod-compatible fixtures into the existing `MiniProtocol` abstraction prematurely
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - confirms the downstream replacement target still expects candidate block discovery semantics and currently parses legacy scraped log-line JSON into `TraceAdoptedBlock`
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - confirms later node-state work will need the same transport/session harness shape, but the exact node-state payload family remains intentionally unpinned until `task-204`
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - current repo test style prefers focused Kotlin test helpers and inline fixtures
- `src/test/kotlin/com/swiftmako/jormanager/LocalTailTest.kt`
  - legacy ad hoc log-tail test exists, but it is not a reusable foundation for tracing protocol automation

## Files Expected To Change

- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203.md`
  - canonical task plan
- `src/test/kotlin/com/swiftmako/jormanager/...`
  - new test-only tracing fixture package and focused fixture tests
- `src/test/resources/...` only if implementation truthfully benefits from small reusable JSON or raw-frame samples
- `build.gradle.kts` only if a missing existing test dependency blocks the smallest fixture harness

## Implementation Approach

- Prefer the smallest reusable fixture layer: a test-only scripted fake tracing server plus a small set of Kotlin fixture builders.
- Keep the first fixture package under `src/test/kotlin` rather than production code.
- Split fixture responsibilities explicitly so later tasks do not rely on the wrong boundary:
  - transport/session fixtures cover the minimum evidence-backed socket exchange, scripted replies, disconnects, and reconnects
  - decoded payload fixtures cover forwarded `TraceObject`-like bodies used by decoder tests
- Use the fake server to cover the behaviors static byte fixtures alone cannot cover:
  - connection acceptance
  - handling the minimum evidence-backed trace session request/reply flow needed by the first tracing client work
  - sending deterministic protocol replies
  - deliberate disconnects to exercise reconnect logic
  - repeated scripted sessions for retry scenarios
- Use builder-style test fixtures for the payloads that later tasks must share:
  - forwarded adopted-block `TraceObject` samples matching namespace `Forge.AdoptedBlock`
  - malformed/unknown namespace variants for negative decode tests
- Anchor the fake-server contract only to the minimum upstream message family already called out by the research note, such as the trace-objects request/reply and done flow, instead of inventing a broader protocol model in this task.
- Keep fixture APIs protocol-centric, not task-centric, so later tasks can reuse the same server and payload builders without renaming or copying them.
- Do not force the new tracing work into `Mux` or `MiniProtocol` during this task just to mirror existing code. Reuse the lifecycle ideas, not the existing protocol abstraction, until tracing wire needs are implemented.
- If raw wire encoding is still being pinned during implementation, prefer readable Kotlin fixture builders plus a scripted socket server over opaque committed hex blobs alone.
- Only add `src/test/resources` if it improves reuse materially, such as shared machine JSON payload samples for decoder and later mapping tests without pre-committing to a node-state family.

## Acceptance Criteria

- Automated tracing implementation can run against committed synthetic fixtures without depending primarily on a live node.
- A reusable fake server or equivalent fixture harness exists for scripted connect/send/disconnect scenarios.
- Reconnect behavior is locally testable without SSH or remote infrastructure.
- Reproducible payload fixtures exist for forwarded adopted-block decode work.
- The same fixture foundation is reusable by later node-state mapping work at the transport/session layer and fixture organization level, without hardcoding guessed node-state messages.
- No production runtime dependency on `cardano-tracer`, `hermod-rust`, or manual protocol experimentation is introduced.

## Verification Plan

- Static verification:
  - confirm the fixture package is test-only and does not leak runtime dependencies into production code
  - confirm the fixture API cleanly separates transport/session fixtures from decoded payload fixtures
  - confirm the transport/session harness is reusable across lifecycle and later node-state work without guessing the node-state payload family
  - confirm the plan does not over-couple tracing fixtures to the existing node mini-protocol stack
- Automated verification:
  - add focused tests for the fake server or scripted harness showing minimal request/reply handling, disconnect, and repeat-session behavior
  - add focused tests that fixture builders can generate reproducible valid and malformed adopted-block payload cases
  - if resources are added, verify tests consume them rather than duplicating equivalent inline copies
  - run the narrowest relevant backend test target for the new fixture package
  - run the concrete task-specific test class or package once the fixture class names exist
- Truthful validation boundary:
  - live core-node compatibility is intentionally not required for this task because the goal is to remove dependence on live-node experimentation before `task-200` and `task-201`

## Risks And Open Questions

- Main risk is scope creep into production tracing client code; this task should stop at reusable automated fixtures.
- A fake server that mirrors too much guessed protocol behavior could calcify the wrong wire assumptions; keep the first version minimal and evidence-backed from the cited research.
- Only static payload fixtures would be too weak for reconnect/lifecycle testing, but only a fake server without reusable payload builders would underserve `task-201`; the task should intentionally provide both at the smallest size.
- Reuse for `task-204` should stay at the harness and fixture-layout level until that task pins the node-state family.
- Because there is no current `src/test/resources` tree, adding a large fixture corpus would be unnecessary complexity unless later tasks truly reuse it.

## Required Docs, Tracking, And Research Updates

- Create this canonical plan doc at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203.md`.
- Do not write the planning review log from this pass.
- Mark `task-203` complete in `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json` only after implementation and review finish.
- Add a narrow research note for the adopted task-203 fixture constraints so later tracing tasks reuse the same wrapper/session boundaries and independent byte anchors.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-203-impl-review.md`

## Self-Review

- Scope stays on reusable automated fixtures and avoids widening into client, decoder, or monitor migration implementation.
- Workflow text matches live repo reality: current monitors still use SSH/file and EKG HTTP paths, and no tracing fixture harness exists yet.
- Missing test coverage is addressed directly by requiring lifecycle plus payload-fixture tests.
- Complexity is kept down by preferring a small test-only scripted server and builders over a larger abstraction or production-side framework.

## Final Outcome

- Result: completed and review-approved in implementation iteration 2.
- Final implementation summary:
  - added a test-only `tracing/fixtures` package under `src/test/kotlin` with reusable session-message builders for the minimum evidence-backed trace-forward request/reply/done flow
  - added a small scripted fake server that validates expected client bytes, emits scripted responses, and supports repeated sessions for reconnect-style tests
  - added forwarded adopted-block wrapper fixtures that keep downstream work anchored on the `TraceObject`-style `toNamespace` plus `toMachine` boundary rather than a pre-normalized helper shape
  - added focused tests that independently pin literal CBOR session bytes and exercise single-session plus repeated-session socket behavior
- Files changed:
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixtures.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/ScriptedTraceForwardServer.kt`
  - `src/test/kotlin/com/swiftmako/jormanager/tracing/fixtures/TraceForwardFixturesTest.kt`
  - `.agent/plans/cardano-node-jormanager-tracing/research/task-203-tracing-fixture-foundation.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
- Verification executed:
  - `./gradlew test --tests "com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixturesTest"`
  - repo wiring also ran Vue unit tests and production build during that Gradle target; both passed
- Review outcome:
  - planning log approved for build entry after critique-driven plan narrowing
  - implementation log approved on iteration 2 after tightening the forwarded wrapper fixture shape and adding independent literal protocol-byte assertions
- User handoff: none required
