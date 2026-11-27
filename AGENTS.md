# AGENTS.md

This guide documents local setup, build, lint, test, and Gradle cache verification commands that mirror `.github/workflows/build.yml`.

## Prerequisites

- **Java**: JDK 21 (Temurin recommended; `java.sourceCompatibility = JavaVersion.VERSION_21`).
- **Node.js**: `>=26.0.0` (used by `vue/` scripts, `vue/package.json`, and `.githooks/pre-commit`).
- **IOG `libsodium`**: Installed at `/usr/local/lib/libsodium.so` (commit `dbb48cce5429cb6585c9034f002568964f1ce567` from [`input-output-hk/libsodium`](https://github.com/input-output-hk/libsodium.git)) when running VRF-dependent backend JUnit suites.

### Why IOG `libsodium` is required

`src/main/resources/application.properties` configures `libsodium.path=/usr/local/lib/libsodium.so`, and `src/test/kotlin/com/swiftmako/jormanager/BlockVerifyTest.kt` loads `/usr/local/lib/libsodium.so` via JNA to call `cryptoVrfProofToHash_ietfdraft03` and `cryptoVrfVerify_ietfdraft03`. Stock OS distribution packages of `libsodium` do not export Cardano's `crypto_vrf_ietfdraft03_*` symbols.

## Installing IOG `libsodium` Locally

Build and install commit `dbb48cce5429cb6585c9034f002568964f1ce567` into `/usr/local` (matching `.github/workflows/build.yml` and `docs/installation.md`):

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

## Local Build, Lint, and Test Commands (CI Parity)

- **Bootstrap frontend dependencies** (requires Node.js `>=26.0.0`):
  ```bash
  cd vue && npm ci
  ```
- **Run frontend ESLint checks**:
  ```bash
  cd vue && npm run lint:check
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
- **Run only the VRF block verification test**:
  ```bash
  ./gradlew test --tests 'com.swiftmako.jormanager.BlockVerifyTest'
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
