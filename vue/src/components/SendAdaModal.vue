<template>
  <div>
    <BModal
      id="modal-send-ada"
      v-model="isVisible"
      :title="modalTitle"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent
      @hidden="clearFormSendAda"
    >
      <template #footer="{ cancel }">
        <div class="w-100 d-flex justify-content-between align-items-center flex-wrap gap-2">
          <BButton variant="outline-primary" :disabled="reviewing || isDefragment" @click="addPaymentEntry">
            <font-awesome-icon :icon="['fas', 'plus']" class="me-1" aria-hidden="true" />Add Entry
          </BButton>
          <div class="d-flex gap-2">
            <BButton variant="secondary" @click="cancel()">Cancel</BButton>
            <BButton variant="primary" :disabled="reviewing || !store.connected || !sourcesReady || !feeReady" @click="reviewSend">
              {{ reviewing ? 'Submitting…' : isDefragment ? 'Defragment UTxOs' : 'Send' }}
            </BButton>
          </div>
        </div>
      </template>

      <div v-if="reviewError" class="alert alert-danger" role="alert">{{ reviewError }}</div>

      <!-- Transaction Telemetry Summary Strip -->
      <div class="jm-tx-strip mb-3">
        <div class="jm-sub-panel jm-tx-stat">
          <span class="jm-kpi-label">{{ formSendAda.isClaim ? 'Claiming Total' : 'Available Source' }}</span>
          <span class="jm-mono fw-bold">{{ formatAda(sendingAmount) }}</span>
        </div>
        <div class="jm-sub-panel jm-tx-stat">
          <span class="jm-kpi-label">Calculated Tx Fee</span>
          <span v-if="calculatingFee" class="jm-mono fw-bold text-info" data-testid="fee-calculating-label">Calculating…</span>
          <span v-else class="jm-mono fw-bold text-warning">{{ formatAda(store.txFee) }}</span>
        </div>
        <div class="jm-sub-panel jm-tx-stat">
          <span class="jm-kpi-label">Remaining Change</span>
          <span class="jm-mono fw-bold text-success">{{ formatAda(calculateSpent(paymentEntries.length).remaining.ada || 0n) }}</span>
        </div>
      </div>

      <div
        v-if="formSendAda.isClaim && calculatingFee"
        class="jm-sub-panel mb-3 small text-info"
        data-testid="tx-plan-loading"
        role="status"
        aria-live="polite"
      >
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
          <span>
            <font-awesome-icon :icon="['fas', 'layer-group']" class="me-1" aria-hidden="true" />
            <strong>Calculating claim fee…</strong>
          </span>
        </div>
        <progress class="jm-budget-progress mt-2" aria-label="Calculating claim fee" />
      </div>

      <BForm ref="sendAdaForm" @submit.stop.prevent>
        <fieldset :disabled="reviewing">
          <div v-if="!formSendAda.isClaim" class="jm-sub-panel mb-3">
            <BFormGroup label="Coin Selection" label-for="wallet-selection-mode" label-cols-md="3" label-align-md="right" class="mb-1">
              <BFormRadioGroup id="wallet-selection-mode" aria-label="Coin selection" v-model="formSendAda.selectionMode" class="pt-2" data-testid="selection-mode-group">
                <BFormRadio value="STANDARD" class="me-4">
                  <font-awesome-icon :icon="['fas', 'dice-d20']" class="me-1 text-primary" />
                  Standard (Random-Improve)
                </BFormRadio>
                <BFormRadio value="DEFRAGMENT" :disabled="!canDefragmentSource">
                  <font-awesome-icon :icon="['fas', 'compress-arrows-alt']" class="me-1 text-info" />
                  Defragment UTxOs (Unfrack)
                </BFormRadio>
              </BFormRadioGroup>
              <small class="text-secondary d-block mt-1">
                <span v-if="isDefragment">
                  Sweeps 100% of the selected inputs back to {{ fromWalletItems[0]?.name || 'this wallet' }}, consolidating pure ADA into a single UTxO and grouping native tokens by policy.
                </span>
                <span v-else-if="!canDefragmentSource">
                  Uses Cardano SDK Random-Improve with Largest-First fallback. This wallet already holds a single pure-ADA UTxO.
                </span>
                <span v-else>
                  Uses Cardano SDK Random-Improve with Largest-First fallback to preserve wallet concurrency.
                </span>
              </small>
            </BFormGroup>

            <div
              v-if="calculatingFee"
              class="mt-3 pt-2 border-top small text-info"
              data-testid="tx-plan-loading"
              role="status"
              aria-live="polite"
            >
              <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                <span>
                  <font-awesome-icon :icon="['fas', 'layer-group']" class="me-1" aria-hidden="true" />
                  <strong>{{ isDefragment ? 'Planning UTxO defragmentation & fee…' : 'Calculating transaction plan & fee…' }}</strong>
                </span>
              </div>
              <progress
                class="jm-budget-progress mt-2"
                :aria-label="isDefragment ? 'Planning UTxO defragmentation and fee' : 'Calculating transaction plan and fee'"
              />
            </div>

            <div
              v-else-if="feeReady && store.planInputCount > 0"
              class="mt-3 pt-2 border-top small"
              :class="store.planSelectionMode === 'DEFRAGMENT' ? 'text-info' : 'text-light'"
              data-testid="tx-plan-summary"
              role="status"
              aria-live="polite"
            >
              <div class="d-flex flex-wrap justify-content-between align-items-center gap-2">
                <span>
                  <font-awesome-icon :icon="['fas', 'layer-group']" class="me-1" aria-hidden="true" />
                  <strong>Planned UTxOs:</strong>
                  <span class="jm-mono ms-1">{{ store.planInputCount }} input{{ store.planInputCount === 1 ? '' : 's' }} &rarr; {{ store.planOutputCount }} output{{ store.planOutputCount === 1 ? '' : 's' }}</span>
                </span>
                <span class="jm-mono" title="Compared with the default transaction size limit">
                  <strong>Estimated Tx Size:</strong> {{ store.planEstimatedTxSize.toLocaleString() }} / {{ defaultMaxTxBytes.toLocaleString() }} bytes
                  ({{ txSizePercent.toFixed(1) }}%)
                </span>
              </div>
              <progress
                class="jm-budget-progress mt-2"
                :class="txSizeBarClass"
                :value="Math.min(defaultMaxTxBytes, store.planEstimatedTxSize)"
                :max="defaultMaxTxBytes"
                :aria-label="`Estimated transaction size: ${store.planEstimatedTxSize.toLocaleString()} of ${defaultMaxTxBytes.toLocaleString()} bytes`"
              />
            </div>
          </div>

          <div class="accordion" role="tablist">
            <BCard
              no-body
              class="mb-3"
              v-for="(toAccount, index) in isDefragment ? [] : formSendAda.toAccounts"
              :key="index"
            >
              <BCardHeader header-tag="header" class="p-0" role="tab">
                <BButton
                  block
                  @click="toggleAccordion(index)"
                  :variant="(headerVariant(index, toAccount) as any)"
                  class="text-start d-flex align-items-center justify-content-between w-100 p-2 px-3 text-wrap"
                >
                  <div class="d-flex align-items-center flex-wrap gap-2 me-3 jm-mono small">
                    <span v-if="feePayerId === toAccount.account && toAccount.destination === 'account'" class="jm-pill jm-pill-cyan">Fee payer</span>
                    <span>Available: {{ entryAvailableLabel(index, toAccount.currency) }} | Remaining: {{ entryRemainingLabel(index, toAccount.currency) }}</span>
                  </div>
                  <div class="d-flex align-items-center">
                    <span
                      class="clickable me-3"
                      v-show="index > 0"
                      @click.stop="removePaymentEntry(index)"
                      title="Remove Entry"
                    >✕</span>
                    <font-awesome-icon
                      :icon="['fas', 'chevron-down']"
                      class="transition-transform"
                      :class="{ 'rotate-180': openIndex === index }"
                    />
                  </div>
                </BButton>
              </BCardHeader>
              <BCollapse
                :id="'accordion-' + index"
                :model-value="openIndex === index"
                role="tabpanel"
              >
                <BCardBody>
                  <BFormGroup
                    label="Currency"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                  >
                    <BFormSelect
                      v-model="toAccount.currency"
                      :state="currencyState(toAccount.currency)"
                      :options="currencyOptions"
                      @change="onCurrencyChange(toAccount)"
                    />
                  </BFormGroup>

                  <BFormGroup
                    label="Destination"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                  >
                    <BFormRadioGroup
                      v-model="toAccount.destination"
                      :state="destinationState(toAccount.destination)"
                      @change="onDestinationChange(toAccount)"
                      class="pt-2"
                    >
                      <BFormRadio value="account" class="me-3">Account</BFormRadio>
                      <BFormRadio value="address">Address</BFormRadio>
                    </BFormRadioGroup>
                  </BFormGroup>

                  <BFormGroup
                    label="To Account"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                    v-show="toAccount.destination === 'account'"
                  >
                    <BFormSelect
                      v-model="toAccount.account"
                      :state="accountState(toAccount)"
                      :options="paymentOptions"
                    >
                      <template #first>
                        <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
                      </template>
                    </BFormSelect>
                  </BFormGroup>

                  <BFormGroup
                    label="To Address"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                    v-show="toAccount.destination === 'address'"
                  >
                    <BFormInput
                      v-model="toAccount.address"
                      :state="addressState(toAccount)"
                      placeholder="e.g. addr1v805z8cn8z...xrrqj4t30l"
                      class="jm-mono"
                      trim
                    />
                    <BFormInvalidFeedback>Enter a valid wallet address.</BFormInvalidFeedback>
                  </BFormGroup>

                  <BFormGroup
                    label="Entry Type"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                  >
                    <BFormRadioGroup
                      v-model="toAccount.type"
                      :state="typeState(toAccount.type)"
                      @change="onTypeChange(toAccount)"
                      class="pt-2"
                    >
                      <BFormRadio value="amount" class="me-3">
                        <font-awesome-icon :icon="['fas', 'weight-hanging']" />&nbsp;Amount
                      </BFormRadio>
                      <BFormRadio value="percent">
                        <font-awesome-icon :icon="['fas', 'balance-scale-right']" />&nbsp;Percent
                      </BFormRadio>
                    </BFormRadioGroup>
                  </BFormGroup>

                  <BFormGroup
                    label="Amount"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                    v-show="toAccount.type === 'amount'"
                  >
                    <BFormInput
                      v-model="toAccount.amount"
                      :state="amountState(index, toAccount.amount, toAccount.currency)"
                      :placeholder="amountPlaceholder(toAccount.currency)"
                      class="jm-mono"
                      trim
                    />
                    <BFormInvalidFeedback>
                      Enter an amount between {{ minAmountLabel(toAccount.currency) }} and {{ amountRemainingLabel(index, toAccount.currency) }}
                    </BFormInvalidFeedback>
                  </BFormGroup>

                  <BFormGroup
                    label="Percent"
                    label-cols-md="3"
                    label-align-md="right"
                    class="mb-3"
                    v-show="toAccount.type === 'percent'"
                  >
                    <BFormInput
                      v-model="toAccount.percent"
                      type="range"
                      min="0"
                      max="100"
                      step="1"
                      :state="percentState(index, toAccount.percent, toAccount.currency)"
                    />
                    <p class="text-center jm-mono mt-2 mb-0">{{ percentLabel(index, toAccount.percent, toAccount.currency) }}</p>
                  </BFormGroup>
                </BCardBody>
              </BCollapse>
            </BCard>

            <!-- Metadata Card -->
            <BCard no-body class="mb-1">
              <BCardHeader header-tag="header" class="p-1" role="tab">
                <BButton
                  block
                  @click="toggleMetadata"
                  :variant="metadataState ? 'outline-success' : 'outline-danger'"
                  class="text-start d-flex align-items-center justify-content-between w-100 p-2 px-3"
                >
                  <span>Transaction Metadata (Optional JSON)</span>
                  <font-awesome-icon
                    :icon="['fas', 'chevron-down']"
                    class="transition-transform"
                    :class="{ 'rotate-180': metadataOpen }"
                  />
                </BButton>
              </BCardHeader>
              <BCollapse id="accordion-metadata" v-model="metadataOpen" accordion="accounts-accordion" role="tabpanel">
                <BCardBody>
                  <BFormGroup label="Metadata" label-cols-md="3" label-align-md="right" class="mb-0">
                    <BFormTextarea
                      v-model="formSendAda.metadata"
                      :state="metadataState"
                      rows="5"
                      class="jm-mono"
                      :placeholder="metadataPlaceholder"
                    />
                    <BFormInvalidFeedback>{{ metadataError }}</BFormInvalidFeedback>
                  </BFormGroup>
                </BCardBody>
              </BCollapse>
            </BCard>
          </div>
        </fieldset>
      </BForm>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import bs58 from 'bs58check'
