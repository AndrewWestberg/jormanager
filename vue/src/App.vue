<template>
  <div id="app">
    <template v-if="connected">
      <Header />
      <BContainer>
        <router-view />
      </BContainer>
      <SpendingPasswordConfirmModal />
    </template>
    <div v-else class="text-center mt-5">
      <div class="spinner-border text-primary" role="status">
        <span class="visually-hidden">Loading...</span>
      </div>
      <p class="mt-2">Connecting to server...</p>
    </div>

    <!-- Toast Container -->
    <div class="toast-container position-fixed top-0 end-0 p-3" style="z-index: 1100">
      <BToast
        v-for="toast in toasts"
        :key="toast.id"
        v-model="toast.visible"
        :title="toast.title"
        :class="['mb-2', `custom-toast-${toast.variant}`]"
        @hidden="removeToast(toast.id)"
      >
        {{ toast.message }}
      </BToast>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { BContainer, BToast } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import Header from '@/components/Header.vue'
import SpendingPasswordConfirmModal from '@/components/SpendingPasswordConfirmModal.vue'

interface ToastItem {
  id: number
  title: string
  message: string
  variant: string
  visible: boolean
}

const store = useJorManagerStore()
const { connected, toastError, toastWarn, toastInfo, toastSuccess } = storeToRefs(store)

const toasts = ref<ToastItem[]>([])

function addToast(data: { title: string; message: string } | null, variant: string) {
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
/* Custom Toast Styles to match "Previous" look */
.custom-toast-success .toast-header {
  background-color: #d4edda;
  color: #155724;
  border-bottom: 1px solid #c3e6cb;
}
.custom-toast-success .toast-body,
.custom-toast-success {
  background-color: #d4edda;
  color: #155724;
}

.custom-toast-danger .toast-header {
  background-color: #f8d7da;
  color: #721c24;
  border-bottom: 1px solid #f5c6cb;
}
.custom-toast-danger .toast-body,
.custom-toast-danger {
  background-color: #f8d7da;
  color: #721c24;
}

.custom-toast-warning .toast-header {
  background-color: #fff3cd;
  color: #856404;
  border-bottom: 1px solid #ffeeba;
}
.custom-toast-warning .toast-body,
.custom-toast-warning {
  background-color: #fff3cd;
  color: #856404;
}

.custom-toast-info .toast-header {
  background-color: #d1ecf1;
  color: #0c5460;
  border-bottom: 1px solid #bee5eb;
}
.custom-toast-info .toast-body,
.custom-toast-info {
  background-color: #d1ecf1;
  color: #0c5460;
}
</style>
