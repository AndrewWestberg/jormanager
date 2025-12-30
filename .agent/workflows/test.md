---
description: Run test suites for backend and frontend
---

# Test Workflow

This workflow guides running tests across JorManager components.

## Quick Test Commands

### Backend Tests

// turbo
```bash
./gradlew test
```

### Frontend Tests

// turbo
```bash
cd vue && npm run test
```

---

## Backend Testing

### Run All Tests

// turbo
```bash
./gradlew test
```

### Run Single Test Class

```bash
./gradlew test --tests "com.swiftmako.jormanager.SomeTest"
```

### Run Tests with Output

```bash
./gradlew test --info
```

### Test Configuration

Tests use the same configuration as application with:
- Test database (configure in test profile if needed)
- Mockito/MockK for mocking

---

## Frontend Testing

### Run Unit Tests

// turbo
```bash
cd vue && npm run test
```

### Run Tests in Watch Mode

```bash
cd vue && npm run test:watch
```

### Linting

// turbo
```bash
cd vue && npm run lint
```

---

## Test Coverage

### Backend Coverage

```bash
./gradlew jacocoTestReport
# Report: build/reports/jacoco/
```

### Frontend Coverage

```bash
cd vue && npm run test:coverage
# Report: vue/coverage/
```

---

## Test Patterns

### Unit Test Pattern

```kotlin
class NodeServiceTest {
    private val nodeRepository = mockk<NodeRepository>()
    private val sshService = mockk<SshService>()
    private val nodeService = NodeService(nodeRepository, sshService)
    
    @Test
    fun `restart node executes ssh command`() {
        val node = Node(id = 1, name = "test", host = mockk())
        
        every { nodeRepository.findById(1) } returns Optional.of(node)
        every { sshService.executeCommand(any(), any()) } returns Result.success()
        
        val result = nodeService.restart(1)
        
        assertThat(result.isSuccess).isTrue()
        verify { sshService.executeCommand(node.host, "systemctl restart cardano-node") }
    }
}
```

### Integration Test Pattern

```kotlin
@SpringBootTest
@Transactional
class NodeRepositoryIntegrationTest {
    @Autowired
    lateinit var nodeRepository: NodeRepository
    
    @Test
    fun `findByHostId returns nodes for host`() {
        // Test with actual database
    }
}
```

---

## Troubleshooting

### Tests Fail with Database Errors

**Problem:** Tests can't connect to database  
**Solution:** Ensure PostgreSQL is running and test database exists

### Flaky Tests

**Problem:** Tests sometimes pass, sometimes fail  
**Solution:** Check for shared state or timing issues. Use `@Transactional` for database tests.
