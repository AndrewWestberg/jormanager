<template>
  <BModal
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="Governance Vote"
    size="lg"
    :no-close-on-backdrop="true"
    @hidden="resetForm"
  >
    <div v-if="reviewError" class="alert alert-danger" role="alert">{{ reviewError }}</div>
    <fieldset :disabled="busy">
      <div class="jm-sub-panel mb-3">
        <BFormGroup label="Fees Account" label-cols-md="4" class="mb-3">
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

        <BFormGroup label="Governance Action ID" label-cols-md="4" class="mb-3">
          <BFormInput
            v-model="govActionId"
            placeholder="gov_action1..."
            class="jm-mono"
            :state="govActionIdState"
          />
          <BFormInvalidFeedback>
            Invalid format. Must be bech32 format starting with "gov_action1"
          </BFormInvalidFeedback>
          <BFormText class="text-secondary">
            Enter the bech32 governance action ID (e.g., gov_action1qqqqqqqqqq...)
          </BFormText>
        </BFormGroup>

        <BFormGroup label="Rationale (optional)" label-for="governance-vote-rationale" label-cols-md="4">
          <BFormTextarea id="governance-vote-rationale" v-model="rationale" rows="3" />
          <BFormText class="text-secondary">
            Published publicly and attached to every pool vote below, even if their choices differ. Publication may remain if the transaction fails.
          </BFormText>
        </BFormGroup>
      </div>

      <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-3">
        <div>
          <h6 class="mb-0">Vote Selection</h6>
          <p class="text-secondary small mb-0">Select a vote for each core node. All nodes must vote.</p>
        </div>
        <div class="d-flex align-items-center gap-2 flex-wrap">
          <span class="jm-pill jm-pill-emerald jm-mono">Yes: {{ voteTally.YES }}</span>
          <span class="jm-pill jm-pill-rose jm-mono">No: {{ voteTally.NO }}</span>
          <span class="jm-pill jm-pill-slate jm-mono">Abstain: {{ voteTally.ABSTAIN }}</span>
        </div>
      </div>

      <div v-if="coreNodes.length > 1" class="d-flex align-items-center gap-2 mb-2 flex-wrap">
        <span class="small text-secondary">Quick set all:</span>
        <BButton variant="outline-success" size="sm" @click="setAllVotes('YES')">All Yes</BButton>
        <BButton variant="outline-danger" size="sm" @click="setAllVotes('NO')">All No</BButton>
        <BButton variant="outline-secondary" size="sm" @click="setAllVotes('ABSTAIN')">All Abstain</BButton>
      </div>

      <div class="jm-table-wrapper" tabindex="0" role="region" aria-label="Pool votes">
        <BTable
          :items="coreNodesWithVotes"
          :fields="nodeFields"
          small
          striped
          hover
          responsive
        >
          <template #cell(name)="{ item }">
            <span class="d-inline-flex align-items-center gap-2">
              <span class="jm-dot" :style="{ backgroundColor: (item as any).color }" />
              <strong class="jm-mono">{{ (item as any).name }}</strong>
            </span>
          </template>
          <template #cell(poolId)="{ item }">
            <span class="jm-mono text-secondary small">{{ truncatePoolId((item as any).poolId) }}</span>
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
      </div>
    </fieldset>

    <template #footer>
      <BButton variant="secondary" @click="handleCancel">Cancel</BButton>
      <BButton
        variant="success"
        :disabled="busy || !walletReady || !isValidGovActionId || feesAccountId === null || !coreNodes.length"
        @click="reviewVote"
      >
        Submit Vote
      </BButton>
    </template>
  </BModal>
</template>

<script setup lang="ts">
import { generateUUID } from '@/utils/filters'
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
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
import { useEventBus } from '@/composables/useEventBus'

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
const rationale = ref('')
const nodeVotes = ref<Record<number, 'YES' | 'NO' | 'ABSTAIN'>>({})
const feesAccountId = ref<number | null>(null)
const reviewError = ref('')
const busy = ref(false)
const walletReady = computed(() => store.connected)
let reviewGeneration = 0
let requestId = ''

function formSignature() {
  return JSON.stringify([govActionId.value, rationale.value, feesAccountId.value, nodeVotes.value])
}
watch(formSignature, () => { requestId = '' }, { flush: 'sync' })

function reviewVote() {
  if (busy.value || !walletReady.value || !isValidGovActionId.value || feesAccountId.value === null ||
      !feeAccountOptions.value.some(option => option.value === feesAccountId.value) ||
      !coreNodes.value.length || coreNodes.value.some(node => !node.id || !nodeVotes.value[node.id])) return
  requestId ||= generateUUID()
  emitter.emit('show-spending-password-modal', { action: 'submit-governance-vote', data: {
    requestId, signature: formSignature(), generation: reviewGeneration,
    request: { govActionId: govActionId.value.trim(),
      votes: coreNodes.value.map(node => ({ nodeId: node.id, vote: nodeVotes.value[node.id!] })),
      feesAccountId: feesAccountId.value, rationale: rationale.value.trim() || null }
  } })
}

