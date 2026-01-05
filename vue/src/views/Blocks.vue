<template>
  <div>
    <div>
      <BButton
        variant="outline-primary"
        @click="showLeaderLogsModal"
        v-b-tooltip.hover.bottom="'Calculate Leader Logs.'"
      >
        <font-awesome-icon :icon="['fas', 'clipboard-list']" />&nbsp; Leader Logs
      </BButton>
    </div>
    <hr />

    <BContainer fluid>
      <BRow>
        <BCol cols="6">
          <BFormGroup label="Epoch" label-cols="2" label-align="center">
            <BFormSelect v-model="selectedEpoch" :options="epochOptions">
              <template #first>
                <BFormSelectOption :value="null">-- Current --</BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
        </BCol>
        <BCol cols="6">
          <BFormGroup label="Pool" label-cols="2" label-align="center">
            <BFormSelect v-model="selectedPool" :options="coreNodeOptions">
              <template #first>
                <BFormSelectOption :value="null">-- All --</BFormSelectOption>
              </template>
            </BFormSelect>
          </BFormGroup>
        </BCol>
      </BRow>
      <BRow class="mb-3">
        <BCol cols="2"> <strong>Total:</strong> {{ totalRows }}</BCol>
        <BCol cols="2" class="text-secondary">
          <font-awesome-icon :icon="['fas', 'clock']" />
          <strong> Pending:</strong> {{ pendingRows }}
        </BCol>
        <BCol cols="2" class="text-primary">
          <font-awesome-icon :icon="['fas', 'cube']" />
          <strong> Completed:</strong> {{ completedRows }}
        </BCol>
        <BCol cols="2" class="text-success">
          <font-awesome-icon :icon="['fas', 'hammer']" />
          <strong> Forged:</strong> {{ forgedRows }}
        </BCol>
        <BCol cols="2" class="text-warning">
          <font-awesome-icon :icon="['fas', 'ghost']" />
          <strong> Orphaned:</strong> {{ orphanedRows }}
        </BCol>
        <BCol cols="2" class="text-danger">
          <font-awesome-icon :icon="['fas', 'dumpster-fire']" />
          <strong> Missed:</strong> {{ missedRows }}
        </BCol>
      </BRow>
      <BTable
        small
        hover
        dark
        bordered
        striped
        head-variant="light"
        :items="filteredBlocks"
        :fields="fields"
        v-show="blocks.length > 0"
        class="mt-3"
      >
        <template #cell(num)="{ index }">
          {{ totalRows ? totalRows - index : '---' }}
        </template>
        <template #cell(status)="{ value }">
          <div
            :class="blockClass(String(value))"
            class="d-inline-block"
            v-b-tooltip.hover.right="blockTooltip(String(value))"
          >
            <font-awesome-icon :icon="blockIcon(String(value))" />
          </div>
        </template>
        <template #cell(hash)="{ value }">
          <a
            v-show="value !== ''"
            :href="'https://explorer.cardano.org/en/block.html?id=' + value"
            target="_explorer"
          >{{ String(value).substring(0, 6) }}...</a>
        </template>
      </BTable>
    </BContainer>

    <BModal
      id="modal-leader-logs"
      v-model="showLeaderLogs"
      title="Leader Logs Request"
      :no-close-on-backdrop="true"
      @ok="handleLeaderLogs"
    >
      <BFormGroup label="Request Type">
        <BFormRadioGroup v-model="formLeaderLogs.requestType">
          <BFormRadio value="currentEpoch">
            <font-awesome-icon :icon="['fas', 'clipboard-list']" />&nbsp;Current Epoch
          </BFormRadio>
          <BFormRadio value="futureEpoch">
            <font-awesome-icon :icon="['fas', 'hat-wizard']" />&nbsp;Future Epoch
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
  BContainer,
  BRow,
  BCol,
  BFormGroup,
  BFormSelect,
  BFormSelectOption,
  BTable,
  BModal,
  BFormRadioGroup,
  BFormRadio
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import type { Block } from '@/types'

const store = useJorManagerStore()
const { blocks, epoch } = storeToRefs(store)
const emitter = useEventBus()

const selectedEpoch = ref<number | null>(null)
const selectedPool = ref<string | null>(null)
const showLeaderLogs = ref(false)
const pendingAction = ref<string | null>(null)
const formLeaderLogs = ref({
  spendingPassword: null as string | null,
  requestType: 'currentEpoch'
})

const fields: any[] = [
  { key: 'num' },
  { key: 'status', label: '' },
  { key: 'at', label: 'Timestamp' },
  { key: 'epoch' },
  { key: 'slotInEpoch', label: 'Slot' },
  { key: 'slot', label: 'Full Slot' },
  { key: 'pool' },
  {
    key: 'hash',
    formatter: (value: string) => value.replace(/"/g, '')
  }
]

const epochOptions = computed(() => store.epochSelectOptions)
const coreNodeOptions = computed(() => store.coreNodeSelectOptions)

const filteredBlocks = computed(() => {
  const targetEpoch = selectedEpoch.value ?? epoch.value
  return blocks.value.filter((block: Block) => {
    const matchPool = selectedPool.value == null || (block as any).pool === selectedPool.value
    const matchEpoch = targetEpoch == null || block.epoch === targetEpoch
    return matchPool && matchEpoch
  })
})

const totalRows = computed(() => filteredBlocks.value.length)
const pendingRows = computed(() => filteredBlocks.value.filter((b: Block) => (b as any).status === 'pending').length)
const forgedRows = computed(() => filteredBlocks.value.filter((b: Block) => (b as any).status === 'forged').length)
const orphanedRows = computed(() => filteredBlocks.value.filter((b: Block) => (b as any).status === 'orphaned').length)
const completedRows = computed(() => filteredBlocks.value.filter((b: Block) => (b as any).status === 'completed').length)
const missedRows = computed(() => filteredBlocks.value.filter((b: Block) => {
  const status = (b as any).status
  return status === 'missed' || !['pending', 'forged', 'orphaned', 'completed'].includes(status)
}).length)

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

function blockClass(value: string): string {
  switch (value) {
    case 'pending': return 'text-secondary'
    case 'forged': return 'text-success'
    case 'orphaned': return 'text-warning'
    case 'completed': return 'text-primary'
    case 'missed':
    default: return 'text-danger'
  }
}

function blockTooltip(value: string) {
  const variant = blockClass(value).replace('text-', '')
  return { title: startCase(value), variant }
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