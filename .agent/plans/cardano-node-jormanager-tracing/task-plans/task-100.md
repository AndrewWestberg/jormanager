# Task 100 Plan

## Summary

- Task ID: `task-100`
- Title: `Build dispatcher tracing config logic from Markus baseline`
- Why now: next unblocked critical-path task after `task-002`, and current create-node config generation is still the legacy regex-mutation path
- Interaction mode: `autonomous`
- Planning status: `approved`
- Build status: `completed`

## Scope

Replace the legacy regex-driven tracing-config mutation in `NodeController.createConfigFile()` with structured app-side config generation that starts from the Markus tracing baseline and applies only the required dispatcher-era JorManager mutations for new node configs.

In scope:
- keep config generation work inside `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
- stop generating legacy file-scribe and HTTP metrics config for new node configs
- generate dispatcher-era tracing config for the live relay/core config-generation path only
- keep pool behavior truthful: pool creation continues to inherit the parent core config and ports instead of generating its own config file
- preserve existing network/genesis file rewrites and existing port-allocation behavior needed by current create-node flow
- add focused backend tests for the new config-generation seam

Out of scope:
- tracing listener/startup wiring verification or implementation (`task-101`, `task-102`)
- removing `ekgPort` and `promPort` from request/UI plumbing (`task-104`)
- direct protocol / Hermod-compatible client work
- monitor migration
- deployed template config updates under `/home/westbam/bcsh/jormanager/` (`task-103`)
- db-sync template changes

## Docs And Workflow Inputs

- Docs consulted:
  - `.agent/readme.md`
  - `.agent/system/architecture.md`
- Workflows consulted:
  - `.agent/workflows/backend.md`
  - `.agent/workflows/test.md`
  - `.agent/workflows/update-doc.md`
- Skills consulted:
  - none
- Governing task sources consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md`
  - `.agent/plans/cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-tasks.json`
- Existing task docs consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-001.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002.md`
  - `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-002-plan-review.md`
- Research consulted:
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-markus.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/config-new.json`
  - `.agent/plans/cardano-node-jormanager-tracing/research/hermod-protocol-reference-note.md`
  - live deployed template reference: `/home/westbam/bcsh/jormanager/mainnet-config.json`

## Code-Index Sync

- Set project path to `/home/westbam/Development/jormanager`
- Used code-index summaries and search first, then verified all important findings with live file reads
- Material planning note from code-index verification:
  - `NodeController.createConfigFile()` is still the live legacy regex-mutation seam
  - the existing `Config` model in `src/main/kotlin/com/swiftmako/jormanager/model/Config.kt` is only a one-field DTO and is not a realistic full-config typed model for this task

## Relevant Dependencies

- Upstream dependency already completed:
  - `task-002` provides persisted `Node.tracingPort` but does not change config generation
- Downstream dependencies this task should unblock:
  - `task-101` listener verification
  - `task-102` startup wiring
  - `task-104` request/UI cleanup
- Release gating for this task and its downstream config/request cleanup work:
  - `task-100` is not independently deployable for newly created nodes because live `BlockMonitor` still depends on `logs/node.json` output and live `NodeMonitor` still depends on EKG polling
  - do not roll out `task-100` for real new-node creation until the replacement consumer path is complete enough to remove those dependencies, at minimum through `task-102`, `task-300`, `task-301`, `task-302`, and `task-103`
  - `task-104` inherits the same rollout gate because removing EKG/Prometheus request plumbing before node-state migration would otherwise strand newly created nodes without the old metrics path or the new direct-protocol one
- Explicit non-dependency decision:
  - do not load `cardano-cli-doctor` for this task because exact listener/startup compatibility is deferred to `task-101`, not required to truthfully plan app-side config generation here

