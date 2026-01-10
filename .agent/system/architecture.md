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
| `vue/src/views/` | Page-level Vue 3 components (Composition API) |
| `vue/src/components/` | Reusable UI components |
| `vue/src/stores/` | Pinia state management stores |
| `vue/src/composables/` | Vue 3 composition API utilities |
| `vue/src/types/` | TypeScript type definitions |
| `vue/src/utils/` | Utility functions and filters |
| `vue/src/router.ts` | Vue Router 4 configuration |
| `vue/tests/unit/` | Vitest unit tests |

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

**1. Composition API with TypeScript**
```vue
<template>
  <div>{{ nodeName }}</div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useJorManagerStore } from '@/stores/jormanager'

const props = defineProps<{ nodeName: string }>()
const store = useJorManagerStore()
</script>
```

**2. Pinia State Management**
```typescript
import { defineStore } from 'pinia'

export const useJorManagerStore = defineStore('jormanager', {
  state: () => ({ hosts: [], nodes: [] }),
  getters: {
    hostsCount: (state) => state.hosts.length
  },
  actions: {
    async fetchHosts() { ... }
  }
})
```

**3. Event Bus (Mitt)**
```typescript
import { useEventBus } from '@/composables/useEventBus'

const emitter = useEventBus()
emitter.emit('show-modal', data)
emitter.on('confirm', handler)
```

**4. Bootstrap-Vue-Next Components**
```vue
import { BTable, BModal, BButton } from 'bootstrap-vue-next'
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
