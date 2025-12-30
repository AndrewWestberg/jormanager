---
description: Safe Java to Kotlin migration with test-first approach
---

# Java to Kotlin Migration Workflow

This workflow guides safe migration of Java code to Kotlin with a test-first approach.

## Overview

JorManager is primarily Kotlin, but may contain legacy Java code. This workflow ensures safe migration without breaking functionality.

---

## Migration Process

### Step 1: Add Tests First

Before migrating any code, ensure test coverage exists:

```kotlin
class HostServiceTest {
    @Test
    fun `existing functionality works`() {
        // Test current behavior before migration
    }
}
```

### Step 2: Use IntelliJ Auto-Convert

1. Open the Java file
2. **Code → Convert Java File to Kotlin File** (Ctrl+Alt+Shift+K)
3. Review the generated Kotlin code

### Step 3: Kotlin-ify the Code

After auto-conversion, improve the code with Kotlin idioms:

```kotlin
// Before (auto-converted)
fun findById(id: Long): Host? {
    val optional = hostRepository.findById(id)
    if (optional.isPresent) {
        return optional.get()
    }
    return null
}

// After (Kotlin idioms)
fun findById(id: Long): Host? = hostRepository.findById(id).orElse(null)
```

### Step 4: Run Tests

// turbo
```bash
./gradlew test
```

### Step 5: Format Code

// turbo
```bash
./gradlew ktlintFormat
```

---

## Common Conversions

### Null Safety

```kotlin
// Java nullable → Kotlin nullable type
String name = null;  // Java
val name: String? = null  // Kotlin

// Java Optional → Kotlin nullable
Optional<Host> → Host?

// Null checks
if (host != null) { ... }  // Java
host?.let { ... }  // Kotlin
```

### Data Classes

```kotlin
// Java POJO → Kotlin data class
// Before
public class HostDto {
    private String name;
    private String hostname;
    // getters, setters, equals, hashCode...
}

// After
data class HostDto(
    val name: String,
    val hostname: String
)
```

### Extension Functions

```kotlin
// Convert utility methods to extensions
// Before (Java static method)
public static String formatHostInfo(Host host) {
    return host.getName() + " (" + host.getHostname() + ")";
}

// After (Kotlin extension)
fun Host.formatInfo(): String = "$name ($hostname)"
```

### When Expressions

```kotlin
// Java switch → Kotlin when
// Before
switch (status) {
    case RUNNING: return "Active";
    case STOPPED: return "Inactive";
    default: return "Unknown";
}

// After
when (status) {
    NodeStatus.RUNNING -> "Active"
    NodeStatus.STOPPED -> "Inactive"
    else -> "Unknown"
}
```

---

## Package Structure

All Kotlin code should be in:
```
src/main/kotlin/com/swiftmako/jormanager/
```

---

## Checklist

For each file being migrated:

- [ ] Tests exist for current functionality
- [ ] Auto-convert using IntelliJ
- [ ] Replace Java patterns with Kotlin idioms
- [ ] Use nullable types instead of Optional
- [ ] Convert to data classes where appropriate
- [ ] Add extension functions for utilities
- [ ] Run tests
- [ ] Run ktlint format
- [ ] Verify no regressions

---

## Troubleshooting

### Compilation Errors After Conversion

**Problem:** Code doesn't compile after auto-conversion  
**Solution:** Check for:
- Missing `?` on nullable types
- SAM conversion issues with lambdas
- Platform type mismatches

### Test Failures

**Problem:** Tests fail after migration  
**Solution:** 
1. Check null handling differences
2. Verify equals/hashCode behavior with data classes
3. Check for mutable vs immutable collection issues
