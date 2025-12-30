<template>
  <div>
    <BModal
      id="modal-send-ada"
      v-model="isVisible"
      :title="modalTitle"
      size="xl"
      :no-close-on-backdrop="true"
      @ok="handleValidateAndSend"
    >
      <template #footer="{ ok, cancel }">
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
            <BButton
              variant="primary"
              @click="ok()"
              :disabled="requestFeesUUID !== responseFeesUUID"
            >Send</BButton>
          </div>
        </div>
      </template>
      
      <BForm ref="sendAdaForm" @submit.stop.prevent="handleValidateAndSend">
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
                     Available: {{ entryAvailableLabel(index, toAccount.currency) }} | 
                     Remaining: {{ entryRemainingLabel(index, toAccount.currency) }}
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
import { find, map, clone, countBy } from 'lodash-es'
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
import { lovelaceToAda } from '@/utils/filters'

interface ToAccount {
  currency: string
  destination: 'account' | 'address'
  account: number | null
  address: string
  type: 'amount' | 'percent' | null
  amount: string | null
  percent: number
  tokenFee: number
  isFeePayer?: boolean
}

interface WalletItemRef {
  id: number
  name: string
  paymentAddrLovelace: number
  stakingAddrLovelace: number
  nativeAssetMap: Record<string, number>
}

const store = useJorManagerStore()
const { walletItems, txFee, tokenKeepFee, tokenFees, tokenLocked, minUTxOValue, requestFeesUUID, responseFeesUUID, toastSuccess } = storeToRefs(store)
const emitter = useEventBus()

const isVisible = ref(false)
const remainingLovelace = ref(0)
const fromWalletItem = ref<WalletItemRef>({
  id: 0,
  name: '',
  paymentAddrLovelace: 0,
  stakingAddrLovelace: 0,
  nativeAssetMap: {}
})

const formSendAda = ref({
  spendingPassword: null as string | null,
  fromId: null as number | null,
  isClaim: false,
  toAccounts: [] as ToAccount[],
  metadata: null as string | null,
  metadataError: null as string | null,
  valid: false
})

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

const paymentOptions = computed(() => {
  const formatter = (val: number, _sym: string, dec: number) => lovelaceToAda(val * 1000000, dec)
  return store.paymentSelectOptions(formatter)
})

const sendingAmount = computed(() => {
  return formSendAda.value.isClaim
    ? fromWalletItem.value.stakingAddrLovelace
    : fromWalletItem.value.paymentAddrLovelace
})

const modalTitle = computed(() => {
  const tkFee = tokenKeepFee.value || 0
  const tkLocked = tokenLocked.value || 0
  const remaining = remainingLovelace.value + tkFee + tkLocked
  const sending = sendingAmount.value
  
  let title = (formSendAda.value.isClaim ? 'Claiming: ' : 'Sending: ') +
    lovelaceToAda(sending) +
    ', TxFee: ' + lovelaceToAda(txFee.value || 0)
  
  if (tkLocked > 0) title += ', TokenLocked: ' + lovelaceToAda(tkLocked)
  if (tkFee > 0) title += ', TokenKeep: ' + lovelaceToAda(tkFee)
  
  title += ', Remaining: ' + lovelaceToAda(remaining)
  return title
})

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
    isClaim: false,
    toAccounts: [createEmptyToAccount()],
    metadata: null,
    metadataError: null,
    valid: false,
  }
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
    tokenFee: 0
  }
}

function showSendAdaModal(walletItem: WalletItemRef, isClaim: boolean) {
  clearFormSendAda()
  fromWalletItem.value = walletItem
  formSendAda.value.fromId = walletItem.id
  formSendAda.value.isClaim = isClaim
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
    const spent = calculateSpent(index + 1)
    const remaining = spent.remaining[currency] || 0
    return tokens >= (currency === 'ada' ? minUTxOValue.value : 1) && remaining >= 0
  }
  return null
}

function percentState(index: number, percent: number, currency: string): boolean | null {
  recalculateRemaining()
  if (percent != null && percent > 0) {
    const spent = calculateSpent(index + 1)
    const tokens = spent.amount[currency] || 0
    const remaining = spent.remaining[currency] || 0
    return tokens >= (currency === 'ada' ? minUTxOValue.value : 1) && remaining >= 0
  }
  return null
}

