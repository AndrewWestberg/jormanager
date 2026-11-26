<template>
  <div>
    <div v-if="!showAddWalletEntryWizard">
      <div class="jm-page-header">
        <div>
          <h1 class="jm-page-title">
            Wallet &amp; Treasury
          </h1>
          <p class="jm-page-subtitle">
            Exact lovelace balances, UTxO defragmentation, stake registrations, and reward claims
          </p>
        </div>
        <div class="jm-action-group">
          <BButton
            v-if="hasSelectedClaims"
            v-b-tooltip.hover.bottom="'Claim rewards from selected stake addresses'"
            variant="success"
            @click="claimMultipleClick"
          >
            <font-awesome-icon
              :icon="['fas', 'cash-register']"
              class="me-1"
              aria-hidden="true"
            />
            Claim Selected ({{ visibleSelectedCount }})
          </BButton>
          <BButton
            v-b-tooltip.hover.bottom="'Download all of your wallet and pool keys.'"
            variant="secondary"
            @click="backupClicked"
          >
            <font-awesome-icon
              :icon="['fas', 'file-archive']"
              class="me-1"
              aria-hidden="true"
            />
            Save Backup
          </BButton>
          <BButton
            v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
            variant="primary"
            @click="showAddWalletEntryWizard = true"
          >
            <font-awesome-icon
              :icon="['fas', 'plus']"
              class="me-1"
              aria-hidden="true"
            />
            Create Entry
          </BButton>
        </div>
      </div>

      <!-- Exact BigInt Portfolio Summary Strip -->
      <div
        v-if="walletItems.length > 0"
        class="jm-summary-grid-4"
      >
        <div class="jm-card jm-kpi-card">
          <span class="jm-kpi-label">Total Balance</span>
          <div class="jm-kpi-value jm-mono jm-summary-ada">
            {{ totalPaymentBalanceFormatted }}
          </div>
          <span class="small text-secondary">Exact lovelace sum</span>
        </div>
        <div class="jm-card jm-kpi-card">
          <span class="jm-kpi-label">Unclaimed Rewards</span>
          <div class="jm-kpi-value jm-mono jm-summary-ada text-success">
            {{ totalRewardsFormatted }}
          </div>
          <span class="small text-secondary">{{ claimableCount }} stake account{{ claimableCount === 1 ? '' : 's' }} claimable</span>
        </div>
        <div class="jm-card jm-kpi-card">
          <span class="jm-kpi-label">Wallet Entries</span>
          <div class="jm-kpi-value jm-mono">
            {{ walletItems.length }}
          </div>
          <span
            class="small"
            :class="fragmentedCount > 0 ? 'text-warning' : 'text-secondary'"
          >
            {{ fragmentedCount > 0 ? `${fragmentedCount} multi-UTxO wallet${fragmentedCount === 1 ? '' : 's'}` : 'All UTxOs tidy' }}
          </span>
        </div>
        <div class="jm-card jm-kpi-card">
          <span class="jm-kpi-label">Native Assets</span>
          <div
            class="jm-kpi-value jm-mono"
            style="color: var(--jm-indigo);"
          >
            {{ distinctNativeAssetCount }}
          </div>
          <span class="small text-secondary">Distinct policy assets</span>
        </div>
      </div>

      <!-- Filter & Search Bar -->
      <div class="jm-toolbar">
        <div
          class="jm-chip-group"
          role="group"
          aria-label="Filter wallet entries by type"
        >
          <button
            v-for="tab in typeFilterTabs"
            :key="tab.value"
            type="button"
            class="jm-chip-btn"
            :class="{ active: typeFilter === tab.value }"
            @click="typeFilter = tab.value"
          >
            {{ tab.label }} ({{ tab.count }})
          </button>
        </div>
        <div class="jm-wallet-search">
          <BInputGroup size="sm">
            <BFormInput
              id="filter-input"
              v-model="filter"
              type="search"
              placeholder="Search name, type, address…"
              aria-label="Filter wallet entries"
            />
            <template #append>
              <BButton
                variant="secondary"
                :disabled="!filter"
                @click="filter = ''"
              >
                Clear
              </BButton>
            </template>
          </BInputGroup>
        </div>
      </div>

      <div
        v-show="walletItems.length > 0"
        class="jm-table-wrapper"
        tabindex="0"
        role="region"
        aria-label="Wallet Entries"
      >
        <BTable
          small
          hover
          striped
          :items="displayedWalletItems"
          :fields="fields"
        >
          <template #cell(name)="{ value }">
            <span class="jm-mono fw-semibold">{{ value }}</span>
          </template>

          <template #cell(type)="{ value }">
            <span
              class="jm-pill text-capitalize"
              :class="walletTypePillClass(String(value))"
            >
              {{ value }}
            </span>
          </template>

          <template #cell(paymentAddr)="{ value }">
            <div class="jm-addr-chip jm-mono">
              <span>{{ String(value).substring(0, 14) }}...</span>
              <button
                v-b-tooltip.hover.right="{ title: 'Copy to clipboard', variant: 'secondary' }"
                type="button"
                class="jm-icon-btn cyan jm-mini-copy"
                aria-label="Copy payment address"
                @click="copyToClipboard(String(value))"
              >
                <font-awesome-icon
                  :icon="['fas', 'copy']"
                  aria-hidden="true"
                />
              </button>
            </div>
          </template>

          <template #cell(paymentAddrUtxoCount)="{ item }">
            <span
              v-if="((item as WalletItem).paymentAddrUtxoCount ?? 0) > 0"
              v-b-tooltip.hover.bottom="{ title: `${(item as WalletItem).paymentAddrUtxoCount} unspent UTxO(s)`, variant: 'secondary' }"
              class="jm-pill jm-mono jm-utxo-pill"
              :class="((item as WalletItem).paymentAddrUtxoCount ?? 0) > 20 ? 'jm-pill-amber' : 'jm-pill-slate'"
            >
              {{ (item as WalletItem).paymentAddrUtxoCount }} UTxO{{ (item as WalletItem).paymentAddrUtxoCount === 1 ? '' : 's' }}
            </span>
            <span
              v-else
              class="text-secondary"
            >—</span>
          </template>

          <template #cell(paymentAddrLovelace)="{ item, value }">
            <div class="jm-balance-row">
              <span class="jm-mono fw-semibold">{{ formatAda(value) }}</span>
              <span class="jm-balance-actions">
                <button
                  v-if="canSend(item)"
                  v-b-tooltip.hover.bottom="{ title: 'Send', variant: 'success' }"
                  type="button"
                  class="jm-icon-btn emerald"
                  aria-label="Send ADA"
                  @click="sendAdaClick(item.name)"
                >
                  <font-awesome-icon
                    :icon="['fas', 'hand-holding-usd']"
                    aria-hidden="true"
                  />
                </button>
                <BButton
                  v-if="canDefragment(item)"
                  v-b-tooltip.hover.bottom="{ title: 'Defragment UTxOs', variant: 'info' }"
                  variant="link"
                  class="jm-icon-btn cyan p-0"
                  aria-label="Defragment UTxOs"
                  data-testid="defragment-utxos-btn"
                  @click="defragmentUtxosClick(item.name)"
                >
                  <font-awesome-icon
                    :icon="['fas', 'compress-arrows-alt']"
                    aria-hidden="true"
                  />
                </BButton>
              </span>
            </div>
            <div
              v-if="hasNativeAssets(item.name)"
              class="jm-assets-list mt-2 pt-2"
            >
              <div
                v-for="(name, index) in getNativeAssetKeys(item.name)"
                :key="index"
                class="jm-pill jm-pill-indigo jm-mono"
              >
                <font-awesome-icon
                  :icon="['fas', 'coins']"
                  class="text-warning"
                  aria-hidden="true"
                />
                <span>{{ hex2ascii(name.substring(name.indexOf('.') + 1)) }}</span>
                <span class="opacity-75">({{ name.slice(0, 8) }}…)</span>
                <strong>{{ getNativeAssetAmount(item.name, name) }}</strong>
              </div>
            </div>
          </template>

          <template #cell(stakingAddr)="{ item, value }">
            <div
              v-if="value && String(value).length > 0"
              class="d-inline-flex align-items-center gap-2 flex-wrap"
            >
              <span class="jm-addr-chip jm-mono">{{ String(value).substring(0, 15) }}...</span>
              <span
                v-if="balanceReady(item) && item.stakingAddrRegistered === true"
                v-b-tooltip.hover.bottom="{ title: 'Registered on chain. Click to de-register.', variant: 'success' }"
                class="jm-pill jm-pill-emerald clickable"
                @click="deregisterStakingAddress(item.name)"
              >
                <font-awesome-icon
                  :icon="['fas', 'link']"
                  aria-hidden="true"
                />
                Registered
              </span>
              <span
                v-if="balanceReady(item) && item.stakingAddrRegistered === false"
                v-b-tooltip.hover.bottom="{ title: 'Not registered on chain. Click to register.', variant: 'warning' }"
                class="jm-pill jm-pill-amber clickable"
                @click="registerStakingAddress(item.name)"
              >
                <font-awesome-icon
                  :icon="['fas', 'unlink']"
                  aria-hidden="true"
                />
                Unregistered
              </span>
            </div>
            <div
              v-else
              class="text-secondary"
            >
              —
            </div>
          </template>

          <template #cell(stakingAddrLovelace)="{ item, value }">
            <div
              v-if="item.type === 'stake'"
              class="d-flex align-items-center justify-content-end gap-2"
            >
              <span
                class="jm-mono fw-semibold"
                :class="positive(value) ? 'text-success' : 'text-secondary'"
              >
                {{ formatAda(value) }}
              </span>
              <button
                v-if="canClaim(item)"
                v-b-tooltip.hover.bottom="{ title: 'Claim Rewards', variant: 'success' }"
                type="button"
                class="jm-icon-btn emerald flex-shrink-0"
                aria-label="Claim Rewards"
                @click="claimAdaClick(item.name)"
              >
                <font-awesome-icon
                  :icon="['fas', 'cash-register']"
                  aria-hidden="true"
                />
              </button>
              <BFormCheckbox
                v-if="canClaim(item)"
                v-b-tooltip.hover.bottom="{ title: 'Select for multi-claim', variant: 'info' }"
                :model-value="selectedClaimIds.has(item.id)"
                class="rewards-checkbox"
                @update:model-value="toggleClaimSelection(item.id)"
              />
            </div>
            <div
              v-else
              class="text-end text-secondary"
            >
              —
            </div>
          </template>

          <template #cell(edit)="{ item }">
            <button
              v-b-tooltip.hover.left="{ title: 'Delete this Entry', variant: 'danger' }"
              type="button"
              class="jm-icon-btn rose"
              aria-label="Delete this Entry"
              @click="deleteItem(item.name)"
            >
              <font-awesome-icon
                :icon="['fas', 'trash-alt']"
                aria-hidden="true"
              />
            </button>
          </template>
        </BTable>
      </div>

      <div
        v-if="walletItems.length === 0"
        class="jm-card jm-empty-state"
      >
        <div class="jm-empty-icon">
          <font-awesome-icon
            :icon="['fas', 'wallet']"
            aria-hidden="true"
          />
        </div>
        <h2 class="h6 mb-1">
          No wallet entries yet
        </h2>
        <p class="small mb-3">
          Create a payment, stake, pledge, or watch-only address entry to begin.
        </p>
        <BButton
          variant="primary"
          size="sm"
          @click="showAddWalletEntryWizard = true"
        >
          + Create Entry
        </BButton>
      </div>
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
      @hidden="resetStaking"
    >
      <template #footer>
        <BButton
          variant="secondary"
          @click="showStakingModal = false"
        >
          Close
        </BButton>
        <BButton
          variant="primary"
          :disabled="stakingBusy || !store.connected || !stakingFeesAccountState"
          @click="confirmStaking"
        >
          Submit
        </BButton>
      </template>
      <fieldset :disabled="stakingBusy">
        <BFormGroup
          label="Fees Account"
          label-for="staking-fees-account-select"
          label-cols-md="3"
          label-align="right"
        >
          <BFormSelect
            v-model="stakingAddressForm.stakingFeesAccount"
            aria-describedby="staking-fees-account-live-feedback"
            :options="stakingFeesOptions"
            :state="stakingFeesAccountState"
          >
            <template #first>
              <BFormSelectOption
                :value="null"
                disabled
              >
                -- Please select an option --
              </BFormSelectOption>
            </template>
          </BFormSelect>
          <BFormInvalidFeedback id="staking-fees-account-live-feedback">
            Account must hold enough to pay fees and/or deposit.
          </BFormInvalidFeedback>
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
const typeFilter = ref<'all' | 'payment' | 'stake' | 'pledge' | 'address'>('all')
const filterOn = ref<string[]>(['name', 'type', 'paymentAddr', 'stakingAddr'])
const selectedClaimIds = ref<Set<number>>(new Set())
const pendingAction = ref<string | null>(null)
const stakingBusy = computed(() => store.stakingSubmitting)

