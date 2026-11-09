<template>
  <div>
    <div v-if="!showAddWalletEntryWizard">
      <div>
        <BButton
          variant="outline-primary"
          @click="showAddWalletEntryWizard = true"
          v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
        >
          +&nbsp;Create Entry
        </BButton>&nbsp;
        <BButton
          variant="outline-primary"
          @click="backupClicked"
          v-b-tooltip.hover.bottom="'Download all of your wallet and pool keys.'"
        >
          <font-awesome-icon :icon="['fas', 'file-archive']" />&nbsp;Save Backup
        </BButton>&nbsp;
        <BButton
          v-if="hasSelectedClaims"
          variant="success"
          @click="claimMultipleClick"
          v-b-tooltip.hover.bottom="'Claim rewards from selected stake addresses'"
        >
          <font-awesome-icon :icon="['fas', 'cash-register']" />&nbsp;Claim Selected ({{ visibleSelectedCount }})
        </BButton>
      </div>
      <hr />
      <BContainer fluid>
        <BRow>
          <BCol lg="6" class="my-1">
            <BFormGroup
              label="Filter"
              label-for="filter-input"
              label-cols-sm="3"
              label-align-sm="right"
              label-size="sm"
            >
              <BInputGroup size="sm">
                <BFormInput
                  id="filter-input"
                  v-model="filter"
                  type="search"
                  placeholder="Type to Search"
                />
                <template #append>
                  <BButton :disabled="!filter" @click="filter = ''">Clear</BButton>
                </template>
              </BInputGroup>
            </BFormGroup>
          </BCol>
        </BRow>
        <BTable
          small
          hover
          dark
          bordered
          striped
          head-variant="light"
          :items="walletItems"
          :fields="fields"
          :filter="filter || undefined"
          :filter-included-fields="filterOn"
          v-show="walletItems.length > 0"
        >
          <template #cell(paymentAddr)="{ value }">
            {{ String(value).substring(0, 14) }}...
            <span
              class="clickable text-secondary d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Copy to clipboard', variant: 'secondary' }"
              @click="copyToClipboard(String(value))"
            >
              <font-awesome-icon :icon="['fas', 'copy']" />
            </span>
          </template>
          <template #cell(paymentAddrLovelace)="{ item, value }">
            <div class="text-right">
              {{ formatAda(value) }}
              <span
                v-if="canSend(item as WalletItem)"
                class="clickable text-success d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Send', variant: 'success' }"
                @click="sendAdaClick((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'hand-holding-usd']" />
              </span>
            </div>
            <hr v-if="hasNativeAssets((item as any).name)" />
            <div
              v-for="(name, index) in getNativeAssetKeys((item as any).name)"
              :key="index"
            >
              {{ hex2ascii(name.substring(name.indexOf('.') + 1)) }} ({{ name }}) -
              {{ getNativeAssetAmount((item as any).name, name) }}
              <span
                class="text-warning d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Native Asset', variant: 'warning' }"
              >
                <font-awesome-icon :icon="['fas', 'coins']" />
              </span>
            </div>
          </template>
          <template #cell(stakingAddr)="{ item, value }">
            <div v-if="value && String(value).length > 0">
              {{ String(value).substring(0, 15) }}... &nbsp;
              <span
                v-if="balanceReady(item as WalletItem) && (item as any).stakingAddrRegistered === true"
                class="clickable text-success d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Registered on chain. Click to de-register.', variant: 'success' }"
                @click="deregisterStakingAddress((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'link']" />
              </span>
              <span
                v-if="balanceReady(item as WalletItem) && (item as any).stakingAddrRegistered === false"
                class="clickable text-warning d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Not registered on chain. Click to register.', variant: 'warning' }"
                @click="registerStakingAddress((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'unlink']" />
              </span>
              <BButton v-if="balanceReady(item as WalletItem) && (item as any).stakingAddrRegistered === true && (item as any).type === 'stake'" size="sm" variant="outline-primary" class="ms-2" @click="delegateStakingAddress((item as any).name)">Delegate</BButton>
            </div>
            <div v-else class="text-center">---</div>
          </template>
          <template #cell(stakingAddrLovelace)="{ item, value }">
            <div class="text-right d-flex align-items-center justify-content-end gap-2" v-if="(item as any).type === 'stake'">
              <span>{{ formatAda(value) }}</span>
              <span
                v-if="canClaim(item as WalletItem)"
                class="clickable text-success flex-shrink-0"
                v-b-tooltip.hover.bottom="{ title: 'Claim Rewards', variant: 'success' }"
                @click="claimAdaClick((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'cash-register']" />
              </span>
              <BFormCheckbox
                v-if="canClaim(item as WalletItem)"
                :model-value="selectedClaimIds.has((item as any).id)"
                @update:model-value="toggleClaimSelection((item as any).id)"
                class="rewards-checkbox"
                v-b-tooltip.hover.bottom="{ title: 'Select for multi-claim', variant: 'info' }"
              />
            </div>
            <div class="text-center" v-else>---</div>
          </template>
          <template #cell(edit)="{ item }">
            <span
              class="clickable text-danger d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Delete this Entry', variant: 'danger' }"
              @click="deleteItem((item as any).name)"
            >
              <font-awesome-icon :icon="['fas', 'trash-alt']" />
            </span>
          </template>
        </BTable>
      </BContainer>
    </div>
    <AddWalletEntryWizard
      v-if="showAddWalletEntryWizard"
      @hide-wallet-entry-wizard="showAddWalletEntryWizard = false"
    />
    <SendAdaModal />
    <BModal
      id="modal-staking-address"
      v-model="showStakingModal"
      :title="stakingAddressModalTitle"
      :no-close-on-backdrop="true"
      size="lg"
      @hidden="resetStakingReview"
    >
      <div v-if="stakingError" class="alert alert-danger" role="alert">{{ stakingError }}</div>
      <template #footer>
        <BButton variant="secondary" @click="showStakingModal = false">Close</BButton>
        <BButton variant="primary" :disabled="stakingBusy || !stakingFeesAccountState || !chainReady || (stakingAddressForm.isDelegation && !stakingAddressForm.poolId.trim())" @click="reviewStaking">Submit</BButton>
      </template>
      <fieldset :disabled="stakingBusy">
      <BFormGroup
        label="Fees Account"
        label-for="staking-fees-account-select"
        label-cols-md="2"
        label-align="right"
      >
        <BFormSelect
          aria-describedby="staking-fees-account-live-feedback"
          v-model="stakingAddressForm.stakingFeesAccount"
          :options="stakingFeesOptions"
          :state="stakingFeesAccountState"
        >
          <template #first>
            <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
          </template>
        </BFormSelect>
        <BFormInvalidFeedback id="staking-fees-account-live-feedback">
          Account must hold enough to pay fees and/or deposit.
        </BFormInvalidFeedback>
      </BFormGroup>
      <BFormGroup v-if="stakingAddressForm.isDelegation" label="Pool ID" label-cols-md="2">
        <BFormInput v-model="stakingAddressForm.poolId" placeholder="pool1… or pool key hash" />
      </BFormGroup>
      </fieldset>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { find } from 'lodash-es'