function parseAmount(amount: string | null, currency: string): number {
  if (!amount) return 0
  const numStr = amount.replace(/[^0-9.-]/g, '')
  const val = parseFloat(numStr) || 0
  return currency === 'ada' ? Math.round(val * 1000000) : Math.round(val)
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

// Add methods to help with template display for Accordion
function entryAvailableLabel(index: number, currency: string): string {
  const spent = calculateSpent(index, true)
  return currency === 'ada' ? lovelaceToAda(spent.remaining['ada'] || 0) : String(spent.remaining[currency] || 0)
}

function entryRemainingLabel(index: number, currency: string): string {
  const spent = calculateSpent(index + 1, false) // Include fees in remaining
  return currency === 'ada' ? lovelaceToAda(spent.remaining['ada'] || 0) : String(spent.remaining[currency] || 0)
}

function amountPlaceholder(currency: string): string {
  return currency === 'ada' ? 'e.g. ₳10.000000' : 'e.g. 100'
}

function minAmountLabel(currency: string): string {
  return currency === 'ada' ? lovelaceToAda(minUTxOValue.value) : '1'
}

function amountRemainingLabel(index: number, currency: string): string {
  const spent = calculateSpent(index, true)
  return currency === 'ada' ? lovelaceToAda(spent.remaining['ada'] || 0) : String(spent.remaining[currency] || 0)
}

function percentLabel(index: number, percent: number, currency: string): string {
  const amount = calculateSpent(index + 1).amount[currency] || 0
  const formatted = currency === 'ada' ? lovelaceToAda(amount) : String(amount)
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
  remainingLovelace.value = calculateSpent(formSendAda.value.toAccounts.length).remaining['ada'] || 0
}

function calculateClaimRewardsFeePayer(): number {
  for (const account of formSendAda.value.toAccounts) {
    const walletItem = find(walletItems.value, ['id', account.account])
    if (walletItem && (walletItem as any).type !== 'address' && (walletItem as any).type !== 'pledge' &&
        walletItem.paymentAddrLovelace >= 1000000 + (txFee.value || 0)) {
      return walletItem.id
    }
  }
  return -1
}

function calculateSpent(index: number, skipTokenFees = false): { remaining: Record<string, number>, amount: Record<string, number> } {
  let feePayerAccountId = formSendAda.value.isClaim ? calculateClaimRewardsFeePayer() : fromWalletItem.value.id
  const tkKeepFee = skipTokenFees ? 0 : (tokenKeepFee.value || 0)
  const tkLocked = skipTokenFees ? 0 : (tokenLocked.value || 0)
  
  const baseAmount: Record<string, number> = fromWalletItem.value.nativeAssetMap
    ? clone(fromWalletItem.value.nativeAssetMap)
    : {}
  
  baseAmount['ada'] = formSendAda.value.isClaim
    ? fromWalletItem.value.stakingAddrLovelace
    : fromWalletItem.value.paymentAddrLovelace - (txFee.value || 0) - tkKeepFee - tkLocked
  
  const alreadySpentPercentages: Record<string, number> = {}
  const amount: Record<string, number> = {}
  
  for (let i = 0; i < index; i++) {
    const account = formSendAda.value.toAccounts[i]
    amount[account.currency] = 0
    account.isFeePayer = feePayerAccountId === account.account
    
    if (account.type === 'amount' && account.amount != null) {
      amount[account.currency] = parseAmount(account.amount, account.currency)
      baseAmount[account.currency] = (baseAmount[account.currency] || 0) - amount[account.currency]
      
      if (formSendAda.value.isClaim && account.account === feePayerAccountId && account.currency === 'ada') {
        baseAmount[account.currency] -= (txFee.value || 0)
      }
      alreadySpentPercentages[account.currency] = 0
    } else if (account.type === 'percent' && account.percent > 0) {
      if (alreadySpentPercentages[account.currency] === undefined) {
        alreadySpentPercentages[account.currency] = 0
      }
      const percent = account.percent
      if (alreadySpentPercentages[account.currency] >= 100) {
        amount[account.currency] = 0
      } else {
        amount[account.currency] = Math.round(
          (baseAmount[account.currency] || 0) * (percent / (100 - alreadySpentPercentages[account.currency]))
        )
        alreadySpentPercentages[account.currency] += percent
      }
      baseAmount[account.currency] = (baseAmount[account.currency] || 0) - amount[account.currency]
      
      if (formSendAda.value.isClaim && account.account === feePayerAccountId && account.currency === 'ada') {
        if (baseAmount[account.currency] >= (txFee.value || 0)) {
          baseAmount[account.currency] -= (txFee.value || 0)
        } else {
          amount[account.currency] -= (txFee.value || 0)
        }
      }
    }
    
    if (!skipTokenFees && account.currency !== 'ada') {
      account.tokenFee = (tokenFees.value as number[])?.[i] || 0
      baseAmount['ada'] = (baseAmount['ada'] || 0) - account.tokenFee
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
  const uniqueToAccounts = Object.keys(countBy(formSendAda.value.toAccounts, (ta) => 
    ta.account && ta.account > 0 ? ta.account : ta.address
  )).length
  const returnChangeTxOut = remainingLovelace.value + (tokenKeepFee.value || 0) > 0 ? 1 : 0
  
  const toAccounts = map(formSendAda.value.toAccounts, (toAccount, index) => ({
    currency: toAccount.currency,
    account: toAccount.account || -1,
    address: toAccount.address,
    type: toAccount.type,
    amount: toAccount.amount == null
      ? calculateSpent(index + 1).amount[toAccount.currency]
      : parseAmount(toAccount.amount, toAccount.currency),
    percent: toAccount.percent,
    tokenFee: toAccount.tokenFee
  }))
  
  const request = {
    fromId: fromWalletItem.value.id,
    toAccounts,
    txOut: uniqueToAccounts + returnChangeTxOut,
    isClaim: formSendAda.value.isClaim,
    metadata: formSendAda.value.metadata,
    uuid: crypto.randomUUID()
  }
  
  validateForm()

  if (request.fromId && formSendAda.value.valid) {
    store.calculateSendAdaFees(request)
  } else {
    store.invalidateSendAdaFees()
  }
}

function validateForm() {
  formSendAda.value.valid = true
  for (const toAccount of formSendAda.value.toAccounts) {
    if (!currencyState(toAccount.currency) || !destinationState(toAccount.destination) ||
        !accountState(toAccount) || !addressState(toAccount) || !typeState(toAccount.type)) {
      formSendAda.value.valid = false
      break
    }
    if (toAccount.type === 'amount' && !amountState(formSendAda.value.toAccounts.indexOf(toAccount), toAccount.amount, toAccount.currency)) {
      formSendAda.value.valid = false
      break
    }
    if (toAccount.type === 'percent' && !percentState(formSendAda.value.toAccounts.indexOf(toAccount), toAccount.percent, toAccount.currency)) {
      formSendAda.value.valid = false
      break
    }
  }
  if (formSendAda.value.valid && !metadataState.value) {
    formSendAda.value.valid = false
  }
}

function handleValidateAndSend() {
  // Validate all entries
  for (const toAccount of formSendAda.value.toAccounts) {
    if (!currencyState(toAccount.currency) || !destinationState(toAccount.destination) ||
        !accountState(toAccount) || !addressState(toAccount) || !typeState(toAccount.type)) {
      store.toastError = { title: 'Invalid Form', message: 'Please fill out all required fields.' }
      return
    }
    if (toAccount.type === 'amount' && !amountState(formSendAda.value.toAccounts.indexOf(toAccount), toAccount.amount, toAccount.currency)) {
      store.toastError = { title: 'Invalid Form', message: 'Invalid amount specified.' }
      return
    }
  }
  
  if (remainingLovelace.value < 0) {
    store.toastError = { title: 'Invalid Form', message: 'Negative remaining balance or not enough left to keep tokens!' }
    return
  }
  
  emitter.emit('show-spending-password-modal', { action: 'send-ada' })
}

function passwordConfirmed(spendingPassword: string) {
  const toAccounts = map(formSendAda.value.toAccounts, (toAccount, index) => ({
    currency: toAccount.currency,
    account: toAccount.account,
    address: toAccount.address,
    type: toAccount.type,
    amount: toAccount.amount == null
      ? calculateSpent(index + 1).amount[toAccount.currency]
      : parseAmount(toAccount.amount, toAccount.currency),
    percent: toAccount.percent,
    tokenFee: toAccount.tokenFee
  }))
  
  store.submitTransaction({
    spendingPassword,
    fromId: formSendAda.value.fromId!,
    isClaim: formSendAda.value.isClaim,
    txFee: txFee.value || 0,
    tokenKeepFee: tokenKeepFee.value || 0,
    toAccounts,
    metadata: formSendAda.value.metadata
  })
}

function onSpendingPasswordConfirmed(password: string) {
  passwordConfirmed(password)
}

function onShowSendAdaModal(data: { walletItem: WalletItemRef; isClaim: boolean }) {
  showSendAdaModal(data.walletItem, data.isClaim)
}

// Watch for successful send
watch(toastSuccess, (toast) => {
  if (toast?.title === 'Ada Sent') {
    isVisible.value = false
    clearFormSendAda()
  }
})

// Watch token fees updates
watch(tokenFees, (fees) => {
  if (fees) {
    for (let i = 0; i < formSendAda.value.toAccounts.length; i++) {
      formSendAda.value.toAccounts[i].tokenFee = (fees as number[])[i] || 0
    }
  }
})

onMounted(() => {
  clearFormSendAda()
  emitter.on('show-send-ada-modal', onShowSendAdaModal as any)
  emitter.on('confirm-spending-password', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('show-send-ada-modal', onShowSendAdaModal as any)
  emitter.off('confirm-spending-password', onSpendingPasswordConfirmed)
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