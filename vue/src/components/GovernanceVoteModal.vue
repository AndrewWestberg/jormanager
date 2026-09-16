<template>
  <BModal
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="Governance Vote"
    size="lg"
    :no-close-on-backdrop="true"
    @hidden="resetForm"
  >
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
      <template #head(selected)>
        <BFormCheckbox v-model="selectAllNodes" />
      </template>
      <template #cell(selected)="{ item }">
        <BFormCheckbox v-model="selectedNodes[(item as any).id]" />
      </template>
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
        :disabled="!isFormValid"
        @click="handleSubmitVote"
      >
        🗳️ Submit Vote
      </BButton>
    </template>
  </BModal>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { filter } from 'lodash-es'
import {
  BModal,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption,
  BFormInvalidFeedback,
  BFormText,
  BFormCheckbox,
  BTable,
  BButton
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { lovelaceToAda } from '@/utils/filters'

interface NodeVote {
  nodeId: number
  vote: 'YES' | 'NO' | 'ABSTAIN'
}

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const store = useJorManagerStore()
const { nodes } = storeToRefs(store)
const emitter = useEventBus()

// Form state
const govActionId = ref('')
const nodeVotes = ref<Record<number, 'YES' | 'NO' | 'ABSTAIN'>>({})
const selectedNodes = ref<Record<number, boolean>>({})
const feesAccountId = ref<number | null>(null)

// Vote options
const voteOptions = [
  { value: 'YES', text: '✅ Yes' },
  { value: 'NO', text: '❌ No' },
  { value: 'ABSTAIN', text: '🤷 Abstain' }
]

// Table fields
const nodeFields = [
  { key: 'selected', label: '', thClass: 'text-center', tdClass: 'text-center' },
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
const feeAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.registrationFeesSelectOptions(formatter)
})

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

// Select all logic
const selectAllNodes = computed({
  get: () => coreNodes.value.length > 0 && coreNodes.value.every((node) => selectedNodes.value[node.id!]),
  set: (value) => {
    coreNodes.value.forEach((node) => {
      selectedNodes.value[node.id!] = value
    })
  }
})

// Check if all selected nodes have votes
const allNodesHaveVotes = computed(() => {
  const selected = coreNodes.value.filter((node) => selectedNodes.value[node.id!])
  if (selected.length === 0) return false
  return selected.every((node) => nodeVotes.value[node.id!] !== undefined)
})

// Form validation
const isFormValid = computed(() => {
  return isValidGovActionId.value && allNodesHaveVotes.value && feesAccountId.value !== null
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
    if (selectedNodes.value[node.id!] === undefined) {
      selectedNodes.value[node.id!] = false
    }
  })
}

// Reset form
function resetForm() {
  govActionId.value = ''
  nodeVotes.value = {}
  selectedNodes.value = {}
  feesAccountId.value = null
  initializeDefaultVotes()
}

// Handle cancel
function handleCancel() {
  emit('update:modelValue', false)
}

// Handle submit - triggers spending password modal
function handleSubmitVote() {
  if (!isFormValid.value) return

  const votes: NodeVote[] = Object.entries(nodeVotes.value)
    .filter(([nodeId, _]) => selectedNodes.value[parseInt(nodeId, 10)])
    .map(([nodeId, vote]) => ({
      nodeId: parseInt(nodeId, 10),
      vote
    }))

  emitter.emit('show-spending-password-modal', {
    action: 'governance-vote',
    data: {
      govActionId: govActionId.value.trim(),
      votes,
      feesAccountId: feesAccountId.value
    }
  })
}

// Handle spending password confirmation
function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: any }) {
  if (data.action === 'governance-vote') {
    store.submitGovernanceVote({
      ...data.originalData,
      spendingPassword: data.password
    })
    emit('update:modelValue', false)
  }
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
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.small {
  font-size: 0.85em;
}
</style>