async function onPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action !== 'submit-governance-vote' || busy.value) return
  const pending = data.originalData as { requestId: string; signature: string; generation: number; request: unknown }
  if (!props.modelValue || !walletReady.value || pending.generation !== reviewGeneration ||
      pending.requestId !== requestId || pending.signature !== formSignature() ||
      !feeAccountOptions.value.some(option => option.value === feesAccountId.value) ||
      !coreNodes.value.length ||
      JSON.stringify(coreNodes.value.map(node => ({ nodeId: node.id, vote: nodeVotes.value[node.id!] }))) !==
        JSON.stringify((pending.request as { votes: unknown[] }).votes)) return
  busy.value = true
  reviewError.value = ''
  try {
    const result = await store.submitGovernance(pending.requestId, pending.request, data.password)
    if (result.state !== 'submitted' && result.state !== 'confirmed') {
      throw new Error(`Transaction ${result.txId}: ${result.state}`)
    }
    if (pending.generation === reviewGeneration) {
      store.toastInfo = { title: 'Vote submitted', message: result.txId }
      emit('update:modelValue', false)
    }
  } catch (e) {
    if (pending.generation === reviewGeneration) reviewError.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (pending.generation === reviewGeneration) busy.value = false
  }
}

const voteOptions = [
  { value: 'YES', text: 'Yes' },
  { value: 'NO', text: 'No' },
  { value: 'ABSTAIN', text: 'Abstain' }
]

const nodeFields = [
  { key: 'name', label: 'Node', sortable: true },
  { key: 'poolId', label: 'Pool ID' },
  { key: 'vote', label: 'Vote', thClass: 'text-center', tdClass: 'text-center' }
]

const coreNodes = computed(() => {
  return filter(nodes.value, (node) => (node as any).type !== 'relay' && !(node as any).isDeleted)
})

const coreNodesWithVotes = computed(() => {
  return coreNodes.value.map((node) => ({
    id: node.id,
    name: node.name,
    color: node.color,
    poolId: (node as any).poolId,
    vote: nodeVotes.value[node.id!] || 'ABSTAIN'
  }))
})

const voteTally = computed(() => {
  const counts = { YES: 0, NO: 0, ABSTAIN: 0 }
  for (const node of coreNodes.value) {
    const choice = nodeVotes.value[node.id!] || 'ABSTAIN'
    counts[choice] += 1
  }
  return counts
})

function setAllVotes(choice: 'YES' | 'NO' | 'ABSTAIN') {
  for (const node of coreNodes.value) {
    if (node.id != null) nodeVotes.value[node.id] = choice
  }
}

const feeAccountOptions = computed(() => store.walletItems
  .filter(item => item.type !== 'address' && item.hasPaymentKeys)
  .map(item => {
    const value = String(item.paymentAddrLovelace)
    const amount = typeof item.paymentAddrLovelace === 'string' && /^\d+$/.test(value)
      ? BigInt(value) : null
    const balance = amount === null ? 'Unavailable' : `₳${amount / 1000000n}.${(amount % 1000000n).toString().padStart(6, '0')}`
    return { value: item.id, text: `${item.name} - ${balance}` }
  }).sort((a, b) => a.text.localeCompare(b.text)))

const isValidGovActionId = computed(() => {
  const id = govActionId.value.trim()
  if (!id) return false
  return /^gov_action1[a-z0-9]{50,}$/i.test(id)
})

const govActionIdState = computed(() => {
  if (!govActionId.value) return null
  return isValidGovActionId.value
})

function truncatePoolId(poolId: string): string {
  if (!poolId || poolId.length < 20) return poolId || '-'
  return `${poolId.substring(0, 10)}...${poolId.substring(poolId.length - 6)}`
}

function initializeDefaultVotes() {
  coreNodes.value.forEach((node) => {
    if (nodeVotes.value[node.id!] === undefined) {
      nodeVotes.value[node.id!] = 'ABSTAIN'
    }
  })
}

function resetForm() {
  reviewGeneration++
  busy.value = false
  requestId = ''
  reviewError.value = ''
  govActionId.value = ''
  rationale.value = ''
  nodeVotes.value = {}
  feesAccountId.value = null
  initializeDefaultVotes()
}

function handleCancel() {
  emit('update:modelValue', false)
}

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
  emitter.on('confirm-spending-password-with-action', onPasswordConfirmed)
})
onUnmounted(() => emitter.off('confirm-spending-password-with-action', onPasswordConfirmed))
</script>