## Verified Task Surfaces

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - `createNode()` is still the only live node-creation path and calls `createConfigFile()` for relay and core creation only.
  - `createConfigFile()` at lines `3372-3437` still mutates raw config JSON via chained regex/string replacements.
  - current replacements still explicitly depend on legacy keys such as `defaultScribes`, `hasEkg`, `hasEKG`, and a blind `12798` replacement.
  - `createSystemdFile()` and `createEnvFile()` do not yet carry tracing-listener wiring, confirming that startup/listener work should stay out of this task.
  - pool creation reuses the parent core `configFileId`, `ekgPort`, and `promPort` rather than generating a pool config, so pool-specific config assertions do not belong in this task.
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - current active test coverage only covers `allocateTracingPort()`.
  - there is no existing truthful automated coverage for config generation.
- `src/main/kotlin/com/swiftmako/jormanager/model/CreateNodeRequest.kt`
  - request model still carries `ekgPort` and `promPort`; removal belongs to `task-104`, so this task must tolerate those fields without broadening scope.
- `src/main/kotlin/com/swiftmako/jormanager/monitors/BlockMonitor.kt`
  - live block discovery still depends on `logs/node.json`, rotated `logs/node-*.json`, and `TraceAdoptedBlock` grep/tail commands, so task-100 cannot be treated as rollout-complete by itself.
- `src/main/kotlin/com/swiftmako/jormanager/monitors/NodeMonitor.kt`
  - live node stats still depend on `EkgService` and `node.ekgPort`, so removing generated HTTP metrics config is not safe to roll out independently before later monitor migration tasks land.
- `/home/westbam/bcsh/jormanager/mainnet-config.json`
  - deployed templates are still legacy-shaped (`UseTraceDispatcher: false`, `defaultScribes`, `hasEKG`, `hasPrometheus`, `rotation`), which confirms rollout alignment is still later `task-103` work, not an app-side blocker for this task plan.

## Files Expected To Change

- `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt`
  - replace legacy regex mutation in `createConfigFile()` with a structured config-generation helper
- `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`
  - add focused tests for generated dispatcher tracing config shape
- `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`
  - canonical task plan

## Implementation Approach

- Keep the change local to `NodeController`.
- Introduce one small structured config-generation seam, for example a helper that:
  - reads the stored template config JSON into a mutable JSON tree
  - rewrites the non-tracing path fields JorManager already owns (`*GenesisFile`, `GenesisFile`, `PeerSharing`, `MaxConcurrencyDeadline`)
  - replaces the tracing section from Markus baseline plus minimal JorManager-owned mutations
- Prefer a JSON tree approach over a new broad typed config model:
  - `jackson-module-kotlin` is already available in the build
  - the existing `Config` DTO is not reusable for full config shape
  - structured tree mutation is the smallest truthful replacement for the current regex chain
- Treat `config-markus.json` as the tracing baseline source, not `config-new.json`.
- Apply only these JorManager-owned tracing mutations:
  - set `UseTraceDispatcher` to `true` for relay/core generated configs
  - preserve Markus baseline `TraceOptions` namespace map as the source-of-truth tracing profile
  - replace the Markus root `TraceOptions[""]` backends with exactly:
    - relay: `["Stdout MachineFormat"]`
    - core: `["Stdout MachineFormat", "Forwarder"]`
  - preserve Markus root `TraceOptions[""]` severity as `Notice`
  - remove Prometheus and EKG HTTP backends and bindings from generated output
  - add `Forwarder` only for `core` nodes
  - include `TraceOptionForwarder` only for `core`, with the exact queue defaults from `config-new.json`: `connQueueSize: 64`, `disconnQueueSize: 128`, `maxReconnectDelay: 30`
  - retain exactly these dispatcher-era top-level tracing keys in generated output:
    - `UseTraceDispatcher: true`
    - `TraceOptions`
    - `TurnOnLogging: true`
    - `TurnOnLogMetrics: true`
    - `minSeverity: "Critical"`
    - `TraceOptionForwarder` for `core` only
  - do not introduce any additional dispatcher-era top-level tracing keys in task-100 beyond that whitelist
