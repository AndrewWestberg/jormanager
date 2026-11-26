<template>
  <div>
    <div class="jm-page-header">
      <div>
        <h1 class="jm-page-title">
          Block Production
        </h1>
        <p class="jm-page-subtitle">
          Leader schedules, forged blocks, and epoch outcome analytics
        </p>
      </div>
      <div class="jm-action-group">
        <BButton
          v-b-tooltip.hover.bottom="'Calculate Leader Logs.'"
          variant="primary"
          @click="showLeaderLogsModal"
        >
          <font-awesome-icon
            :icon="['fas', 'clipboard-list']"
            class="me-1"
            aria-hidden="true"
          />
          Leader Logs
        </BButton>
      </div>
    </div>

    <!-- Epoch & Pool Filter Controls -->
    <div class="jm-card p-3 mb-3">
      <div class="row g-3 align-items-center">
        <div class="col-md-6">
          <BFormGroup
            label="Epoch"
            label-cols="3"
            label-align="right"
            class="mb-0"
          >
            <BFormSelect
              v-model="selectedEpoch"
              :options="epochOptions"
            >
              <template #first>
                <BFormSelectOption :value="null">
                  -- Current --
                </BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
        </div>
        <div class="col-md-6">
          <BFormGroup
            label="Pool"
            label-cols="3"
            label-align="right"
            class="mb-0"
          >
            <BFormSelect
              v-model="selectedPool"
              :options="coreNodeOptions"
            >
              <template #first>
                <BFormSelectOption :value="null">
                  -- All --
                </BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
        </div>
      </div>
    </div>

    <!-- 6 Interactive Clickable Status KPI Cards -->
    <div
      class="jm-blocks-kpi-grid"
      role="group"
      aria-label="Filter blocks by status"
    >
      <button
        v-for="card in statusCards"
        :key="card.key"
        type="button"
        class="jm-block-stat-btn"
        :class="{ active: statusFilter === card.key }"
        @click="statusFilter = card.key"
      >
        <span
          class="jm-block-stat-label"
          :style="{ color: card.color }"
        >
          <font-awesome-icon
            v-if="card.icon"
            :icon="card.icon"
            class="me-1"
            aria-hidden="true"
          />
          {{ card.label }}
        </span>
        <span
          class="jm-block-stat-val jm-mono"
          :style="{ color: card.color }"
        >{{ card.count }}</span>
      </button>
    </div>

    <!-- Outcome Distribution Bar -->
    <div
      v-if="totalRows > 0"
      class="jm-card p-3 mb-3"
    >
      <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 small">
        <span class="text-secondary fw-semibold">
          Outcome Distribution <span class="fw-normal opacity-75">(click any card above to filter)</span>
        </span>
        <span
          v-if="finalizedRows > 0"
          class="jm-mono text-success fw-semibold"
        >
          {{ successRate.toFixed(1) }}% Success ({{ forgedRows + completedRows }}/{{ finalizedRows }} finalized)
        </span>
        <span
          v-else
          class="jm-mono text-secondary"
        >All scheduled blocks pending</span>
      </div>
      <div
        class="jm-dist-bar mt-2"
        aria-hidden="true"
      >
        <div
          v-if="forgedRows"
          :style="{ width: pct(forgedRows), background: 'var(--jm-emerald)' }"
        />
        <div
          v-if="completedRows"
          :style="{ width: pct(completedRows), background: 'var(--jm-cyan)' }"
        />
        <div
          v-if="pendingRows"
          :style="{ width: pct(pendingRows), background: '#475569' }"
        />
        <div
          v-if="orphanedRows"
          :style="{ width: pct(orphanedRows), background: 'var(--jm-amber)' }"
        />
        <div
          v-if="missedRows"
          :style="{ width: pct(missedRows), background: 'var(--jm-rose)' }"
        />
      </div>
    </div>

    <div
      v-if="displayedBlocks.length > 0"
      class="jm-table-wrapper"
      tabindex="0"
      role="region"
      aria-label="Block Production Schedule"
    >
      <BTable
        small
        hover
        striped
        :items="displayedBlocks"
        :fields="fields"
      >
        <template #cell(num)="{ index }">
          <span class="jm-mono text-secondary">{{ displayedBlocks.length - index }}</span>
        </template>
        <template #cell(status)="{ value }">
          <span
            v-b-tooltip.hover.right="blockTooltip(String(value))"
            class="jm-pill"
            :class="blockPillClass(String(value))"
          >
            <font-awesome-icon
              :icon="blockIcon(String(value))"
              aria-hidden="true"
            />
            {{ startCase(String(value)) }}
          </span>
        </template>
        <template #cell(at)="{ value }">
          <span class="jm-mono small">{{ value }}</span>
        </template>
        <template #cell(epoch)="{ value }">
          <span class="jm-mono">{{ value }}</span>
        </template>
        <template #cell(slotInEpoch)="{ value }">
          <span class="jm-mono">{{ Number(value).toLocaleString() }}</span>
        </template>
        <template #cell(slot)="{ value }">
          <span class="jm-mono text-secondary">{{ Number(value).toLocaleString() }}</span>
        </template>
        <template #cell(pool)="{ value }">
          <span class="jm-pill jm-pill-indigo jm-mono">{{ value }}</span>
        </template>
        <template #cell(hash)="{ value }">
          <a
            v-if="value && String(value) !== ''"
            class="jm-addr-chip jm-mono text-decoration-none"
            :href="'https://explorer.cardano.org/en/block.html?id=' + value"
            target="_explorer"
            rel="noopener noreferrer"
          >
            <span>{{ String(value).substring(0, 8) }}…</span>
            <font-awesome-icon
              :icon="['fas', 'arrow-up-right-from-square']"
              class="small text-info"
              aria-hidden="true"
            />
          </a>
          <span
            v-else
            class="text-secondary"
          >—</span>
        </template>
      </BTable>
    </div>

    <div
      v-else
      class="jm-card jm-empty-state"
    >
      <div class="jm-empty-icon">
        <font-awesome-icon
          :icon="['fas', 'cubes']"
          aria-hidden="true"
        />
      </div>
      <h2 class="h6 mb-1">
        No blocks to display
      </h2>
      <p class="small mb-3">
        {{ blocks.length === 0 ? 'Request Leader Logs to populate scheduled blocks for your pools.' : 'No blocks match the current epoch, pool, and status filters.' }}
      </p>
      <BButton
        v-if="statusFilter !== 'all'"
        variant="secondary"
        size="sm"
        @click="statusFilter = 'all'"
      >
        Show All Statuses
      </BButton>
    </div>

    <BModal
      id="modal-leader-logs"
      v-model="showLeaderLogs"
      title="Leader Logs Request"
      :no-close-on-backdrop="true"
      @ok="handleLeaderLogs"
    >
      <BFormGroup label="Request Type">
        <BFormRadioGroup v-model="formLeaderLogs.requestType">
          <BFormRadio
            value="currentEpoch"
            class="me-3"
          >
            <font-awesome-icon
              :icon="['fas', 'clipboard-list']"
              class="me-1"
            />Current Epoch
          </BFormRadio>
          <BFormRadio value="futureEpoch">
            <font-awesome-icon
              :icon="['fas', 'hat-wizard']"
              class="me-1"
            />Future Epoch
          </BFormRadio>
        </BFormRadioGroup>
      </BFormGroup>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { startCase } from 'lodash-es'
