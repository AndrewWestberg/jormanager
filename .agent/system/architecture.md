# 🏗️ JorManager System Architecture

> **Source of truth for system-wide architectural decisions.**

This document provides the definitive overview of the JorManager system architecture, component relationships, and core design patterns.

---

## System Overview

JorManager is a GUI-based Cardano stakepool management system that connects to remote Cardano nodes via SSH and provides a web interface for pool operators.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              JORMANAGER                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────┐     REST API      ┌─────────────────────────────────────┐  │
│  │  Vue.js UI  │◄──────────────────►│       Spring Boot Backend           │  │
│  │(Bootstrap)  │    WebSocket       │       (Kotlin/Java 21)              │  │
│  └─────────────┘                    └───────────┬───────────────────────┬─┘  │
│                                                 │                       │    │
│                          ┌──────────────────────┼───────────────────────┤    │
│                          │                      │                       │    │
│                          ▼                      ▼                       ▼    │
│               ┌──────────────────┐   ┌─────────────────┐   ┌───────────────┐│
│               │   PostgreSQL     │   │  Remote Hosts   │   │ Cardano Node  ││
│               │   (Liquibase)    │   │    (SSHJ)       │   │   Client      ││
│               └──────────────────┘   └─────────────────┘   └───────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

### Backend Package Structure

| Package | Responsibility |
|---------|----------------|
| `com.swiftmako.jormanager.controllers` | REST API endpoints |
| `com.swiftmako.jormanager.services` | Business logic |
| `com.swiftmako.jormanager.entities` | JPA entity classes |
| `com.swiftmako.jormanager.repositories` | Spring Data repositories |
| `com.swiftmako.jormanager.model` | Data models and DTOs |
| `com.swiftmako.jormanager.nodeclient` | Cardano node interaction |
| `com.swiftmako.jormanager.monitors` | Background monitoring tasks |

### Controllers

| Controller | Endpoints | Responsibility |
|------------|-----------|----------------|
| `BlockController` | `/api/blocks/*` | Block monitoring, leader logs |
| `HostController` | `/api/hosts/*` | SSH host management |
| `NodeController` | `/api/nodes/*` | Node operations (restart, KES rotation) |
| `WalletController` | `/api/wallet/*` | Wallet operations, transactions |
| `FileController` | `/api/files/*` | File operations on remote hosts |

### Frontend Structure

| Directory | Purpose |
|-----------|---------|
| `vue/src/views/` | Page-level Vue components |
| `vue/src/components/` | Reusable UI components |
| `vue/src/store/` | Vuex state modules |
| `vue/src/router.js` | Vue Router configuration |

---

## Core Design Patterns

### Backend Patterns

**1. Repository Pattern**
```kotlin
// All data access goes through Spring Data repositories
interface HostRepository : JpaRepository<Host, Long> {
    fun findByName(name: String): Host?
}
```

**2. Service Layer**
```kotlin
// Business logic in @Service classes
@Service
class NodeService(private val nodeRepository: NodeRepository) {
    fun restartNode(nodeId: Long): Result { ... }
}
```

**3. Controller Layer**
```kotlin
// REST endpoints in @RestController classes
@RestController
@RequestMapping("/api/nodes")
class NodeController(private val nodeService: NodeService) {
    @PostMapping("/{id}/restart")
    fun restartNode(@PathVariable id: Long): ResponseEntity<Result>
}
```

### Frontend Patterns

**1. Single File Components**
```vue
<template>
  <div>{{ nodeName }}</div>
</template>

<script>
export default {
  props: ['nodeName']
}
</script>
```

**2. Vuex State Management**
```javascript
// Centralized state in store modules
export default {
  state: { hosts: [] },
  mutations: { SET_HOSTS(state, hosts) { state.hosts = hosts } },
  actions: { async fetchHosts({ commit }) { ... } }
}
```

---

## Integration Points

### SSH Integration (SSHJ)
- Connects to remote hosts for node management
- Executes commands (restart, status checks)
- Transfers files (KES key rotation)

### Cardano Node Client
- Communicates with cardano-node instances
- Queries tip, calculates leader logs
- Submits transactions

### PostgreSQL (Liquibase)
- Persistent storage for hosts, nodes, blocks
- Migrations managed via Liquibase XML changelogs

### PoolTool API
- Optional integration for pool statistics
- Configured via `pooltool.apikey` property

---

## Environment Configuration

### Application Properties

```properties
# Server
server.address=127.0.0.1
server.port=8787

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/jormanager
spring.datasource.username=jormanager
spring.datasource.password=jormanager

# External
pooltool.apikey=your_api_key
libsodium.path=/usr/local/lib/libsodium.so
```

---

## Build & Deploy Overview

See [`/workflows/build.md`](../workflows/build.md) for detailed build instructions.

### Quick Reference

```bash
# Full backend build (includes frontend)
./gradlew bootJar

# Frontend only
cd vue && npm run build

# Deploy frontend to backend resources
./vue/deploy.sh
```
