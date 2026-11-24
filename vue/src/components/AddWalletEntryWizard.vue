<template>
  <div id="add_wallet">
    <div class="jm-page-header">
      <div>
        <h1 class="jm-page-title">Create Wallet Entry</h1>
        <p class="jm-page-subtitle">Add a watch-only address, payment wallet, stake account, or pledge-only entry</p>
      </div>
      <BButton variant="outline-secondary" @click="handleBack">
        <font-awesome-icon :icon="['fas', 'arrow-left']" class="me-1" aria-hidden="true" />
        Back to Wallet
      </BButton>
    </div>

    <div class="jm-card">
      <div class="p-4">
        <div class="jm-sub-panel mb-3">
          <h2 class="jm-sub-panel-title">1. Entry Identity &amp; Type</h2>
          <BFormGroup label="Name" label-for="name-input" class="mb-3">
            <BFormInput
              id="name-input"
              v-model="formWallet.name"
              :state="nameState"
              aria-describedby="name-input-live-feedback"
              placeholder="e.g. tickr_owner, funds, etc..."
              class="jm-mono"
              trim
            />
            <BFormInvalidFeedback id="name-input-live-feedback">
              Enter at least 3 letters with no spaces
            </BFormInvalidFeedback>
          </BFormGroup>

          <BFormGroup label="Entry Type" label-for="type-radio">
            <div class="jm-type-card-grid">
              <label
                v-for="option in entryTypeOptions"
                :key="option.value"
                class="jm-type-card"
                :class="{ selected: formWallet.type === option.value }"
              >
                <input
                  type="radio"
                  name="wallet-entry-type"
                  :value="option.value"
                  v-model="formWallet.type"
                  class="form-check-input mt-1"
                />
                <div>
                  <div class="fw-semibold">{{ option.label }}</div>
                  <div class="small text-secondary">{{ option.description }}</div>
                </div>
              </label>
            </div>
          </BFormGroup>
        </div>

        <div v-if="formWallet.type === 'address' || formWallet.type === 'pledge'" class="jm-sub-panel mb-3">
          <h2 class="jm-sub-panel-title">2. Cardano Address</h2>
          <BFormGroup label="Address" label-for="address-input">
            <BFormInput
              id="address-input"
              v-model="formWallet.paymentAddr"
              :state="paymentAddrState"
              aria-describedby="address-input-live-feedback"
              placeholder="e.g. addr1v805z8cn8z...xrrqj4t30l"
              class="jm-mono"
              trim
            />
            <BFormInvalidFeedback id="address-input-live-feedback">
              Wallet address that can only receive payments or monitor funds it holds
            </BFormInvalidFeedback>
          </BFormGroup>
        </div>

        <div v-if="formWallet.type != null && formWallet.type !== 'address'" class="jm-sub-panel mb-3">
          <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-3">
            <h2 class="jm-sub-panel-title mb-0">Key Material</h2>
            <BFormCheckbox
              id="keys-generate-checkbox"
              v-model="formWallet.generateKeys"
              v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
              switch
            >
              Generate Keys Automatically
            </BFormCheckbox>
          </div>

          <div class="row g-3">
            <div v-if="formWallet.type === 'payment' || formWallet.type === 'stake'" class="col-md-6">
              <BFormGroup label="payment skey" label-for="payment-skey-file">
                <input
                  id="payment-skey-file"
                  type="file"
                  class="form-control"
                  :disabled="formWallet.generateKeys"
                  @change="handleFileUpload($event, 'paymentSKey')"
                />
              </BFormGroup>
            </div>
            <div v-if="formWallet.type === 'payment' || formWallet.type === 'stake'" class="col-md-6">
              <BFormGroup label="payment vkey" label-for="payment-vkey-file">
                <input
                  id="payment-vkey-file"
                  type="file"
                  class="form-control"
                  :disabled="formWallet.generateKeys"
                  @change="handleFileUpload($event, 'paymentVKey')"
                />
              </BFormGroup>
            </div>
            <div v-if="formWallet.type === 'stake' || formWallet.type === 'pledge'" class="col-md-6">
              <BFormGroup label="staking skey" label-for="staking-skey-file">
                <input
                  id="staking-skey-file"
                  type="file"
                  class="form-control"
                  :disabled="formWallet.generateKeys"
                  @change="handleFileUpload($event, 'stakingSKey')"
                />
              </BFormGroup>
            </div>
            <div v-if="formWallet.type === 'stake' || formWallet.type === 'pledge'" class="col-md-6">
              <BFormGroup label="staking vkey" label-for="staking-vkey-file">
                <input
                  id="staking-vkey-file"
                  type="file"
                  class="form-control"
                  :disabled="formWallet.generateKeys"
                  @change="handleFileUpload($event, 'stakingVKey')"
                />
              </BFormGroup>
            </div>
          </div>
        </div>

        <div class="d-flex justify-content-between align-items-center pt-3 border-top">
          <BButton variant="secondary" @click="handleBack">Back</BButton>
          <BButton variant="primary" @click="handleNext">Create Wallet Entry</BButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import bs58 from 'bs58check'
