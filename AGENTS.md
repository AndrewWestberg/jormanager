# AGENTS.md

This guide documents local setup, build, lint, test, and Gradle cache verification commands that mirror `.github/workflows/build.yml`.

## Overview

JorManager is a Cardano stake pool and node management platform consisting of:
- **Backend**: Kotlin / Spring Boot WebSocket server (`src/main/kotlin/`, Gradle Kotlin DSL).
- **Frontend**: Vue 3 + TypeScript + Pinia + BootstrapVueNext single-page application (`vue/`).

## Prerequisites

- **Java**: JDK 21 (Temurin recommended).
- **Node.js**: `>=26.0.0` (used by `vue/` scripts and hooks).
- **IOG `libsodium`**: Installed at `/usr/local/lib/libsodium.so` (commit `dbb48cce5429cb6585c9034f002568964f1ce567` from `input-output-hk/libsodium`) when running VRF-dependent backend JUnit suites.

## Local Build, Lint, and Test Commands (CI Parity)

- **Bootstrap frontend dependencies** (requires Node.js `>=26.0.0`):
  ```bash
  cd vue && npm ci
  ```
- **Run frontend ESLint checks** (enforces `pluginVue.configs['flat/recommended']`, strict TypeScript rules, and `--max-warnings 0`, matching CI):
  ```bash
  cd vue && npm run lint:check
  ```
- **Auto-fix frontend ESLint issues** (with `--max-warnings 0`):
  ```bash
  cd vue && npm run lint
  ```
- **Run frontend unit tests (Vitest) and production type-check/build**:
  ```bash
  cd vue && npm test
  cd vue && npm run build
  ```
- **Run full CI build and cache check** (Kotlin formatting, backend + frontend unit tests, and `bootJar` packaging):
  ```bash
  ./gradlew ktlintCheck test bootJar --build-cache --stacktrace
  ```
- **Run backend and frontend unit test suites**:
  ```bash
  ./gradlew test
  ```
- **Run backend-only tests without frontend tasks**:
  ```bash
  ./gradlew test -x testVue --build-cache
  ```
- **Run Kotlin formatting check without frontend tasks** (matches `.githooks/pre-commit`):
  ```bash
  ./gradlew ktlintCheck -x testVue -x buildVue
  ```
- **Run the application locally** (builds `vue/dist` and serves compiled SPA assets alongside Spring Boot static locations):
  ```bash
  ./gradlew bootRun
  ```
- **Verify configuration-cache reuse across invocations**:
  ```bash
  GRADLE_ENCRYPTION_KEY=<base64-key> ./gradlew help --configuration-cache
  ```
  Generate a local 32-byte base64 test key if needed via `head -c 32 /dev/urandom | base64 -w0`.

## Git Hooks

Configure the repository pre-commit hook with:
```bash
git config core.hooksPath .githooks
```
`.githooks/pre-commit` runs `./gradlew ktlintCheck` on staged Kotlin files and `npx --no-install eslint --max-warnings 0` on staged frontend files under `vue/`.

## Build & Packaging Guardrails

- **Clean `src/main/resources/`**: Frontend builds (`:buildVue` / `./vue/deploy.sh`) emit compiled Vite assets exclusively to `vue/dist/` and must never copy files into `src/main/resources/static/`.
- **Deterministic `bootJar`**: `bootJar` packages `vue/dist/` directly under `BOOT-INF/classes/static/` with normalized timestamps and reproducible file order, and `:bootBuildInfo` excludes `time` while preserving `build.version`.
- **Single-project Gradle layout**: Keep custom frontend tasks (`:buildVue`, `:testVue`) annotated with `@CacheableTask` and complete `@PathSensitive(PathSensitivity.RELATIVE)` inputs (including `vue/tests` on `:buildVue` for `vue-tsc` coverage).
