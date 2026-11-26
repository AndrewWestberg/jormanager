# AGENTS.md

## Overview

JorManager is a Cardano stake pool and node management platform consisting of:
- **Backend**: Kotlin / Spring Boot on Java 21 (configured via `build.gradle.kts` and `buildSrc/src/main/kotlin/Versions.kt`).
- **Frontend**: Vue 3 / TypeScript / Vite in `vue/` (requires Node.js >= 26).

Both ecosystems enforce deterministic lockfiles (`gradle.lockfile` and `vue/package-lock.json`) and vulnerability checks via [OSV-Scanner](https://github.com/google/osv-scanner).

## Prerequisites

- **Java**: JDK 21 (Temurin recommended)
- **Node.js**: >= 26.0.0 (`nvm use 26` if using `nvm`)
- **Native crypto (VRF unit tests)**: Input Output HK fork of `libsodium` installed into `/usr/local` (see `.github/workflows/build.yml`)
- **Vulnerability scanner**: `osv-scanner` v2.x ([release binaries](https://github.com/google/osv-scanner/releases))

## Dependency Locking

### Refreshing Gradle Lockfile

`build.gradle.kts` enables strict Gradle dependency locking (`LockMode.STRICT`) across all project configurations (excluding `ktlint*` tool configurations). Any change to `build.gradle.kts` or `buildSrc/src/main/kotlin/Versions.kt` that alters resolved Maven coordinates will fail the Gradle build until `gradle.lockfile` is updated and committed.

To regenerate `gradle.lockfile` after modifying backend dependencies:

```bash
./gradlew dependencies --write-locks
```

### Refreshing Frontend Lockfile

When updating packages in `vue/package.json`, regenerate `vue/package-lock.json` using Node.js >= 26:

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

## Local Build, Lint, and Test Parity

Run the following commands before opening or updating a pull request to match `.github/workflows/build.yml`:

### Frontend (`vue/`)

```bash
cd vue
npm ci
npm run lint:check
npm test
npm run build
```

### Backend & Full Package (`.` root)

```bash
./gradlew ktlintCheck test bootJar --stacktrace
```

To auto-format Kotlin and `.kts` files if `ktlintCheck` reports formatting issues:

```bash
./gradlew ktlintFormat
```
