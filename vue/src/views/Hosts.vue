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

    <div
      v-if="mismatchedHosts.length > 0"
      class="jm-security-banner mb-3"
      role="alert"
      data-testid="host-key-mismatch-banner"
    >
      <div class="jm-security-banner-head">
        <span class="jm-security-banner-icon" aria-hidden="true">
          <font-awesome-icon :icon="['fas', 'exclamation-triangle']" />
        </span>
        <div>
          <h2 class="jm-security-banner-title">SSH Host Key Mismatch Detected — Connections Blocked</h2>
          <p class="jm-security-banner-desc mb-0">
            Remote SSH execution and file transfers to the affected server(s) are blocked because the presented host key does not match the database-pinned fingerprint. Verify the server identity out-of-band (<code class="jm-mono">ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub</code>) before accepting or resetting.
          </p>
        </div>
      </div>

      <div class="jm-mismatch-list mt-3">
        <div
          v-for="host in mismatchedHosts"
          :key="host.id ?? host.hostname"
          class="jm-mismatch-item"
          data-testid="mismatch-host-card"
        >
          <div class="jm-mismatch-host-meta">
            <div class="d-flex align-items-center gap-2 flex-wrap">
              <span class="jm-mono fw-semibold">{{ host.hostname }}:{{ host.sshPort }}</span>
              <span class="jm-pill jm-pill-rose">
                <font-awesome-icon :icon="['fas', 'shield-halved']" aria-hidden="true" />
                Blocked
              </span>
            </div>
            <div class="jm-action-group">
              <BButton
                size="sm"
                variant="outline-success"
                data-testid="accept-mismatch-btn"
                :disabled="submittingHostId === host.id"
                @click="promptAcceptFingerprint(host)"
              >
                <font-awesome-icon :icon="['fas', 'check']" class="me-1" aria-hidden="true" />
                Accept New Key
              </BButton>
              <BButton
                size="sm"
                variant="outline-secondary"
                data-testid="reset-mismatch-btn"
                :disabled="submittingHostId === host.id"
                @click="promptResetFingerprint(host)"
              >
                <font-awesome-icon :icon="['fas', 'rotate']" class="me-1" aria-hidden="true" />
                Reset Fingerprint
              </BButton>
            </div>
          </div>

          <div class="jm-fingerprint-compare mt-2">
            <div class="jm-fp-box">
              <span class="jm-fp-label">Stored (Trusted) Fingerprint</span>
              <code class="jm-mono jm-fp-value">{{ host.sshFingerprint || '—' }}</code>
            </div>
            <div class="jm-fp-box mismatch">
              <span class="jm-fp-label">Received (Untrusted) Fingerprint</span>
              <code class="jm-mono jm-fp-value">{{ host.sshMismatchFingerprint }}</code>
            </div>
          </div>
        </div>
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
        <div v-if="remoteCount > 0" class="jm-kpi-foot">
          <span>{{ pinnedRemoteCount }} pinned (TOFU)</span>
          <span v-if="mismatchedHosts.length > 0" style="color: var(--jm-rose);">
            {{ mismatchedHosts.length }} mismatch
          </span>
        </div>
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
        <template #cell(sshFingerprint)="data">
          <span v-if="data.item.type !== 'remote'" class="text-secondary">—</span>
          <div v-else-if="data.item.sshMismatchFingerprint" class="d-inline-flex align-items-center gap-2 flex-wrap">
            <span class="jm-pill jm-pill-rose">
              <font-awesome-icon :icon="['fas', 'exclamation-triangle']" aria-hidden="true" />
              Key Mismatch
            </span>
            <span
              class="jm-addr-chip jm-mono jm-addr-chip-rose"
              :title="`Stored: ${data.item.sshFingerprint || '—'} | Received: ${data.item.sshMismatchFingerprint}`"
            >
              {{ formatFingerprint(data.item.sshMismatchFingerprint) }}
            </span>
          </div>
          <div v-else-if="data.item.sshFingerprint" class="d-inline-flex align-items-center gap-2 flex-wrap">
            <span class="jm-pill jm-pill-emerald">
              <font-awesome-icon :icon="['fas', 'shield-halved']" aria-hidden="true" />
              Pinned
            </span>
            <span class="jm-addr-chip jm-mono" :title="data.item.sshFingerprint">
              {{ formatFingerprint(data.item.sshFingerprint) }}
            </span>
            <button
              type="button"
              class="jm-icon-btn cyan"
              :aria-label="`Copy SSH fingerprint for ${data.item.hostname}`"
              v-b-tooltip.hover.top="'Copy full SSH fingerprint'"
              @click="copyFingerprint(data.item)"
            >
              <font-awesome-icon
                :icon="['fas', copiedHostKey === (data.item.id ?? data.item.hostname) ? 'check' : 'copy']"
                aria-hidden="true"
              />
            </button>
          </div>
          <span v-else class="jm-pill jm-pill-amber" title="Fingerprint will be pinned automatically on initial SSH connection">
            <font-awesome-icon :icon="['fas', 'clock']" aria-hidden="true" />
            Pending TOFU
          </span>
        </template>
        <template #cell(nodeHomePath)="{ value }">
          <span class="jm-mono small text-secondary">{{ value || '—' }}</span>
        </template>
        <template #cell(edit)="data">
          <div class="d-inline-flex align-items-center justify-content-end gap-1">
            <button
              v-if="data.item.type === 'remote' && data.item.sshMismatchFingerprint && data.item.id != null"
              type="button"
              class="jm-icon-btn emerald"
              :aria-label="`Accept updated SSH key for ${data.item.hostname}`"
              v-b-tooltip.hover.left="{ title: 'Accept New SSH Key', variant: 'success' }"
              :disabled="submittingHostId === data.item.id"
              @click="promptAcceptFingerprint(data.item)"
            >
              <font-awesome-icon :icon="['fas', 'check']" aria-hidden="true" />
            </button>
            <button
              v-if="data.item.type === 'remote' && (data.item.sshFingerprint || data.item.sshMismatchFingerprint) && data.item.id != null"
              type="button"
              class="jm-icon-btn rose"
              :aria-label="`Reset SSH fingerprint for ${data.item.hostname}`"
              v-b-tooltip.hover.left="{ title: 'Reset SSH Fingerprint (Re-run TOFU)', variant: 'danger' }"
              :disabled="submittingHostId === data.item.id"
              @click="promptResetFingerprint(data.item)"
            >
              <font-awesome-icon :icon="['fas', 'rotate']" aria-hidden="true" />
            </button>
            <button
              type="button"
              class="jm-icon-btn amber"
              aria-label="View or Edit this Host"
              v-b-tooltip.hover.left="{ title: 'Host Details & Edit', variant: 'warning' }"
              @click="editHostClick(data.index)"
            >
              <font-awesome-icon :icon="['fas', 'edit']" aria-hidden="true" />
            </button>
          </div>
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { BButton, BTable } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { generateUUID } from '@/utils/filters'
import AddEditHostModal from '@/components/AddEditHostModal.vue'
import type { Host } from '@/types'