import {
  BModal,
  BButton,
  BForm,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption,
  BFormRadioGroup,
  BFormRadio,
  BFormTextarea,
  BFormInvalidFeedback,
  BCard,
  BCardHeader,
  BCardBody,
  BCollapse
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { generateUUID } from '@/utils/filters'
import type { SelectionMode, WalletItem } from '@/types'

interface ToAccount {
  currency: string
  destination: 'account' | 'address'
  account: number | null
  address: string
  type: 'amount' | 'percent' | null
  amount: string | null
  percent: number
}

const defaultMaxTxBytes = 16_384

const store = useJorManagerStore()
const { walletItems } = storeToRefs(store)
const emitter = useEventBus()

const isVisible = ref(false)
const reviewing = computed(() => store.walletSubmitting)
const reviewError = ref('')

function formSignature() {
  const form = formSendAda.value
  return JSON.stringify([form.fromId, form.fromIds, form.isClaim, form.selectionMode, form.toAccounts, form.metadata])
}

function reviewSend() {
  if (reviewing.value || !store.connected || !sourcesReady.value || !feeReady.value) return
  if (!metadataState.value || (!isDefragment.value && paymentEntries.value.some((entry, index) => headerVariant(index, entry) !== 'success'))) {
    reviewError.value = 'Complete all payment entries and correct any invalid metadata.'
    return
  }
  emitter.emit('show-spending-password-modal', { action: 'submit-wallet-send', data: store.responseFeesUUID })
}

function onPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action !== 'submit-wallet-send' || reviewing.value || !isVisible.value ||
      !store.connected || !sourcesReady.value || !feeReady.value || data.originalData !== store.responseFeesUUID || !metadataState.value ||
      (!isDefragment.value && paymentEntries.value.some((entry, index) => headerVariant(index, entry) !== 'success'))) return
  const toAccounts = paymentEntries.value.map((entry, index) => ({
    currency: entry.currency,
    account: entry.destination === 'account' ? entry.account : -1,
    address: entry.destination === 'address' ? entry.address : '',
    type: entry.type,
    amount: String(calculateSpent(index + 1).amount[entry.currency] || 0n),
    percent: entry.type === 'percent' ? Number(entry.percent) : null,
    tokenFee: store.tokenFees[index] || '0'
  }))
  store.submitTransaction({
    spendingPassword: data.password, fromId: formSendAda.value.fromId,
    fromIds: [...formSendAda.value.fromIds], isClaim: formSendAda.value.isClaim,
    selectionMode: formSendAda.value.selectionMode,
    txFee: store.txFee, tokenKeepFee: store.tokenKeepFee, toAccounts, metadata: formSendAda.value.metadata
  })
}

