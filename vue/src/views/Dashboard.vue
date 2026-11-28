<template>
  <div class="jm-dashboard">
    <div class="jm-page-header">
      <div>
        <h1 class="jm-page-title">
          Mission Control
        </h1>
        <p class="jm-page-subtitle">
          Real-time Cardano node telemetry, KES readiness, and epoch progress
        </p>
      </div>
    </div>

    <!-- Hero Telemetry Strip: Epoch Ring + 4 Fleet KPIs -->
    <div class="jm-dash-hero">
      <section
        class="jm-card jm-epoch-hero"
        aria-label="Epoch Time Remaining"
      >
        <div class="jm-epoch-eyebrow">
          Epoch Time Remaining
        </div>
        <div class="jm-epoch-ring-wrap">
          <svg
            class="jm-epoch-ring-svg"
            viewBox="0 0 180 180"
            aria-hidden="true"
          >
            <defs>
              <linearGradient
                id="jmEpochGrad"
                x1="0%"
                y1="0%"
                x2="100%"
                y2="100%"
              >
                <stop
                  offset="0%"
                  :stop-color="ringStartColor"
                />
                <stop
                  offset="100%"
                  :stop-color="ringEndColor"
                />
              </linearGradient>
            </defs>
            <circle
              cx="90"
              cy="90"
              r="76"
              fill="none"
              stroke="rgba(148, 163, 184, 0.14)"
              stroke-width="11"
            />
            <circle
              cx="90"
              cy="90"
              r="76"
              fill="none"
              stroke="url(#jmEpochGrad)"
              stroke-width="11"
              stroke-linecap="round"
              :stroke-dasharray="ringCircumference"
              :stroke-dashoffset="ringDashOffset"
            />
          </svg>
          <div class="jm-epoch-ring-center">
            <span class="jm-epoch-label">Epoch</span>
            <span class="jm-epoch-number jm-mono">{{ epoch }}</span>
            <span class="jm-epoch-pct jm-mono">{{ epochProgressPercent.toFixed(1) }}%</span>
          </div>
        </div>
        <div
          class="jm-epoch-countdown jm-mono"
          :class="epochRemainingClass"
        >
          {{ epochTimeRemaining }}
        </div>
        <div class="jm-epoch-slot jm-mono">
          Slot {{ slot.toLocaleString() }} / {{ epochLength.toLocaleString() }}
        </div>
      </section>

      <div class="jm-kpi-grid">
        <div class="jm-card jm-kpi-card">
          <div class="jm-kpi-top">
            <span class="jm-kpi-label">Active Fleet</span>
            <span
              class="jm-kpi-icon"
              style="background: var(--jm-cyan-soft); color: var(--jm-cyan);"
            >
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                aria-hidden="true"
              />
            </span>
          </div>
          <div class="jm-kpi-value jm-mono">
            {{ nodes.length }}
            <span class="jm-kpi-unit">node{{ nodes.length === 1 ? '' : 's' }}</span>
          </div>
          <div class="jm-kpi-foot">
            <div class="d-flex gap-1 flex-wrap">
              <span class="jm-pill jm-pill-emerald">{{ coreNodeCount }} Core/Pool</span>
              <span class="jm-pill jm-pill-rose">{{ relayNodeCount }} Relay</span>
            </div>
          </div>
        </div>

        <div class="jm-card jm-kpi-card">
          <div class="jm-kpi-top">
            <span class="jm-kpi-label">Chain Tip Height</span>
            <span
              class="jm-kpi-icon"
              style="background: var(--jm-indigo-soft); color: var(--jm-indigo);"
            >
              <font-awesome-icon
                :icon="['fas', 'cubes']"
                aria-hidden="true"
              />
            </span>
          </div>
          <div class="jm-kpi-value jm-mono">
            {{ highestTip > 0 ? highestTip.toLocaleString() : '—' }}
          </div>
          <div class="jm-kpi-foot">
            <span
              class="jm-pill"
              :class="tipDelta === null ? 'jm-pill-slate' : tipDelta <= 2 ? 'jm-pill-emerald' : 'jm-pill-amber'"
            >
              {{ tipDelta === null ? 'Awaiting telemetry' : tipDelta <= 2 ? 'Nodes in sync' : `Δ ${tipDelta} blocks` }}
            </span>
            <span
              v-if="blockHeightSeries.length"
              class="jm-mono"
            >
              {{ blockHeightSeries.length }} reporting
            </span>
          </div>
        </div>

        <div class="jm-card jm-kpi-card">
          <div class="jm-kpi-top">
            <span class="jm-kpi-label">KES Key Readiness</span>
            <span
              class="jm-kpi-icon"
              style="background: var(--jm-emerald-soft); color: var(--jm-emerald);"
            >
              <font-awesome-icon
                :icon="['fas', 'key']"
                aria-hidden="true"
              />
            </span>
          </div>
          <div class="jm-kpi-value jm-mono">
            <template v-if="minKesDays !== null">
              {{ minKesDays.toFixed(1) }} <span class="jm-kpi-unit">days min</span>
            </template>
            <template v-else>
              —
            </template>
          </div>
          <div class="jm-kpi-foot">
            <span
              class="jm-pill"
              :class="kesStatusPill.cls"
            >
              {{ kesStatusPill.label }}
            </span>
            <span
              v-if="remainingKESSeriesCategoryLabels.length"
              class="jm-mono"
            >
              {{ remainingKESSeriesCategoryLabels.length }} core tracked
            </span>
          </div>
        </div>

        <div class="jm-card jm-kpi-card">
          <div class="jm-kpi-top">
            <span class="jm-kpi-label">Network Peers</span>
            <span
              class="jm-kpi-icon"
              style="background: var(--jm-amber-soft); color: var(--jm-amber);"
            >
              <font-awesome-icon
                :icon="['fas', 'network-wired']"
                aria-hidden="true"
              />
            </span>
          </div>
          <div class="jm-kpi-value jm-mono">
            {{ totalPeers }} <span class="jm-kpi-unit">connected</span>
          </div>
          <div class="jm-kpi-foot">
            <span
              class="jm-mono"
              style="color: #7dd3fc;"
            >↑ {{ totalOutgoingPeers }} Outgoing</span>
            <span
              class="jm-mono"
              style="color: #6ee7b7;"
            >↓ {{ totalIncomingPeers }} Incoming</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Telemetry Charts Grid -->
    <div class="jm-charts-grid">
      <NodeChart
        title="Block Height"
        :series="blockHeightSeries"
        :colors="nodeColors"
        curve="stepline"
      />
      <StackedBarChart
        title="KES Days Remaining"
        :series="remainingKESSeries"
        :categories="remainingKESSeriesCategoryLabels"
      />
      <NodeChart
        title="Outgoing Peers"
        :series="peersSeries"
        :colors="nodeColors"
        :min="0"
      />
      <NodeChart
        title="Incoming Peers"
        :series="incomingPeersSeries"
        :colors="nodeColors"
        :min="0"
      />
      <div class="jm-chart-span-full">
        <NodeChart
          title="Transactions Processed"
          :series="txsProcessedSeries"
          :colors="nodeColors"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useJorManagerStore } from '@/stores/jormanager'
