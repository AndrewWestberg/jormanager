<template>
  <div class="governance-registration">
    <h3>Governance Registration</h3>
    <BFormGroup label="Registration Type">
      <BFormSelect v-model="registrationType" :options="['DRep Registration', 'SPO Registration']" />
    </BFormGroup>

    <BFormGroup label="Deposit Source Wallet">
      <BFormSelect v-model="sourceWalletId" :options="walletOptions">
        <template #first>
          <BFormSelectOption :value="null" disabled>-- Select Treasury Wallet --</BFormSelectOption>
        </template>
      </BFormSelect>
    </BFormGroup>
    
    <div v-if="registrationType === 'DRep Registration'" class="mt-3">
      <p>Required Deposit: 500 ADA</p>
    </div>

    <BButton variant="primary" :disabled="!sourceWalletId" @click="submitRegistration">
      Register
    </BButton>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { BFormGroup, BFormSelect, BFormSelectOption, BButton } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'

const store = useJorManagerStore()
const registrationType = ref('DRep Registration')
const sourceWalletId = ref<number | null>(null)

const walletOptions = computed(() => {
  return store.wallets.map(w => ({ value: w.id, text: w.name }))
})

function submitRegistration() {
  // Implementation for certificate generation and submission
}
</script>