watch(() => store.toastSuccess, toast => {
  if (toast?.title === 'Ada Sent') isVisible.value = false
})

const feeReady = computed(() => !!store.requestFeesUUID && store.requestFeesUUID === store.responseFeesUUID &&
  store.planSelectionMode === formSendAda.value.selectionMode)

const calculatingFee = computed(() => isVisible.value && store.connected && !!store.requestFeesUUID &&
  store.requestFeesUUID !== store.responseFeesUUID)

const txSizePercent = computed(() => (store.planEstimatedTxSize / defaultMaxTxBytes) * 100)

const txSizeBarClass = computed(() => {
  if (txSizePercent.value >= 90) return 'danger'
  if (txSizePercent.value >= 70) return 'warning'
  return 'normal'
})

const formSendAda = ref({
  fromId: null as number | null,
  fromIds: [] as number[],
  isClaim: false,
  isMultiClaim: false,
  selectionMode: 'STANDARD' as SelectionMode,
  toAccounts: [] as ToAccount[],
  metadata: null as string | null
})

const isDefragment = computed(() => formSendAda.value.selectionMode === 'DEFRAGMENT')
const paymentEntries = computed<ToAccount[]>(() => isDefragment.value
  ? [{ ...createEmptyToAccount(), account: formSendAda.value.fromId, type: 'percent', percent: 100 }]
  : formSendAda.value.toAccounts)
