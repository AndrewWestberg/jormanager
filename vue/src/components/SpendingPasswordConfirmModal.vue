<template>
  <div>
    <BModal
      id="modal-spending-password-confirm"
      v-model="isVisible"
      title="Confirm spending password to continue"
      size="sm"
      :no-close-on-backdrop="true"
      ok-title="Confirm"
      ok-variant="danger"
      @ok="confirmClicked"
      @hidden="onHidden"
    >
      <BFormInput
        id="spending-password-input"
        type="password"
        v-model="spendingPassword"
        @keydown="handleKeydown"
      />
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { BModal, BFormInput } from 'bootstrap-vue-next'
import { useEventBus } from '@/composables/useEventBus'

const emitter = useEventBus()

const isVisible = ref(false)
const spendingPassword = ref<string | null>(null)
const pendingAction = ref<string>('')
const pendingData = ref<unknown>(null)

function show(action: string, data?: unknown) {
  spendingPassword.value = null
  pendingAction.value = action
  pendingData.value = data
  isVisible.value = true
}

function confirmClicked() {
  if (spendingPassword.value && pendingAction.value) {
    emitter.emit('confirm-spending-password-with-action', {
      action: pendingAction.value,
      password: spendingPassword.value,
      originalData: pendingData.value
    })
  }
  spendingPassword.value = null
  isVisible.value = false
}

function onHidden() {
  spendingPassword.value = null
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    confirmClicked()
  }
}

onMounted(() => {
  emitter.on('show-spending-password-modal', (payload: { action: string; data?: unknown }) => {
    show(payload.action, payload.data)
  })
})

onUnmounted(() => {
  emitter.off('show-spending-password-modal')
})
</script>