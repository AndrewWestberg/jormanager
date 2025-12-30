<template>
  <div>
    <BContainer>
      <BCardGroup deck>
        <BCard
          no-body
          border-variant="secondary"
          header="Epoch Time Remaining"
          header-border-variant="secondary"
          align="center"
        >
          <BCardText style="padding: 25% 0">
            <h1 :class="epochRemainingClass">
              <div>Epoch: {{ epoch }}</div>
              <div>{{ epochTimeRemaining }}</div>
            </h1>
          </BCardText>
        </BCard>
        <NodeChart
          title="Block Height"
          :series="blockHeightSeries"
          :colors="nodeColors"
        />
      </BCardGroup>
      <br />
      <BCardGroup deck>
        <NodeChart
          title="Outgoing Peers"
          :series="peersSeries"
          :colors="nodeColors"
          :min="0"
        />
        <StackedBarChart
          title="KES Days Remaining"
          :series="remainingKESSeries"
          :categories="remainingKESSeriesCategoryLabels"
        />
      </BCardGroup>
      <br />
      <BCardGroup deck>
        <NodeChart
          title="Incoming Peers"
          :series="incomingPeersSeries"
          :colors="nodeColors"
          :min="0"
        />
        <NodeChart
          title="Transactions Processed"
          :series="txsProcessedSeries"
          :colors="nodeColors"
        />
      </BCardGroup>
    </BContainer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { BContainer, BCardGroup, BCard, BCardText } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import NodeChart from '@/components/NodeChart.vue'
import StackedBarChart from '@/components/StackedBarChart.vue'

const store = useJorManagerStore()
const {
  peersSeries,
  incomingPeersSeries,
  blockHeightSeries,
  remainingKESSeries,
  remainingKESSeriesCategoryLabels,
  nodeColors,
  txsProcessedSeries,
  epoch,
  slot,
  epochLength
} = storeToRefs(store)

const epochRemainingClass = computed(() => {
  const epochTimeRemainingSecs = epochLength.value - slot.value
  const ratio = epochTimeRemainingSecs / epochLength.value
  if (ratio > 0.4) return 'text-success'
  if (ratio > 0.2) return 'text-warning'
  return 'text-danger'
})

const epochTimeRemaining = computed(() => {
  const time = epochLength.value - slot.value
  const days = Math.floor(time / 60 / 60 / 24)
  const hours = Math.floor(time / 60 / 60) % 24
  const minutes = Math.floor(time / 60) % 60
  const seconds = Math.floor(time % 60)
  return `${days}d ${hours}h ${minutes}m ${seconds}s`
})
</script>