import {
  BButton,
  BFormGroup,
  BFormSelect,
  BFormSelectOption,
  BTable,
  BModal,
  BFormRadioGroup,
  BFormRadio,
  type TableFieldRaw
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import type { Block } from '@/types'

const store = useJorManagerStore()
const { blocks, epoch } = storeToRefs(store)
const emitter = useEventBus()

const selectedEpoch = ref<number | null>(null)
const selectedPool = ref<string | null>(null)
const statusFilter = ref<'all' | 'pending' | 'completed' | 'forged' | 'orphaned' | 'missed'>('all')
const showLeaderLogs = ref(false)
const pendingAction = ref<string | null>(null)
const formLeaderLogs = ref({
  spendingPassword: null as string | null,
  requestType: 'currentEpoch'
})

const fields: TableFieldRaw<Block>[] = [
  { key: 'num', label: '#' },
  { key: 'status', label: 'Status' },
  { key: 'at', label: 'Timestamp' },
  { key: 'epoch', label: 'Epoch' },
  { key: 'slotInEpoch', label: 'Slot' },
  { key: 'slot', label: 'Full Slot' },
  { key: 'pool', label: 'Pool' },
  {
    key: 'hash',
    label: 'Block Hash',
    formatter: ({ value }) => (value ? String(value).replace(/"/g, '') : '')
  }
]

const epochOptions = computed(() => store.epochSelectOptions)
const coreNodeOptions = computed(() => store.coreNodeSelectOptions)

const filteredBlocks = computed(() => {
  const targetEpoch = selectedEpoch.value ?? epoch.value
  return blocks.value.filter((block: Block) => {
    const matchPool = selectedPool.value == null || block.pool === selectedPool.value
    const matchEpoch = targetEpoch == null || block.epoch === targetEpoch
    return matchPool && matchEpoch
  })
})

const totalRows = computed(() => filteredBlocks.value.length)
const pendingRows = computed(() => filteredBlocks.value.filter((b: Block) => b.status === 'pending').length)
const forgedRows = computed(() => filteredBlocks.value.filter((b: Block) => b.status === 'forged').length)
const orphanedRows = computed(() => filteredBlocks.value.filter((b: Block) => b.status === 'orphaned').length)
const completedRows = computed(() => filteredBlocks.value.filter((b: Block) => b.status === 'completed').length)
const missedRows = computed(() => filteredBlocks.value.filter((b: Block) => {
  const status = b.status ?? ''
  return status === 'missed' || !['pending', 'forged', 'orphaned', 'completed'].includes(status)
}).length)

const finalizedRows = computed(() => totalRows.value - pendingRows.value)
const successRate = computed(() =>
  finalizedRows.value > 0 ? ((forgedRows.value + completedRows.value) / finalizedRows.value) * 100 : 0
)

const displayedBlocks = computed(() => {
  if (statusFilter.value === 'all') return filteredBlocks.value
  if (statusFilter.value === 'missed') {
    return filteredBlocks.value.filter((b: Block) => {
      const s = b.status ?? ''
      return s === 'missed' || !['pending', 'forged', 'orphaned', 'completed'].includes(s)
    })
  }
  return filteredBlocks.value.filter((b: Block) => b.status === statusFilter.value)
})

const statusCards = computed(() => [
  { key: 'all' as const, label: 'Total', count: totalRows.value, color: 'var(--jm-text-primary)', icon: null },
  { key: 'pending' as const, label: 'Pending', count: pendingRows.value, color: '#cbd5e1', icon: ['fas', 'clock'] as [string, string] },
  { key: 'completed' as const, label: 'Completed', count: completedRows.value, color: 'var(--jm-cyan)', icon: ['fas', 'cube'] as [string, string] },
  { key: 'forged' as const, label: 'Forged', count: forgedRows.value, color: 'var(--jm-emerald)', icon: ['fas', 'hammer'] as [string, string] },
  { key: 'orphaned' as const, label: 'Orphaned', count: orphanedRows.value, color: 'var(--jm-amber)', icon: ['fas', 'ghost'] as [string, string] },
  { key: 'missed' as const, label: 'Missed', count: missedRows.value, color: 'var(--jm-rose)', icon: ['fas', 'dumpster-fire'] as [string, string] }
])

function pct(count: number): string {
  return totalRows.value > 0 ? `${(count / totalRows.value) * 100}%` : '0%'
}

function showLeaderLogsModal() {
  showLeaderLogs.value = true
}

function handleLeaderLogs() {
  pendingAction.value = 'leader-logs'
  emitter.emit('show-spending-password-modal', { action: 'leader-logs' })
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action !== 'leader-logs') return
  formLeaderLogs.value.spendingPassword = data.password
  store.requestLeaderLogs(formLeaderLogs.value)
  formLeaderLogs.value.spendingPassword = null
}

function blockIcon(value: string): [string, string] {
  switch (value) {
    case 'pending': return ['fas', 'clock']
    case 'forged': return ['fas', 'hammer']
    case 'orphaned': return ['fas', 'ghost']
    case 'completed': return ['fas', 'cube']
    case 'missed':
    default: return ['fas', 'dumpster-fire']
  }
}

function blockPillClass(value: string): string {
  switch (value) {
    case 'pending': return 'jm-pill-slate'
    case 'forged': return 'jm-pill-emerald'
    case 'orphaned': return 'jm-pill-amber'
    case 'completed': return 'jm-pill-cyan'
    case 'missed':
    default: return 'jm-pill-rose'
  }
}

function blockTooltip(value: string) {
  const variantMap: Record<string, string> = {
    pending: 'secondary',
    forged: 'success',
    orphaned: 'warning',
    completed: 'primary'
  }
  return { title: startCase(value), variant: variantMap[value] ?? 'danger' }
}

onMounted(() => {
  store.requestBlocks()
  store.requestNodes()
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.jm-blocks-kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 0.75rem;
  margin-block-end: 1rem;
}

.jm-block-stat-btn {
  appearance: none;
  text-align: left;
  background: var(--jm-bg-surface);
  border: 1px solid var(--jm-border-subtle);
  border-radius: var(--jm-radius-md);
  padding: 0.85rem 1rem;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  transition: border-color 0.15s ease, transform 0.15s ease, background-color 0.15s ease;
}

.jm-block-stat-btn:hover {
  border-color: var(--jm-border-strong);
  transform: translateY(-1px);
}

.jm-block-stat-btn.active {
  border-color: var(--jm-cyan);
  background: rgba(56, 189, 248, 0.08);
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.35);
}

.jm-block-stat-label {
  font-size: 0.73rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.jm-block-stat-val {
  font-size: 1.4rem;
  font-weight: 700;
}

.jm-dist-bar {
  height: 10px;
  border-radius: 99px;
  overflow: hidden;
  display: flex;
  gap: 2px;
  background: var(--jm-bg-input);
}

@media (max-width: 1080px) {
  .jm-blocks-kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .jm-blocks-kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
