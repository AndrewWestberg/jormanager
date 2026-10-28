<template>
  <div>
    <BModal
      id="modal-send-ada"
      v-model="isVisible"
      :title="modalTitle"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent
    >
      <template #footer="{ cancel }">
        <div class="w-100 d-flex justify-content-between align-items-center">
          <BButton
            variant="outline-primary"
            @click="addPaymentEntry"
            v-b-tooltip.hover.top="'Add a new payment entry.'"
          >
            +&nbsp;Add Entry
          </BButton>
          <div>
            <BButton variant="secondary" @click="cancel()" class="me-2">Cancel</BButton>
            <BButton variant="primary" disabled>Review and send unavailable</BButton>
          </div>
        </div>
      </template>
      <div class="alert alert-warning" role="alert">
        A transaction cannot be authorized until a target-network era, qualified operation,
        complete wallet state and a durable transaction preview are available. Amounts below are
        provisional; fees and final consequences have not been reviewed.
      </div>
      
      <BForm ref="sendAdaForm" @submit.stop.prevent>
        <div class="accordion" role="tablist">
          <BCard
            no-body
            class="mb-3 border-0"
            v-for="(toAccount, index) in formSendAda.toAccounts"
            :key="index"
          >
            <BCardHeader header-tag="header" class="p-0" role="tab">
              <BButton
                block
                @click="toggleAccordion(index)"
                :variant="(headerVariant(index, toAccount) as any)"
                class="text-start d-flex align-items-center justify-content-between w-100 p-2 text-wrap"
              >
                <div class="d-flex align-items-center w-100 me-3">
                  <font-awesome-icon
                    v-if="toAccount.isFeePayer"
                    :icon="['fas', 'hand-holding-usd']"
                    class="text-warning me-2"
                    v-b-tooltip.hover.bottom="'Fee Payer'"
                  />
                  <span>
                     {{ toAccount.currency === 'ada' ? 'ADA' : toAccount.currency }} — review unavailable
                  </span>
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
                    trim
                    @input="prepareCalculateSendAdaFees"
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
                    @input="prepareCalculateSendAdaFees"
                  />
                  <p class="text-center mt-2">{{ percentLabel(index, toAccount.percent, toAccount.currency) }}</p>
                </BFormGroup>
              </BCardBody>
            </BCollapse>
          </BCard>
          
          <!-- Metadata Card -->
          <BCard no-body class="mb-1 border-0">
            <BCardHeader header-tag="header" class="p-1" role="tab">
              <BButton 
                block 
                @click="toggleMetadata" 
                :variant="metadataState ? 'outline-success' : 'outline-danger'"
                class="text-start d-flex align-items-center justify-content-between w-100 p-2"
              >
                <span>Metadata</span>
                <font-awesome-icon
                  :icon="['fas', 'chevron-down']"
                  class="transition-transform"
                  :class="{ 'rotate-180': metadataOpen }" 
                />
              </BButton>
            </BCardHeader>
            <BCollapse id="accordion-metadata" v-model="metadataOpen" accordion="accounts-accordion" role="tabpanel">
              <BCardBody>
                <BFormGroup label="Metadata" label-cols-md="3" label-align-md="right" class="mb-3">
                  <BFormTextarea
                    v-model="formSendAda.metadata"
                    :state="metadataState"
                    rows="5"
                    :placeholder="metadataPlaceholder"
                    @input="prepareCalculateSendAdaFees"
                  />
                  <BFormInvalidFeedback>{{ formSendAda.metadataError }}</BFormInvalidFeedback>
                </BFormGroup>
              </BCardBody>
            </BCollapse>
          </BCard>
        </div>
      </BForm>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { find } from 'lodash-es'
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

interface ToAccount {
  currency: string
  destination: 'account' | 'address'
  account: number | null
  address: string
  type: 'amount' | 'percent' | null
  amount: string | null
  percent: number
  tokenFee: string
  isFeePayer?: boolean
}

interface WalletItemRef {
  id: number
  name: string
  paymentAddrLovelace: string
  stakingAddrLovelace: string | null
  nativeAssetMap: Record<string, string>
}

const store = useJorManagerStore()
const { walletItems, txFee, tokenKeepFee, tokenFees, tokenLocked, minUTxOValue } = storeToRefs(store)
const emitter = useEventBus()

const isVisible = ref(false)
const remainingLovelace = ref(0n)
const fromWalletItem = ref<WalletItemRef>({
  id: 0,
  name: '',
  paymentAddrLovelace: '0',
  stakingAddrLovelace: '0',
  nativeAssetMap: {}
})

const formSendAda = ref({
  spendingPassword: null as string | null,
  fromId: null as number | null,
  fromIds: [] as number[],
  isClaim: false,
  isMultiClaim: false,
  toAccounts: [] as ToAccount[],
  metadata: null as string | null,
  metadataError: null as string | null,
  valid: false
})

