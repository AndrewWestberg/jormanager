# 📚 JorManager Agent Documentation Index

> **For AI Agents:** Read this file first to understand available documentation and when to reference each resource.

This folder contains all the documentation needed for AI agents to effectively assist with development on the JorManager project. Use this index to quickly navigate to the relevant documentation for your current task.

---

## 🗺️ Quick Navigation

| Folder | Purpose | When to Read |
|--------|---------|--------------|
| [`/system`](./system/) | Architecture, schemas, API endpoints | **First**, for any architectural decisions or understanding system design |
| [`/task`](./task/) | PRDs and implementation plans history | When implementing new features similar to past work |
| [`/SOPs`](./SOPs/) | Standard Operating Procedures | When encountering known issues or following established patterns |
| [`/workflows`](./workflows/) | Step-by-step development workflows | When executing specific development tasks |

---

## 📁 Folder Details

### `/system` — Architecture & Schemas

**The source of truth for major architectural decisions.**

Read these files to understand:
- Overall system architecture and component relationships
- Database schemas and entity relationships
- API endpoints and contracts

Files:
- `architecture.md` — System overview, module dependencies, data flow
- `database-schema.md` — Database entities, relationships, migrations
- `api-endpoints.md` — REST endpoints, request/response formats

---

### `/task` — Implementation History

**Successful PRDs and implementation plans for reference.**

Before implementing a feature:
1. Check if a similar feature was implemented before
2. Use past plans as templates for consistency
3. Follow established patterns from successful implementations

---

### `/SOPs` — Standard Operating Procedures

**Learnings from resolved issues and best practices.**

When an issue is resolved or a complex integration succeeds:
1. Document the step-by-step solution
2. Include common pitfalls and how to avoid them
3. Reference related code or configuration

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
| [`database.md`](./workflows/database.md) | Database migrations (Liquibase) | `/database` |
| [`kotlin_migration.md`](./workflows/kotlin_migration.md) | Java to Kotlin migration | `/kotlin_migration` |
| [`update-doc.md`](./workflows/update-doc.md) | Update documentation | `/update-doc` |

---

## 🏗️ Project Overview

**JorManager** is a GUI-based Cardano stakepool management system that allows operators to:
- Monitor nodes and keys on a dashboard
- Create SSH connections to remote hosts
- Restart nodes, rotate KES keys, modify pool fees
- Monitor and validate blocks, calculate leader logs
- Manage stakepool funds, claim rewards, perform transactions

### Technology Stack

| Layer | Technology | Key Components |
|-------|------------|----------------|
| **Backend** | Kotlin (Java 21), Spring Boot | `controllers/`, `services/`, `entities/` |
| **Frontend** | Vue.js 2, Bootstrap-Vue, Vuex | `vue/src/views/`, `vue/src/components/` |
| **Database** | PostgreSQL, Liquibase | `src/main/resources/db/` |
| **SSH** | SSHJ | Remote host connections |

### Project Structure

```
jormanager/
├── src/main/kotlin/com/swiftmako/jormanager/
│   ├── controllers/          # REST API endpoints
│   ├── entities/             # JPA entities
│   ├── repositories/         # Spring Data repositories
│   ├── services/             # Business logic
│   ├── model/                # Data models
│   └── nodeclient/           # Cardano node client
├── vue/                      # Vue.js frontend
│   ├── src/views/            # Dashboard, Hosts, Nodes, Blocks, Wallet
│   ├── src/components/       # Reusable components
│   └── src/store/            # Vuex state management
└── src/main/resources/db/    # Liquibase migrations
```

---

## ⚡ Quick Commands

### Backend
```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Create executable JAR
./gradlew bootJar

# Format Kotlin code
./gradlew ktlintFormat
```

### Frontend
```bash
cd vue
npm install
npm run serve      # Development server
npm run build      # Production build
./deploy.sh        # Build and sync to backend resources
```

---

## 🔐 Security Notes

- Never commit secrets or API keys
- Database credentials in `application.properties` are for local development
- SSH keys are managed per-host within the application
- PoolTool API key configurable in `application.properties`
