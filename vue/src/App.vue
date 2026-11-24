<template>
  <div id="app" class="jm-app-shell">
    <template v-if="connected">
      <Header />
      <main class="jm-main">
        <router-view v-slot="{ Component }">
          <Transition name="jm-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </router-view>
      </main>
      <SpendingPasswordConfirmModal />
    </template>

    <div v-else class="jm-connecting-screen" role="status" aria-live="polite">
      <div class="jm-card jm-connecting-card">
        <div class="jm-orbital-spinner" aria-hidden="true">
          <div class="jm-orbital-ring outer" />
          <div class="jm-orbital-ring inner" />
          <img :src="jormanagerLogo" alt="" class="jm-orbital-logo" width="38" height="38" />
        </div>
        <h1 class="jm-connecting-title">JorManager</h1>
        <p class="jm-connecting-subtitle">Connecting to server telemetry…</p>
        <div class="jm-pill jm-pill-cyan jm-mono">STOMP / WebSocket</div>
      </div>
    </div>

    <!-- Adaptive Dark Glass Toast Stack -->
    <div class="toast-container position-fixed top-0 end-0 p-3 jm-toast-stack">
      <BToast
        v-for="toast in toasts"
        :key="toast.id"
        v-model="toast.visible"
        :is-status="toast.variant !== 'danger'"
        :class="['mb-2', 'jm-toast', `jm-toast-${toast.variant}`]"
        @hidden="removeToast(toast.id)"
      >
        <template #title>
          <span class="jm-toast-title-wrap">
            <font-awesome-icon
              :icon="toastIcon(toast.variant)"
              :class="`jm-toast-icon-${toast.variant}`"
              aria-hidden="true"
            />
            <span>{{ toast.title }}</span>
          </span>
        </template>
        {{ toast.message }}
      </BToast>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { BToast } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import Header from '@/components/Header.vue'
import SpendingPasswordConfirmModal from '@/components/SpendingPasswordConfirmModal.vue'
import jormanagerLogo from '@/assets/jormanager-logo.png'

interface ToastItem {
  id: number
  title: string
  message: string
  variant: 'success' | 'danger' | 'warning' | 'info'
  visible: boolean
}

const store = useJorManagerStore()
const { connected, toastError, toastWarn, toastInfo, toastSuccess } = storeToRefs(store)

const toasts = ref<ToastItem[]>([])

function toastIcon(variant: ToastItem['variant']): [string, string] {
  switch (variant) {
    case 'success':
      return ['fas', 'check-circle']
    case 'danger':
      return ['fas', 'times-circle']
    case 'warning':
      return ['fas', 'exclamation-triangle']
    default:
      return ['fas', 'info-circle']
  }
}

function addToast(data: { title: string; message: string } | null, variant: ToastItem['variant']) {
  if (!data) return
  const id = Date.now() + Math.random()
  const toast: ToastItem = {
    id,
    title: data.title,
    message: data.message,
    variant,
    visible: true
  }
  toasts.value.push(toast)

  // Auto-dismiss for non-errors
  if (variant !== 'danger') {
    setTimeout(() => {
      const t = toasts.value.find(item => item.id === id)
      if (t) t.visible = false
    }, 5000)
  }
}

function removeToast(id: number) {
  const index = toasts.value.findIndex(t => t.id === id)
  if (index !== -1) {
    toasts.value.splice(index, 1)
  }
}

// Watch for toast notifications
watch(toastError, (val) => addToast(val, 'danger'))
watch(toastWarn, (val) => addToast(val, 'warning'))
watch(toastInfo, (val) => addToast(val, 'info'))
watch(toastSuccess, (val) => addToast(val, 'success'))

onMounted(() => {
  store.connectToServer()
})
</script>

<style>
.jm-app-shell {
  min-height: 100vh;
}

.jm-connecting-screen {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 1.5rem;
}

.jm-connecting-card {
  width: 100%;
  max-width: 380px;
  padding: 2.5rem 2rem;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
}

.jm-connecting-title {
  margin: 0.35rem 0 0;
  font-size: 1.35rem;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.jm-connecting-subtitle {
  margin: 0 0 0.25rem;
  font-size: 0.88rem;
  color: var(--jm-text-secondary);
}

.jm-orbital-spinner {
  position: relative;
  width: 72px;
  height: 72px;
  display: grid;
  place-items: center;
}

.jm-orbital-ring {
  position: absolute;
  border-radius: 50%;
  border: 2px solid transparent;
}

.jm-orbital-ring.outer {
  inset: 0;
  border-top-color: var(--jm-cyan);
  border-right-color: rgba(56, 189, 248, 0.3);
  animation: jm-spin 1.35s linear infinite;
}

.jm-orbital-ring.inner {
  inset: 11px;
  border-bottom-color: var(--jm-indigo);
  border-left-color: rgba(129, 140, 248, 0.35);
  animation: jm-spin 1.9s linear infinite reverse;
}

.jm-orbital-logo {
  width: 38px;
  height: 38px;
  object-fit: contain;
  position: relative;
  z-index: 1;
  filter: drop-shadow(0 0 10px rgba(56, 189, 248, 0.45));
}

@keyframes jm-spin {
  to {
    transform: rotate(360deg);
  }
}

/* Dark Glass Toasts */
.jm-toast-stack {
  z-index: 1100;
  margin-block-start: 3.75rem;
}

.jm-toast {
  background: rgba(15, 22, 36, 0.96) !important;
  color: var(--jm-text-primary) !important;
  border: 1px solid var(--jm-border-strong) !important;
  border-radius: var(--jm-radius-md) !important;
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.58) !important;
  backdrop-filter: blur(12px);
  overflow: hidden;
}

.jm-toast .toast-header {
  background: rgba(21, 30, 48, 0.85) !important;
  color: var(--jm-text-primary) !important;
  border-bottom: 1px solid var(--jm-border-subtle) !important;
  font-weight: 600;
}

.jm-toast .toast-body {
  color: #cbd5e1 !important;
  font-size: 0.85rem;
}

.jm-toast-title-wrap {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

.jm-toast-success { border-left: 4px solid var(--jm-emerald) !important; }
.jm-toast-danger { border-left: 4px solid var(--jm-rose) !important; }
.jm-toast-warning { border-left: 4px solid var(--jm-amber) !important; }
.jm-toast-info { border-left: 4px solid var(--jm-cyan) !important; }

.jm-toast-icon-success { color: var(--jm-emerald); }
.jm-toast-icon-danger { color: var(--jm-rose); }
.jm-toast-icon-warning { color: var(--jm-amber); }
.jm-toast-icon-info { color: var(--jm-cyan); }
</style>