function resetStaking() {
  stakingAddressForm.value.stakingFeesAccount = null
}

function confirmStaking() {
  if (stakingBusy.value || !store.connected || !stakingTargetReady() || !stakingFeesAccountState.value) return
  emitter.emit('show-spending-password-modal', { action: 'submit-wallet-stake' })
}

function stakingTargetReady() {
  const form = stakingAddressForm.value
  const item = walletItems.value.find(entry => entry.id === form.id)
  return !!item && item.stakingAddrRegistered === !form.isRegistration
}

function submitStaking(data: { password: string }) {
  if (stakingBusy.value || !showStakingModal.value || !store.connected || !stakingTargetReady() || !stakingFeesAccountState.value) return
  store.updateStakingAddress({ ...stakingAddressForm.value, spendingPassword: data.password })
}

watch(() => store.toastSuccess, toast => {
  if (toast?.title === 'Update Staking Address') showStakingModal.value = false
})

const stakingAddressForm = ref({
  id: -1,
  stakingFeesAccount: null as number | null,
  isRegistration: false
})

const fields = [
  { key: 'name', label: 'Name', sortable: true },
  { key: 'type', label: 'Item Type', sortable: true },
  { key: 'paymentAddr', label: 'Payment Address' },
  { key: 'paymentAddrUtxoCount', label: 'UTxOs', thClass: 'text-center', tdClass: 'text-center', sortable: true },
  { key: 'paymentAddrLovelace', label: 'Balance', thClass: 'text-end', tdClass: 'text-end', sortable: true },
  { key: 'stakingAddr', label: 'Reward Address' },
  { key: 'stakingAddrLovelace', label: 'Rewards', thClass: 'text-end', tdClass: 'text-end' },
  { key: 'edit', label: '', thClass: 'text-end', tdClass: 'text-end' }
]

