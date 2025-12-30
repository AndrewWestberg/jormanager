---
description: Check and research dependency updates with user approval workflow
---

# Backend Dependencies Workflow

This workflow guides checking and updating dependencies in the Gradle project.

## Quick Checks

### Check for Available Updates

// turbo
```bash
./gradlew dependencyUpdates -Drevision=release --no-parallel
```

This generates a report of available dependency updates.

---

## Dependency Management

### Version Catalog

Versions are managed in:
```
buildSrc/src/main/kotlin/Versions.kt
```

### Key Dependencies

| Dependency | Purpose |
|------------|---------|
| Spring Boot | Application framework |
| Kotlin | Primary language |
| PostgreSQL | Database driver |
| Liquibase | Database migrations |
| SSHJ | SSH connections |
| Moshi | JSON serialization |
| OkHttp | HTTP client |
| Ktor Network | Network utilities |

---

## Update Process

### Step 1: Check Available Updates

// turbo
```bash
./gradlew dependencyUpdates --no-parallel
```

### Step 2: Research Updates

For each update, research:
1. **Changelog** — What changed?
2. **Breaking changes** — Any API changes?
3. **Compatibility** — Works with current Java/Kotlin version?

### Step 3: Get User Approval

Before applying updates:
- Present findings to user
- Highlight any breaking changes
- Get explicit approval

### Step 4: Apply Updates

Update version in `buildSrc/src/main/kotlin/Versions.kt`:

```kotlin
object Versions {
    const val KOTLIN = "2.1.0"
    const val SPRING_BOOT = "3.4.0"
    // ...
}
```

### Step 5: Verify

// turbo
```bash
./gradlew clean build
./gradlew test
```

---

## Version Categories

### Safe Updates (Usually automatic)
- Patch versions (x.y.Z)
- Security fixes

### Review Required
- Minor versions (x.Y.z)
- New features

### Careful Review Required
- Major versions (X.y.z)
- Breaking changes possible

---

## Common Dependencies

### Spring Boot
- Check Spring Blog for release notes
- Major versions often have migration guides

### Kotlin
- Check Kotlin Blog for changes
- Update Kotlin plugin version in sync

### PostgreSQL Driver
- Usually safe to update minor/patch versions

### SSHJ
- Check for security updates
- Test SSH functionality after updates

---

## Troubleshooting

### Build Fails After Update

**Problem:** Build fails with new dependency version  
**Solution:**
1. Check error message for incompatibilities
2. Review migration guide if available
3. Consider rolling back if fix is complex

### Test Failures After Update

**Problem:** Tests fail with new version  
**Solution:**
1. Check for behavior changes in changelog
2. Update tests if behavior change is expected
3. Report bug if unexpected