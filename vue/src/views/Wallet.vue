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
      <div class="alert alert-warning" role="status">
        Wallet completeness and current-era evidence are not available in this view. Balances
        may be stale; do not treat them as available-to-spend until the target network's complete
        index and reviewed transaction are confirmed.
      </div>
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
                v-if="(item as any).type !== 'address' && (item as any).type !== 'pledge' && positive(value)"
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
              {{ hex2ascii(name.substring(name.indexOf('.') + 1)) }} -
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
                v-if="(item as any).stakingAddrRegistered"
                class="clickable text-success d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Registered on chain. Click to de-register.', variant: 'success' }"
                @click="deregisterStakingAddress((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'link']" />
              </span>
              <span
                v-if="!(item as any).stakingAddrRegistered"
                class="clickable text-warning d-inline-block"
                v-b-tooltip.hover.bottom="{ title: 'Not registered on chain. Click to register.', variant: 'warning' }"
                @click="registerStakingAddress((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'unlink']" />
              </span>
            </div>
            <div v-else class="text-center">---</div>
          </template>
          <template #cell(stakingAddrLovelace)="{ item, value }">
            <div class="text-right d-flex align-items-center justify-content-end gap-2" v-if="(item as any).type === 'stake'">
              <span>{{ formatAda(value) }}</span>
              <span
                v-if="positive(value)"
                class="clickable text-success flex-shrink-0"
                v-b-tooltip.hover.bottom="{ title: 'Claim Rewards', variant: 'success' }"
                @click="claimAdaClick((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'cash-register']" />
              </span>
              <BFormCheckbox
                v-if="positive(value)"
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
      :ok-disabled="true"
    >
      <div class="alert alert-warning" role="alert">
        Registration changes require current target-network era and capability evidence and a
        durable preview of the fee, deposit or refund before authorization. Preview unavailable.
      </div>
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
import { hex2ascii } from '@/utils/filters'
import AddWalletEntryWizard from '@/components/AddWalletEntryWizard.vue'
import SendAdaModal from '@/components/SendAdaModal.vue'
import type { WalletItem } from '@/types'

const store = useJorManagerStore()
const { walletItems } = storeToRefs(store)
const emitter = useEventBus()

const showAddWalletEntryWizard = ref(false)
const showStakingModal = ref(false)
const stakingAddressModalTitle = ref('')
const filter = ref<string>('')
const filterOn = ref<string[]>(['name', 'type', 'paymentAddr', 'stakingAddr'])
const selectedClaimIds = ref<Set<number>>(new Set())
const pendingAction = ref<string | null>(null)

const stakingAddressForm = ref({
  id: -1,
  spendingPassword: null as string | null,
  stakingFeesAccount: null as number | null,
  isRegistration: false
})

const fields = [
  { key: 'name', label: 'Name', sortable: true },
  { key: 'type', label: 'Item Type', sortable: true },
  { key: 'paymentAddr', label: 'Payment Address' },
  { key: 'paymentAddrLovelace', label: 'Balance', class: 'text-right', sortable: true },
  { key: 'stakingAddr', label: 'Reward Address' },
  { key: 'stakingAddrLovelace', label: 'Rewards', sortable: true },
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

function formatAda(value: unknown): string {
  const amount = exactInteger(value)
  if (amount === null) return 'Unavailable'
  const sign = amount < 0n ? '-' : ''
  const absolute = amount < 0n ? -amount : amount
  return `${sign}₳${absolute / 1000000n}.${(absolute % 1000000n).toString().padStart(6, '0')}`
}

const stakingFeesAccountState = computed(() => stakingAddressForm.value.stakingFeesAccount != null)
const stakingFeesOptions = computed(() => walletItems.value
  .filter(item => item.type !== 'address' && item.hasPaymentKeys && positive(item.paymentAddrLovelace))
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
    selectedClaimIds.value.has(item.id) &&
    (item as any).type === 'stake' &&
    positive(item.stakingAddrLovelace)
  ).length
})

const hasSelectedClaims = computed(() => visibleSelectedCount.value > 0)

// Clear selections when filter changes
watch(filter, () => {
  selectedClaimIds.value = new Set()
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
  if (item) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: false })
  }
}

function claimAdaClick(name: string) {
  const item = findWalletItemByName(name)
  if (item) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: true })
  }
}

function toggleClaimSelection(id: number) {
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
    selectedClaimIds.value.has(item.id) &&
    (item as any).type === 'stake' &&
    positive(item.stakingAddrLovelace)
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
  if (!item) return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isRegistration = true
  stakingAddressModalTitle.value = 'Register Staking Address'
  showStakingModal.value = true
}

function deregisterStakingAddress(name: string) {
  const item = findWalletItemByName(name)
  if (!item) return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isRegistration = false
  stakingAddressModalTitle.value = 'Deregister Staking Address (note: Ensure any 500 ada pool deposits have already been refunded)'
  showStakingModal.value = true
}


function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action === 'backup') {
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