watch(formSignature, scheduleFeeCalculation, { flush: 'sync' })

const fromWalletItems = computed(() => formSendAda.value.fromIds
  .map(id => walletItems.value.find(item => item.id === id))
  .filter((item): item is NonNullable<typeof item> => item != null))
const fromWalletItem = computed(() => fromWalletItems.value[0] ?? {
  id: 0, paymentAddrLovelace: null, paymentAddrUtxoCount: null, stakingAddrLovelace: null, nativeAssetMap: null
})
const canDefragmentSource = computed(() => {
  const item = fromWalletItem.value
  const hasNativeAssets = Object.keys(item.nativeAssetMap || {}).length > 0
  return hasNativeAssets || item.paymentAddrUtxoCount == null || item.paymentAddrUtxoCount > 1
})
const sourcesReady = computed(() => formSendAda.value.fromIds.length > 0 &&
  fromWalletItems.value.length === formSendAda.value.fromIds.length &&
  fromWalletItems.value.every(item => formSendAda.value.isClaim
    ? item.stakingAddrLovelace !== null
    : item.paymentAddrLovelace !== null && item.nativeAssetMap !== null))

const metadataPlaceholder = `{
  "411": [
    "Strings must be less than 64",
    "Characters in length"
  ],
  "500": {
    "The": "first level must be a number",
    "and": "the max size of metadata is",
    "sixteen": "kilobytes."
  }
}`