interface PendingFingerprintPayload {
  requestId: string
  hostId: number
  action: 'reset' | 'accept'
  fingerprint?: string | null
}

const FINGERPRINT_CONFIRM_ACTION = 'hosts-view-fingerprint-action'

const store = useJorManagerStore()
const { hosts, mismatchedHosts } = storeToRefs(store)
const emitter = useEventBus()

const submittingHostId = ref<number | null>(null)
const copiedHostKey = ref<number | string | null>(null)

const remoteCount = computed(() => hosts.value.filter(h => h.type === 'remote').length)
const localCount = computed(() => hosts.value.filter(h => h.type === 'local').length)
const pinnedRemoteCount = computed(
  () => hosts.value.filter(h => h.type === 'remote' && Boolean(h.sshFingerprint) && !h.sshMismatchFingerprint).length
)

const fields = [
  { key: 'hostname', label: 'Hostname', sortable: true },
  { key: 'type', label: 'Connection', sortable: true },
  { key: 'sshUser', label: 'User' },
  { key: 'sshPort', label: 'SSH Port' },
  { key: 'sshFingerprint', label: 'SSH Host Key' },
  { key: 'nodeHomePath', label: 'Node Home Path' },
  { key: 'edit', label: '', thClass: 'text-end', tdClass: 'text-end' }
]

function formatFingerprint(fp: string): string {
  if (fp.length <= 28) return fp
  return `${fp.slice(0, 19)}…${fp.slice(-8)}`
}

