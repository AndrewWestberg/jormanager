<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <BButton
          variant="outline-primary"
          @click="poolParentId = null; setupContext = null; showAddNodeWizard = true"
          v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
        >
          +&nbsp;Node
        </BButton>
        <BButton
          v-if="hasCoreNodes"
          variant="outline-success"
          class="ms-2"
          @click="showGovernanceVoteModal = true"
          v-b-tooltip.hover.bottom="'Submit governance vote for all core nodes'"
        >
          🗳️ Vote
        </BButton>
      </div>
      <hr />
      <div>
        <BTable
          small
          hover
          dark
          bordered
          striped
          head-variant="light"
          :items="displayNodes as any[]"
          :fields="fields"
          v-if="(displayNodes as any[]).length > 0"
        >
          <template #cell(name)="{ item, value }">
            <div class="clearfix">
              <div
                class="clickable d-inline-block"
                v-b-tooltip.hover.left="'Update Color'"
                @click="openColorModal((item as any).id, (item as any).color)"
              >
                <font-awesome-icon
                  :style="{ color: (item as any).color }"
                  :icon="(item as any).isDefault ? ['fas', 'check-circle'] : ['fas', 'circle']"
                />
              </div>
              &nbsp;{{ value }}
              <div
                class="float-end clickable text-secondary d-inline-block"
                v-show="(item as any).type !== 'relay'"
                v-b-tooltip.hover.right="{ title: 'Copy PoolId to clipboard', variant: 'secondary' }"
                @click="copyToClipboard((item as any).poolId)"
              >
                <font-awesome-icon :icon="['fas', 'copy']" />
              </div>
            </div>
          </template>
          <template #cell(type)="{ value }">
            <div
              v-show="value === 'relay'"
              class="text-danger text-center"
              v-b-tooltip.hover.right="{ title: 'Relay Node', variant: 'danger' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
            <div
              v-show="value === 'core'"
              class="text-success text-center"
              v-b-tooltip.hover.right="{ title: 'Core Node', variant: 'success' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
            <div
              v-show="value === 'pool'"
              class="text-primary text-center"
              v-b-tooltip.hover.right="{ title: 'Pool Node', variant: 'primary' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
          </template>
          <template #cell(kesExpireTimeSec)="{ item, value }">
            <span v-if="(value as number) > -1 && !isDraftNode((item as any).id)">
              {{ formatKesExpiry(value as number) }}&nbsp;
              <div
                class="text-warning clickable d-inline-block"
                v-b-tooltip.hover.right="{ title: 'Rotate KES Key', variant: 'warning' }"
                @click="rotateKesKey((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'key']" />
              </div>
            </span>
          </template>
          <template #cell(edit)="{ item }">
            <div
              v-if="(item as any).type !== 'pool' && !isDraftNode((item as any).id)"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Restart Node', variant: 'danger' }"
              @click="restartNode((item as any).name)"
            >
              <font-awesome-icon :icon="['fas', 'power-off']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-warning clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Pool Config', variant: 'warning' }"
              @click="openPoolConfigModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'percent']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-primary clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Metadata', variant: 'primary' }"
              @click="openMetadataModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'info-circle']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-success clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Relays', variant: 'success' }"
              @click="openRelaysModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'project-diagram']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-info clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Leios key status', variant: 'info' }"
              @click="leiosNodeId = (item as any).id"
            >
              <font-awesome-icon :icon="['fas', 'key']" />
            </div>&nbsp;
            <div
              v-if="isDraftNode((item as any).id)"
              class="text-info clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Resume setup', variant: 'info' }"
              @click="openSetup((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'check-circle']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Retire Pool', variant: 'danger' }"
              @click="openRetireModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'skull']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type === 'core' && mp && !isDraftNode((item as any).id)"
              class="text-primary clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Add Pool', variant: 'primary' }"
              @click="addPool((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'plus-circle']" />
            </div>
          </template>
        </BTable>
      </div>
    </div>
    
    <AddNodeWizard
      v-if="showAddNodeWizard"
      :parent-id="poolParentId"
      :setup-context="setupContext"
      @hide-add-node-wizard="showAddNodeWizard = false"
    />
    <BModal :model-value="leiosNodeId !== null" title="Leios key lifecycle" size="xl" hide-footer
      @update:model-value="value => { if (!value) leiosNodeId = null }">
      <LeiosKeyPanel v-if="leiosNodeId !== null" :node-id="leiosNodeId" />
    </BModal>



    
    <!-- Edit Color Modal -->
    <BModal
      v-model="showColorModal"
      title="Edit Color"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveColor"
    >
      <BFormGroup label="Color" label-cols-md="2">
        <BFormInput v-model="editColorForm.color" type="color" />
      </BFormGroup>
    </BModal>
    
    <!-- Edit Pool Config Modal -->
    <BModal
      v-model="showPoolConfigModal"
      :title="poolConfigStep === 1 ? 'Edit Pool Config' : 'Review Pool Config'"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSavePoolConfig"
      :ok-title="poolConfigStep === 1 ? 'Review' : 'Submit'"
      :ok-disabled="poolConfigStep !== 1 && nodeChainUnavailable(editPoolConfigForm.nodeId)"
      :cancel-title="poolConfigStep === 1 ? 'Cancel' : 'Back'"
      @cancel.prevent="handleCancelPoolConfig"
    >
      <div v-if="poolConfigStep === 1">
        <h5>Account Config</h5>
        <BFormGroup label="Fees Account" label-cols-md="2">
          <BFormSelect
            v-model="editPoolConfigForm.registrationFeesAccount"
            :options="feeAccountOptions"
            :state="editPoolConfigForm.registrationFeesAccount != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        <label class="d-block mb-2"><input v-model="replacePoolOwners" type="checkbox"> Replace the entire owner set with the selected accounts</label>
        <BFormGroup label="Owner (Pledge) Accounts" label-cols-md="2">
          <BFormSelect
            v-model="selectedPoolOwners"
            :options="stakingAccountOptions"
            :state="!replacePoolOwners || selectedPoolOwners.length > 0"
            :disabled="!replacePoolOwners"
            multiple
          >
          </BFormSelect>
        </BFormGroup>
        <label class="d-block mb-2"><input v-model="changePoolReward" type="checkbox"> Change the reward account</label>
        <BFormGroup label="Rewards Account" label-cols-md="2">
          <BFormSelect
            v-model="editPoolConfigForm.rewardsStakingAccount"
            :options="rewardsAccountOptions"
            :state="editPoolConfigForm.rewardsStakingAccount != null"
            :disabled="!changePoolReward"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <h5 class="mt-4">Pledge & Fees</h5>
        <BFormGroup label="Pledge (lovelace)" label-cols-md="2">
          <BFormInput v-model="editPoolConfigForm.poolPledge" placeholder="e.g. 250000000000" />
        </BFormGroup>
        <BFormGroup label="Cost (lovelace)" label-cols-md="2">
          <BFormInput v-model="editPoolConfigForm.poolCost" placeholder="e.g. 340000000" />
        </BFormGroup>
        <BFormGroup label="Margin" label-cols-md="2">
          <BFormInput
            v-model="editPoolConfigForm.poolMargin"
            type="number"
            min="0.00"
            max="1.00"
            step="0.001"
          />
          <p class="text-center">{{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%</p>
        </BFormGroup>
      </div>
      <div v-else>
        <p>Target pool: {{ nodes.find(node => node.id === editPoolConfigForm.nodeId)?.name }} ({{ editPoolConfigForm.nodeId }}).</p>
        <h5>Please Review Your Changes</h5>
        <p>Owner set: {{ replacePoolOwners ? selectedPoolOwners.join(', ') : 'preserve current owners' }}. Reward account: {{ changePoolReward ? editPoolConfigForm.rewardsStakingAccount : 'preserve current reward account' }}.</p>
        <table class="table table-dark table-sm">
          <tbody>
            <tr>
              <th style="width: 30%">Pledge</th>
              <td>{{ editPoolConfigForm.poolPledge }}</td>
            </tr>
            <tr>
              <th>Fixed Cost</th>
              <td>{{ editPoolConfigForm.poolCost }}</td>
            </tr>
            <tr>
              <th>Margin</th>
              <td>{{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </BModal>
    
    <!-- Edit Metadata Modal -->
    <BModal
      v-model="showMetadataModal"
      title="Edit Metadata"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveMetadata"
      ok-title="Submit"
      :ok-disabled="nodeChainUnavailable(editMetadataForm.nodeId)"
    >
      <BFormGroup label="Fees Account"><BFormSelect v-model="poolChangeFeeAccount" :options="feeAccountOptions" /></BFormGroup>
      <h5>Primary (Required)</h5>
      <BFormGroup label="Ticker" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.ticker" placeholder="e.g. TICKR" maxlength="5" />
      </BFormGroup>
      <BFormGroup label="Name" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.name" placeholder="e.g. My Awesome Pool" maxlength="50" />
      </BFormGroup>
      <BFormGroup label="Description" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.description" placeholder="Pool description" maxlength="255" />
      </BFormGroup>
      <BFormGroup label="Homepage" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.homepage" placeholder="e.g. https://mypool.com" />
      </BFormGroup>
      
      <h5 class="mt-4">Extended (Optional)</h5>
      <BFormGroup label="Location" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.location" placeholder="e.g. United States" />
      </BFormGroup>
      <BFormGroup label="Twitter" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.twitter" placeholder="e.g. mypool" />
      </BFormGroup>
    </BModal>
    
    <!-- Edit Relays Modal -->
    <BModal
      v-model="showRelaysModal"
      :title="relaysStep === 1 ? 'Edit Relays' : 'Review Relays'"
      size="lg"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveRelays"
      :ok-title="relaysStep === 1 ? 'Review' : 'Submit'"
      :ok-disabled="relaysStep !== 1 && nodeChainUnavailable(editRelaysForm.nodeId)"
      :cancel-title="relaysStep === 1 ? 'Cancel' : 'Back'"
      @cancel.prevent="handleCancelRelays"
    >
      <BFormGroup label="Fees Account"><BFormSelect v-model="poolChangeFeeAccount" :options="feeAccountOptions" /></BFormGroup>
      <div v-if="relaysStep === 1">
        <div v-for="(relay, index) in editRelaysForm.relays" :key="index" class="mb-3">
          <BCard class="bg-dark">
            <div class="d-flex justify-content-between align-items-center mb-2">
              <h5>Relay {{ index + 1 }}</h5>
              <BButton variant="outline-danger" size="sm" @click="editRelaysForm.relays.splice(index, 1)">✕</BButton>
            </div>
            <BFormGroup label="Address" label-cols-md="2">
              <BFormInput v-model="relay.addr" placeholder="e.g. relay1.mypool.com" />
            </BFormGroup>
            <BFormGroup label="Port" label-cols-md="2">
              <BFormInput v-model="relay.port" type="number" placeholder="e.g. 3001" />
            </BFormGroup>
          </BCard>
        </div>
        <BButton variant="primary" @click="editRelaysForm.relays.push({ addr: '', port: 3001 })">
          + Add Relay
        </BButton>
      </div>
      <div v-else>
        <h5>Please Review Your Relays</h5>
        <table class="table table-dark table-sm" v-if="editRelaysForm.relays.length > 0">
          <thead>
            <tr>
              <th>Address</th>
              <th>Port</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(relay, idx) in editRelaysForm.relays" :key="idx">
              <td>{{ relay.addr }}</td>
              <td>{{ relay.port }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else>No relays configured.</p>
      </div>
    </BModal>

    
    <!-- Retire Pool Modal -->
    <BModal
      v-model="showRetireModal"
      title="Retire Pool"
      :no-close-on-backdrop="true"
      hide-footer
      :no-close-on-esc="retirementBusy"
      :hide-header-close="retirementBusy"
    >
      <p>Target pool: {{ nodes.find(node => node.id === retirePoolForm.id)?.name }} ({{ retirePoolForm.id }}).</p>
      <BFormGroup label="Fees Account" label-cols-md="4">
        <BFormSelect
          v-model="retirePoolForm.retireFeesAccount"
          :options="retireFeeAccountOptions"
          :state="retirePoolForm.retireFeesAccount !== null"
        >
          <template #first>
            <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
          </template>
        </BFormSelect>
      </BFormGroup>
      <p class="text-danger">
        <font-awesome-icon :icon="['fas', 'exclamation-triangle']" />
        Warning: This action will retire your pool!
      </p>
      <BFormGroup label="Retire in Epoch" label-cols-md="4">
        <BFormInput
          v-model="retirePoolForm.retireEpoch"
          type="number"
          placeholder="e.g. Current epoch + 2"
          :state="/^[1-9]\d*$/.test(String(retirePoolForm.retireEpoch))"
        />
      </BFormGroup>
      <BButton variant="success" :disabled="retirementBusy || nodeChainUnavailable(retirePoolForm.id)" @click="requestRetirement">Retire pool</BButton>
      <p v-if="retirementError" class="text-danger mt-2">{{ retirementError }}</p>
      <BButton class="mt-2 ms-2" variant="secondary" :disabled="retirementBusy" @click="showRetireModal = false">Close</BButton>
    </BModal>
    
    <!-- Governance Vote Modal -->
    <GovernanceVoteModal v-model="showGovernanceVoteModal" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import moment from 'moment-timezone'
import { find } from 'lodash-es'
import {
  BButton,
  BTable,
  BModal,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption,
  BCard
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { generateUUID } from '@/utils/filters'
import type { PoolRegistrationResult, PoolRetirementResult } from '@/types'
import LeiosKeyPanel from '@/components/LeiosKeyPanel.vue'
import AddNodeWizard from '@/components/AddNodeWizard.vue'
import GovernanceVoteModal from '@/components/GovernanceVoteModal.vue'

interface Relay {
  addr: string
  port: number
}

const store = useJorManagerStore()
const { displayNodes, mp, nodes } = storeToRefs(store)
const emitter = useEventBus()

const showAddNodeWizard = ref(false)
const poolParentId = ref<number | null>(null)
const leiosNodeId = ref<number | null>(null)

// Modal visibility
const showColorModal = ref(false)
const showPoolConfigModal = ref(false)
const showMetadataModal = ref(false)
const showRelaysModal = ref(false)
const showRetireModal = ref(false)
const showGovernanceVoteModal = ref(false)

const poolConfigStep = ref(1)
const relaysStep = ref(1)

// Form data
const editColorForm = ref({ nodeId: 0, color: '#000000' })
const editPoolConfigForm = ref({
  nodeId: 0,
  registrationFeesAccount: null as number | null,
  ownerStakingAccount: null as number | null,
  rewardsStakingAccount: null as number | null,
  poolPledge: 0,
  poolCost: 340,
  poolMargin: 0.03
})
const editMetadataForm = ref({
  nodeId: 0,
  ticker: '',
  name: '',
  description: '',
  homepage: '',
  location: '',
  twitter: ''
})
type LoadedMetadata = {
  nodeId: number
  metadata: { ticker?: string; name?: string; description?: string; homepage?: string }
  extendedMetadata?: {
    telegramAdminHandle?: string[]; adapoolsVerify?: string
    info?: {
      urlPngIcon64x64?: string; urlPngLogo?: string; location?: string; rss?: string
      social?: { twitterHandle?: string; telegramHandle?: string; facebookHandle?: string; youtubeHandle?: string; twitchHandle?: string; discordHandle?: string; githubHandle?: string }
      company?: { name?: string; addr?: string; city?: string; country?: string; companyId?: string; vatId?: string }
      about?: { me?: string; server?: string; company?: string }
    }
  }
}
let loadedMetadata: LoadedMetadata | null = null
const editRelaysForm = ref({
  nodeId: 0,
  relays: [] as Relay[]
})
type PoolChangeRequest = { requestId: string; nodeId: number; feePayerAccountId: number; action: 'update'; keyId: null; changes: Record<string, unknown>; metadata?: unknown }
const replacePoolOwners = ref(false)
const selectedPoolOwners = ref<number[]>([])
const changePoolReward = ref(false)
const poolChangeFeeAccount = ref<number | null>(null)
const poolChangeBusy = ref(false)
const retirementBusy = ref(false)
const retirementError = ref('')
let poolRequestId = generateUUID()
let retirementRequestId = generateUUID()
watch([editPoolConfigForm, editMetadataForm, editRelaysForm, replacePoolOwners, selectedPoolOwners, changePoolReward, poolChangeFeeAccount],
  () => { poolRequestId = generateUUID() }, { deep: true, flush: 'sync' })
function nodeChainUnavailable(_nodeId: number) {
  return !store.connected
}
function requestPoolChange(nodeId: number, feePayerAccountId: number | null, changes: Record<string, unknown>, metadata?: unknown) {
  if (feePayerAccountId == null || poolChangeBusy.value) {
    store.toastError = { title: 'Pool update', message: 'Select a fee payer account.' }
    return
  }
  if (nodeChainUnavailable(nodeId)) return
  const data: PoolChangeRequest = JSON.parse(JSON.stringify({ requestId: poolRequestId, nodeId, feePayerAccountId, action: 'update', keyId: null, changes, metadata }))
  emitter.emit('show-spending-password-modal', { action: 'submit-pool-change', data })
}

async function submitPoolChange(request: PoolChangeRequest, password: string) {
  if (poolChangeBusy.value || !(showPoolConfigModal.value || showRelaysModal.value || showMetadataModal.value) || request.requestId !== poolRequestId || nodeChainUnavailable(request.nodeId)) return
  poolChangeBusy.value = true
  try {
    const result = await store.socketRequest<PoolRegistrationResult>('/jormanager/leios/registration/submit', 'leios_registration_submitted', { ...request, spendingPassword: password })
    if (result.requestId !== request.requestId || result.nodeId !== request.nodeId || result.action !== 'update') throw new Error('Submission target does not match')
    if (!['submitted', 'confirmed'].includes(result.state)) throw new Error(`Transaction outcome: ${result.state}`)
    if (poolRequestId === request.requestId) {
      showPoolConfigModal.value = false
      showRelaysModal.value = false
      showMetadataModal.value = false
      poolRequestId = generateUUID()
    }
    store.toastSuccess = { title: 'Pool update submitted', message: result.txId }
  } catch (error) {
    store.toastError = { title: 'Pool update failed', message: error instanceof Error ? error.message : String(error) }
  } finally {
    poolChangeBusy.value = false
  }
}

const retirePoolForm = ref({
  id: 0,
  retireFeesAccount: null as number | null,
  retireEpoch: 0
})
watch(retirePoolForm, () => { retirementRequestId = generateUUID() }, { deep: true, flush: 'sync' })
type PoolSetupContext = {
  requestId: string; nodeId: number; hostId: number; observerNodeId: number
  networkKey: string; configSha256: string; request: Record<string, unknown>
  keyId: number | null; registrationRequestId: string | null
  keys: { keyId: number; publicKeyHex: string }[]; setupRequestId: string | null
}
const setupContext = ref<PoolSetupContext | null>(null)
const setupLoading = ref(false)

async function openSetup(nodeId: number) {
  if (setupLoading.value || !store.connected || !isDraftNode(nodeId)) return
  setupLoading.value = true
  try {
    const context = await store.socketRequest<PoolSetupContext>('/jormanager/node/setup-context', 'node_setup_context', { nodeId })
    const node = nodes.value.find(item => item.id === nodeId)
    if (!node || !isDraftNode(nodeId) || context.nodeId !== nodeId || context.hostId !== node.hostId) {
      throw new Error('Setup target does not match')
    }
    setupContext.value = context
    poolParentId.value = null
    showAddNodeWizard.value = true
  } catch (error) {
    store.toastError = { title: 'Setup unavailable', message: error instanceof Error ? error.message : String(error) }
  } finally {
    setupLoading.value = false
  }
}



// Select options
const feeAccountOptions = computed(() => store.registrationFeesSelectOptions())

const retireFeeAccountOptions = computed(() => store.reregistrationFeesSelectOptions())

const stakingAccountOptions = computed(() => store.stakingSelectOptions())

const rewardsAccountOptions = computed(() => store.rewardsSelectOptions())

// Check if there are any core/pool nodes (non-relay)
const hasCoreNodes = computed(() => {
  return nodes.value.some((node) => (node as any).type !== 'relay')
})

const fields = [
  { key: 'name', sortable: true },
  { key: 'type', label: '', sortable: true },
  { key: 'host', sortable: true },
  { key: 'kesExpireTimeSec', label: 'KES Expiry' },
  { key: 'edit', label: '' }
]

function formatKesExpiry(timestamp: number): string {
  const date = moment.unix(timestamp)
  return `${date.format('YYYY-MM-DD h:mma UTCZ')} (${date.fromNow()})`
}

function isDraftNode(nodeId: number) {
  return !!(nodes.value.find(node => node.id === nodeId) as { isDraft?: boolean } | undefined)?.isDraft
}


function openColorModal(nodeId: number, currentColor: string) {
  editColorForm.value = { nodeId, color: currentColor }
  showColorModal.value = true
}

function handleSaveColor() {
  emitter.emit('show-spending-password-modal', { action: 'update-color', data: editColorForm.value })
}

function openPoolConfigModal(nodeId: number) {
  const node = find(nodes.value, { id: nodeId })
  if (node) {
    editPoolConfigForm.value = {
      nodeId,
      registrationFeesAccount: (node as any).registrationFeesAccountId || null,
      ownerStakingAccount: (node as any).ownerStakingAccountId || null,
      rewardsStakingAccount: (node as any).rewardsStakingAccountId || null,
      poolPledge: (node as any).poolPledge ?? 0,
      poolCost: (node as any).poolCost ?? 0,
      poolMargin: (node as any).poolMargin ?? 0
    }
  }
  poolConfigStep.value = 1
  replacePoolOwners.value = false
  selectedPoolOwners.value = []
  changePoolReward.value = false
  showPoolConfigModal.value = true
}

function handleSavePoolConfig() {
  if (poolConfigStep.value === 1) {
    poolConfigStep.value = 2
    return
  }
  const pledge = String(editPoolConfigForm.value.poolPledge)
  const cost = String(editPoolConfigForm.value.poolCost)
  const margin = String(editPoolConfigForm.value.poolMargin)
  if (!/^(0|[1-9]\d*)$/.test(pledge) || !/^(0|[1-9]\d*)$/.test(cost) || !/^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(margin)) {
    store.toastError = { title: 'Pool review', message: 'Use whole lovelace amounts and a margin between 0 and 1.' }
    return
  }
  const changes: Record<string, unknown> = { pledge, cost, margin }
  if (replacePoolOwners.value) {
    if (selectedPoolOwners.value.length === 0) {
      store.toastError = { title: 'Pool review', message: 'Select at least one replacement owner account.' }
      return
    }
    changes.ownerAccountIds = [...selectedPoolOwners.value]
  }
  if (changePoolReward.value) {
    if (editPoolConfigForm.value.rewardsStakingAccount == null) {
      store.toastError = { title: 'Pool review', message: 'Select the replacement reward account.' }
      return
    }
    changes.rewardAccountId = editPoolConfigForm.value.rewardsStakingAccount
  }
  requestPoolChange(editPoolConfigForm.value.nodeId, editPoolConfigForm.value.registrationFeesAccount, changes)
}

function handleCancelPoolConfig() {
  if (poolConfigStep.value === 2) {
    poolConfigStep.value = 1
  } else {
    showPoolConfigModal.value = false
    poolConfigStep.value = 1
  }
}

watch(() => store.editorMetadata, value => {
  if (!showMetadataModal.value || !value || typeof value !== 'object' || !('nodeId' in value) || value.nodeId !== editMetadataForm.value.nodeId || !('metadata' in value)) return
  const response = value as LoadedMetadata
  if (!response.metadata || typeof response.metadata !== 'object') return
  loadedMetadata = response
  const metadata = response.metadata
  const info = response.extendedMetadata?.info
  if (editMetadataForm.value.ticker || editMetadataForm.value.name || editMetadataForm.value.description || editMetadataForm.value.homepage || editMetadataForm.value.location || editMetadataForm.value.twitter) return
  editMetadataForm.value = {
    nodeId: editMetadataForm.value.nodeId,
    ticker: metadata.ticker || '', name: metadata.name || '', description: metadata.description || '',
    homepage: metadata.homepage || '', location: info?.location || '', twitter: info?.social?.twitterHandle || ''
  }
})

function openMetadataModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  loadedMetadata = null
  store.editorMetadata = null
  editMetadataForm.value = { nodeId, ticker: '', name: '', description: '', homepage: '', location: '', twitter: '' }
  showMetadataModal.value = true
  store.requestMetadata(nodeId)
}
function handleSaveMetadata() {
  const { nodeId, ticker, name, description, homepage, location, twitter } = editMetadataForm.value
  if (!ticker.trim() || !name.trim() || !description.trim() || !homepage.trim()) {
    store.toastError = { title: 'Pool update', message: 'Complete the required metadata fields.' }
    return
  }
  const current = loadedMetadata?.extendedMetadata
  const info = current?.info
  const social = info?.social
  const extended = { itn: null, telegramAdminHandle: current?.telegramAdminHandle?.[0] ?? null, adapoolsVerify: current?.adapoolsVerify ?? null,
    info: { icon64: info?.urlPngIcon64x64 ?? null, logo: info?.urlPngLogo ?? null, location,
      social: { twitter, telegram: social?.telegramHandle ?? null, facebook: social?.facebookHandle ?? null,
        youtube: social?.youtubeHandle ?? null, twitch: social?.twitchHandle ?? null,
        discord: social?.discordHandle ?? null, github: social?.githubHandle ?? null },
      company: info?.company ? { name: info.company.name, addr: info.company.addr, city: info.company.city, country: info.company.country, company_id: info.company.companyId, vat_id: info.company.vatId } : null,
      about: info?.about ?? null, rss: info?.rss ?? null } }
  requestPoolChange(nodeId, poolChangeFeeAccount.value, {}, { ticker, name, description, homepage, extended })
}



function openRelaysModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  const node = find(nodes.value, { id: nodeId })
  editRelaysForm.value = {
    nodeId,
    relays: (node as any)?.relays?.map((r: any) => ({ addr: r.addr, port: r.port })) || []
  }
  relaysStep.value = 1
  showRelaysModal.value = true
}

function handleSaveRelays() {
  if (relaysStep.value === 1) {
    relaysStep.value = 2
    return
  }
  const relays: Record<string, unknown>[] = []
  for (const relay of editRelaysForm.value.relays) {
    const address = relay.addr.trim()
    const port = Number(relay.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      store.toastError = { title: 'Pool review', message: 'Each relay needs a valid port.' }
      return
    }
    if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) {
      if (address.split('.').some(part => Number(part) > 255)) {
        store.toastError = { title: 'Pool review', message: 'Invalid IPv4 relay address.' }
        return
      }
      relays.push({ 'single host address': { port, IPv4: address, IPv6: null } })
    } else if (/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(address)) {
      relays.push({ 'single host name': { port, dnsName: address } })
    } else {
      store.toastError = { title: 'Pool review', message: 'Use an IPv4 address or DNS name for each relay.' }
      return
    }
  }
  requestPoolChange(editRelaysForm.value.nodeId, poolChangeFeeAccount.value, { relays })
}

function handleCancelRelays() {
  if (relaysStep.value === 2) {
    relaysStep.value = 1
  } else {
    showRelaysModal.value = false
    relaysStep.value = 1
  }
}

function openRetireModal(nodeId: number) {
  retirementError.value = ''
  retirePoolForm.value = {
    id: nodeId,
    retireFeesAccount: null,
    retireEpoch: 0
  }
  showRetireModal.value = true
}

function requestRetirement() {
  const { id: nodeId, retireFeesAccount: feePayerAccountId, retireEpoch } = retirePoolForm.value
  if (retirementBusy.value || nodeChainUnavailable(nodeId)) return
  if (feePayerAccountId === null || !/^[1-9]\d*$/.test(String(retireEpoch))) {
    retirementError.value = 'Select a fee account and a valid retirement epoch.'
    return
  }
  emitter.emit('show-spending-password-modal', { action: 'submit-pool-retirement', data: {
    requestId: retirementRequestId, nodeId, feePayerAccountId, retireEpoch: String(retireEpoch)
  } })
}

async function submitRetirement(request: { requestId: string; nodeId: number; feePayerAccountId: number; retireEpoch: string }, password: string) {
  if (retirementBusy.value || !showRetireModal.value || request.requestId !== retirementRequestId || nodeChainUnavailable(request.nodeId)) return
  retirementBusy.value = true
  retirementError.value = ''
  try {
    const result = await store.socketRequest<PoolRetirementResult>('/jormanager/pool/retirement/submit', 'pool_retirement_submit', { ...request, spendingPassword: password })
    if (result.requestId !== request.requestId || result.nodeId !== request.nodeId) throw new Error('Submission target does not match')
    if (!['submitted', 'confirmed'].includes(result.state)) throw new Error(`Transaction outcome: ${result.state}`)
    if (retirementRequestId === request.requestId) {
      showRetireModal.value = false
      retirementRequestId = generateUUID()
    }
    store.toastSuccess = { title: 'Pool retirement submitted', message: result.txId }
  } catch (error) {
    retirementError.value = error instanceof Error ? error.message : String(error)
  } finally {
    retirementBusy.value = false
  }
}


function copyToClipboard(value: string) {
  if (!value) return
  navigator.clipboard.writeText(value).then(
    () => { store.toastInfo = { title: 'Pool ID', message: 'Copied to clipboard' } },
    () => { store.toastError = { title: 'Pool ID', message: 'Copy to clipboard failed.' } }
  )
}

function rotateKesKey(nodeName: string) {
  emitter.emit('show-spending-password-modal', { action: 'rotate-kes', data: nodeName })
}

function restartNode(nodeName: string) {
  store.restartNodeByName(nodeName)
}

function addPool(parentId: number) {
  setupContext.value = null
  poolParentId.value = parentId
  showAddNodeWizard.value = true
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: any }) {
  switch (data.action) {
    case 'update-color':
      store.updateNodeColor({ ...data.originalData, spendingPassword: data.password })
      showColorModal.value = false
      break
    case 'submit-pool-change':
      void submitPoolChange(data.originalData as PoolChangeRequest, data.password)
      break
    case 'submit-pool-retirement':
      void submitRetirement(data.originalData, data.password)
      break
    case 'rotate-kes':
      store.rotateKesByName({ name: data.originalData as string, spendingPassword: data.password })
      break
  }
}

onMounted(() => {
  store.requestNodes()
  store.requestHosts()
  store.fetchWalletItems()
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.clickable:hover {
  cursor: pointer;
}
</style>