// Multi-claim state
const fromWalletItems = ref<WalletItemRef[]>([])

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

// Computed
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

function formatAda(value: bigint | string | number): string {
  const amount = integer(value)
  const sign = amount < 0n ? '-' : ''
  const absolute = amount < 0n ? -amount : amount
  return `${sign}₳${absolute / 1000000n}.${(absolute % 1000000n).toString().padStart(6, '0')}`
}

const sendingAmount = computed(() => formSendAda.value.isMultiClaim
  ? fromWalletItems.value.reduce((sum, item) => sum + integer(item.stakingAddrLovelace), 0n)
  : integer(formSendAda.value.isClaim ? fromWalletItem.value.stakingAddrLovelace : fromWalletItem.value.paymentAddrLovelace))

const modalTitle = computed(() => formSendAda.value.isMultiClaim
  ? `Claiming from ${fromWalletItems.value.length} addresses: ${formatAda(sendingAmount.value)}`
  : `${formSendAda.value.isClaim ? 'Claiming' : 'Sending'}: ${formatAda(sendingAmount.value)}`)

const metadataState = computed(() => {
  if (!formSendAda.value.metadata) return true
  try {
    const parsed = JSON.parse(formSendAda.value.metadata)
    const error = validateMetadataItem(parsed)
    if (error) {
      formSendAda.value.metadataError = error
      return false
    }
    formSendAda.value.metadataError = null
    return true
  } catch {
    formSendAda.value.metadataError = 'Invalid JSON'
    return false
  }
})

// Reactive state
const openIndex = ref<number>(0)
const metadataOpen = ref(false)

// Methods
function clearFormSendAda() {
  formSendAda.value = {
    spendingPassword: null,
    fromId: null,
    fromIds: [],
    isClaim: false,
    isMultiClaim: false,
    toAccounts: [createEmptyToAccount()],
    metadata: null,
    metadataError: null,
    valid: false,
  }
  fromWalletItems.value = []
  openIndex.value = 0
  metadataOpen.value = false
  store.invalidateSendAdaFees()
}

function createEmptyToAccount(): ToAccount {
  return {
    currency: 'ada',
    destination: 'account',
    account: null,
    address: '',
    type: null,
    amount: null,
    percent: 0,
    tokenFee: '0'
  }
}

function showSendAdaModal(walletItem: WalletItemRef, isClaim: boolean) {
  clearFormSendAda()
  fromWalletItem.value = walletItem
  formSendAda.value.fromId = walletItem.id
  formSendAda.value.fromIds = [walletItem.id]
  formSendAda.value.isClaim = isClaim
  formSendAda.value.isMultiClaim = false
  isVisible.value = true
  prepareCalculateSendAdaFees()
}

function showMultiClaimModal(items: WalletItemRef[]) {
  clearFormSendAda()
  fromWalletItems.value = items
  // Use the first item as the primary for compatibility
  fromWalletItem.value = items[0]
  formSendAda.value.fromId = items[0].id
  formSendAda.value.fromIds = items.map(item => item.id)
  formSendAda.value.isClaim = true
  formSendAda.value.isMultiClaim = true
  isVisible.value = true
  prepareCalculateSendAdaFees()
}

function addPaymentEntry() {
  formSendAda.value.toAccounts.push(createEmptyToAccount())
  openIndex.value = formSendAda.value.toAccounts.length - 1
  metadataOpen.value = false // Ensure metadata is closed when adding new payment (focus shifts)
}

function toggleAccordion(index: number) {
  openIndex.value = openIndex.value === index ? -1 : index
  if (openIndex.value !== -1) {
    metadataOpen.value = false // Close metadata if opening an entry
  }
}

function toggleMetadata() {
  metadataOpen.value = !metadataOpen.value
  if (metadataOpen.value) {
    openIndex.value = -1 // Close account entries if opening metadata
  }
}

function removePaymentEntry(index: number) {
  formSendAda.value.toAccounts.splice(index, 1)
  // If we closed the current one or one before it, we might need to adjust openIndex
  // But defaulting to -1 (all closed) or keeping previous is fine.
  // Let's ensure if we remove the open one, we close.
  if (openIndex.value === index) {
      openIndex.value = -1 // Close
  } else if (openIndex.value > index) {
      openIndex.value-- // Shift up
  }
}

function currencyState(currency: string): boolean {
  recalculateRemaining()
  return currency != null
}

function destinationState(destination: string): boolean {
  return destination != null
}

function accountState(toAccount: ToAccount): boolean {
  recalculateRemaining()
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
  recalculateRemaining()
  return type != null
}

function amountState(index: number, amount: string | null, currency: string): boolean | null {
  recalculateRemaining()
  if (amount != null && amount !== '') {
    const tokens = parseAmount(amount, currency)
    const remaining = calculateSpent(index + 1).remaining[currency] || 0n
    return tokens !== null && tokens >= (currency === 'ada' ? integer(minUTxOValue.value) : 1n) && remaining >= 0n
  }
  return null
}

