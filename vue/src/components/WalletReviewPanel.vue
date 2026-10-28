<template>
  <section class="border rounded p-3 mt-3" aria-label="Transaction review">
    <h5>Review transaction</h5>
    <p>Network: {{ review.networkKey }} · Era: {{ review.era }} · State: {{ review.state }}<span v-if="stale"> (historical; needs reconciliation)</span></p>
    <p v-if="review.reason">{{ review.reason }}</p>
    <p v-if="stale" class="alert alert-warning" role="alert">Chain status changed or became unavailable for this network. This review is historical and its confirmation or effects must not be relied on until reconciled. If it has not been submitted, change details and review again.</p>
    <p v-if="review.state === 'submitted' || review.state === 'submission_uncertain'" class="text-warning">This transaction is not confirmed. Check its transaction ID and chain inclusion before relying on its effects.</p>
    <p v-if="review.state === 'phase2_failed'" class="text-danger">The transaction was included but failed phase 2. Its ordinary effects were not applied.</p>
    <p class="text-break">Transaction ID: {{ review.txId }}<br>Preview ID: {{ review.previewId }}</p>
    <p>Fee: {{ lovelaceToAda(String(review.fee)) }} · Deposit: {{ lovelaceToAda(String(review.deposit)) }} · Refund: {{ lovelaceToAda(String(review.refund)) }}</p>
    <p>Valid from: {{ review.validFrom ?? 'Not specified' }} · Valid until: {{ review.validUntil ?? 'Not specified' }}</p>
    <div v-if="review.effects != null"><strong>Transaction effects</strong><pre class="text-wrap text-break">{{ JSON.stringify(review.effects, null, 2) }}</pre></div>
    <BFormGroup v-if="(review.state === 'previewed' && !uncertain && !stale) || canReconcile" label="Spending password" label-for="review-password">
      <BFormInput id="review-password" v-model="password" type="password" autocomplete="current-password" />
    </BFormGroup>
    <div v-if="error" class="alert alert-danger mt-2" role="alert">{{ error }}</div>
    <BButton v-if="review.state === 'previewed' && !uncertain && !stale" variant="success" :disabled="busy || !password || !chainComplete" @click="submit">Authorize and submit</BButton>
    <div v-if="canReconcile" class="mt-2">
      <p>Reconciliation checks the complete authorized transaction, chain state and mempool. It never resubmits the transaction.</p>
      <BButton variant="warning" :disabled="busy || !password" @click="reconcile">Authorize reconciliation</BButton>
    </div>
    <p v-if="review.state === 'previewed' && !chainComplete" class="text-warning">Current complete chain state for this network is required before authorization.</p>
    <BButton class="ms-2" variant="secondary" :disabled="busy" @click="emit('close')">{{ review.state === 'previewed' && !uncertain ? 'Change details' : 'Close' }}</BButton>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted, watch } from 'vue'
import { BButton, BFormGroup, BFormInput } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { lovelaceToAda } from '@/utils/filters'
import type { WalletReview } from '@/types'

const props = defineProps<{ review: WalletReview; governance?: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'submitted', review: WalletReview): void }>()
const store = useJorManagerStore()
const chainComplete = computed(() => store.connected && store.chainReadState?.status === 'complete' && store.chainReadState.networkKey === props.review.networkKey)
const password = ref('')
const busy = ref(false)
const uncertain = ref(false)
const error = ref('')
const stale = ref(!chainComplete.value)
watch(() => store.networkInvalidations[props.review.networkKey] ?? 0, (value, oldValue) => {
  if (value !== oldValue) stale.value = true
}, { flush: 'sync' })
watch(() => !store.connected || (store.walletNetworkKey === props.review.networkKey &&
  (store.chainReadState?.networkKey !== props.review.networkKey || store.chainReadState.status !== 'complete')), unavailable => {
  if (unavailable) stale.value = true
}, { flush: 'sync' })
watch(() => [props.review.previewId, props.review.networkKey], () => {
  stale.value = !chainComplete.value
})
const canReconcile = computed(() => uncertain.value || (stale.value && props.review.state !== 'previewed') || ['signing', 'submission_uncertain', 'submitted', 'rolled_back', 'confirmed', 'phase2_failed'].includes(props.review.state))

async function reconcile() {
  if (busy.value || !password.value || !canReconcile.value) return
  busy.value = true
  error.value = ''
  const secret = password.value
  password.value = ''
  const networkKey = props.review.networkKey
  const invalidation = store.networkInvalidations[networkKey] ?? 0
  try {
    const result = await store.reconcileWallet(props.review.previewId, secret)
    if (result.previewId !== props.review.previewId || result.txId !== props.review.txId || result.networkKey !== props.review.networkKey) throw new Error('Reconciliation response target did not match the review')
    uncertain.value = false
    stale.value = (store.networkInvalidations[networkKey] ?? 0) !== invalidation || !chainComplete.value
    emit('submitted', { ...props.review, state: result.state, reason: result.reason })
  } catch (e) {
    error.value = `${e instanceof Error ? e.message : String(e)}. Reconciliation outcome may be uncertain; inspect the transaction before retrying.`
    uncertain.value = true
  } finally {
    busy.value = false
  }
}
async function submit() {
  if (busy.value || !password.value || !chainComplete.value || stale.value) return
  busy.value = true
  error.value = ''
  const secret = password.value
  password.value = ''
  const networkKey = props.review.networkKey
  const invalidation = store.networkInvalidations[networkKey] ?? 0
  try {
    const result = await store.submitReviewedWallet(props.review.previewId, secret, props.governance)
    if (result.previewId !== props.review.previewId || result.txId !== props.review.txId || result.networkKey !== props.review.networkKey) throw new Error('Submission response target did not match the review')
    if ((store.networkInvalidations[networkKey] ?? 0) !== invalidation || !chainComplete.value) stale.value = true
    emit('submitted', result)
  } catch (e) {
    error.value = `${e instanceof Error ? e.message : String(e)}. Submission outcome may be uncertain; check the transaction before retrying.`
    uncertain.value = true
  } finally {
    busy.value = false
  }
}
onUnmounted(() => { password.value = '' })
</script>
