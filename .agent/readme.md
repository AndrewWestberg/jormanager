# 📚 JorManager Agent Documentation Index

> **For AI agents:** Read this file first to understand available documentation and when to reference each resource.

This folder contains the documentation needed for agents to work effectively in the JorManager codebase. Use this index to locate architecture references, planning documents, SOPs, skills, and execution workflows.

---

## 🗺️ Quick Navigation

| Folder | Purpose | When to Read |
|--------|---------|--------------|
| [`/system`](./system/) | Architecture, schemas, API endpoints | **First**, for architecture or system understanding |
| [`/plans`](./plans/) | Active and historical implementation plans | When planning new work or referencing similar features |
| [`/SOPs`](./SOPs/) | Standard Operating Procedures | When encountering known issues or reusing established procedures |
| [`/skills`](./skills/) | Reusable skills and operators | When a task matches a supported skill |
| [`/workflows`](./workflows/) | Step-by-step development workflows | When executing common development tasks |
| [`/plans/readme.md`](./plans/readme.md) | Plans index | When you need the full plan catalog and schema |
| [`/SOPs/readme.md`](./SOPs/readme.md) | SOP index | When you need the SOP catalog |

---

## 📁 Folder Details

### `/system` — Architecture & Schemas

**The source of truth for major architectural decisions.**

Read these files to understand:
- Overall system architecture and component relationships
- Database schemas and entity relationships
- API endpoints and contracts
- Cardano-specific integration boundaries and operational flow

Files:
- [`architecture.md`](./system/architecture.md) — System overview, module boundaries, and data flow
- [`database-schema.md`](./system/database-schema.md) — Database entities, relationships, and migration notes
- [`api-endpoints.md`](./system/api-endpoints.md) — REST and messaging endpoint documentation

Reference index:
- [System architecture](./system/architecture.md)
- [Database schema](./system/database-schema.md)
- [API endpoints](./system/api-endpoints.md)

---

### `/plans` — Plans & Implementation History

**Active and historical PRDs, orchestration prompts, and task trackers for reference.**

Before implementing a feature:
1. Check whether a similar or active plan already exists.
2. Reuse the established plan structure and naming convention.
3. Keep PRD, tasks JSON, and any prompt/research docs aligned as work progresses.

This folder uses one subdirectory per plan.

Reference index:
- [Plans index](./plans/readme.md)
- [Governance Voting](./plans/governance-voting/governance-voting-prd.md)
- [Advanced Governance Suite](./plans/advanced-governance-suite/advanced-governance-suite-prd.md)

---

### `/SOPs` — Standard Operating Procedures

**Learnings from resolved issues and repeatable operational procedures.**

When an issue is resolved or a complex integration succeeds:
1. Document the step-by-step solution.
2. Include common pitfalls and how to avoid them.
3. Reference related code, commands, or configuration.

Reference index:
- [SOP index](./SOPs/readme.md)
- [How to Ignore Files in KtLint](./SOPs/ktlint_ignore.md)

---

### `/skills` — Agent Skills

**Reusable, task-specific playbooks and operators.**

Use these when a task matches a supported skill:

| Skill | Description |
|-------|-------------|
| [`bech32-encoding-decoding`](./skills/bech32-encoding-decoding/SKILL.md) | Encode and decode bech32 strings |
| [`cardano-cli-staking`](./skills/cardano-cli-staking/SKILL.md) | Staking guidance templates |
| [`cardano-cli-staking-operator`](./skills/cardano-cli-staking-operator/SKILL.md) | Execute staking operations manually |
| [`cardano-cli-wallets-operator`](./skills/cardano-cli-wallets-operator/SKILL.md) | Execute wallet operations manually |
| [`git-commit-formatter`](./skills/git-commit-formatter/SKILL.md) | Conventional commit formatting |

Add to this list when new skills are introduced.

---

### `/workflows` — Development Workflows

**Step-by-step guides for common development tasks.**

Available workflows:

| Workflow | Description | Trigger |
|----------|-------------|---------|
| [`build.md`](./workflows/build.md) | Build backend and frontend | `/build` |
| [`test.md`](./workflows/test.md) | Run test suites | `/test` |
| [`backend.md`](./workflows/backend.md) | Backend Kotlin development | `/backend` |
| [`backend_dependencies.md`](./workflows/backend_dependencies.md) | Check and update dependencies | `/backend_dependencies` |
| [`frontend.md`](./workflows/frontend.md) | Frontend Vue.js development | `/frontend` |
| [`database.md`](./workflows/database.md) | Database migrations | `/database` |
| [`kotlin_migration.md`](./workflows/kotlin_migration.md) | Java to Kotlin migration | `/kotlin_migration` |
| [`update-doc.md`](./workflows/update-doc.md) | Update the agent documentation system | `/update-doc` |
| [`version.md`](./workflows/version.md) | Update project version and ASCII banner | `/version` |

---

## 🏗️ Project Overview

**JorManager** is a GUI-based Cardano stake pool management system that allows operators to:
- Monitor nodes and keys on a dashboard
- Create SSH connections to remote hosts
- Restart nodes, rotate KES keys, and modify pool fees
- Monitor and validate blocks, calculate leader logs, and review node health
- Manage stake pool funds, claim rewards, and perform transactions

### Technology Stack

| Layer | Technology | Key Components |
|-------|------------|----------------|
| **Backend** | Kotlin on Java 21, Spring Boot, Spring Data JPA | `controllers/`, `services/`, `entities/`, `repositories/` |
| **Frontend** | Vue 3, TypeScript, Pinia, Vite, Bootstrap 5 | `vue/src/views/`, `vue/src/components/`, `vue/src/stores/` |
| **Database** | PostgreSQL, Liquibase | `src/main/resources/db/` |
| **SSH / Node Ops** | SSHJ, Cardano CLI integrations | remote host and node management flows |
| **Testing** | JUnit 5, Vitest | `src/test/kotlin/`, `vue/tests/unit/` |

### Project Structure

```
jormanager/
├── src/main/kotlin/com/swiftmako/jormanager/
│   ├── controllers/
│   ├── entities/
│   ├── repositories/
│   ├── services/
│   ├── model/
│   └── nodeclient/
├── src/main/resources/db/
└── vue/
    ├── src/views/
    ├── src/components/
    ├── src/stores/
    ├── src/types/
    ├── src/composables/
    └── tests/unit/
```

---

## ⚡ Quick Commands

### Backend
```bash
./gradlew build
./gradlew test
./gradlew bootJar
./gradlew ktlintFormat
```

### Frontend
```bash
cd vue
npm install
npm run dev
npm run build
npm run test
./deploy.sh
```

---

## 🔐 Security Notes

- Never commit secrets or API keys.
- Database credentials in `application.properties` are for local development.
- SSH keys are managed per host within the application.
- PoolTool API keys and similar external credentials must stay out of commits.
