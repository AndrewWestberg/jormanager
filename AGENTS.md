# AGENTS.md

## Local Environment Prerequisites

- **Java**: JDK 21 (`java.sourceCompatibility = JavaVersion.VERSION_21`)
- **Node.js**: `>= 26.0.0` (required by `vue/package.json`, `vue/test.sh`, `vue/deploy.sh`, and `.githooks/pre-commit`)
- **IOG `libsodium`**: Pinned revision `dbb48cce5429cb6585c9034f002568964f1ce567` from [`input-output-hk/libsodium`](https://github.com/input-output-hk/libsodium.git) installed at `/usr/local/lib/libsodium.so`

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

## Local Build, Lint, and Test Checks

Run the same checks executed in `.github/workflows/build.yml`:

1. **Frontend dependencies and ESLint**:
   ```bash
   cd vue
   npm ci
   npm run lint:check
   ```

2. **Kotlin formatting (`ktlint`), backend + Vue tests (including `BlockVerifyTest`), and Spring Boot JAR**:
   ```bash
   ./gradlew ktlintCheck test bootJar
   ```

3. **Run only the VRF block verification test**:
   ```bash
   ./gradlew test --tests 'com.swiftmako.jormanager.BlockVerifyTest'
   ```