import {
  BButton,
  BContainer,
  BRow,
  BCol,
  BFormGroup,
  BInputGroup,
  BFormInput,
  BTable,
  BModal,
  BFormSelect,
  BFormSelectOption,
  BFormInvalidFeedback,
  BFormCheckbox
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { hex2ascii, generateUUID } from '@/utils/filters'
import AddWalletEntryWizard from '@/components/AddWalletEntryWizard.vue'
import SendAdaModal from '@/components/SendAdaModal.vue'
import type { WalletItem } from '@/types'

const store = useJorManagerStore()
const { walletItems } = storeToRefs(store)
const chainReady = computed(() => store.connected && !!store.walletNetworkKey &&
  store.chainReadState?.networkKey === store.walletNetworkKey && store.chainReadState?.walletReady === true)
const emitter = useEventBus()

const showAddWalletEntryWizard = ref(false)
const showStakingModal = ref(false)
const stakingAddressModalTitle = ref('')
const filter = ref<string>('')
const filterOn = ref<string[]>(['name', 'type', 'paymentAddr', 'stakingAddr'])
const selectedClaimIds = ref<Set<number>>(new Set())
const pendingAction = ref<string | null>(null)
const stakingError = ref('')
const stakingBusy = ref(false)
let stakingGeneration = 0
let stakingRequestId = ''
function stakingSignature() { return JSON.stringify(stakingAddressForm.value) }
function stakingTargetReady() {
  const form = stakingAddressForm.value
  const item = walletItems.value.find(entry => entry.id === form.id)
  return !!item && balanceReady(item) &&
    (form.isDelegation ? item.type === 'stake' && item.stakingAddrRegistered === true :
      item.stakingAddrRegistered === !form.isRegistration)
}

function resetStakingReview() {
  stakingGeneration++
  stakingRequestId = ''
  stakingBusy.value = false
  stakingError.value = ''
  stakingAddressForm.value.stakingFeesAccount = null
}

function reviewStaking() {
  if (stakingBusy.value || !chainReady.value || !stakingTargetReady() || !stakingFeesAccountState.value ||
      (stakingAddressForm.value.isDelegation && !stakingAddressForm.value.poolId.trim())) return
  stakingRequestId ||= generateUUID()
  const form = stakingAddressForm.value
  emitter.emit('show-spending-password-modal', { action: 'submit-wallet-stake', data: {
    requestId: stakingRequestId, signature: stakingSignature(), generation: stakingGeneration,
    networkKey: store.walletNetworkKey, chainGeneration: store.chainReadState?.generation,
    request: { id: form.id, stakingFeesAccount: form.stakingFeesAccount,
      ...(form.isDelegation ? { poolId: form.poolId.trim() } : { isRegistration: form.isRegistration }) }
  } })
}

async function submitStaking(data: { password: string; originalData: unknown }) {
  const pending = data.originalData as { requestId: string; signature: string; generation: number; networkKey: string | null; chainGeneration: string | undefined; request: unknown }
  if (stakingBusy.value || !showStakingModal.value || !chainReady.value || !stakingTargetReady() || !stakingFeesAccountState.value ||
      pending.generation !== stakingGeneration || pending.requestId !== stakingRequestId ||
      pending.signature !== stakingSignature() || pending.networkKey !== store.walletNetworkKey ||
      pending.chainGeneration !== store.chainReadState?.generation) return
  stakingBusy.value = true
  stakingError.value = ''
  try {
    const result = await store.submitWallet(pending.requestId, 'stake', pending.request, data.password)
    if (result.state !== 'submitted' && result.state !== 'confirmed') {
      throw new Error(`Transaction ${result.txId}: ${result.state}`)
    }
    if (pending.generation === stakingGeneration) {
      store.toastInfo = { title: 'Transaction submitted', message: result.txId }
      showStakingModal.value = false
    }
  } catch (e) {
    if (pending.generation === stakingGeneration) stakingError.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (pending.generation === stakingGeneration) stakingBusy.value = false
  }
}

const stakingAddressForm = ref({
  id: -1,
  stakingFeesAccount: null as number | null,
  isRegistration: false,
  isDelegation: false,
  poolId: ''
})
watch(stakingSignature, () => { stakingRequestId = '' }, { flush: 'sync' })

const fields = [
  { key: 'name', label: 'Name', sortable: true },
  { key: 'type', label: 'Item Type', sortable: true },
  { key: 'paymentAddr', label: 'Payment Address' },
  { key: 'paymentAddrLovelace', label: 'Balance', class: 'text-right' },
  { key: 'stakingAddr', label: 'Reward Address' },
  { key: 'stakingAddrLovelace', label: 'Rewards' },
  { key: 'edit', label: '' }
]

function exactInteger(value: unknown): bigint | null {
  if (typeof value !== 'string' && typeof value !== 'bigint') return null
  const text = String(value)
  return /^-?\d+$/.test(text) ? BigInt(text) : null
}

function positive(value: unknown): boolean {
  const amount = exactInteger(value)
  return amount !== null && amount > 0n
}

function balanceReady(item: WalletItem): boolean {
  return chainReady.value && item.paymentAddrLovelace !== null
}

function canSend(item: WalletItem): boolean {
  return balanceReady(item) && item.type !== 'address' && item.type !== 'pledge' && positive(item.paymentAddrLovelace)
}

function canClaim(item: WalletItem): boolean {
  return balanceReady(item) && item.type === 'stake' && positive(item.stakingAddrLovelace)
}

function formatAda(value: unknown): string {
  const amount = exactInteger(value)
  if (amount === null) return 'Unavailable'
  const sign = amount < 0n ? '-' : ''
  const absolute = amount < 0n ? -amount : amount
  return `${sign}₳${absolute / 1000000n}.${(absolute % 1000000n).toString().padStart(6, '0')}`
}

const stakingFeesAccountState = computed(() => stakingFeesOptions.value.some(option => option.value === stakingAddressForm.value.stakingFeesAccount))
const stakingFeesOptions = computed(() => walletItems.value
  .filter(item => chainReady.value && item.type !== 'address' && item.hasPaymentKeys && positive(item.paymentAddrLovelace))
  .map(item => ({ value: item.id, text: `${item.name} - ${formatAda(item.paymentAddrLovelace)}` }))
  .sort((a, b) => a.text.localeCompare(b.text)))

// Compute filtered wallet items based on current filter
const filteredWalletItems = computed(() => {
  if (!filter.value) return walletItems.value
  const searchLower = filter.value.toLowerCase()
  return walletItems.value.filter(item => {
    const searchableFields = filterOn.value.length > 0 ? filterOn.value : Object.keys(item)
    return searchableFields.some(field => {
      const val = (item as any)[field]
      return val && String(val).toLowerCase().includes(searchLower)
    })
  })
})

// Count only visible selected items for button label
const visibleSelectedCount = computed(() => {
  return filteredWalletItems.value.filter(item =>
    selectedClaimIds.value.has(item.id) && canClaim(item)
  ).length
})

const hasSelectedClaims = computed(() => visibleSelectedCount.value > 0)

// Clear selections when filter changes
watch(filter, () => {
  selectedClaimIds.value = new Set()
})

watch(chainReady, ready => {
  if (!ready) selectedClaimIds.value = new Set()
})

watch(() => store.connected, connected => {
  if (connected) store.fetchWalletItems()
})

function findWalletItemByName(name: string): WalletItem | undefined {
  return find(walletItems.value, ['name', name])
}

function hasNativeAssets(name: string): boolean {
  const item = findWalletItemByName(name)
  return item ? Object.keys(item.nativeAssetMap || {}).length > 0 : false
}

function getNativeAssetKeys(name: string): string[] {
  const item = findWalletItemByName(name)
  return item ? Object.keys(item.nativeAssetMap || {}).sort() : []
}

function getNativeAssetAmount(name: string, assetName: string): string {
  const item = findWalletItemByName(name)
  return String(item?.nativeAssetMap?.[assetName] ?? 'Unavailable')
}

function copyToClipboard(value: string) {
  navigator.clipboard.writeText(value).then(
    () => {
      store.toastInfo = { title: 'Payment Address', message: 'Copied to clipboard' }
    },
    () => {
      store.toastError = { title: 'Payment Address', message: 'Copy to clipboard failed.' }
    }
  )
}

function sendAdaClick(name: string) {
  const item = findWalletItemByName(name)
  if (item && canSend(item)) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: false })
  }
}

