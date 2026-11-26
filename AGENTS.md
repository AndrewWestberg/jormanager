# AGENTS.md

## Overview
JorManager is a Cardano stake pool and node management platform consisting of:
- **Backend**: Kotlin / Spring Boot WebSocket server (`src/main/kotlin/`, Gradle Kotlin DSL).
- **Frontend**: Vue 3 + TypeScript + Pinia + BootstrapVueNext single-page application (`vue/`).

## Prerequisites
- **Java**: JDK 25 (configured via Gradle toolchain).
- **Node.js**: `>= 26.0.0` (required by `vue/package.json` and `.githooks/pre-commit`).

## Frontend Commands (`vue/`)
Run all frontend commands from the `vue/` directory:
- Install dependencies: `npm ci`
- Check lint (enforces `pluginVue.configs['flat/recommended']`, strict TypeScript rules, and `--max-warnings 0`, matching CI): `npm run lint:check`
- Auto-fix lint issues (with `--max-warnings 0`): `npm run lint`
- Run unit tests (Vitest): `npm test`
- Type-check and build production bundle (`vue-tsc --noEmit && vite build`): `npm run build`

## Backend Commands (Repository Root)
- Check Kotlin formatting: `./gradlew ktlintCheck`
- Format Kotlin sources: `./gradlew ktlintFormat`
- Run backend & frontend checks and build bootJar: `./gradlew test bootJar`

## Git Hooks
Configure the repository pre-commit hook with:
```bash
git config core.hooksPath .githooks
```
`.githooks/pre-commit` runs `./gradlew ktlintCheck` on staged Kotlin files and `npx --no-install eslint --max-warnings 0` on staged frontend files under `vue/`.
