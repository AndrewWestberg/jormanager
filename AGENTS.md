# AGENTS.md

This guide documents local setup, build, lint, type-check, test, security scanning, packaging, and Gradle cache verification commands that mirror `.github/workflows/build.yml`.

## Prerequisites

- **Java**: JDK 21 (Temurin recommended).
- **Node.js**: `>=26.0.0` (used by `vue/` scripts and hooks).
- **IOG `libsodium`**: Installed at `/usr/local/lib/libsodium.so` (commit `dbb48cce5429cb6585c9034f002568964f1ce567` from `input-output-hk/libsodium`) when running VRF-dependent backend JUnit suites.
- **Vulnerability scanner**: `osv-scanner` v2.x ([release binaries](https://github.com/google/osv-scanner/releases)) for local lockfile CVE checks.

### Installing IOG `libsodium` Locally

`src/main/resources/application.properties` configures `libsodium.path=/usr/local/lib/libsodium.so`, and VRF tests (`BlockVerifyTest`, `LeaderLogCalculatorTest`) load `/usr/local/lib/libsodium.so` via JNA for Cardano's `crypto_vrf_ietfdraft03_*` symbols (not exported by stock OS `libsodium` packages).

Build and install commit `dbb48cce5429cb6585c9034f002568964f1ce567` into `/usr/local` (matching `.github/workflows/build.yml`):

```bash
BUILD_DIR="$(mktemp -d /tmp/libsodium.XXXXXX)"
cd "$BUILD_DIR"
git init -q
git fetch --depth=1 https://github.com/input-output-hk/libsodium.git dbb48cce5429cb6585c9034f002568964f1ce567
git checkout --detach FETCH_HEAD
DO_NOT_UPDATE_CONFIG_SCRIPTS=1 ./autogen.sh
./configure --prefix=/usr/local
make -j"$(nproc)"
sudo make install
sudo ldconfig
rm -rf "$BUILD_DIR"
```

Verify that `/usr/local/lib/libsodium.so` exports the required Cardano VRF symbols:

```bash
nm -D /usr/local/lib/libsodium.so | grep -E ' T crypto_vrf_ietfdraft03_(proof_to_hash|verify)$'
```

## Decoupled Local Build, Lint, and Test Commands (CI Parity)

Frontend and backend checks are decoupled in both Gradle and GitHub Actions CI (`.github/workflows/build.yml`). Running `./gradlew test` or `./gradlew ktlintCheck` does **not** require Node.js, invoke Vitest, or write into `src/main/resources/static`.

### Frontend (`vue/`)

- **Bootstrap frontend dependencies** (requires Node.js `>=26.0.0`):
  ```bash
  cd vue && npm ci
  ```
- **Run frontend ESLint checks** (enforces `pluginVue.configs['flat/recommended']`, strict TypeScript rules, and `--max-warnings 0`; use `npm run lint` to autofix):
  ```bash
  cd vue && npm run lint:check
  ```
- **Type-check Vue and TypeScript sources without emitting files**:
  ```bash
  cd vue && npx vue-tsc --noEmit
  ```
- **Run Vitest unit tests** (console output + JUnit XML and JSON reports in `build/reports/vue-tests/`):
  ```bash
  cd vue && npm run test
  ```
- **Build production SPA assets into `vue/dist/`**:
  ```bash
  cd vue && npx vite build
  ```

Optional Gradle wrappers `./gradlew testVue` and `./gradlew buildVue` remain available for manual use and write outputs to `build/reports/vue-tests/` and `vue/dist/` respectively without modifying `src/main/resources/static`.

### Backend (Repository Root)

- **Check Kotlin and Gradle Kotlin DSL formatting** (use `./gradlew ktlintFormat` to autofix):
  ```bash
  ./gradlew ktlintCheck
  ```
- **Run backend unit tests without Node.js or rebuilding Vue assets**:
  ```bash
  ./gradlew test
  ```
- **Run only the VRF block verification test**:
  ```bash
  ./gradlew test --tests 'com.swiftmako.jormanager.BlockVerifyTest'
  ```
- **Run all backend checks without stopping at the first failure** (matches CI `backend` job):
  ```bash
  ./gradlew ktlintCheck test --continue --build-cache --stacktrace
  ```

### Packaging, Local Run, and Configuration Cache

- **Build executable JAR (`bootJar`) locally** (runs `buildVue` -> `vue/dist/` and embeds under `BOOT-INF/classes/static/`):
  ```bash
  ./gradlew bootJar
  ```
- **Build executable JAR with prebuilt `vue/dist/`** (matches CI `package` job, no Node.js required):
  ```bash
  ./gradlew bootJar -PprebuiltFrontend=true --build-cache --stacktrace
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

## Dependency Locking & Vulnerability Scanning (OSV-Scanner)

`build.gradle.kts` enables strict Gradle dependency locking (`LockMode.STRICT`) across all project configurations (excluding `ktlint*` tool configurations). Any change to `build.gradle.kts` or `buildSrc/src/main/kotlin/Versions.kt` that alters resolved Maven coordinates will fail the Gradle build until `gradle.lockfile` is updated and committed.

- **Regenerate `gradle.lockfile`** after modifying backend dependencies:
  ```bash
  ./gradlew dependencies --write-locks
  ```
- **Refresh `vue/package-lock.json`** after modifying `vue/package.json` (requires Node.js `>=26.0.0`):
  ```bash
  cd vue && npm install
  ```
- **Run offline OSV-Scanner check locally** (matches `.github/workflows/build.yml` and `.github/workflows/osv-scan.yml`; both lockfiles must report zero known advisories):
  ```bash
  osv-scanner --offline --download-offline-databases --lockfile=vue/package-lock.json --lockfile=gradle.lockfile
  ```

## Git Hooks

Configure the repository pre-commit hook (runs `./gradlew ktlintCheck -x testVue -x buildVue` on staged Kotlin/Gradle files and `npx --no-install eslint --max-warnings 0` on staged frontend files under `vue/`):

```bash
./gradlew installGitHooks
```

## Build & Packaging Guardrails

- **Clean `src/main/resources/`**: Frontend builds (`:buildVue` / `./vue/deploy.sh`) emit compiled Vite assets exclusively to `vue/dist/` and must never copy files into `src/main/resources/static/`.
- **Deterministic `bootJar`**: `bootJar` packages `vue/dist/` directly under `BOOT-INF/classes/static/` with normalized timestamps and reproducible file order, and `:bootBuildInfo` excludes `time` while preserving `build.version`.
- **Single-project Gradle layout**: Keep custom frontend tasks (`:buildVue`, `:testVue`) annotated with `@CacheableTask` and complete `@PathSensitive(PathSensitivity.RELATIVE)` inputs (including `vue/tests` on `:buildVue` for `vue-tsc` coverage).