function claimAdaClick(name: string) {
  const item = findWalletItemByName(name)
  if (item && canClaim(item)) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: true })
  }
}

function toggleClaimSelection(id: number) {
  const item = walletItems.value.find(item => item.id === id)
  if (!item || !canClaim(item)) return
  const newSet = new Set(selectedClaimIds.value)
  if (newSet.has(id)) {
    newSet.delete(id)
  } else {
    newSet.add(id)
  }
  selectedClaimIds.value = newSet
}

function claimMultipleClick() {
  const items = filteredWalletItems.value.filter(item =>
    selectedClaimIds.value.has(item.id) && canClaim(item)
  )
  if (items.length > 0) {
    emitter.emit('show-send-ada-modal', { walletItems: items, isClaim: true, isMultiClaim: true })
    selectedClaimIds.value = new Set()
  }
}

function deleteItem(name: string) {
  const item = findWalletItemByName(name)
  if (!item) return
  emitter.emit('show-spending-password-modal', { action: 'delete-wallet-item', data: item.id })
}

function backupClicked() {
  pendingAction.value = 'backup'
  emitter.emit('show-spending-password-modal', { action: 'backup' })
}

function registerStakingAddress(name: string) {
  const item = findWalletItemByName(name)
  if (!item || !balanceReady(item) || item.stakingAddrRegistered !== false) return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isDelegation = false
  stakingAddressForm.value.isRegistration = true
  stakingAddressModalTitle.value = 'Register Staking Address'
  showStakingModal.value = true
}

