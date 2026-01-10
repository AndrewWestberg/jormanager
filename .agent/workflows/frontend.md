---
description: Frontend Vue.js development workflow
---

# Frontend Development Workflow

This workflow guides development on the Vue.js frontend (`vue/`).

## Overview

The frontend is built with:
- **Vue.js 3** — Progressive JavaScript framework (Composition API)
- **Bootstrap-Vue-Next** — Bootstrap 5 components for Vue 3
- **Pinia** — State management (replaces Vuex)
- **Vue Router** — Client-side routing
- **Font Awesome** — Icons
- **Vite** — Build tool with hot module replacement

---

## Setup

### Prerequisites

- Node.js 24+ (use nvm: `nvm use v24`)
- npm

### Installation

// turbo
```bash
cd vue
npm install
```

---

## Development Server

### Standalone Mode (Frontend Only)

// turbo
```bash
cd vue
npm run dev
```

Opens at [http://localhost:5173](http://localhost:5173)

### Testing with Backend

To test the frontend against a running backend, use port 8082 which is configured to proxy to the backend:

// turbo
```bash
cd vue
npm run dev -- --port 8082
```

Opens at [http://localhost:8082](http://localhost:8082)

This allows:
- Hot module replacement (changes reflect immediately)
- API calls proxy to backend server
- Full integration testing of frontend changes

> **Note**: Ensure the backend is running before testing features that require API calls.

---

## Project Structure

```
vue/src/
├── App.vue                 # Root component
├── main.ts                 # Application entry point
├── router/index.ts         # Vue Router configuration
├── views/                  # Page-level components
│   ├── Dashboard.vue       # Main dashboard
│   ├── Hosts.vue           # SSH host management
│   ├── Nodes.vue           # Node management
│   ├── Blocks.vue          # Block monitoring
│   └── Wallet.vue          # Wallet management
├── components/             # Reusable components
│   ├── AddEditHostModal.vue
│   ├── AddNodeWizard.vue
│   ├── AddWalletEntryWizard.vue
│   ├── SendAdaModal.vue
│   ├── SpendingPasswordConfirmModal.vue
│   └── ...
├── stores/                 # Pinia state stores
├── composables/            # Vue 3 composables
├── types/                  # TypeScript type definitions
├── utils/                  # Utility functions and filters
└── assets/                 # Static assets
```

```
vue/tests/unit/
├── components/             # Component unit tests
├── store/                  # Store unit tests
└── views/                  # View unit tests
```

---

## Component Guidelines

### Single File Components (Vue 3 Composition API)

```vue
<template>
  <div class="node-card">
    <h3>{{ node.name }}</h3>
    <p>Status: {{ node.status }}</p>
  </div>
</template>

<script setup lang="ts">
interface NodeProps {
  node: {
    name: string
    status: string
  }
}

defineProps<NodeProps>()
</script>

<style scoped>
.node-card {
  padding: 1rem;
  border: 1px solid #ccc;
}
</style>
```

### Bootstrap-Vue-Next Usage

```vue
<template>
  <BCard title="Node Status">
    <BTable :items="nodes" :fields="fields">
      <template #cell(actions)="{ item }">
        <BButton size="sm" variant="primary" @click="restart(item)">
          Restart
        </BButton>
      </template>
    </BTable>
  </BCard>
</template>
```

### Pinia State Management

```typescript
// stores/nodes.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useNodesStore = defineStore('nodes', () => {
  const nodes = ref([])
  const loading = ref(false)

  async function fetchNodes() {
    loading.value = true
    try {
      const response = await fetch('/api/nodes')
      nodes.value = await response.json()
    } finally {
      loading.value = false
    }
  }

  return { nodes, loading, fetchNodes }
})
```

### Event Bus Pattern

For cross-component communication:

```typescript
import { useEventBus } from '@/composables/useEventBus'

const emitter = useEventBus()

// Emit event
emitter.emit('show-spending-password-modal', { action: 'send-ada' })

// Listen for event
emitter.on('confirm-spending-password-with-action', (data) => {
  if (data.action === 'send-ada') {
    // Handle password confirmation
  }
})
```

### Modal Best Practices

When using modals that trigger password confirmation:

1. **Don't use `ok()` from slot** - it auto-closes the modal
2. **Use `@ok.prevent`** - prevents auto-close while still handling the event
3. **Call handler directly** - bypass modal auto-close behavior

```vue
<!-- Wrong: Modal closes when clicking ok() -->
<template #footer="{ ok }">
  <BButton @click="ok()">Send</BButton>
</template>

<!-- Right: Modal stays open during password confirmation -->
<template #footer>
  <BButton @click="handleSend">Send</BButton>
</template>

<!-- Or use .prevent modifier -->
<BModal @ok.prevent="handleOk">
```
```

### Vue Router 4

```typescript
// router.ts
import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: () => import('./views/Dashboard.vue') },
    { path: '/hosts', name: 'hosts', component: () => import('./views/Hosts.vue') },
    { path: '/nodes', name: 'nodes', component: () => import('./views/Nodes.vue') },
    { path: '/blocks', name: 'blocks', component: () => import('./views/Blocks.vue') },
    { path: '/wallet', name: 'wallet', component: () => import('./views/Wallet.vue') }
  ]
})
```

---

## Code Quality

### Linting

// turbo
```bash
npm run lint        # Check for issues
```

The project uses ESLint with Vue plugin.

---

## Building for Production

// turbo
```bash
npm run build
```

Build output goes to `vue/dist/`.

### Deploy to Backend

// turbo
```bash
./deploy.sh
```

This script:
1. Builds the Vue app
2. Syncs `dist/` to `src/main/resources/static/`

---

## API Integration

### Fetch API Pattern

```javascript
async function getNodes() {
  const response = await fetch('/api/nodes')
  if (!response.ok) throw new Error('Failed to fetch nodes')
  return response.json()
}
```

### WebSocket Connection

```javascript
import SockJS from 'sockjs-client'
import Stomp from 'webstomp-client'

const socket = new SockJS('/ws')
const stompClient = Stomp.over(socket)

stompClient.connect({}, () => {
  stompClient.subscribe('/topic/nodes', (message) => {
    const update = JSON.parse(message.body)
    // Handle node status update
  })
})
```

---

## Common Issues

### CORS Errors

**Problem:** API calls fail with CORS errors  
**Solution:** Run Vue dev server with proxy or use backend port directly

### Hot Reload Not Working

**Problem:** Changes don't reflect automatically  
**Solution:** Restart dev server or check file watcher limits

### Bootstrap-Vue-Next Component Not Rendering

**Problem:** Component shows as plain HTML  
**Solution:** Ensure components are imported from `bootstrap-vue-next`:
```typescript
import { BCard, BTable, BButton } from 'bootstrap-vue-next'
```

---

## Testing

### Running Tests

// turbo
```bash
cd vue
npm run test
```

### Watch Mode

// turbo
```bash
npm run test:watch
```

### Coverage

// turbo
```bash
npm run test:coverage
```

### Test Structure

Tests use Vitest with Vue Test Utils:

```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MyComponent from '@/components/MyComponent.vue'

describe('MyComponent', () => {
  it('renders correctly', () => {
    const wrapper = mount(MyComponent, {
      props: { title: 'Test' }
    })
    expect(wrapper.text()).toContain('Test')
  })
})
```