const currencyOptions = computed(() => {
  const options = [{ value: 'ada', text: '₳ - Ada' }]
  if (fromWalletItem.value?.nativeAssetMap) {
    Object.keys(fromWalletItem.value.nativeAssetMap).forEach(key => {
      const assetName = key.substring(key.indexOf('.') + 1)
      let decoded = assetName
      try {
        decoded = store.hex2ascii(assetName)
      } catch { /* use original */ }
      options.push({ value: key, text: decoded })
    })
  }
  return options
})

const paymentOptions = computed(() => walletItems.value.map(item => ({
  value: item.id,
  text: `${item.name} - ${formatAda(item.paymentAddrLovelace)}`
})).sort((a, b) => a.text.localeCompare(b.text)))

function integer(value: string | number | bigint | null | undefined): bigint {
  if (value == null) return 0n
  const text = String(value)
  if (!/^-?\d+$/.test(text) || (typeof value === 'number' && !Number.isSafeInteger(value))) {
    throw new Error('Amount is not an exact integer')
  }
  return BigInt(text)
}

function formatAda(value: bigint | string | number | null): string {
  if (value === null) return 'Unavailable'
  const amount = integer(value)
  const sign = amount < 0n ? '-' : ''
  const absolute = amount < 0n ? -amount : amount
  return `${sign}₳${absolute / 1000000n}.${(absolute % 1000000n).toString().padStart(6, '0')}`
}

const sendingAmount = computed(() => formSendAda.value.isMultiClaim
  ? fromWalletItems.value.reduce((sum, item) => sum + integer(item.stakingAddrLovelace), 0n)
  : integer(formSendAda.value.isClaim ? fromWalletItem.value.stakingAddrLovelace : fromWalletItem.value.paymentAddrLovelace))

const modalTitle = computed(() => {
  const prefix = formSendAda.value.isMultiClaim
    ? `Claiming from ${fromWalletItems.value.length} addresses`
    : formSendAda.value.isClaim ? 'Claiming' : isDefragment.value ? 'Defragmenting' : 'Sending'
  const feeLabel = calculatingFee.value ? 'Calculating…' : formatAda(store.txFee)
  return `${prefix}: ${formatAda(sendingAmount.value)}, TxFee: ${feeLabel}, Remaining: ${formatAda(calculateSpent(paymentEntries.value.length).remaining.ada || 0n)}`
})

// Pure computed metadata validation (resolves vue/no-side-effects-in-computed-properties warnings)
const metadataValidation = computed<{ valid: boolean; error: string | null }>(() => {
  if (!formSendAda.value.metadata) return { valid: true, error: null }
  try {
    const parsed = JSON.parse(formSendAda.value.metadata)
    const error = validateMetadataItem(parsed)
    return error ? { valid: false, error } : { valid: true, error: null }
  } catch {
    return { valid: false, error: 'Invalid JSON' }
  }
})

const metadataState = computed(() => metadataValidation.value.valid)
const metadataError = computed(() => metadataValidation.value.error)

const openIndex = ref<number>(0)
const metadataOpen = ref(false)

function clearFormSendAda() {
  reviewError.value = ''
  formSendAda.value = {
    fromId: null,
    fromIds: [],
    isClaim: false,
    isMultiClaim: false,
    selectionMode: 'STANDARD',
    toAccounts: [createEmptyToAccount()],
    metadata: null
  }
  openIndex.value = 0
  metadataOpen.value = false
  store.invalidateSendAdaFees()
  store.txFee = '0'
  store.tokenFees = []
  store.tokenKeepFee = '0'
  store.tokenLocked = '0'
}

function createEmptyToAccount(): ToAccount {
  return {
    currency: 'ada',
    destination: 'account',
    account: null,
    address: '',
    type: null,
    amount: null,
    percent: 0
  }
}

function showSendAdaModal(walletItem: WalletItem, isClaim: boolean, isDefragment?: boolean) {
  clearFormSendAda()
  formSendAda.value.fromId = walletItem.id
  formSendAda.value.fromIds = [walletItem.id]
  formSendAda.value.isClaim = isClaim
  if (!isClaim && isDefragment) formSendAda.value.selectionMode = 'DEFRAGMENT'
  isVisible.value = true
  scheduleFeeCalculation()
}

