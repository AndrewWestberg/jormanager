<template>
  <section class="mt-3" aria-label="Transaction details">
    <div v-if="'error' in detail" class="alert alert-danger" role="alert">{{ detail.error }}</div>
    <template v-else>
      <h5 class="text-break">Transaction {{ detail.txId }}</h5>
      <p>{{ detail.readState.status === 'stale' ? 'Historical authorization state at last query' : 'Local authorization state' }}: {{ detail.operationState ?? 'not available' }}</p>
      <p>Network: {{ detail.networkKey }} · Index: {{ detail.readState.status }}<span v-if="detail.readState.reason"> — {{ detail.readState.reason }}</span></p>
      <p>Observed tip: {{ point(detail.readState.observedTip) }} · Last complete: {{ point(detail.readState.lastCompletePoint) }}</p>
      <div v-if="detail.readState.status === 'stale'" class="alert alert-warning">
        The chain index changed. Inclusion, adoption and effect labels below are historical and do not establish current confirmation.
        <button class="btn btn-link p-0" @click="emit('navigate', detail.txId)">Refresh this transaction</button>
      </div>
      <p v-if="!detail.occurrences.length" class="alert alert-warning">No indexed inclusion was found. This does not establish whether the transaction is pending, failed or rolled back.</p>
      <article v-for="(occurrence, occurrenceIndex) in detail.occurrences" :key="`${occurrence.inclusion.hash}-${occurrenceIndex}`" class="border rounded p-3 mb-3">
        <h6>Inclusion {{ occurrenceIndex + 1 }}: {{ detail.readState.status === 'stale' ? 'at last query, ' : '' }}{{ occurrence.state }} ({{ occurrence.inclusion.adopted ? 'adopted' : 'not adopted' }})</h6>
        <p class="text-break">Block {{ occurrence.inclusion.hash }} · Slot {{ occurrence.inclusion.slot }} · Height {{ occurrence.inclusion.height }} · Era {{ occurrence.inclusion.era }} · Source {{ occurrence.inclusion.source }}</p>
        <div v-if="occurrence.leios" class="text-break mb-2">
          <strong>Certified endorsement</strong><br>
          Announced at {{ point(occurrence.leios.announcingPoint) }}<br>
          Endorsement block: {{ occurrence.leios.ebHash }}<br>
          Full transaction commitment: {{ occurrence.leios.fullTransactionHash }}<br>
          Reference position: {{ occurrence.leios.referencePosition }} · Encoded size: {{ occurrence.leios.transactionSize }} bytes
          <details><summary>Certificate bytes</summary>{{ occurrence.leios.certificateHex }}</details>
        </div>
        <p>Ledger validity: {{ occurrence.valid ? 'valid' : 'invalid' }} · Effects: {{ occurrence.effective ? 'effective' : 'not effective' }} · Fee: {{ occurrence.fee === null ? 'not present' : `${occurrence.fee} lovelace` }}</p>
        <p v-if="occurrence.parentId" class="text-break">Parent: <button class="btn btn-link p-0 text-break" @click="emit('navigate', occurrence.parentId!)">{{ occurrence.parentId }}</button></p>
        <div v-if="occurrence.childIds.length"><strong>Children</strong><ul><li v-for="child in occurrence.childIds" :key="child"><button class="btn btn-link p-0 text-break" @click="emit('navigate', child)">{{ child }}</button></li></ul></div>
        <h6>Inputs</h6>
        <p v-if="!occurrence.inputs.length">None</p>
        <ul v-else><li v-for="(input, i) in occurrence.inputs" :key="i" class="text-break"><button class="btn btn-link p-0 text-break" @click="emit('navigate', input.txId)">{{ input.txId }}</button>#{{ input.index }} · {{ input.role }} · {{ input.effective ? 'effective' : 'not effective' }}</li></ul>
        <h6>Outputs</h6>
        <p v-if="!occurrence.outputs.length">None</p>
        <ul v-else><li v-for="output in occurrence.outputs" :key="`${output.index}-${output.collateralReturn}`" class="text-break">
          #{{ output.index }} · {{ output.collateralReturn ? 'collateral return' : 'payment output' }} · {{ output.effective ? 'effective' : 'not effective' }}<br>
          Address (hex): {{ output.addressHex }}<br>Coin: {{ output.coin }} lovelace
          <ul><li v-for="(asset, i) in output.assets" :key="i">Policy: {{ asset.policyHex }} · Name (hex): {{ asset.nameHex || '(empty)' }} · Quantity: {{ asset.quantity }}</li></ul>
        </li></ul>
        <h6>Direct deposits ({{ occurrence.directDeposits.presence }})</h6>
        <ul><li v-for="(entry, i) in occurrence.directDeposits.entries" :key="i" class="text-break">Account: {{ entry.accountAddressHex }} · Amount: {{ entry.amount }} · {{ entry.effective ? 'effective' : 'not effective' }}</li></ul>
        <h6>Account effects</h6>
        <p v-if="!occurrence.accountEffects.length">None</p>
        <ul v-else><li v-for="(effect, i) in occurrence.accountEffects" :key="i" class="text-break">{{ effect.kind }} · Account: {{ effect.accountAddressHex }} · Amount: {{ effect.amount ?? 'not present' }} · Lower inclusive: {{ effect.lowerInclusive ?? 'not present' }} · Upper exclusive: {{ effect.upperExclusive ?? 'not present' }} · {{ effect.effective ? 'effective' : 'not effective' }}</li></ul>
        <h6>Member data</h6>
        <p v-if="!occurrence.memberData.length">None</p>
        <div v-for="(member, i) in occurrence.memberData" :key="i" class="mb-2 text-break">
          <strong>{{ member.family }} #{{ member.position }}</strong><br>Raw (hex): {{ member.rawHex }}
          <pre v-if="member.typed !== null" class="text-wrap text-break">{{ JSON.stringify(member.typed, null, 2) }}</pre>
        </div>
      </article>
    </template>
  </section>
</template>

<script setup lang="ts">
import type { ChainPoint, TransactionDetail } from '@/types'
defineProps<{ detail: TransactionDetail | { error: string } }>()
const emit = defineEmits<{ (e: 'navigate', txId: string): void }>()
function point(value: ChainPoint | null): string {
  return value ? `${value.slot} / ${value.hash}` : 'unavailable'
}
</script>