- Remove legacy tracing/file-output assumptions from generated output, including:
  - `defaultBackends`
  - `defaultScribes`
  - `setupBackends`
  - `setupScribes`
  - `rotation`
  - `TracingVerbosity`
  - `EKGBackend`
  - `PrometheusSimple`
  - `hasEkg`
  - `hasEKG`
  - `hasPrometheus`
  - `TraceOptionMetricsPrefix`
  - `TraceOptionResourceFrequency`
  - legacy `TraceBlockFetch...` boolean families and other old trace boolean toggles
- Keep current `createConfigFile()` return shape as `Triple<Long, Int, Int>` for this task so request/UI cleanup and startup wiring can remain separate.
- Keep relay/core differentiation limited to tracing shape only on the live generated-config path:
  - `core`: dispatcher tracing plus `Forwarder`
  - `relay`: dispatcher tracing without `Forwarder`
- Pool truth for this task:
  - no pool config is generated here
  - pools continue inheriting the parent core config generated earlier, so task-100 affects pools only indirectly through future core config generation, not through a separate pool config branch

## Release Gate

- `task-100` is a prerequisite code change, not a safe standalone rollout unit.
- Until the direct-consumer migration is complete, newly created nodes from this config shape would regress:
  - block discovery, because `BlockMonitor` still depends on `logs/node.json`
  - node stats, because `NodeMonitor` still depends on EKG
- Planning consequence:
  - implementation and tests for `task-100` may proceed now
  - release/use of the new config shape for actual new-node provisioning must wait for the later consumer-migration tasks listed above

## Acceptance Criteria

- Config generation no longer depends on legacy regex replacement of tracing/file-scribe fields.
- Relay/core generated configs set `UseTraceDispatcher: true`.
- Core generated configs include forwarding and relay generated configs do not.
- Generated configs preserve stdout machine-format logging for journald.
- Generated configs do not include EKG or Prometheus HTTP configuration.
- Generated configs do not include legacy file-log scribe or rotation configuration.
- Generated configs retain only the pinned dispatcher-era top-level tracing key whitelist for this task.
- Pool behavior remains unchanged in this task: pools inherit the parent core config rather than generating a separate pool config.
- The change does not widen into listener/startup wiring, request-model cleanup, or deployed-template edits.
- The plan explicitly records that this task is not rollout-complete until the later monitor-migration and template-alignment tasks land.

## Verification Plan

- Static verification:
  - confirm `createConfigFile()` no longer relies on regex mutation for tracing keys
  - confirm legacy tracing/file-output keys are absent from relay/core generated config output
  - confirm only the pinned dispatcher-era top-level tracing key whitelist is present in relay/core generated config output
- Automated verification:
  - add one focused helper seam in `NodeController` that returns generated config content as a string from template input plus node-type/context
  - test `core` config output includes:
    - `UseTraceDispatcher: true`
    - top-level tracing keys exactly: `UseTraceDispatcher`, `TraceOptions`, `TurnOnLogging`, `TurnOnLogMetrics`, `minSeverity`, and `TraceOptionForwarder`
    - `TraceOptions[""]` backends exactly `Stdout MachineFormat` plus `Forwarder`
    - `Forwarder`
    - `TraceOptionForwarder`
    - `TraceOptionForwarder` values exactly `64`, `128`, and `30`
    - no `EKGBackend`, `PrometheusSimple`, `hasEkg`, `hasEKG`, `hasPrometheus`, `defaultScribes`, `rotation`, `TraceOptionMetricsPrefix`, or `TraceOptionResourceFrequency`
  - test `relay` config output includes:
    - `UseTraceDispatcher: true`
    - top-level tracing keys exactly: `UseTraceDispatcher`, `TraceOptions`, `TurnOnLogging`, `TurnOnLogMetrics`, and `minSeverity`
    - `TraceOptions[""]` backends exactly `Stdout MachineFormat`
    - no `Forwarder`
    - no `TraceOptionForwarder`
    - no HTTP metrics bindings or legacy file-scribe keys
  - test pool truth statically rather than through config-generation output:
    - confirm pool creation still reuses the parent core `configFileId` path and does not call `createConfigFile()` in a separate pool branch
  - test existing non-tracing mutations still apply:
    - genesis filenames are normalized to local names
    - `PeerSharing` is set from caller input
    - `MaxConcurrencyDeadline` is set from caller input
  - prefer structural JSON assertions over substring-only assertions so key presence/absence and backend arrays are checked deterministically
