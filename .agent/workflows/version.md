---
description: Update project version in build.gradle.kts and ASCII banner
---

# Version Update Workflow

Update the JorManager version number in both the build configuration and the ASCII art startup banner.

## Files to Update

| File | Description |
|------|-------------|
| `build.gradle.kts` | Line 19: `version = "X.X.X"` |
| `src/main/resources/banner.txt` | ASCII art banner with version |

---

## Quick Commands

```bash
# View current version
grep "^version = " build.gradle.kts
```

---

## Procedure

### Step 1: Update build.gradle.kts

Edit line 19 in `build.gradle.kts`:

```kotlin
version = "X.X.X"  // e.g., version = "11.0.0"
```

### Step 2: Generate ASCII Art Banner

1. Navigate to the ASCII art generator:
   ```
   https://patorjk.com/software/taag/#p=display&f=Ogre&t=JorManager+X+X+.+X+.+X
   ```

2. **Important formatting rules:**
   - Use the **Ogre** font
   - Put **spaces between each version digit**: `1 1 . 0 . 0` not `11 . 0 . 0`
   - Use `+` for spaces in the URL: `JorManager+1+1+.+0+.+0`

3. Example URLs:
   - Version 11.0.0: `https://patorjk.com/software/taag/#p=display&f=Ogre&t=JorManager+1+1+.+0+.+0`
   - Version 12.13.15: `https://patorjk.com/software/taag/#p=display&f=Ogre&t=JorManager+1+2+.+1+3+.+1+5`

4. Click the **Copy** button on the website to copy the ASCII art to clipboard

5. Replace the entire contents of `src/main/resources/banner.txt` with the copied text

> [!IMPORTANT]
> Always use the Copy button on the website rather than manually copying the text. This preserves exact spacing and trailing whitespace that is critical for proper alignment.

### Step 3: Verify Changes

```bash
# Verify version in build.gradle.kts
grep "^version = " build.gradle.kts

# View the banner
cat src/main/resources/banner.txt
```

---

## Example Banner Output (11.0.0)

```
   __                                                       _   _        ___         ___  
   \ \  ___  _ __ /\/\   __ _ _ __   __ _  __ _  ___ _ __  / | / |      / _ \       / _ \ 
    \ \/ _ \| '__/    \ / _` | '_ \ / _` |/ _` |/ _ \ '__| | | | |     | | | |     | | | |
 /\_/ / (_) | | / /\/\ \ (_| | | | | (_| | (_| |  __/ |    | | | |  _  | |_| |  _  | |_| |
 \___/ \___/|_| \/    \/\__,_|_| |_|\__,_|\__, |\___|_|    |_| |_| (_)  \___/  (_)  \___/ 
                                          |___/                                           
```

---

## Commit

```bash
git add build.gradle.kts src/main/resources/banner.txt
git commit -m "chore: Bump version to X.X.X"
```