<template>
  <div>
    <div class="jm-page-header">
      <div>
        <h1 class="jm-page-title">Hosts</h1>
        <p class="jm-page-subtitle">Manage local and remote SSH servers running your Cardano nodes</p>
      </div>
      <div class="jm-action-group">
        <BButton
          variant="primary"
          @click="addHostClick"
          v-b-tooltip.hover.bottom="'Add a connection to a new remote or local server.'"
        >
          <font-awesome-icon :icon="['fas', 'plus']" class="me-1" aria-hidden="true" />
          Host
        </BButton>
      </div>
    </div>

    <div v-if="hosts.length > 0" class="jm-summary-grid-3">
      <div class="jm-card jm-kpi-card">
        <span class="jm-kpi-label">Total Hosts</span>
        <div class="jm-kpi-value jm-mono">{{ hosts.length }}</div>
      </div>
      <div class="jm-card jm-kpi-card">
        <span class="jm-kpi-label">Remote SSH</span>
        <div class="jm-kpi-value jm-mono" style="color: var(--jm-cyan);">{{ remoteCount }}</div>
      </div>
      <div class="jm-card jm-kpi-card">
        <span class="jm-kpi-label">Local Machine</span>
        <div class="jm-kpi-value jm-mono" style="color: var(--jm-emerald);">{{ localCount }}</div>
      </div>
    </div>

    <div
      v-if="hosts.length > 0"
      class="jm-table-wrapper"
      tabindex="0"
      role="region"
      aria-label="Configured Hosts"
    >
      <BTable
        small
        hover
        striped
        :items="hosts"
        :fields="fields"
      >
        <template #cell(hostname)="{ value }">
          <span class="jm-mono fw-semibold">{{ value }}</span>
        </template>
        <template #cell(type)="{ value }">
          <span class="jm-pill" :class="value === 'remote' ? 'jm-pill-cyan' : 'jm-pill-emerald'">
            {{ value === 'remote' ? 'Remote SSH' : 'Local' }}
          </span>
        </template>
        <template #cell(sshUser)="{ value }">
          <span class="jm-mono">{{ value || '—' }}</span>
        </template>
        <template #cell(sshPort)="data">
          <span v-if="data.item.type === 'remote'" class="jm-mono">{{ data.value }}</span>
          <span v-else class="text-secondary">—</span>
        </template>
        <template #cell(nodeHomePath)="{ value }">
          <span class="jm-mono small text-secondary">{{ value || '—' }}</span>
        </template>
        <template #cell(edit)="data">
          <button
            type="button"
            class="jm-icon-btn amber"
            aria-label="Edit this Host"
            v-b-tooltip.hover.left="{ title: 'Edit this Host', variant: 'warning' }"
            @click="editHostClick(data.index)"
          >
            <font-awesome-icon :icon="['fas', 'edit']" aria-hidden="true" />
          </button>
        </template>
      </BTable>
    </div>

    <div v-else class="jm-card jm-empty-state">
      <div class="jm-empty-icon">
        <font-awesome-icon :icon="['fas', 'server']" aria-hidden="true" />
      </div>
      <h2 class="h6 mb-1">No hosts configured yet</h2>
      <p class="small mb-3">Add your local server or a remote SSH host to start provisioning Cardano nodes.</p>
      <BButton variant="primary" size="sm" @click="addHostClick">+ Add Host</BButton>
    </div>

    <AddEditHostModal />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { BButton, BTable } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import AddEditHostModal from '@/components/AddEditHostModal.vue'

const store = useJorManagerStore()
const { hosts } = storeToRefs(store)
const emitter = useEventBus()

const remoteCount = computed(() => hosts.value.filter(h => h.type === 'remote').length)
const localCount = computed(() => hosts.value.filter(h => h.type === 'local').length)

const fields = [
  { key: 'hostname', label: 'Hostname', sortable: true },
  { key: 'type', label: 'Connection', sortable: true },
  { key: 'sshUser', label: 'User' },
  { key: 'sshPort', label: 'SSH Port' },
  { key: 'nodeHomePath', label: 'Node Home Path' },
  { key: 'edit', label: '', thClass: 'text-end', tdClass: 'text-end' }
]

function addHostClick() {
  emitter.emit('add-host')
}

function editHostClick(index: number) {
  emitter.emit('edit-host', hosts.value[index])
}

onMounted(() => {
  store.requestHosts()
})
</script>