function showMultiClaimModal(items: WalletItem[]) {
  clearFormSendAda()
  formSendAda.value.fromId = items[0].id
  formSendAda.value.fromIds = items.map(item => item.id)
  formSendAda.value.isClaim = true
  formSendAda.value.isMultiClaim = true
  isVisible.value = true
  scheduleFeeCalculation()
}

function addPaymentEntry() {
  formSendAda.value.toAccounts.push(createEmptyToAccount())
  openIndex.value = formSendAda.value.toAccounts.length - 1
  metadataOpen.value = false
}

function toggleAccordion(index: number) {
  openIndex.value = openIndex.value === index ? -1 : index
  if (openIndex.value !== -1) {
    metadataOpen.value = false
  }
}

function toggleMetadata() {
  metadataOpen.value = !metadataOpen.value
  if (metadataOpen.value) {
    openIndex.value = -1
  }
}

function removePaymentEntry(index: number) {
  formSendAda.value.toAccounts.splice(index, 1)
  if (openIndex.value === index) {
    openIndex.value = -1
  } else if (openIndex.value > index) {
    openIndex.value--
  }
}

function currencyState(currency: string): boolean {
  return currency != null
}

function destinationState(destination: string): boolean {
  return destination != null
}

function accountState(toAccount: ToAccount): boolean {
  if (toAccount.destination === 'address') return true
  if (toAccount.destination === 'account') return toAccount.account != null
  return false
}

function addressState(toAccount: ToAccount): boolean | null {
  if (toAccount.destination === 'account') return true
  if (toAccount.destination === 'address' && toAccount.address) {
    const bech32Regex = /^.*1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{53}|.{98})$/
    if (bech32Regex.test(toAccount.address)) return true
    try {
      bs58.decode(toAccount.address)
      return true
    } catch {
      return false
    }
  }
  return null
}

function typeState(type: string | null): boolean {
  return type != null
}

function amountState(index: number, amount: string | null, currency: string): boolean | null {
  if (amount != null && amount !== '') {
    const tokens = parseAmount(amount, currency)
    const remaining = calculateSpent(index + 1).remaining[currency] || 0n
    return tokens !== null && tokens > 0n && remaining >= 0n
  }
  return null
}

function percentState(index: number, percent: number, currency: string): boolean | null {
  if (percent != null && Number.isInteger(Number(percent)) && percent > 0 && percent <= 100) {
    const spent = calculateSpent(index + 1)
    const tokens = spent.amount[currency] || 0n
    const remaining = spent.remaining[currency] || 0n
    return tokens > 0n && remaining >= 0n
  }
  return null
}

function parseAmount(amount: string | null, currency: string): bigint | null {
  if (!amount) return null
  const match = currency === 'ada' ? /^(\d+)(?:\.(\d{1,6}))?$/.exec(amount.trim()) : /^(\d+)$/.exec(amount.trim())
  if (!match) return null
  return currency === 'ada'
    ? BigInt(match[1]) * 1000000n + BigInt((match[2] || '').padEnd(6, '0'))
    : BigInt(match[1])
}

function headerVariant(_index: number, toAccount: ToAccount): string {
  const isValid = currencyState(toAccount.currency) &&
    destinationState(toAccount.destination) &&
    (toAccount.destination === 'account' ? accountState(toAccount) : true) &&
    (toAccount.destination === 'address' ? addressState(toAccount) : true) &&
    typeState(toAccount.type) &&
    (toAccount.type === 'amount' ? amountState(_index, toAccount.amount, toAccount.currency) : true) &&
    (toAccount.type === 'percent' ? percentState(_index, toAccount.percent, toAccount.currency) : true)
  return isValid ? 'success' : 'danger'
}

function entryAvailableLabel(index: number, currency: string): string {
  const value = calculateSpent(index).remaining[currency] || 0n
  return currency === 'ada' ? formatAda(value) : String(value)
}

function entryRemainingLabel(index: number, currency: string): string {
  const value = calculateSpent(index + 1).remaining[currency] || 0n
  return currency === 'ada' ? formatAda(value) : String(value)
}

function amountPlaceholder(currency: string): string {
  return currency === 'ada' ? 'e.g. 10.000000' : 'e.g. 100'
}

function minAmountLabel(currency: string): string {
  return currency === 'ada' ? '₳0.000001' : '1'
}

function amountRemainingLabel(index: number, currency: string): string {
  const spent = calculateSpent(index)
  return currency === 'ada' ? formatAda(spent.remaining['ada'] || 0n) : String(spent.remaining[currency] || 0n)
}