import NodeChart from '@/components/NodeChart.vue'
import StackedBarChart from '@/components/StackedBarChart.vue'

const store = useJorManagerStore()
const {
  nodes,
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

const ringCircumference = 2 * Math.PI * 76

const epochProgressPercent = computed(() => {
  if (!epochLength.value || epochLength.value <= 0) return 0
  const ratio = Math.max(0, Math.min(1, slot.value / epochLength.value))
  return ratio * 100
})

const ringDashOffset = computed(() => {
  return ringCircumference * (1 - epochProgressPercent.value / 100)
})

const epochRemainingClass = computed(() => {
  const epochTimeRemainingSecs = epochLength.value - slot.value
  const ratio = epochTimeRemainingSecs / epochLength.value
  if (ratio > 0.4) return 'text-success'
  if (ratio > 0.2) return 'text-warning'
  return 'text-danger'
})

const ringStartColor = computed(() => {
  if (epochRemainingClass.value === 'text-danger') return '#f43f5e'
  if (epochRemainingClass.value === 'text-warning') return '#f59e0b'
  return '#38bdf8'
})

const ringEndColor = computed(() => {
  if (epochRemainingClass.value === 'text-danger') return '#fb7185'
  if (epochRemainingClass.value === 'text-warning') return '#fbbf24'
  return '#10b981'
})

const epochTimeRemaining = computed(() => {
  const time = Math.max(0, epochLength.value - slot.value)
  const days = Math.floor(time / 60 / 60 / 24)
  const hours = Math.floor(time / 60 / 60) % 24
  const minutes = Math.floor(time / 60) % 60
  const seconds = Math.floor(time % 60)
  return `${days}d ${hours}h ${minutes}m ${seconds}s`
})

const coreNodeCount = computed(() =>
  nodes.value.filter(n => n.type !== 'relay' && !n.isDeleted).length
)

const relayNodeCount = computed(() =>
  nodes.value.filter(n => n.type === 'relay' && !n.isDeleted).length
)

const latestBlockHeights = computed(() =>
  blockHeightSeries.value
    .map(s => s.data[s.data.length - 1]?.[1])
    .filter((v): v is number => typeof v === 'number' && Number.isFinite(v))
)

const highestTip = computed(() =>
  latestBlockHeights.value.length ? Math.max(...latestBlockHeights.value) : 0
)

const tipDelta = computed(() => {
  if (latestBlockHeights.value.length === 0) return null
  return highestTip.value - Math.min(...latestBlockHeights.value)
})

const minKesDays = computed(() => {
  const count = remainingKESSeriesCategoryLabels.value.length
  if (count === 0) return null
  const lows = remainingKESSeries.value[0]?.data ?? []
  const oks = remainingKESSeries.value[1]?.data ?? []
  const goods = remainingKESSeries.value[2]?.data ?? []
  const totals: number[] = []
  for (let i = 0; i < count; i++) {
    const sum = (lows[i] ?? 0) + (oks[i] ?? 0) + (goods[i] ?? 0)
    if (sum > 0) totals.push(sum)
  }
  return totals.length ? Math.min(...totals) : null
})

const kesStatusPill = computed(() => {
  if (minKesDays.value === null) return { cls: 'jm-pill-slate', label: 'No KES data yet' }
  if (minKesDays.value <= 5) return { cls: 'jm-pill-rose', label: 'Critical — Rotate Now' }
  if (minKesDays.value <= 20) return { cls: 'jm-pill-amber', label: 'Warning — Rotate Soon' }
  return { cls: 'jm-pill-emerald', label: 'All Keys Healthy' }
})

const totalOutgoingPeers = computed(() =>
  peersSeries.value.reduce((acc, s) => acc + (s.data[s.data.length - 1]?.[1] ?? 0), 0)
)

const totalIncomingPeers = computed(() =>
  incomingPeersSeries.value.reduce((acc, s) => acc + (s.data[s.data.length - 1]?.[1] ?? 0), 0)
)

const totalPeers = computed(() => totalOutgoingPeers.value + totalIncomingPeers.value)
</script>

<style scoped>
.jm-dash-hero {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 1.15rem;
  margin-block-end: 1.15rem;
}

.jm-epoch-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 1.35rem 1.25rem;
  text-align: center;
  background:
    radial-gradient(circle at 50% 0%, rgba(56, 189, 248, 0.13), transparent 65%),
    var(--jm-bg-surface);
}

