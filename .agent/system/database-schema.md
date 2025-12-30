# 📊 Database Schema Reference

> **Source of truth for database entities and relationships.**

This document describes the database schema for JorManager. The persistence layer uses **Spring Data JPA** with **PostgreSQL** and **Liquibase** for migrations.

---

## Entity Overview

The database is organized into the following domains:

| Domain | Description |
|--------|-------------|
| **Hosts** | SSH connection configurations for remote servers |
| **Nodes** | Cardano node instances running on hosts |
| **Blocks** | Block production records and leader logs |
| **Wallets** | Wallet entries and transaction management |

---

## Key Entities

Entities are located in:
```
src/main/kotlin/com/swiftmako/jormanager/entities/
```

### Common Patterns

```kotlin
@Entity
@Table(name = "hosts")
class Host(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    
    @Column(nullable = false)
    val name: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
```

---

## Database Conventions

### Naming Conventions
- **Tables**: `snake_case`, lowercase (e.g., `hosts`, `wallet_entries`)
- **Columns**: `snake_case` (e.g., `created_at`, `host_id`)
- **Entity classes**: PascalCase, singular (e.g., `Host`, `WalletEntry`)
- **Foreign keys**: `{table_singular}_id` (e.g., `host_id`, `node_id`)

### Common Columns
All entities should include where applicable:
- `id` — Primary key (auto-generated)
- `created_at` — Creation timestamp
- `updated_at` — Last update timestamp

---

## Liquibase Migrations

### Overview

Migrations are XML-based changelogs that run automatically on server startup.

**Location:**
```
src/main/resources/db/
├── liquibase-changelog.xml    # Master changelog
└── *.xml                      # Individual changesets
```

### Configuration

```properties
spring.liquibase.change-log=classpath:db/liquibase-changelog.xml
spring.jpa.hibernate.ddl-auto=none  # Liquibase handles schema
```

### Creating a New Migration

1. **Create a new changeset file** in `src/main/resources/db/`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="add-new-column" author="developer">
        <addColumn tableName="hosts">
            <column name="description" type="VARCHAR(255)"/>
        </addColumn>
    </changeSet>
    
</databaseChangeLog>
```

2. **Include in master changelog** (`liquibase-changelog.xml`):
```xml
<include file="classpath:db/new-changeset.xml"/>
```

3. **Migrations run automatically** when the server starts.

---

## Repository Pattern

All database access goes through Spring Data JPA repositories:

```kotlin
// src/main/kotlin/com/swiftmako/jormanager/repositories/

interface HostRepository : JpaRepository<Host, Long> {
    fun findByName(name: String): Host?
}

interface NodeRepository : JpaRepository<Node, Long> {
    fun findByHostId(hostId: Long): List<Node>
}
```

---

## Common Operations

### Backup Database

```bash
pg_dump -h localhost -U jormanager -d jormanager > backup.sql
```

### Restore Database

```bash
psql -h localhost -U jormanager -d jormanager < backup.sql
```

### Unlock Liquibase (if needed)

```sql
-- If migrations get stuck
DELETE FROM databasechangeloglock;
```

---

## Performance Tips

### Indexes
Ensure indexes exist on:
- All foreign key columns
- Frequently queried columns
- Columns used in WHERE clauses

### N+1 Query Prevention

```kotlin
// Use @EntityGraph for eager loading
@EntityGraph(attributePaths = ["nodes"])
fun findByIdWithNodes(id: Long): Host?
```

---

## Troubleshooting

### Migration Failed on Startup

**Problem:** Server won't start due to migration error  
**Solution:** 
1. Check the error message for the failing changeset
2. Fix the migration file
3. If partially applied, may need to update `databasechangelog` table

### LazyInitializationException

**Problem:** Accessing lazy-loaded collection outside transaction  
**Solution:** Use `@EntityGraph` or `JOIN FETCH`
