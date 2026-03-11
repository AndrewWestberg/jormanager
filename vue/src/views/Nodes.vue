<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <BButton
          variant="outline-primary"
          @click="showAddNodeWizard = true"
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
            <span v-if="(value as number) > -1">
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
              v-if="(item as any).type !== 'pool'"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Restart Node', variant: 'danger' }"
              @click="restartNode((item as any).name)"
            >
              <font-awesome-icon :icon="['fas', 'power-off']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-warning clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Pool Config', variant: 'warning' }"
              @click="openPoolConfigModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'percent']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-primary clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Metadata', variant: 'primary' }"
              @click="openMetadataModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'info-circle']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-success clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Relays', variant: 'success' }"
              @click="openRelaysModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'project-diagram']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Retire Pool', variant: 'danger' }"
              @click="openRetireModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'skull']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type === 'core' && mp"
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
      @hide-add-node-wizard="showAddNodeWizard = false"
    />
    
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
      title="Edit Pool Config"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSavePoolConfig"
    >
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
      <BFormGroup label="Owner (Pledge) Account" label-cols-md="2">
        <BFormSelect
          v-model="editPoolConfigForm.ownerStakingAccount"
          :options="stakingAccountOptions"
          :state="editPoolConfigForm.ownerStakingAccount != null"
        >
          <template #first>
            <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
          </template>
        </BFormSelect>
      </BFormGroup>
      <BFormGroup label="Rewards Account" label-cols-md="2">
        <BFormSelect
          v-model="editPoolConfigForm.rewardsStakingAccount"
          :options="rewardsAccountOptions"
          :state="editPoolConfigForm.rewardsStakingAccount != null"
        >
          <template #first>
            <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
          </template>
        </BFormSelect>
      </BFormGroup>
      
      <h5 class="mt-4">Pledge & Fees</h5>
      <BFormGroup label="Pledge" label-cols-md="2">
        <BFormInput v-model="editPoolConfigForm.poolPledge" placeholder="e.g. 250000" />
      </BFormGroup>
      <BFormGroup label="Cost" label-cols-md="2">
        <BFormInput v-model="editPoolConfigForm.poolCost" placeholder="e.g. 340" />
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
    </BModal>
    
    <!-- Edit Metadata Modal -->
    <BModal
      v-model="showMetadataModal"
      title="Edit Metadata"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveMetadata"
    >
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
      title="Edit Relays"
      size="lg"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveRelays"
    >
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
    </BModal>
    
    <!-- Retire Pool Modal -->
    <BModal
      v-model="showRetireModal"
      title="Retire Pool"
      :no-close-on-backdrop="true"
      @ok.prevent="handleRetirePool"
    >
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
      <BFormGroup label="SUDO Password" label-cols-md="4">
        <BFormInput
          v-model="retirePoolForm.sudoPassword"
          type="password"
          placeholder="Enter host sudo password"
          :state="retirePoolForm.sudoPassword.trim().length > 0"
        />
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
          :state="retirePoolForm.retireEpoch > 0"
        />
      </BFormGroup>
      <p class="text-muted">Pool will be retired after the specified epoch.</p>
    </BModal>
    
    <!-- Governance Vote Modal -->
    <GovernanceVoteModal v-model="showGovernanceVoteModal" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
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
import { lovelaceToAda } from '@/utils/filters'
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

// Modal visibility
const showColorModal = ref(false)
const showPoolConfigModal = ref(false)
const showMetadataModal = ref(false)
const showRelaysModal = ref(false)
const showRetireModal = ref(false)
const showGovernanceVoteModal = ref(false)

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
const editRelaysForm = ref({
  nodeId: 0,
  relays: [] as Relay[]
})
const retirePoolForm = ref({
  id: 0,
  sudoPassword: '',
  retireFeesAccount: null as number | null,
  retireEpoch: 0
})

// Select options
const feeAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.registrationFeesSelectOptions(formatter)
})

const retireFeeAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.reregistrationFeesSelectOptions(formatter)
})

const stakingAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.stakingSelectOptions(formatter)
})

const rewardsAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.rewardsSelectOptions(formatter)
})

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
      poolPledge: (node as any).poolPledge || 0,
      poolCost: (node as any).poolCost || 340,
      poolMargin: (node as any).poolMargin || 0.03
    }
  }
  showPoolConfigModal.value = true
}

function handleSavePoolConfig() {
  emitter.emit('show-spending-password-modal', { action: 'update-pool-config', data: editPoolConfigForm.value })
}

function openMetadataModal(nodeId: number) {
  store.requestMetadata(nodeId)
  const metadata = store.editorMetadata as any
  editMetadataForm.value = {
    nodeId,
    ticker: metadata?.ticker || '',
    name: metadata?.name || '',
    description: metadata?.description || '',
    homepage: metadata?.homepage || '',
    location: metadata?.extended?.info?.location || '',
    twitter: metadata?.extended?.info?.social?.twitter || ''
  }
  showMetadataModal.value = true
}

function handleSaveMetadata() {
  emitter.emit('show-spending-password-modal', { action: 'update-metadata', data: editMetadataForm.value })
}

function openRelaysModal(nodeId: number) {
  const node = find(nodes.value, { id: nodeId })
  editRelaysForm.value = {
    nodeId,
    relays: (node as any)?.relays?.map((r: any) => ({ addr: r.addr, port: r.port })) || []
  }
  showRelaysModal.value = true
}

function handleSaveRelays() {
  emitter.emit('show-spending-password-modal', { action: 'update-relays', data: editRelaysForm.value })
}

function openRetireModal(nodeId: number) {
  retirePoolForm.value = {
    id: nodeId,
    sudoPassword: '',
    retireFeesAccount: null,
    retireEpoch: 0
  }
  showRetireModal.value = true
}

function handleRetirePool() {
  if (retirePoolForm.value.retireFeesAccount === null || retirePoolForm.value.retireEpoch <= 0 || retirePoolForm.value.sudoPassword.trim().length === 0) {
    store.toastError = { title: 'Error', message: 'You must provide a fee wallet, sudo password, and retirement epoch.' }
    return
  }
  emitter.emit('show-spending-password-modal', { action: 'retire-pool', data: retirePoolForm.value })
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
  poolParentId.value = parentId
  showAddNodeWizard.value = true
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: any }) {
  switch (data.action) {
    case 'update-color':
      store.updateNodeColor({ ...data.originalData, spendingPassword: data.password })
      showColorModal.value = false
      break
    case 'update-pool-config':
      store.updatePoolConfig({ ...data.originalData, spendingPassword: data.password })
      showPoolConfigModal.value = false
      break
    case 'update-metadata':
      store.updateMetadata({ ...data.originalData, spendingPassword: data.password })
      showMetadataModal.value = false
      break
    case 'update-relays':
      store.sendEditRelays({ ...data.originalData, spendingPassword: data.password })
      showRelaysModal.value = false
      break
    case 'retire-pool':
      store.sendRetirePool({ ...data.originalData, spendingPassword: data.password })
      showRetireModal.value = false
      retirePoolForm.value = {
        id: 0,
        sudoPassword: '',
        retireFeesAccount: null,
        retireEpoch: 0
      }
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
