# Task 100 Research Note

## Summary

- Date: 2026-05-12
- Task: `task-100`
- Topic: dispatcher tracing config generation from Markus baseline

## Durable Findings

- `NodeController.createConfigFile()` was still using a legacy regex-driven mutation chain against stored config blobs at task start.
- The accepted task-100 implementation needs to replace the tracing section, not incrementally mutate whatever `TraceOptions` happen to exist in the stored DB template.
- For the current task scope, the smallest truthful implementation is a local JSON-tree helper in `NodeController` that:
  - preserves JorManager-owned non-tracing mutations such as normalized genesis filenames, `PeerSharing`, and `MaxConcurrencyDeadline`
  - seeds `TraceOptions` from an explicit Markus-derived baseline
  - rewrites the root `TraceOptions[""]` backends to `Stdout MachineFormat` for relay and `Stdout MachineFormat` plus `Forwarder` for core
  - adds `TraceOptionForwarder` only for core with queue defaults `64/128/30`
  - removes legacy top-level tracing and HTTP metrics keys from generated output
- Focused tests need to assert Markus-derived namespaces such as `Version.NodeVersion`, `ChainSync.Client`, and `Resources`; synthetic legacy-template-only assertions are not enough to prevent drift back to template-shaped tracing output.

## Verification Evidence

- `./gradlew test --tests "com.swiftmako.jormanager.controllers.NodeControllerTest"` passed after the helper was updated to replace `TraceOptions` wholesale from the Markus-derived baseline.

## Residual Notes

- The Markus baseline is currently embedded as a code constant in `NodeController` rather than loaded from a shared fixture or resource file. That is acceptable for `task-100`, but future baseline changes will require a code update plus matching doc or research updates.
- A dead commented legacy regex-replacement block remains in `NodeController.kt`; review treated it as non-blocking readability debt.