function deregisterStakingAddress(name: string) {
  const item = findWalletItemByName(name)
  if (!item || !balanceReady(item) || item.stakingAddrRegistered !== true) return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isDelegation = false
  stakingAddressForm.value.isRegistration = false
  stakingAddressModalTitle.value = 'Deregister Staking Address (note: Ensure any 500 ada pool deposits have already been refunded)'
  showStakingModal.value = true
}

function delegateStakingAddress(name: string) {
  const item = findWalletItemByName(name)
  if (!item || !balanceReady(item) || item.stakingAddrRegistered !== true || item.type !== 'stake') return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isDelegation = true
  stakingAddressForm.value.poolId = ''
  stakingAddressModalTitle.value = 'Delegate Staking Address'
  showStakingModal.value = true
}


function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action === 'submit-wallet-stake') {
    void submitStaking(data)
  } else if (data.action === 'backup') {
    store.downloadBackup(data.password)
  } else if (data.action === 'delete-wallet-item') {
    store.deleteWalletItem({
      id: data.originalData as number,
      spendingPassword: data.password
    })
  }
}

onMounted(() => {
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

/* Fix checkbox positioning in rewards column */
.rewards-checkbox {
  position: relative !important;
  display: inline-flex !important;
  margin: 0 !important;
  padding: 0 !important;
}

.rewards-checkbox :deep(.form-check-input) {
  position: relative !important;
  margin: 0 !important;
}
</style>