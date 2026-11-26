# AGENTS.md

This file documents local build, lint, type-check, test, and packaging commands for contributors and coding agents working in `jormanager`.

## Prerequisites

- **Backend**: Java 21 (Temurin) and the repository Gradle wrapper (`./gradlew`). Native VRF unit tests require IOG `libsodium` (`dbb48cce5429cb6585c9034f002568964f1ce567`) installed on the library path (`/usr/local/lib`).
- **Frontend**: Node.js `>= 26` (`vue/package.json`).

## Decoupled Local Commands

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

Optional Gradle wrappers `./gradlew testVue` and `./gradlew buildVue` are also available for manual use and write outputs to `build/reports/vue-tests/` and `vue/dist/` respectively without modifying `src/main/resources/static`.

### Backend (Repository Root)

Run Kotlin formatting and JUnit 5 verification directly (matching the CI `backend` job):

```bash
# Check Kotlin and Gradle Kotlin DSL formatting (use `./gradlew ktlintFormat` to autofix)
./gradlew ktlintCheck

# Run backend unit tests without Node.js or rebuilding Vue assets
./gradlew test

# Run both formatting and unit tests without stopping at the first failure
./gradlew ktlintCheck test --continue
```

### Full Executable JAR (`bootJar`)

`bootJar` embeds the built Vue SPA from `vue/dist` into `BOOT-INF/classes/static/` inside `build/libs/jormanager-<version>.jar` so Spring Boot serves SPA routes (`/hosts`, `/nodes`, `/blocks`, `/wallet`):

```bash
# Local full build: runs `buildVue` (`vue/deploy.sh` -> `vue/dist`) and packages BOOT-INF/classes/static/
./gradlew bootJar

# CI / prebuilt frontend mode: packages an existing `vue/dist` directory without invoking `buildVue`
./gradlew bootJar -PprebuiltFrontend=true
```

## Git Hooks

Configure local pre-commit hooks (runs `ktlintCheck` on staged `.kt`/`.kts` files and ESLint on staged `vue/` files):

```bash
./gradlew installGitHooks
```