async function copyFingerprint(host: Host) {
  if (!host.sshFingerprint || !navigator?.clipboard?.writeText) return
  try {
    await navigator.clipboard.writeText(host.sshFingerprint)
    copiedHostKey.value = host.id ?? host.hostname
    window.setTimeout(() => {
      if (copiedHostKey.value === (host.id ?? host.hostname)) {
        copiedHostKey.value = null
      }
    }, 1800)
  } catch {
    // Clipboard unavailable in non-secure context
  }
}

function addHostClick() {
  emitter.emit('add-host')
}

function editHostClick(index: number) {
  emitter.emit('edit-host', hosts.value[index])
}

function promptResetFingerprint(host: Host) {
  if (host.id == null) return
  const payload: PendingFingerprintPayload = {
    requestId: generateUUID(),
    hostId: host.id,
    action: 'reset'
  }
  emitter.emit('show-spending-password-modal', {
    action: FINGERPRINT_CONFIRM_ACTION,
    data: payload
  })
}

function promptAcceptFingerprint(host: Host) {
  if (host.id == null || !host.sshMismatchFingerprint) return
  const payload: PendingFingerprintPayload = {
    requestId: generateUUID(),
    hostId: host.id,
    action: 'accept',
    fingerprint: host.sshMismatchFingerprint
  }
  emitter.emit('show-spending-password-modal', {
    action: FINGERPRINT_CONFIRM_ACTION,
    data: payload
  })
}

async function handleConfirmedPassword(event: unknown) {
  const confirmed = event as {
    action: string
    password: string
    originalData?: PendingFingerprintPayload
  }
  if (confirmed?.action !== FINGERPRINT_CONFIRM_ACTION || !confirmed.originalData) return
  const { requestId, hostId, action, fingerprint } = confirmed.originalData
  submittingHostId.value = hostId
  try {
    await store.updateHostFingerprint(requestId, {
      hostId,
      action,
      fingerprint,
      spendingPassword: confirmed.password
    })
    store.requestHosts()
  } catch {
    // Error toast is already surfaced by handleMessage
  } finally {
    submittingHostId.value = null
  }
}

onMounted(() => {
  store.requestHosts()
  emitter.on('confirm-spending-password-with-action', handleConfirmedPassword)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', handleConfirmedPassword)
})
</script>

<style scoped>
.jm-security-banner {
  background: linear-gradient(180deg, rgba(244, 63, 94, 0.14), rgba(15, 22, 36, 0.94));
  border: 1px solid rgba(244, 63, 94, 0.45);
  border-left: 4px solid var(--jm-rose);
  border-radius: var(--jm-radius-lg);
  padding: 1.15rem 1.25rem;
  box-shadow: var(--jm-shadow-card);
}

.jm-security-banner-head {
  display: flex;
  align-items: flex-start;
  gap: 0.85rem;
}

.jm-security-banner-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--jm-rose-soft);
  border: 1px solid rgba(244, 63, 94, 0.4);
  color: #fda4af;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 1rem;
}

.jm-security-banner-title {
  margin: 0 0 0.25rem;
  font-size: 0.98rem;
  font-weight: 700;
  color: #ffe4e6;
}

.jm-security-banner-desc {
  font-size: 0.83rem;
  color: #cbd5e1;
  line-height: 1.45;
}

.jm-mismatch-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.jm-mismatch-item {
  background: rgba(8, 12, 20, 0.72);
  border: 1px solid rgba(244, 63, 94, 0.28);
  border-radius: var(--jm-radius-md);
  padding: 0.9rem 1rem;
}

.jm-mismatch-host-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.jm-fingerprint-compare {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}

.jm-fp-box {
  background: var(--jm-bg-input);
  border: 1px solid var(--jm-border-subtle);
  border-radius: var(--jm-radius-sm);
  padding: 0.55rem 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
}

.jm-fp-box.mismatch {
  border-color: rgba(244, 63, 94, 0.45);
  background: rgba(244, 63, 94, 0.08);
}

.jm-fp-label {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--jm-text-secondary);
}

.jm-fp-box.mismatch .jm-fp-label {
  color: #fda4af;
}

.jm-fp-value {
  font-size: 0.78rem;
  color: var(--jm-text-primary);
  overflow-wrap: anywhere;
}

.jm-addr-chip-rose {
  border-color: rgba(244, 63, 94, 0.4);
  color: #fecdd3;
}

@media (max-width: 820px) {
  .jm-fingerprint-compare {
    grid-template-columns: 1fr;
  }
}
</style>
