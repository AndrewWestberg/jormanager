<template>
  <div>
    <BModal
      id="modal-spending-password-confirm"
      v-model="isVisible"
      size="sm"
      centered
      :no-close-on-backdrop="true"
      ok-title="Confirm"
      ok-variant="danger"
      @shown="focusPasswordInput"
      @ok="confirmClicked"
      @hidden="onHidden"
    >
      <template #title>
        <span class="d-inline-flex align-items-center gap-2">
          <font-awesome-icon :icon="['fas', 'shield-halved']" class="text-danger" aria-hidden="true" />
          <span>Confirm spending password</span>
        </span>
      </template>

      <p class="small text-secondary mb-3">
        Enter your spending password to authorize this privileged operation.
      </p>

      <div class="input-group">
        <BFormInput
          id="spending-password-input"
          ref="passwordInputRef"
          :type="showPassword ? 'text' : 'password'"
          v-model="spendingPassword"
          placeholder="Spending password"
          autocomplete="off"
          @keydown="handleKeydown"
        />
        <button
          type="button"
          class="btn btn-outline-secondary"
          :aria-label="showPassword ? 'Hide password' : 'Show password'"
          @click="showPassword = !showPassword"
        >
          <font-awesome-icon :icon="['fas', showPassword ? 'eye-slash' : 'eye']" aria-hidden="true" />
        </button>
      </div>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, onMounted, onUnmounted } from 'vue'
import { BModal, BFormInput } from 'bootstrap-vue-next'
import { useEventBus } from '@/composables/useEventBus'

const emitter = useEventBus()

const isVisible = ref(false)
const spendingPassword = ref<string | null>(null)
const showPassword = ref(false)
const pendingAction = ref<string>('')
const pendingData = ref<unknown>(null)
const passwordInputRef = ref<InstanceType<typeof BFormInput> | null>(null)

function focusPasswordInput() {
  void nextTick(() => {
    const el = (passwordInputRef.value as any)?.element || document.getElementById('spending-password-input')
    if (el && typeof el.focus === 'function') {
      el.focus()
    }
  })
}

function show(action: string, data?: unknown) {
  spendingPassword.value = null
  showPassword.value = false
  pendingAction.value = action
  pendingData.value = data
  isVisible.value = true
}

function confirmClicked() {
  const action = pendingAction.value
  const password = spendingPassword.value
  const originalData = pendingData.value
  spendingPassword.value = null
  showPassword.value = false
  pendingData.value = null
  if (password && action) {
    emitter.emit('confirm-spending-password-with-action', { action, password, originalData })
  }
  isVisible.value = false
}

function onHidden() {
  spendingPassword.value = null
  showPassword.value = false
  pendingData.value = null
  emitter.emit('spending-password-modal-hidden', { action: pendingAction.value })
  pendingAction.value = ''
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
  onHidden()
  emitter.off('show-spending-password-modal')
})
</script>