function exactInteger(value: unknown): bigint | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) ? BigInt(value) : null
  if (typeof value !== 'string' && typeof value !== 'bigint') return null
  const text = String(value)
  return /^-?\d+$/.test(text) ? BigInt(text) : null
}

function positive(value: unknown): boolean {
  const amount = exactInteger(value)
  return amount !== null && amount > 0n
}

function balanceReady(item: WalletItem): boolean {
  return store.connected && item.paymentAddrLovelace !== null
}

function canSend(item: WalletItem): boolean {
  return balanceReady(item) && item.type !== 'address' && item.type !== 'pledge' && positive(item.paymentAddrLovelace)
}

function canDefragment(item: WalletItem): boolean {
  return canSend(item) && item.hasPaymentKeys === true && item.nativeAssetMap !== null && (item.paymentAddrUtxoCount ?? 0) > 1
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

function formatAdaGrouped(amount: bigint): string {
  const sign = amount < 0n ? '-' : ''
  const absolute = amount < 0n ? -amount : amount
  const whole = (absolute / 1000000n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const fraction = (absolute % 1000000n).toString().padStart(6, '0')
  return `${sign}₳${whole}.${fraction}`
}

function walletTypePillClass(type: string): string {
  switch (type) {
    case 'stake': return 'jm-pill-emerald'
    case 'pledge': return 'jm-pill-indigo'
    case 'payment': return 'jm-pill-cyan'
    default: return 'jm-pill-slate'
  }
}

const totalPaymentBalanceFormatted = computed(() => {
  const sum = walletItems.value.reduce((acc, item) => acc + (exactInteger(item.paymentAddrLovelace) ?? 0n), 0n)
  return formatAdaGrouped(sum)
})

const totalRewardsFormatted = computed(() => {
  const sum = walletItems.value.reduce((acc, item) => {
    if (item.type !== 'stake') return acc
    return acc + (exactInteger(item.stakingAddrLovelace) ?? 0n)
  }, 0n)
  return formatAdaGrouped(sum)
})

const claimableCount = computed(() => walletItems.value.filter(item => canClaim(item)).length)

const fragmentedCount = computed(() => walletItems.value.filter(item => canDefragment(item)).length)

const distinctNativeAssetCount = computed(() => {
  const set = new Set<string>()
  for (const item of walletItems.value) {
    for (const key of Object.keys(item.nativeAssetMap || {})) set.add(key)
  }
  return set.size
})

const typeFilterTabs = computed(() => [
  { value: 'all' as const, label: 'All', count: walletItems.value.length },
  { value: 'payment' as const, label: 'Payment', count: walletItems.value.filter(i => i.type === 'payment').length },
  { value: 'stake' as const, label: 'Stake', count: walletItems.value.filter(i => i.type === 'stake').length },
  { value: 'pledge' as const, label: 'Pledge', count: walletItems.value.filter(i => i.type === 'pledge').length },
  { value: 'address' as const, label: 'Address', count: walletItems.value.filter(i => i.type === 'address').length }
])

const stakingFeesAccountState = computed(() => stakingFeesOptions.value.some(option => option.value === stakingAddressForm.value.stakingFeesAccount))
const stakingFeesOptions = computed(() => walletItems.value
  .filter(item => item.type !== 'address' && item.hasPaymentKeys && positive(item.paymentAddrLovelace))
  .map(item => ({ value: item.id, text: `${item.name} - ${formatAda(item.paymentAddrLovelace)}` }))
  .sort((a, b) => a.text.localeCompare(b.text)))

const filteredWalletItems = computed(() => {
  const byType = typeFilter.value === 'all'
    ? walletItems.value
    : walletItems.value.filter(item => item.type === typeFilter.value)
  if (!filter.value) return byType
  const searchLower = filter.value.toLowerCase()
  return byType.filter(item => {
    const searchableFields = filterOn.value.length > 0 ? filterOn.value : Object.keys(item)
    return searchableFields.some(field => {
      const val = item[field]
      return val && String(val).toLowerCase().includes(searchLower)
    })
  })
})

const displayedWalletItems = computed(() => filteredWalletItems.value)

const visibleSelectedCount = computed(() => {
  return filteredWalletItems.value.filter(item =>
    selectedClaimIds.value.has(item.id) && canClaim(item)
  ).length
})

const hasSelectedClaims = computed(() => visibleSelectedCount.value > 0)

watch([filter, typeFilter], () => {
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
  if (item && canSend(item)) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: false })
  }
}

