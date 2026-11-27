# AGENTS.md

This guide documents local setup, dependency locking, vulnerability scanning, build, lint, test, and Gradle cache verification commands that mirror `.github/workflows/build.yml` and `.github/workflows/osv-scan.yml`.

## Overview

JorManager is a Cardano stake pool and node management platform consisting of:
- **Backend**: Kotlin / Spring Boot on Java 21 (configured via `build.gradle.kts` and `buildSrc/src/main/kotlin/Versions.kt`).
- **Frontend**: Vue 3 / TypeScript / Vite in `vue/` (requires Node.js `>=26.0.0`).

Both ecosystems enforce deterministic lockfiles (`gradle.lockfile` and `vue/package-lock.json`) and vulnerability checks via [OSV-Scanner](https://github.com/google/osv-scanner).

## Prerequisites

- **Java**: JDK 21 (Temurin recommended).
- **Node.js**: `>=26.0.0` (used by `vue/` scripts and hooks; `nvm use 26` if using `nvm`).
- **IOG `libsodium`**: Installed at `/usr/local/lib/libsodium.so` (commit `dbb48cce5429cb6585c9034f002568964f1ce567` from `input-output-hk/libsodium`, see `.github/workflows/build.yml`) when running VRF-dependent backend JUnit suites.
- **Vulnerability scanner**: `osv-scanner` v2.x ([release binaries](https://github.com/google/osv-scanner/releases)).

## Dependency Locking

### Refreshing Gradle Lockfile

`build.gradle.kts` enables strict Gradle dependency locking (`LockMode.STRICT`) across all project configurations (excluding `ktlint*` tool configurations). Any change to `build.gradle.kts` or `buildSrc/src/main/kotlin/Versions.kt` that alters resolved Maven coordinates will fail the Gradle build until `gradle.lockfile` is updated and committed.

To regenerate `gradle.lockfile` after modifying backend dependencies:

```bash
./gradlew dependencies --write-locks
```

### Refreshing Frontend Lockfile

When updating packages in `vue/package.json`, regenerate `vue/package-lock.json` using Node.js `>=26.0.0`:

```bash
cd vue
npm install
```

## Local Vulnerability Scanning (OSV-Scanner)

CI workflows (`.github/workflows/build.yml` and `.github/workflows/osv-scan.yml`) scan both `vue/package-lock.json` and `gradle.lockfile` offline against cached OSV vulnerability databases using read-only repository permissions (`contents: read`), requiring no GitHub Dependency Graph or external API tokens.

Before pushing changes, run the exact offline scan locally from the repository root:

```bash
osv-scanner --offline --download-offline-databases --lockfile=vue/package-lock.json --lockfile=gradle.lockfile
```

Once the local OSV database cache has been downloaded, subsequent scans can also run without network access:

```bash
osv-scanner --lockfile=vue/package-lock.json --lockfile=gradle.lockfile
```

Both lockfiles must report zero known advisories (exit code `0`).

## Local Build, Lint, and Test Commands (CI Parity)

- **Bootstrap frontend dependencies** (requires Node.js `>=26.0.0`):
  ```bash
  cd vue && npm ci
  ```
- **Run frontend ESLint, unit tests, and production build**:
  ```bash
  cd vue && npm run lint:check && npm test && npm run build
  ```
- **Run full CI build and cache check** (Kotlin formatting, backend + frontend unit tests, and `bootJar` packaging):
  ```bash
  ./gradlew ktlintCheck test bootJar --build-cache --stacktrace
  ```
- **Auto-format Kotlin and `.kts` files** (if `ktlintCheck` reports formatting issues):
  ```bash
  ./gradlew ktlintFormat
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

## Build & Packaging Guardrails

- **Clean `src/main/resources/`**: Frontend builds (`:buildVue` / `./vue/deploy.sh`) emit compiled Vite assets exclusively to `vue/dist/` and must never copy files into `src/main/resources/static/`.
- **Deterministic `bootJar`**: `bootJar` packages `vue/dist/` directly under `BOOT-INF/classes/static/` with normalized timestamps and reproducible file order, and `:bootBuildInfo` excludes `time` while preserving `build.version`.
- **Single-project Gradle layout**: Keep custom frontend tasks (`:buildVue`, `:testVue`) annotated with `@CacheableTask` and complete `@PathSensitive(PathSensitivity.RELATIVE)` inputs (including `vue/tests` on `:buildVue` for `vue-tsc` coverage).