function percentState(index: number, percent: number, currency: string): boolean | null {
  recalculateRemaining()
  if (percent != null && percent > 0) {
    const spent = calculateSpent(index + 1)
    const tokens = spent.amount[currency] || 0n
    const remaining = spent.remaining[currency] || 0n
    return tokens >= (currency === 'ada' ? integer(minUTxOValue.value) : 1n) && remaining >= 0n
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


function amountPlaceholder(currency: string): string {
  return currency === 'ada' ? 'e.g. 10.000000' : 'e.g. 100'
}

function minAmountLabel(currency: string): string {
  return currency === 'ada' ? formatAda(minUTxOValue.value) : '1'
}

function amountRemainingLabel(index: number, currency: string): string {
  const spent = calculateSpent(index, true)
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
  prepareCalculateSendAdaFees()
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

function recalculateRemaining() {
  remainingLovelace.value = calculateSpent(formSendAda.value.toAccounts.length).remaining['ada'] || 0n
}

function calculateClaimRewardsFeePayer(): number {
  for (const account of formSendAda.value.toAccounts) {
    const walletItem = find(walletItems.value, ['id', account.account])
    if (walletItem && (walletItem as any).type !== 'address' && (walletItem as any).type !== 'pledge' &&
        integer(walletItem.paymentAddrLovelace) >= 1000000n + integer(txFee.value)) {
      return walletItem.id
    }
  }
  return -1
}

function calculateSpent(index: number, skipTokenFees = false): { remaining: Record<string, bigint>, amount: Record<string, bigint> } {
  const feePayerAccountId = formSendAda.value.isClaim ? calculateClaimRewardsFeePayer() : fromWalletItem.value.id
  const tkKeepFee = skipTokenFees ? 0n : integer(tokenKeepFee.value)
  const tkLocked = skipTokenFees ? 0n : integer(tokenLocked.value)
  const baseAmount: Record<string, bigint> = {}
  for (const [asset, quantity] of Object.entries(fromWalletItem.value.nativeAssetMap || {})) {
    baseAmount[asset] = integer(quantity)
  }
  const rewards = formSendAda.value.isMultiClaim
    ? fromWalletItems.value.reduce((sum, item) => sum + integer(item.stakingAddrLovelace), 0n)
    : integer(fromWalletItem.value.stakingAddrLovelace)
  baseAmount.ada = formSendAda.value.isClaim
    ? rewards
    : integer(fromWalletItem.value.paymentAddrLovelace) - integer(txFee.value) - tkKeepFee - tkLocked

  const spentPercentages: Record<string, number> = {}
  const amount: Record<string, bigint> = {}
  for (let i = 0; i < index; i++) {
    const account = formSendAda.value.toAccounts[i]
    const currency = account.currency
    amount[currency] = 0n
    account.isFeePayer = feePayerAccountId === account.account
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
    if (formSendAda.value.isClaim && account.account === feePayerAccountId && currency === 'ada') {
      const fee = integer(txFee.value)
      if (account.type === 'amount' || baseAmount[currency] >= fee) baseAmount[currency] -= fee
      else amount[currency] -= fee
    }
    if (!skipTokenFees && currency !== 'ada') {
      account.tokenFee = String(tokenFees.value?.[i] ?? '0')
      baseAmount.ada = (baseAmount.ada || 0n) - integer(account.tokenFee)
    }
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

function prepareCalculateSendAdaFees() {
  recalculateRemaining()
  store.invalidateSendAdaFees()
}

function onShowSendAdaModal(data: { walletItem?: WalletItemRef; walletItems?: WalletItemRef[]; isClaim: boolean; isMultiClaim?: boolean }) {
  if (data.isMultiClaim && data.walletItems && data.walletItems.length > 0) {
    showMultiClaimModal(data.walletItems)
  } else if (data.walletItem) {
    showSendAdaModal(data.walletItem, data.isClaim)
  }
}


// Watch token fees updates
watch(tokenFees, (fees) => {
  if (fees) {
    for (let i = 0; i < formSendAda.value.toAccounts.length; i++) {
      formSendAda.value.toAccounts[i].tokenFee = String(fees[i] ?? '0')
    }
  }
})

onMounted(() => {
  clearFormSendAda()
  emitter.on('show-send-ada-modal', onShowSendAdaModal as any)
})

onUnmounted(() => {
  emitter.off('show-send-ada-modal', onShowSendAdaModal as any)
})
</script>

<style scoped>
.transition-transform {
  transition: transform 0.3s ease;
}

.rotate-180 {
  transform: rotate(180deg);
}
</style>

<style scoped>
.collapsed .when-open,
.not-collapsed .when-closed {
  display: none;
}
.clickable:hover {
  cursor: pointer;
}
</style>