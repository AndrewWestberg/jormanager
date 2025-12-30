---
description: Database migrations and persistence layer workflow
---

# Database Workflow

This workflow guides database operations, migrations, and persistence layer development.

## Overview

The persistence layer uses:
- **PostgreSQL** — Database
- **Liquibase** — Database migrations (XML changelogs)
- **Spring Data JPA** — ORM
- **Kotlin** — Entity and repository code

---

## Liquibase Migrations

### Overview

Migrations are **XML changelogs** that run **automatically on server startup**.

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

    <changeSet id="YYYYMMDD-description" author="developer">
        <addColumn tableName="nodes">
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

### Common Changesets

**Add Column:**
```xml
<changeSet id="add-column" author="dev">
    <addColumn tableName="hosts">
        <column name="port" type="INTEGER" defaultValue="22"/>
    </addColumn>
</changeSet>
```

**Create Table:**
```xml
<changeSet id="create-table" author="dev">
    <createTable tableName="logs">
        <column name="id" type="BIGINT" autoIncrement="true">
            <constraints primaryKey="true"/>
        </column>
        <column name="message" type="TEXT"/>
        <column name="created_at" type="TIMESTAMP"/>
    </createTable>
</changeSet>
```

**Add Index:**
```xml
<changeSet id="add-index" author="dev">
    <createIndex tableName="nodes" indexName="idx_nodes_host_id">
        <column name="host_id"/>
    </createIndex>
</changeSet>
```

---

## Project Structure

```
src/main/kotlin/com/swiftmako/jormanager/
├── entities/              # JPA entities
├── repositories/          # Spring Data repositories
└── ...

src/main/resources/db/
├── liquibase-changelog.xml  # Master changelog
└── *.xml                    # Individual changesets
```

---

## Entity Development

### Creating a New Entity

```kotlin
// src/main/kotlin/com/swiftmako/jormanager/entities/

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "hosts")
class Host(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    
    @Column(nullable = false)
    val name: String,
    
    @Column(nullable = false)
    val hostname: String,
    
    @Column(nullable = false)
    val port: Int = 22,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
```

### Database Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Table names | `snake_case`, plural | `wallet_entries` |
| Column names | `snake_case` | `created_at` |
| Entity classes | PascalCase, singular | `WalletEntry` |
| Foreign keys | `{table}_id` | `host_id` |
| Indexes | `idx_{table}_{column}` | `idx_nodes_host_id` |

---

## Repository Development

### Creating a Repository

```kotlin
import org.springframework.data.jpa.repository.JpaRepository

interface HostRepository : JpaRepository<Host, Long> {
    fun findByName(name: String): Host?
    fun findByHostname(hostname: String): Host?
}
```

### Custom Queries

```kotlin
interface NodeRepository : JpaRepository<Node, Long> {
    @Query("SELECT n FROM Node n WHERE n.host.id = :hostId")
    fun findByHostId(@Param("hostId") hostId: Long): List<Node>
    
    @Query("SELECT n FROM Node n JOIN FETCH n.host WHERE n.id = :id")
    fun findByIdWithHost(@Param("id") id: Long): Node?
}
```

---

## Common Operations

### Backup Database

```bash
pg_dump -h localhost -U jormanager -d jormanager > backup.sql
```

The project includes a backup script:
```bash
./dbbackup.sh
```

### Restore Database

```bash
psql -h localhost -U jormanager -d jormanager < backup.sql
```

### Unlock Liquibase

If migrations get stuck:
```sql
-- src/unlock_liquibase.sql
DELETE FROM databasechangeloglock;
```

---

## Performance Tips

### Avoid N+1 Queries

```kotlin
// Bad: N+1 queries
val hosts = hostRepository.findAll()
hosts.forEach { println(it.nodes.size) }

// Good: Eager loading
@EntityGraph(attributePaths = ["nodes"])
fun findAllWithNodes(): List<Host>
```

### Indexes

Ensure indexes exist on:
- All foreign key columns
- Frequently queried columns
- Columns in WHERE clauses

---

## Troubleshooting

### Migration Failed on Startup

**Problem:** Server won't start due to migration error  
**Solution:** 
1. Check the error message for the failing changeset
2. Fix the migration file
3. If already partially applied, may need to manually fix `databasechangelog` table

### LazyInitializationException

**Problem:** Accessing lazy-loaded collection outside transaction  
**Solution:** Use `@EntityGraph` or `JOIN FETCH`

### Slow Queries

**Problem:** Queries taking too long  
**Solution:** Check query plan with `EXPLAIN ANALYZE` and add indexes