function percentLabel(index: number, percent: number, currency: string): string {
  const amount = calculateSpent(index + 1).amount[currency] || 0n
  const formatted = currency === 'ada' ? formatAda(amount) : String(amount)
  return `${percent}% - ${formatted}`
}

function onCurrencyChange(toAccount: ToAccount) {
  toAccount.amount = null
  toAccount.percent = 0
  scheduleFeeCalculation()
}

function onDestinationChange(toAccount: ToAccount) {
  if (toAccount.destination === 'account') {
    toAccount.address = ''
  } else {
    toAccount.account = null
  }
}

function onTypeChange(toAccount: ToAccount) {
  if (toAccount.type === 'amount') {
    toAccount.percent = 0
  } else {
    toAccount.amount = null
  }
}

const feePayerId = computed(() => formSendAda.value.isClaim
  ? formSendAda.value.toAccounts.map(entry => entry.destination === 'account' ? walletItems.value.find(item => item.id === entry.account) : undefined)
    .find(item => item?.hasPaymentKeys && item.paymentAddrLovelace !== null && integer(item.paymentAddrLovelace) >= 1000000n)?.id
  : fromWalletItem.value.id)

function calculateSpent(index: number): { remaining: Record<string, bigint>, amount: Record<string, bigint> } {
  const baseAmount: Record<string, bigint> = {}
  for (const [asset, quantity] of Object.entries(fromWalletItem.value.nativeAssetMap || {})) {
    baseAmount[asset] = integer(quantity)
  }
  const rewards = formSendAda.value.isMultiClaim
    ? fromWalletItems.value.reduce((sum, item) => sum + integer(item.stakingAddrLovelace), 0n)
    : integer(fromWalletItem.value.stakingAddrLovelace)
  baseAmount.ada = formSendAda.value.isClaim
    ? rewards
    : integer(fromWalletItem.value.paymentAddrLovelace) - integer(store.txFee) - integer(store.tokenKeepFee) -
      integer(store.tokenLocked) - store.tokenFees.reduce((sum, fee) => sum + integer(fee), 0n)
  if (isDefragment.value && baseAmount.ada < 0n) baseAmount.ada = 0n

  const spentPercentages: Record<string, number> = {}
  const amount: Record<string, bigint> = {}
  for (let i = 0; i < index; i++) {
    const account = paymentEntries.value[i]
    const currency = account.currency
    amount[currency] = 0n
    if (account.type === 'amount') {
      amount[currency] = parseAmount(account.amount, currency) ?? 0n
      spentPercentages[currency] = 0
    } else if (account.type === 'percent' && account.percent > 0) {
      const previous = spentPercentages[currency] || 0
      if (previous < 100) {
        const denominator = BigInt(100 - previous)
        const numerator = (baseAmount[currency] || 0n) * BigInt(account.percent)
        amount[currency] = (numerator + denominator / 2n) / denominator
        spentPercentages[currency] = previous + Number(account.percent)
      }
    }
    baseAmount[currency] = (baseAmount[currency] || 0n) - amount[currency]
  }
  return { remaining: baseAmount, amount }
}

function validateMetadataItem(field: unknown): string | null {
  if (Array.isArray(field)) {
    for (const item of field) {
      const error = validateMetadataItem(item)
      if (error) return error
    }
  } else if (typeof field === 'string') {
    if (field.length >= 64) return 'Metadata strings must be less than 64 characters.'
  } else if (field && typeof field === 'object') {
    for (const key in field as Record<string, unknown>) {
      const error = validateMetadataItem((field as Record<string, unknown>)[key])
      if (error) return error
    }
  }
  return null
}

let feeCalculationVersion = 0
function scheduleFeeCalculation() {
  store.invalidateSendAdaFees()
  const version = ++feeCalculationVersion
  queueMicrotask(() => {
    if (version === feeCalculationVersion) prepareCalculateSendAdaFees()
  })
}

