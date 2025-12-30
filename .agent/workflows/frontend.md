---
description: Frontend Vue.js development workflow
---

# Frontend Development Workflow

This workflow guides development on the Vue.js frontend (`vue/`).

## Overview

The frontend is built with:
- **Vue.js 2** — Progressive JavaScript framework
- **Bootstrap-Vue** — Bootstrap components for Vue
- **Vuex** — State management
- **Vue Router** — Client-side routing
- **Font Awesome** — Icons

---

## Setup

### Prerequisites

- Node.js 16.17.0 (use nvm: `nvm use v16.17.0`)
- npm

### Installation

// turbo
```bash
cd vue
npm install
```

---

## Development Server

// turbo
```bash
cd vue
npm run serve
```

Opens at [http://localhost:8080](http://localhost:8080)

Uses Vue CLI service with hot reload.

---

## Project Structure

```
vue/src/
├── App.vue                 # Root component
├── main.js                 # Application entry point
├── router.js               # Vue Router configuration
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
│   └── ...
├── store/                  # Vuex state modules
└── assets/                 # Static assets
```

---

## Component Guidelines

### Single File Components

```vue
<template>
  <div class="node-card">
    <h3>{{ node.name }}</h3>
    <p>Status: {{ node.status }}</p>
  </div>
</template>

<script>
export default {
  name: 'NodeCard',
  props: {
    node: {
      type: Object,
      required: true
    }
  }
}
</script>

<style scoped>
.node-card {
  padding: 1rem;
  border: 1px solid #ccc;
}
</style>
```

### Bootstrap-Vue Usage

```vue
<template>
  <b-card title="Node Status">
    <b-table :items="nodes" :fields="fields">
      <template #cell(actions)="data">
        <b-button size="sm" variant="primary" @click="restart(data.item)">
          Restart
        </b-button>
      </template>
    </b-table>
  </b-card>
</template>
```

### Vuex State Management

```javascript
// store/modules/nodes.js
export default {
  namespaced: true,
  state: {
    nodes: [],
    loading: false
  },
  mutations: {
    SET_NODES(state, nodes) {
      state.nodes = nodes
    },
    SET_LOADING(state, loading) {
      state.loading = loading
    }
  },
  actions: {
    async fetchNodes({ commit }) {
      commit('SET_LOADING', true)
      try {
        const response = await fetch('/api/nodes')
        const nodes = await response.json()
        commit('SET_NODES', nodes)
      } finally {
        commit('SET_LOADING', false)
      }
    }
  }
}
```

### Vue Router

```javascript
// router.js
export default new Router({
  mode: 'history',
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

### Bootstrap-Vue Component Not Rendering

**Problem:** Component shows as plain HTML  
**Solution:** Ensure component is imported in `main.js`:
```javascript
import { BCard, BTable, BButton } from 'bootstrap-vue'
Vue.component('b-card', BCard)
```