- Command target once implementation exists:
  - run focused backend tests for `NodeControllerTest`
  - if workspace compile breakage still exists from unrelated code, record it truthfully rather than claiming full backend verification

## Risks And Open Questions

- Startup/listener compatibility is still open and remains `task-101`, but task-100 now pins its own config-generation whitelist and mutation rules strongly enough for implementation without opening that listener question here.
- Because request plumbing still carries `ekgPort` and `promPort`, this task should avoid half-removing them from `createConfigFile()` unless doing so does not ripple into create-node behavior.
- The stored template config files in the database may still be legacy-shaped, so the new helper must be resilient to source templates that contain legacy keys and remove them explicitly.
- Introducing a full typed config DTO set here would be unnecessary complexity compared with a local JSON-tree helper.

## Required Docs, Tracking, And Research Updates

- Create this canonical task plan at `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`.
- Do not update PRD fixed decisions from this planning pass.
- Do not mark `task-100` completed in tasks JSON during planning only.
- No extra research note is required before implementation if task-100 stays within the pinned top-level tracing-key whitelist above.
- If implementation against live fixtures later proves an additional compatibility-era tracing key is required, record that in one narrow follow-up research note before broadening the implementation or tests.

## Review Log Paths

- Canonical plan doc: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100.md`
- Planning review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100-plan-review.md`
- Implementation review log: `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-100-impl-review.md`

## Self-Review

- Scope stays limited to app-side dispatcher config generation and tests.
- Listener/startup wiring, request/UI cleanup, and deployed-template edits are explicitly deferred to their own tasks.
- Relay/core live config-generation scope is now separated cleanly from pool inheritance behavior.
- Rollout gating is explicit, so this task cannot be misread as safe to deploy independently while old monitor consumers still depend on legacy outputs.
- Verification is concrete rather than relying on manual inspection.
- The plan avoids unnecessary complexity by preferring a local structured JSON-tree helper over either more regex patching or a broad new config-model layer.

## Final Outcome

- Implementation completed in `NodeController` by replacing the live tracing-config generation path with a JSON-tree helper `renderManagedConfig()` that preserves existing non-tracing mutations while rebuilding the tracing section from an explicit Markus-derived baseline.
- Relay and core generation now both emit dispatcher-era config with `UseTraceDispatcher: true`, machine-formatted stdout logging, and the pinned compatibility-era top-level tracing keys; only core generation adds `Forwarder` plus `TraceOptionForwarder` defaults `64/128/30`.
- Legacy tracing and HTTP metrics keys are removed from generated output, including `defaultBackends`, `defaultScribes`, `setupBackends`, `setupScribes`, `rotation`, `TracingVerbosity`, `EKGBackend`, `PrometheusSimple`, `hasEkg`, `hasEKG`, `hasPrometheus`, `TraceOptionMetricsPrefix`, and `TraceOptionResourceFrequency`.
- Pool behavior stayed unchanged in scope: no pool config generation path was introduced, and pools still inherit the parent core config indirectly.
- Review result: approved in the latest implementation review entry.
- Verification result:
  - focused structural tests were added in `NodeControllerTest` for relay/core dispatcher output and Markus-baseline namespaces
  - `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed
  - frontend build and unit tests also ran incidentally through Gradle and passed during verification
- Residual note:
  - a dead commented legacy regex-replacement block remains in `NodeController.kt`; review treated it as non-blocking readability debt rather than a task-100 correctness issue

## Required Docs, Tracking, And Research Updates

- Mark `task-100` completed in the tracing tasks tracker with verification notes and rollout-gating reminder.
- Record the durable implementation finding that the Markus baseline is embedded as an explicit code constant for this task and that the helper now replaces, rather than mutates, template `TraceOptions`.
- PRD updated only with a narrow implementation status note; no design decisions changed.