.jm-epoch-eyebrow {
  font-size: 0.74rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--jm-text-secondary);
}

.jm-epoch-ring-wrap {
  position: relative;
  width: 172px;
  height: 172px;
  display: grid;
  place-items: center;
  margin: 0.5rem 0 0.75rem;
}

.jm-epoch-ring-svg {
  width: 172px;
  height: 172px;
  transform: rotate(-90deg);
  filter: drop-shadow(0 0 12px rgba(56, 189, 248, 0.2));
}

.jm-epoch-ring-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.jm-epoch-label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--jm-text-secondary);
  font-weight: 600;
}

.jm-epoch-number {
  font-size: 1.95rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--jm-text-primary);
}

.jm-epoch-pct {
  font-size: 0.76rem;
  color: var(--jm-cyan);
  margin-top: 0.15rem;
  font-weight: 600;
}

.jm-epoch-countdown {
  font-size: 1.22rem;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.jm-epoch-slot {
  margin-top: 0.3rem;
  font-size: 0.75rem;
  color: var(--jm-text-secondary);
}

.jm-kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1.15rem;
}

.jm-kpi-unit {
  font-size: 0.88rem;
  font-weight: 500;
  color: var(--jm-text-secondary);
}

.jm-charts-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1.15rem;
}

.jm-chart-span-full {
  grid-column: 1 / -1;
}

@media (max-width: 1080px) {
  .jm-dash-hero,
  .jm-charts-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .jm-kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
