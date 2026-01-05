<template>
  <div id="add_wallet">
    <h2>Add Wallet Entry</h2>
    
    <!-- Custom Step Indicator -->
    <div class="wizard-steps mb-4">
      <div class="step" :class="{ active: currentStep === 0, completed: currentStep > 0 }">
        <span class="step-number">1</span>
        <span class="step-label">Address/Key Info</span>
      </div>
    </div>
    
    <!-- Step 1: Address/Key Info -->
    <div v-if="currentStep === 0" class="wizard-content">
      <h4>Address/Key Info</h4>
      
      <BFormGroup label="Name" label-for="name-input" label-cols-md="2">
        <BFormInput
          id="name-input"
          v-model="formWallet.name"
          :state="nameState"
          aria-describedby="name-input-live-feedback"
          placeholder="e.g. tickr_owner, funds, etc..."
          trim
        />
        <BFormInvalidFeedback id="name-input-live-feedback">
          Enter at least 3 letters with no spaces
        </BFormInvalidFeedback>
      </BFormGroup>
      
      <BFormGroup label="Entry Type" label-for="type-radio" label-cols-md="2">
        <BFormRadioGroup id="type-radio" v-model="formWallet.type" :state="typeState">
          <BFormRadio value="address">Simple Address</BFormRadio>
          <BFormRadio value="payment">Payment</BFormRadio>
          <BFormRadio value="stake">Stake</BFormRadio>
          <BFormRadio value="pledge">Pledge-Only</BFormRadio>
        </BFormRadioGroup>
      </BFormGroup>
      
      <BFormGroup
        label="Address"
        label-for="address-input"
        label-cols-md="2"
        v-if="formWallet.type === 'address' || formWallet.type === 'pledge'"
      >
        <BFormInput
          id="address-input"
          v-model="formWallet.paymentAddr"
          :state="paymentAddrState"
          aria-describedby="address-input-live-feedback"
          placeholder="e.g. addr1v805z8cn8z...xrrqj4t30l"
          trim
        />
        <BFormInvalidFeedback id="address-input-live-feedback">
          Wallet address that can only receive payments or monitor funds it holds
        </BFormInvalidFeedback>
      </BFormGroup>
      
      <BFormGroup label="Keys" v-if="formWallet.type != null && formWallet.type !== 'address'">
        <BFormCheckbox
          id="keys-generate-checkbox"
          v-model="formWallet.generateKeys"
          v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
        >
          Generate Keys
        </BFormCheckbox>
        
        <BFormGroup
          label="payment skey"
          label-for="payment-skey-file"
          label-cols-md="2"
          label-align="right"
          v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
        >
          <input
            id="payment-skey-file"
            type="file"
            class="form-control"
            :disabled="formWallet.generateKeys"
            @change="handleFileUpload($event, 'paymentSKey')"
          />
        </BFormGroup>
        
        <BFormGroup
          label="payment vkey"
          label-for="payment-vkey-file"
          label-cols-md="2"
          label-align="right"
          v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
        >
          <input
            id="payment-vkey-file"
            type="file"
            class="form-control"
            :disabled="formWallet.generateKeys"
            @change="handleFileUpload($event, 'paymentVKey')"
          />
        </BFormGroup>
        
        <BFormGroup
          label="staking skey"
          label-for="staking-skey-file"
          label-cols-md="2"
          label-align="right"
          v-if="formWallet.type === 'stake' || formWallet.type === 'pledge'"
        >
          <input
            id="staking-skey-file"
            type="file"
            class="form-control"
            :disabled="formWallet.generateKeys"
            @change="handleFileUpload($event, 'stakingSKey')"
          />
        </BFormGroup>
        
        <BFormGroup
          label="staking vkey"
          label-for="staking-vkey-file"
          label-cols-md="2"
          label-align="right"
          v-if="formWallet.type === 'stake' || formWallet.type === 'pledge'"
        >
          <input
            id="staking-vkey-file"
            type="file"
            class="form-control"
            :disabled="formWallet.generateKeys"
            @change="handleFileUpload($event, 'stakingVKey')"
          />
        </BFormGroup>
      </BFormGroup>
      
      <!-- Wizard Actions -->
      <div class="wizard-actions mt-4">
        <BButton variant="secondary" @click="handleBack">Back</BButton>
        <BButton variant="primary" @click="handleNext">Create Wallet Entry</BButton>
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
  BFormRadioGroup,
  BFormRadio,
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

// Computed validation states
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

// Methods
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
    
    // Clear keys if generating
    if (formWallet.value.generateKeys) {
      formWallet.value.paymentSKey = null
      formWallet.value.paymentVKey = null
      formWallet.value.stakingSKey = null
      formWallet.value.stakingVKey = null
    } else {
      // Read file contents
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
    
    // Clear staking keys for payment type
    if (formWallet.value.type === 'payment') {
      formWallet.value.stakingSKey = null
      formWallet.value.stakingVKey = null
    }
    
    // Request spending password
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

// Watch for type changes to disable generate keys for address/pledge
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
#add_wallet {
  padding: 1rem;
}

.wizard-steps {
  display: flex;
  justify-content: center;
  gap: 2rem;
}

.step {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  background: #444;
}

.step.active {
  background: #007bff;
}

.step.completed {
  background: #28a745;
}

.step-number {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(255,255,255,0.2);
  font-weight: bold;
}

.step-label {
  font-weight: 500;
}

.wizard-content {
  background: #333;
  padding: 1.5rem;
  border-radius: 0.5rem;
}

.wizard-actions {
  display: flex;
  justify-content: space-between;
}
</style>