---
description: Build all project modules (backend, frontend)
---

# Build Workflow

This workflow guides building all components of the JorManager project.

## Quick Build Commands

### Full Build (Backend + Frontend)

// turbo
```bash
./gradlew bootJar
```

This automatically:
1. Builds the Vue.js frontend via `buildVue` task
2. Copies frontend to `src/main/resources/static/`
3. Compiles Kotlin backend
4. Creates executable JAR

### Backend Only

// turbo
```bash
./gradlew build -x buildVue
```

### Frontend Only

```bash
cd vue
npm install
npm run build
./deploy.sh
```

---

## Full Build Procedure

### Step 1: Install Frontend Dependencies

// turbo
```bash
cd vue && npm install
```

### Step 2: Build Everything

// turbo
```bash
./gradlew bootJar
```

This will:
- Run `buildVue` task (compiles Vue and syncs to resources)
- Compile all Kotlin code
- Run ktlint checks
- Generate executable JAR in `build/libs/`

**Common issues:**
- If Node.js version mismatch, use `nvm use v16.17.0`
- Use `./gradlew build -x test` to skip tests during development

---

## Build Tasks

### Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew build` | Compile and test |
| `./gradlew bootJar` | Create executable JAR (includes frontend) |
| `./gradlew bootRun` | Run application locally |
| `./gradlew buildVue` | Build Vue.js frontend only |
| `./gradlew ktlintFormat` | Format Kotlin code |

### Vue.js Tasks

| Command | Description |
|---------|-------------|
| `npm run serve` | Development server with hot reload |
| `npm run build` | Production build to `dist/` |
| `npm run lint` | Lint JavaScript/Vue files |
| `./deploy.sh` | Build and sync to backend resources |

---

## Build Artifacts

| Component | Output Location |
|-----------|-----------------|
| Backend JAR | `build/libs/jormanager-*.jar` |
| Frontend | `vue/dist/` → `src/main/resources/static/` |

---

## Frontend Deployment

The `vue/deploy.sh` script handles frontend deployment:

```bash
#!/bin/bash
pushd $(dirname "$0")
export NVM_DIR=$HOME/.nvm
source $NVM_DIR/nvm.sh
nvm use v16.17.0
npm run build
rsync -av --progress --delete dist/ ../src/main/resources/static
popd
```

This is automatically called by the `buildVue` Gradle task.

---

## Running the Application

### Development

```bash
# Backend (with auto-reload)
./gradlew bootRun

# Frontend (separate terminal, with hot reload)
cd vue && npm run serve
```

### Production

```bash
# Build complete JAR
./gradlew bootJar

# Run
java -jar build/libs/jormanager-*.jar
```

---

## Troubleshooting

### Frontend Build Fails

**Problem:** `npm run build` errors  
**Solution:** 
```bash
cd vue
rm -rf node_modules package-lock.json
npm install
npm run build
```

### Node.js Version Issues

**Problem:** Incompatible Node.js version  
**Solution:** 
```bash
nvm install 16.17.0
nvm use 16.17.0
```

### Gradle Build Fails

**Problem:** Build errors  
**Solution:**
```bash
./gradlew clean build --refresh-dependencies
```