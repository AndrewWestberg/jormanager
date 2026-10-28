<template>
  <BModal
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="Governance Vote"
    size="lg"
    :no-close-on-backdrop="true"
    @hidden="resetForm"
  >
    <div class="alert alert-info" role="alert">
      Publishing a rationale is a separate authorized action. It is public and may be permanent even if the vote is never submitted.
    </div>
    <div v-if="reviewError" class="alert alert-danger" role="alert">{{ reviewError }}</div>
    <WalletReviewPanel v-if="review" :review="review" governance @close="review = null" @submitted="review = $event" />
    <fieldset v-if="!review" :disabled="busy">
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
        This rationale will be attached to every pool vote below, even if their choices differ.
      </BFormText>
    </BFormGroup>
    <div v-if="rationale.trim()" class="border rounded p-3 mb-3">
      <p v-if="anchor">Published: {{ anchor.anchorUrl }}<br>Hash: {{ anchor.anchorHash }}</p>
      <template v-else>
        <BFormGroup label="Spending password to authorize publication" label-for="rationale-password">
          <BFormInput id="rationale-password" v-model="publicationPassword" type="password" autocomplete="current-password" />
        </BFormGroup>
        <BButton variant="warning" :disabled="!publicationPassword || busy" @click="publishRationale">Authorize public publication</BButton>
      </template>
    </div>

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
    </fieldset>

    <template #footer>
      <BButton variant="secondary" @click="handleCancel">Cancel</BButton>
      <BButton v-if="!review" variant="success" :disabled="busy || !rationaleReady || store.chainReadState?.status !== 'complete' || !isValidGovActionId || feesAccountId === null || !coreNodes.length" @click="reviewVote">Review vote</BButton>
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
import WalletReviewPanel from '@/components/WalletReviewPanel.vue'
import type { WalletReview } from '@/types'

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
const review = ref<WalletReview | null>(null)
const reviewError = ref('')
const busy = ref(false)
const publicationPassword = ref('')
const anchor = ref<{ anchorUrl: string; anchorHash: string } | null>(null)
const rationaleReady = computed(() => !rationale.value.trim() || anchor.value !== null)
watch(rationale, () => { anchor.value = null; publicationPassword.value = '' })

async function publishRationale() {
  if (busy.value || !rationale.value.trim() || !publicationPassword.value) return
  const generation = ++reviewGeneration
  const spendingPassword = publicationPassword.value
  publicationPassword.value = ''
  busy.value = true
  reviewError.value = ''
  try {
    const result = await store.socketRequest<{ anchorUrl: string; anchorHash: string }>(
      '/jormanager/governancepublish', 'governancepublish',
      { rationale: rationale.value, spendingPassword }
    )
    if (generation === reviewGeneration) anchor.value = result
  } catch (e) {
    if (generation === reviewGeneration) reviewError.value = `${e instanceof Error ? e.message : String(e)}. Publication may have occurred; check before retrying.`
  } finally {
    if (generation === reviewGeneration) busy.value = false
  }
}
let reviewGeneration = 0

async function reviewVote() {
  if (busy.value || !rationaleReady.value || store.chainReadState?.status !== 'complete' || !isValidGovActionId.value || feesAccountId.value === null || !coreNodes.value.length) return
  const generation = ++reviewGeneration
  busy.value = true
  reviewError.value = ''
  try {
    const result = await store.reviewGovernance({
      govActionId: govActionId.value.trim(),
      votes: coreNodes.value.map(node => ({ nodeId: node.id, vote: nodeVotes.value[node.id!] })),
      feesAccountId: feesAccountId.value,
      anchorUrl: anchor.value?.anchorUrl ?? null,
      anchorHash: anchor.value?.anchorHash ?? null
    })
    if (generation === reviewGeneration) review.value = result
  } catch (e) {
    if (generation === reviewGeneration) reviewError.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (generation === reviewGeneration) busy.value = false
  }
}

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
  reviewGeneration++
  busy.value = false
  review.value = null
  reviewError.value = ''
  publicationPassword.value = ''
  anchor.value = null
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
