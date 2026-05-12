# 🤖 JorManager AI Agent Instructions

> **For all AI coding assistants.** This file provides a starting point for understanding the JorManager codebase.

---

## Quick Start

**Read the full documentation:** [`.agent/readme.md`](./.agent/readme.md)

The `.agent/` directory contains comprehensive documentation organized for AI agents:

| Folder | Purpose | When to Read |
|--------|---------|--------------|
| `.agent/system/` | Architecture, schemas, APIs | Understanding system design |
| `.agent/plans/` | Active and historical implementation plans | Before planning or implementing new features |
| `.agent/SOPs/` | Standard operating procedures | When encountering known issues |
| `.agent/skills/` | Reusable skills and operators | When a task matches a supported skill |
| `.agent/workflows/` | Step-by-step guides | When executing specific tasks |

---

## Available Workflows

Use these slash commands to access workflows:

| Command | Description |
|---------|-------------|
| `/build` | Build backend and frontend |
| `/test` | Run test suites |
| `/backend` | Backend Kotlin/Spring development |
| `/backend_dependencies` | Check and update dependencies |
| `/frontend` | Frontend Vue.js development |
| `/database` | Database migrations (Liquibase) |
| `/kotlin_migration` | Java to Kotlin migration |
| `/update-doc` | Update this documentation |

---

## Project Overview

**JorManager** is a GUI-based Cardano stakepool management system that allows operators to:
- Monitor nodes and keys on a dashboard
- Create SSH connections to remote hosts
- Restart nodes, rotate KES keys, modify pool fees
- Monitor and validate blocks, calculate leader logs
- Manage stakepool funds, claim rewards, perform transactions

### Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Kotlin (Java 21), Spring Boot, Spring Data JPA |
| Frontend | Vue 3, TypeScript, Pinia, Vite, Bootstrap 5 |
| Database | PostgreSQL, Liquibase migrations |
| SSH | SSHJ for remote host connections |
| Build | Gradle (Kotlin DSL), npm |

### Project Structure

| Directory | Responsibility |
|-----------|----------------|
| `src/main/kotlin/com/swiftmako/jormanager/` | Backend (controllers, services, entities) |
| `vue/` | Vue.js frontend |
| `src/main/resources/db/` | Liquibase database migrations |

---

## Important Patterns

### Backend (Kotlin)
- Use Repository pattern for data access
- Business logic in `@Service` classes
- REST endpoints in `@RestController` classes
- Null-safe Kotlin idioms

### Frontend (Vue 3)
- Single File Components (`.vue`)
- Pinia for state management
- Vue Router for navigation
- Bootstrap-Vue-Next for UI components
- Vite for development and builds

---

## Before You Start

1. Read [`.agent/readme.md`](./.agent/readme.md) for full documentation index
2. Check [`.agent/system/architecture.md`](./.agent/system/architecture.md) for system overview
3. Review relevant workflow in [`.agent/workflows/`](./.agent/workflows/)

---

## Security Notes

- Never commit secrets or API keys
- Database credentials are in `application.properties` (local dev only)
- SSH keys are managed per-host in the application

## Documentation Update Rules

- Treat PRD history sections such as `Status Log` or `Progress Log` as append-only chronological transcripts.
- Store plan artifacts in `.agent/plans/{feature-name}/` using the current PRD, tasks JSON, and optional `prompt.md` convention.