import {
  BButton,
  BFormGroup,
  BFormInput,
  BFormCheckbox,
  BFormInvalidFeedback
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'

const emit = defineEmits<{
  (e: 'hide-wallet-entry-wizard'): void
}>()

const store = useJorManagerStore()
const emitter = useEventBus()

const currentStep = ref(0)
const pendingAction = ref<string | null>(null)

const entryTypeOptions = [
  { value: 'address' as const, label: 'Simple Address', description: 'Watch-only destination address for monitoring or receiving funds.' },
  { value: 'payment' as const, label: 'Payment', description: 'Spendable payment keys for fees, transfers, and UTxO defragmentation.' },
  { value: 'stake' as const, label: 'Stake', description: 'Full payment + staking keys for reward claims and registration.' },
  { value: 'pledge' as const, label: 'Pledge-Only', description: 'External payment address paired with pool owner staking keys.' }
]

const formWallet = ref({
  spendingPassword: null as string | null,
  name: '',
  type: null as 'address' | 'payment' | 'stake' | 'pledge' | null,
  paymentAddr: '',
  generateKeys: false,
  paymentSKey: null as string | null,
  paymentVKey: null as string | null,
  stakingSKey: null as string | null,
  stakingVKey: null as string | null
})

const fileContents = ref<Record<string, File | null>>({
  paymentSKey: null,
  paymentVKey: null,
  stakingSKey: null,
  stakingVKey: null
})

const nameState = computed(() => formWallet.value.name.length > 2)
const typeState = computed(() => formWallet.value.type != null)

const paymentAddrState = computed(() => {
  if (formWallet.value.type === 'payment' || formWallet.value.type === 'stake') {
    return true
  }
  const addr = formWallet.value.paymentAddr
  if (!addr) return null

  const bech32Regex = /^.*1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{53}|.{98})$/
  if (bech32Regex.test(addr)) return true

  try {
    bs58.decode(addr)
    return true
  } catch {
    return false
  }
})

const paymentSKeyState = computed(() => {
  if (formWallet.value.type === 'address' || formWallet.value.type === 'pledge') return true
  if (formWallet.value.generateKeys) return true
  return fileContents.value.paymentSKey != null
})

const paymentVKeyState = computed(() => {
  if (formWallet.value.type === 'address' || formWallet.value.type === 'pledge') return true
  if (formWallet.value.generateKeys) return true
  return fileContents.value.paymentVKey != null
})

const stakingSKeyState = computed(() => {
  if (formWallet.value.type === 'address' || formWallet.value.type === 'payment') return true
  if (formWallet.value.generateKeys) return true
  return fileContents.value.stakingSKey != null
})

const stakingVKeyState = computed(() => {
  if (formWallet.value.type === 'address' || formWallet.value.type === 'payment') return true
  if (formWallet.value.generateKeys) return true
  return fileContents.value.stakingVKey != null
})

function handleFileUpload(event: Event, field: string) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0] || null
  fileContents.value[field] = file
}

async function handleNext() {
  if (currentStep.value === 0) {
    if (!nameState.value || !typeState.value || !paymentAddrState.value ||
        !paymentSKeyState.value || !paymentVKeyState.value ||
        !stakingSKeyState.value || !stakingVKeyState.value) {
      store.toastError = { title: 'Error', message: 'You must fill out all fields.' }
      return
    }

    if (formWallet.value.generateKeys) {
      formWallet.value.paymentSKey = null
      formWallet.value.paymentVKey = null
      formWallet.value.stakingSKey = null
      formWallet.value.stakingVKey = null
    } else {
      if (formWallet.value.type === 'payment' || formWallet.value.type === 'stake') {
        if (fileContents.value.paymentSKey) {
          formWallet.value.paymentSKey = await fileContents.value.paymentSKey.text()
        }
        if (fileContents.value.paymentVKey) {
          formWallet.value.paymentVKey = await fileContents.value.paymentVKey.text()
        }
      }
      if (formWallet.value.type === 'stake' || formWallet.value.type === 'pledge') {
        if (fileContents.value.stakingSKey) {
          formWallet.value.stakingSKey = await fileContents.value.stakingSKey.text()
        }
        if (fileContents.value.stakingVKey) {
          formWallet.value.stakingVKey = await fileContents.value.stakingVKey.text()
        }
      }
    }

    if (formWallet.value.type === 'payment') {
      formWallet.value.stakingSKey = null
      formWallet.value.stakingVKey = null
    }

    pendingAction.value = 'create-wallet'
    emitter.emit('show-spending-password-modal', { action: 'create-wallet' })
  }
}

function handleBack() {
  emit('hide-wallet-entry-wizard')
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action !== 'create-wallet') return
  formWallet.value.spendingPassword = data.password
  store.createWalletEntry(formWallet.value)
  formWallet.value.spendingPassword = null
  emit('hide-wallet-entry-wizard')
}

watch(() => formWallet.value.type, (type) => {
  if (type === 'address' || type === 'pledge') {
    formWallet.value.generateKeys = false
  }
})

onMounted(() => {
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.jm-type-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}

.jm-type-card {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.9rem 1rem;
  border-radius: var(--jm-radius-md);
  border: 1px solid var(--jm-border-strong);
  background: var(--jm-bg-surface);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.jm-type-card:hover {
  border-color: rgba(56, 189, 248, 0.5);
}

.jm-type-card.selected {
  border-color: var(--jm-cyan);
  background: rgba(56, 189, 248, 0.08);
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.35);
}

@media (max-width: 720px) {
  .jm-type-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
