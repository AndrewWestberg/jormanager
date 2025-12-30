# Claude Code Instructions

> **For Claude Code.** This file points to the centralized documentation in `.agent/`.

---

## Documentation Location

All agent documentation is centralized in the [`.agent/`](./.agent/) directory.

**Start here:** [`.agent/readme.md`](./.agent/readme.md)

---

## Quick Reference

### System Documentation
- [Architecture](./.agent/system/architecture.md) — System overview, modules, patterns
- [Database Schema](./.agent/system/database-schema.md) — Entities, relationships
- [API Endpoints](./.agent/system/api-endpoints.md) — REST API contracts

### Workflows
- [/build](./.agent/workflows/build.md) — Build all modules
- [/test](./.agent/workflows/test.md) — Run tests
- [/backend](./.agent/workflows/backend.md) — Backend Kotlin development
- [/backend_dependencies](./.agent/workflows/backend_dependencies.md) — Dependency updates
- [/frontend](./.agent/workflows/frontend.md) — Vue.js development
- [/database](./.agent/workflows/database.md) — Database operations
- [/kotlin_migration](./.agent/workflows/kotlin_migration.md) — Java to Kotlin migration
- [/update-doc](./.agent/workflows/update-doc.md) — Update documentation

### Learning Resources
- [Task History](./.agent/task/) — Past implementation plans
- [SOPs](./.agent/SOPs/) — Standard operating procedures

---

## Common Commands

### Backend
```bash
./gradlew build                    # Build all
./gradlew test                     # Run all tests
./gradlew bootJar                  # Create executable JAR
./gradlew ktlintFormat             # Format Kotlin code
```

### Frontend
```bash
cd vue
npm install && npm run serve       # Development
npm run build                      # Production build
npm run lint                       # Lint check
./deploy.sh                        # Build and deploy to backend
```

---

## Project Structure

```
jormanager/
├── .agent/                 # 📚 Agent documentation (READ THIS)
├── src/main/kotlin/        # Kotlin backend
│   └── com/swiftmako/jormanager/
│       ├── controllers/    # REST endpoints
│       ├── entities/       # JPA entities
│       ├── repositories/   # Data access
│       ├── services/       # Business logic
│       └── model/          # Data models
├── vue/                    # Vue.js frontend
│   ├── src/views/          # Page components
│   ├── src/components/     # Reusable components
│   └── src/store/          # Vuex state
└── src/main/resources/
    └── db/                 # Liquibase migrations
```

---

## Creating Documentation

When completing features or resolving issues:

1. **Implementation plans** → Save to `.agent/task/{domain}/`
2. **Resolved issues** → Create SOP in `.agent/SOPs/{category}/`
3. **New workflows** → Add to `.agent/workflows/`

Run `/update-doc` workflow for guidance.
