# task-001 tracing port persistence

- Date: 2026-05-12
- Task: `task-001`
- Outcome: completed

## Durable findings

- `Node` constructor compatibility matters because the repo still has positional `Node(...)` call sites outside controller code.
- The safe minimal approach is to append `tracingPort: Int? = null` at the end of the entity constructor instead of inserting it earlier.
- `nodes.tracing_port` should remain nullable in phase 1 so relay and pool rows can stay unset.
- This task should stay code-only; manual Liquibase execution and schema verification are deferred until later broader validation.

## Verification evidence

- `./gradlew compileKotlin` succeeded after Gradle fell back from Kotlin daemon incremental-cache issues to non-daemon compilation.
- Source verification confirmed the new `039-update-node-schema.xml` is included once in `db/liquibase-changelog.xml`.

## Residual notes

- Local verification should avoid triggering database updates until the user runs the broader manual validation flow.