function defragmentUtxosClick(name: string) {
  const item = findWalletItemByName(name)
  if (item && canDefragment(item)) {
    emitter.emit('show-send-ada-modal', { walletItem: item, isClaim: false, isDefragment: true })
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
  stakingAddressForm.value.isRegistration = true
  stakingAddressModalTitle.value = 'Register Staking Address'
  showStakingModal.value = true
}

function deregisterStakingAddress(name: string) {
  const item = findWalletItemByName(name)
  if (!item || !balanceReady(item) || item.stakingAddrRegistered !== true) return
  stakingAddressForm.value.id = item.id
  stakingAddressForm.value.isRegistration = false
  stakingAddressModalTitle.value = 'Deregister Staking Address (note: Ensure any 500 ada pool deposits have already been refunded)'
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
.jm-summary-ada {
  font-size: 1.25rem;
}

.jm-wallet-search {
  min-width: 240px;
  flex: 0 1 320px;
}

.jm-mini-copy {
  width: 22px;
  height: 22px;
  font-size: 0.72rem;
}

.jm-utxo-pill {
  min-width: 5.5rem;
  justify-content: center;
}

.jm-balance-row {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
  white-space: nowrap;
}

.jm-balance-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  gap: 0.2rem;
  min-width: 64px;
}

.jm-assets-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.35rem;
  border-top: 1px dashed var(--jm-border-subtle);
}

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