function prepareCalculateSendAdaFees() {
  store.invalidateSendAdaFees()
  if (!isVisible.value || !store.connected || !sourcesReady.value || !metadataState.value ||
      paymentEntries.value.some(entry => !currencyState(entry.currency) || !destinationState(entry.destination) ||
        !accountState(entry) || !addressState(entry) || !typeState(entry.type) ||
        (entry.type === 'amount' && (parseAmount(entry.amount, entry.currency) ?? 0n) <= 0n) ||
        (entry.type === 'percent' && (!Number.isInteger(Number(entry.percent)) || entry.percent <= 0 || entry.percent > 100)))) return
  const toAccounts = paymentEntries.value.map((entry, index) => ({
    currency: entry.currency,
    account: entry.destination === 'account' ? entry.account : -1,
    address: entry.destination === 'address' ? entry.address : '',
    amount: String(calculateSpent(index + 1).amount[entry.currency] || 0n),
    percent: entry.type === 'percent' ? Number(entry.percent) : null
  }))
  const destinations = new Set(toAccounts.map(entry => `${entry.account}:${entry.address}`))
  store.calculateSendAdaFees({
    fromId: formSendAda.value.fromId, fromIds: [...formSendAda.value.fromIds], toAccounts,
    txOut: destinations.size + (calculateSpent(toAccounts.length).remaining.ada > 0n ? 1 : 0),
    isClaim: formSendAda.value.isClaim, selectionMode: formSendAda.value.selectionMode,
    metadata: formSendAda.value.metadata, uuid: generateUUID()
  })
}

watch(
  () => {
    if (!isVisible.value) return null
    return JSON.stringify([
      store.connected,
      fromWalletItems.value.map(item => ({
        id: item.id,
        paymentAddrLovelace: item.paymentAddrLovelace,
        paymentAddrUtxoCount: item.paymentAddrUtxoCount,
        stakingAddrLovelace: item.stakingAddrLovelace,
        nativeAssetMap: item.nativeAssetMap
      }))
    ])
  },
  (next, prev) => {
    if (next !== null && next !== prev) scheduleFeeCalculation()
  },
  { flush: 'sync' }
)

function onShowSendAdaModal(data: { walletItem?: WalletItem; walletItems?: WalletItem[]; isClaim: boolean; isMultiClaim?: boolean; isDefragment?: boolean }) {
  if (data.isMultiClaim && data.walletItems && data.walletItems.length > 0) {
    showMultiClaimModal(data.walletItems)
  } else if (data.walletItem) {
    showSendAdaModal(data.walletItem, data.isClaim, data.isDefragment)
  }
}

onMounted(() => {
  clearFormSendAda()
  emitter.on('show-send-ada-modal', onShowSendAdaModal)
  emitter.on('confirm-spending-password-with-action', onPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('show-send-ada-modal', onShowSendAdaModal)
  emitter.off('confirm-spending-password-with-action', onPasswordConfirmed)
})
</script>

<style scoped>
.jm-tx-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.75rem;
}

.jm-tx-stat {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.75rem 0.95rem;
}

.jm-budget-progress {
  display: block;
  width: 100%;
  height: 7px;
  appearance: none;
  border: 0 solid;
  border-radius: 99px;
  overflow: clip;
  container-type: inline-size;
  background: rgba(148, 163, 184, 0.18);
  --progress-bar-radius: 99px;
  --progress-bar-fill: linear-gradient(90deg, var(--jm-cyan), var(--jm-indigo));
  --progress-bar-indeterminate-size: 32%;
  --progress-bar-indeterminate-animation-duration: 1.4s;
}

.jm-budget-progress.warning {
  --progress-bar-fill: var(--jm-amber);
}

.jm-budget-progress.danger {
  --progress-bar-fill: var(--jm-rose);
}

.jm-budget-progress:indeterminate {
  --progress-bar-animation: var(
    --progress-bar-indeterminate-animation,
    jm-progress-indeterminate var(--progress-bar-indeterminate-animation-duration) infinite linear
  );
}

.jm-budget-progress:dir(rtl) {
  --progress-bar-indeterminate-animation: jm-progress-indeterminate var(--progress-bar-indeterminate-animation-duration) infinite linear reverse;
}

@media (prefers-reduced-motion: reduce) {
  .jm-budget-progress {
    --progress-bar-indeterminate-animation-duration: 10s;
  }
}

.jm-budget-progress::-webkit-progress-bar {
  background: transparent;
}

.jm-budget-progress::-webkit-progress-value {
  background: var(--progress-bar-fill);
  border-radius: var(--progress-bar-radius);
  transition: inline-size 0.25s ease;
  animation: var(--progress-bar-animation, revert-rule);
}

.jm-budget-progress::-moz-progress-bar {
  background: var(--progress-bar-fill);
  border-radius: var(--progress-bar-radius);
  transition: inline-size 0.25s ease;
  animation: var(--progress-bar-animation, revert-rule);
}

@media (max-width: 720px) {
  .jm-tx-strip {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
@keyframes jm-progress-indeterminate {
  from,
  to {
    inline-size: var(--progress-bar-indeterminate-size);
    margin-right: auto;
  }

  from {
    translate: -100%;
  }

  to {
    translate: 100cqi;
  }
}
</style>
