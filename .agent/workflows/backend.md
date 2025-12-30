---
description: Backend Kotlin development for API, database, and architecture
---

# Backend Development Workflow

This workflow guides development on the Kotlin backend.

## Overview

The backend uses:
- **Kotlin** — Primary language (running on Java 21)
- **Spring Boot** — Application framework
- **Spring Data JPA** — ORM
- **PostgreSQL** — Database
- **Liquibase** — Database migrations
- **Gradle** — Build system (Kotlin DSL)

---

## Package Structure

| Package | Purpose |
|---------|---------|
| `com.swiftmako.jormanager.controllers` | REST API endpoints |
| `com.swiftmako.jormanager.services` | Business logic |
| `com.swiftmako.jormanager.entities` | JPA entity classes |
| `com.swiftmako.jormanager.repositories` | Spring Data repositories |
| `com.swiftmako.jormanager.model` | Data models and DTOs |
| `com.swiftmako.jormanager.nodeclient` | Cardano node interaction |
| `com.swiftmako.jormanager.monitors` | Background monitoring |

---

## Quick Commands

### Build

// turbo
```bash
./gradlew build
```

### Run Application

```bash
./gradlew bootRun
```

Server starts at `http://localhost:8787`

### Test

// turbo
```bash
./gradlew test
```

### Lint (ktlint)

// turbo
```bash
./gradlew ktlintCheck
./gradlew ktlintFormat  # Auto-fix
```

---

## Architecture Patterns

### Layer Architecture

```
┌─────────────────────────────────────────┐
│           Controller Layer              │
│      (@RestController, endpoints)       │
├─────────────────────────────────────────┤
│            Service Layer                │
│     (@Service, business logic)          │
├─────────────────────────────────────────┤
│          Repository Layer               │
│   (JpaRepository, data access)          │
├─────────────────────────────────────────┤
│            Entity Layer                 │
│      (@Entity, domain models)           │
└─────────────────────────────────────────┘
```

### Controller Pattern

```kotlin
@RestController
@RequestMapping("/api/nodes")
class NodeController(
    private val nodeService: NodeService
) {
    @GetMapping("/{id}")
    fun getNode(@PathVariable id: Long): ResponseEntity<NodeDto> {
        return nodeService.findById(id)
            ?.let { ResponseEntity.ok(it.toDto()) }
            ?: ResponseEntity.notFound().build()
    }
    
    @PostMapping("/{id}/restart")
    fun restartNode(@PathVariable id: Long): ResponseEntity<Result> {
        val result = nodeService.restart(id)
        return ResponseEntity.ok(result)
    }
}
```

### Service Pattern

```kotlin
@Service
class NodeService(
    private val nodeRepository: NodeRepository,
    private val sshService: SshService
) {
    fun restart(nodeId: Long): Result {
        val node = nodeRepository.findById(nodeId).orElseThrow()
        return sshService.executeCommand(node.host, "systemctl restart cardano-node")
    }
    
    fun findById(id: Long): Node? = nodeRepository.findById(id).orElse(null)
}
```

### Repository Pattern

```kotlin
interface NodeRepository : JpaRepository<Node, Long> {
    fun findByHostId(hostId: Long): List<Node>
    
    @Query("SELECT n FROM Node n WHERE n.status = :status")
    fun findByStatus(@Param("status") status: NodeStatus): List<Node>
}
```

---

## Entity Development

### Creating Entities

```kotlin
// src/main/kotlin/com/swiftmako/jormanager/entities/

@Entity
@Table(name = "nodes")
class Node(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    
    @Column(nullable = false)
    val name: String,
    
    @ManyToOne
    @JoinColumn(name = "host_id")
    val host: Host,
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: NodeStatus = NodeStatus.UNKNOWN,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

enum class NodeStatus {
    RUNNING, STOPPED, UNKNOWN
}
```

### Database Migrations

See [/database workflow](./database.md) for Liquibase migration details.

---

## Common Patterns

### Null Safety

```kotlin
// Use Kotlin null-safe operators
fun findNode(name: String): NodeDto? {
    return nodeRepository.findByName(name)?.toDto()
}

// Elvis operator for defaults
val nodeName = node?.name ?: "Unknown"
```

### Extension Functions

```kotlin
fun Node.toDto() = NodeDto(
    id = id,
    name = name,
    hostName = host.name,
    status = status.name
)
```

### Coroutines (if used)

```kotlin
@Service
class AsyncService(
    private val scope: CoroutineScope
) {
    fun runAsync(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
```

---

## Environment Configuration

### Application Properties

```properties
# src/main/resources/application.properties
server.address=127.0.0.1
server.port=8787

spring.datasource.url=jdbc:postgresql://localhost:5432/jormanager
spring.datasource.username=jormanager
spring.datasource.password=jormanager

spring.liquibase.change-log=classpath:db/liquibase-changelog.xml
spring.jpa.hibernate.ddl-auto=none
```

---

## Troubleshooting

### Build Failures

**Problem:** Gradle build fails  
**Solution:** 
```bash
./gradlew clean build --refresh-dependencies
```

### Database Connection Issues

**Problem:** Can't connect to PostgreSQL  
**Solution:** Ensure PostgreSQL is running and credentials match `application.properties`

### Lazy Loading Exceptions

**Problem:** `LazyInitializationException` outside transaction  
**Solution:** Use `@EntityGraph` or `JOIN FETCH`:
```kotlin
@EntityGraph(attributePaths = ["host"])
fun findByIdWithHost(id: Long): Node?
```
