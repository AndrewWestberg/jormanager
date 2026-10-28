<template>
  <BModal
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="Governance Vote"
    size="lg"
    :no-close-on-backdrop="true"
    @hidden="resetForm"
  >
    <div class="alert alert-warning" role="alert">
      Voting is unavailable until target-network era and capability evidence and a durable,
      reviewed transaction preview are available. A rationale can be published publicly even
      if a submission fails.
    </div>
    <BFormGroup label="Fees Account" label-cols-md="4">
      <BFormSelect
        v-model="feesAccountId"
        :options="feeAccountOptions"
        :state="feesAccountId !== null"
      >
        <template #first>
          <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
        </template>
      </BFormSelect>
    </BFormGroup>

    <BFormGroup label="Governance Action ID" label-cols-md="4">
      <BFormInput
        v-model="govActionId"
        placeholder="gov_action1..."
        :state="govActionIdState"
      />
      <BFormInvalidFeedback>
        Invalid format. Must be bech32 format starting with "gov_action1"
      </BFormInvalidFeedback>
      <BFormText class="text-muted">
        Enter the bech32 governance action ID (e.g., gov_action1qqqqqqqqqq...)
      </BFormText>
    </BFormGroup>

    <BFormGroup label="Rationale (optional)" label-for="governance-vote-rationale" label-cols-md="4">
      <BFormTextarea id="governance-vote-rationale" v-model="rationale" />
      <BFormText class="text-muted">
        Published publicly and attached to every pool vote below, even if their choices differ.
        Publication may remain if the transaction fails.
      </BFormText>
    </BFormGroup>

    <hr />

    <h6>Vote Selection</h6>
    <p class="text-muted small">Select a vote for each core node. All nodes must vote.</p>

    <BTable
      :items="coreNodesWithVotes"
      :fields="nodeFields"
      small
      striped
      hover
      dark
      responsive
    >
      <template #cell(name)="{ item }">
        <font-awesome-icon
          :style="{ color: (item as any).color }"
          :icon="['fas', 'circle']"
          class="me-2"
        />
        {{ (item as any).name }}
      </template>
      <template #cell(poolId)="{ item }">
        <span class="text-muted small">{{ truncatePoolId((item as any).poolId) }}</span>
      </template>
      <template #cell(vote)="{ item }">
        <BFormSelect
          v-model="nodeVotes[(item as any).id]"
          :options="voteOptions"
          size="sm"
          :state="nodeVotes[(item as any).id] !== undefined"
        />
      </template>
    </BTable>

    <template #footer>
      <BButton variant="secondary" @click="handleCancel">Cancel</BButton>
      <BButton
        variant="success"
        disabled
      >
        Review and submit unavailable
      </BButton>
    </template>
  </BModal>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { filter } from 'lodash-es'
import {
  BModal,
  BFormGroup,
  BFormInput,
  BFormTextarea,
  BFormSelect,
  BFormSelectOption,
  BFormInvalidFeedback,
  BFormText,
  BTable,
  BButton
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const store = useJorManagerStore()
const { nodes } = storeToRefs(store)

// Form state
const govActionId = ref('')
const rationale = ref('')
const nodeVotes = ref<Record<number, 'YES' | 'NO' | 'ABSTAIN'>>({})
const feesAccountId = ref<number | null>(null)

// Vote options
const voteOptions = [
  { value: 'YES', text: '✅ Yes' },
  { value: 'NO', text: '❌ No' },
  { value: 'ABSTAIN', text: '🤷 Abstain' }
]

// Table fields
const nodeFields = [
  { key: 'name', label: 'Node', sortable: true },
  { key: 'poolId', label: 'Pool ID' },
  { key: 'vote', label: 'Vote', thClass: 'text-center', tdClass: 'text-center' }
]

// Core nodes (no relays)
const coreNodes = computed(() => {
  return filter(nodes.value, (node) => (node as any).type !== 'relay')
})

// Core nodes with vote state for table display
const coreNodesWithVotes = computed(() => {
  return coreNodes.value.map((node) => ({
    id: node.id,
    name: node.name,
    color: node.color,
    poolId: (node as any).poolId,
    vote: nodeVotes.value[node.id!] || 'ABSTAIN'
  }))
})

// Fee account options
const feeAccountOptions = computed(() => store.walletItems
  .filter(item => item.type !== 'address' && item.hasPaymentKeys)
  .map(item => {
    const value = String(item.paymentAddrLovelace)
    const amount = typeof item.paymentAddrLovelace === 'string' && /^\d+$/.test(value)
      ? BigInt(value) : null
    const balance = amount === null ? 'Unavailable' : `₳${amount / 1000000n}.${(amount % 1000000n).toString().padStart(6, '0')}`
    return { value: item.id, text: `${item.name} - ${balance}` }
  }).sort((a, b) => a.text.localeCompare(b.text)))

// Validate bech32 governance action ID format
const isValidGovActionId = computed(() => {
  const id = govActionId.value.trim()
  if (!id) return false
  // gov_action1 prefix + alphanumeric characters
  return /^gov_action1[a-z0-9]{50,}$/i.test(id)
})

const govActionIdState = computed(() => {
  if (!govActionId.value) return null
  return isValidGovActionId.value
})


// Truncate pool ID for display
function truncatePoolId(poolId: string): string {
  if (!poolId || poolId.length < 20) return poolId || '-'
  return `${poolId.substring(0, 10)}...${poolId.substring(poolId.length - 6)}`
}

// Initialize default votes when nodes change
function initializeDefaultVotes() {
  coreNodes.value.forEach((node) => {
    if (nodeVotes.value[node.id!] === undefined) {
      nodeVotes.value[node.id!] = 'ABSTAIN'
    }
  })
}

// Reset form
function resetForm() {
  govActionId.value = ''
  rationale.value = ''
  nodeVotes.value = {}
  feesAccountId.value = null
  initializeDefaultVotes()
}

// Handle cancel
function handleCancel() {
  emit('update:modelValue', false)
}


// Watch for modal open to initialize
watch(
  () => props.modelValue,
  (newValue) => {
    if (newValue) {
      initializeDefaultVotes()
    }
  }
)

onMounted(() => {
  initializeDefaultVotes()
})

</script>

<style scoped>
.small {
  font-size: 0.85em;
}
</style>
