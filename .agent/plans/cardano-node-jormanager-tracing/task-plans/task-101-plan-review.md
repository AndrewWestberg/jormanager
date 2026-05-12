Planner: Iteration 1
Timestamp: 2026-05-12T22:07:27Z

Updated `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md` as the canonical plan for pinning the automated tracing listener mechanism. Read all required docs/workflows/research in the requested order, used code-index first, then verified live seams in `src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt` and `src/test/kotlin/com/swiftmako/jormanager/controllers/NodeControllerTest.kt`. Confirmed current startup generation has no tracing listener wiring, existing tests already cover tracing config and tracing-port helpers, and there are no existing `src/test/resources` fixtures to reuse. Loaded `cardano-cli-doctor` and verified locally that `cardano-node 11.0.1` exposes CLI listener syntax via `cardano-node run --help`, specifically `--tracer-socket-network-accept HOST:PORT`; the plan therefore pins task-101 to codify a reusable core-only CLI fragment helper around `--tracer-socket-network-accept 0.0.0.0:<tracingPort>` with focused `NodeControllerTest` coverage, while explicitly deferring actual systemd/manual startup wiring to `task-102`. The plan records docs/workflows/skill/research consulted, code-index sync, verified task surfaces, expected files, autonomous interaction mode, verification steps, and required tracking/docs/research updates. Self-review completed: no scope creep into task-102, no stale workflow claims, test gap addressed with minimal helper-based assertions, and no plan-review log was written.

Outcome: Plan drafted and ready for critique

Critiquer: Iteration 1
Timestamp: 2026-05-12T22:09:54Z

Blocking findings:
- The plan understates the real boundary to `task-102`: startup generation does not currently have access to the pinned `tracingPort` at the point where artifacts are written. In the core creation flow, `createSystemdFile()` and `createManualStartupScripts()` are called before `allocateTracingPort()` and before the `Node` is saved (`src/main/kotlin/com/swiftmako/jormanager/controllers/NodeController.kt:1006-1045`, `1134-1179`). The plan says task-102 should only need to "splice" a helper into existing `ExecStart` generation, but live code shows task-102 will also need an ordering/signature change or a different data source for the port. Without calling that out, the approved plan leaves a hidden implementation checkpoint.
- The plan does not account for the pool-node path that regenerates the parent core systemd unit (`NodeController.kt:1047-1131`, especially `1123-1129`) while only having `coreNode.promPort` from persistence and no saved tracing listener wiring yet. If task-101 is meant to remove ambiguity for `task-102`, the plan needs to verify and state how the reused helper will be fed during both core creation and later pool-triggered core service rewrites. Right now that dependency is implicit and stale.

Non-blocking observations:
- The CLI-vs-config question is resolved correctly. Local `cardano-node 11.0.1` help proves the listener is startup-flag driven via `--tracer-socket-network-accept HOST:PORT`, not JSON config.
- Keeping task-101 out of actual `ExecStart` edits is the right scope cut.
- Inline test coverage in `NodeControllerTest` is a reasonable smaller seam than adding `src/test/resources` just for one argument fragment.
- The plan should tone down the claim that `0.0.0.0` is fully settled by the help output alone; that choice comes from the PRD decision, not from the CLI proof itself.

Approval bar:
- Update the plan to acknowledge that `task-102` will require more than helper insertion: it must also reconcile startup-generation ordering/signatures with `tracingPort` availability.
- Explicitly cover both startup-generation call sites: initial core creation and the pool path that rewrites the parent core service.
- Rephrase the "fully unblock" claim so it matches the verified live code boundary.
Decision: requires_changes

Planner: Iteration 2
Timestamp: 2026-05-12T22:09:54Z

Revised `.agent/plans/cardano-node-jormanager-tracing/task-plans/task-101.md` to incorporate the critique blocking findings without widening scope into task-102 implementation. The canonical plan now explicitly records the live `NodeController` ordering constraint that `createSystemdFile()` and `createManualStartupScripts()` run before `allocateTracingPort()` during core creation, and it separately calls out the pool-node path that rewrites the parent core systemd unit from persisted core-node data. The implementation shape stays minimal for task-101: pin the verified `cardano-node 11.0.1` CLI listener fragment in one reusable helper plus focused tests, while documenting that task-102 must reconcile startup-generation ordering/input flow in both call sites before wiring the fragment into artifacts. I also corrected the source of truth for `0.0.0.0` to the PRD decision rather than the CLI help output and softened the earlier claim about fully unblocking task-102. Self-review after revision: blockers addressed, no new scope creep, and the plan now matches the live repo boundary truthfully.

Outcome: Plan revised after critique and ready for build

