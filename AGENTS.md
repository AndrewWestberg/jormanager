# AGENTS.md

This guide documents local setup, build, lint, type-check, test, packaging, and Gradle cache verification commands that mirror `.github/workflows/build.yml`.

## Prerequisites

- **Backend**: Java 21 (Temurin recommended) and the repository Gradle wrapper (`./gradlew`). Native VRF unit tests require IOG `libsodium` (`dbb48cce5429cb6585c9034f002568964f1ce567` from `input-output-hk/libsodium`) installed at `/usr/local/lib/libsodium.so`.
- **Frontend**: Node.js `>=26.0.0` (used by `vue/` scripts and hooks).

## Decoupled Local Commands (CI Parity)

Frontend and backend checks are decoupled in both Gradle and GitHub Actions CI (`.github/workflows/build.yml`). Running `./gradlew test` or `./gradlew ktlintCheck` does **not** require Node.js, invoke Vitest, or write into `src/main/resources/static`.

### Frontend (`vue/`)

Install dependencies once before running frontend checks:

```bash
cd vue && npm ci
```

Run individual frontend checks (matching the CI `frontend` job):

```bash
# Lint Vue and TypeScript sources (use `npm run lint` to autofix)
cd vue && npm run lint:check

# Type-check Vue and TypeScript sources without emitting files
cd vue && npx vue-tsc --noEmit

# Run Vitest unit tests (console output + JUnit XML and JSON reports in build/reports/vue-tests/)
cd vue && npm run test

# Build production SPA assets into vue/dist
cd vue && npx vite build
```

Optional `@CacheableTask` Gradle wrappers `./gradlew testVue` and `./gradlew buildVue` are also available for manual use and write outputs to `build/reports/vue-tests/` and `vue/dist/` respectively without modifying `src/main/resources/static`.

### Backend (Repository Root)

Run Kotlin formatting and JUnit 5 verification directly (matching the CI `backend` job):

```bash
# Check Kotlin and Gradle Kotlin DSL formatting (use `./gradlew ktlintFormat` to autofix)
./gradlew ktlintCheck

# Run backend unit tests without Node.js or rebuilding Vue assets
./gradlew test --build-cache

# Run both formatting and unit tests without stopping at the first failure
./gradlew ktlintCheck test --continue --build-cache --stacktrace
```

### Local Application Run & Executable JAR (`bootRun` / `bootJar`)

`bootJar` embeds the built Vue SPA from `vue/dist` into `BOOT-INF/classes/static/` inside `build/libs/jormanager-<version>.jar` so Spring Boot serves SPA routes (`/hosts`, `/nodes`, `/blocks`, `/wallet`):

```bash
# Run the application locally (builds vue/dist via buildVue and serves compiled SPA assets alongside Spring Boot static locations)
./gradlew bootRun

# Local full build: runs `buildVue` (`vue/deploy.sh` -> `vue/dist`) and packages BOOT-INF/classes/static/
./gradlew bootJar

# CI / prebuilt frontend mode: packages an existing `vue/dist` directory without invoking `buildVue`
./gradlew bootJar -PprebuiltFrontend=true --build-cache --stacktrace
```

### Gradle Configuration Cache Verification

Verify configuration-cache reuse across invocations:

```bash
GRADLE_ENCRYPTION_KEY=<base64-key> ./gradlew help --configuration-cache
```

Generate a local 32-byte base64 test key if needed via `head -c 32 /dev/urandom | base64 -w0`.

## Git Hooks

Configure local pre-commit hooks (runs `ktlintCheck` on staged `.kt`/`.kts` files and ESLint on staged `vue/` files):

```bash
./gradlew installGitHooks
```

## Build & Packaging Guardrails

- **Clean `src/main/resources/`**: Frontend builds (`:buildVue` / `./vue/deploy.sh`) emit compiled Vite assets exclusively to `vue/dist/` and must never copy files into `src/main/resources/static/`.
- **Deterministic `bootJar`**: `bootJar` packages `vue/dist/` directly under `BOOT-INF/classes/static/` with normalized timestamps and reproducible file order (using `:buildVue` locally or prebuilt `vue/dist/` when `-PprebuiltFrontend=true`), and `:bootBuildInfo` excludes `time` while preserving `build.version`.
- **Single-project Gradle layout**: Keep custom frontend tasks (`:buildVue`, `:testVue`) annotated with `@CacheableTask` and complete `@PathSensitive(PathSensitivity.RELATIVE)` inputs (including `vue/tests` on `:buildVue` for `vue-tsc` coverage), while keeping `:test` and `:ktlintCheck` decoupled from frontend tasks.
