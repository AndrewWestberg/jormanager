<template>
  <div>
    <BModal
      id="modal-edit-host"
      v-model="isVisible"
      :title="formHost.id != null ? 'Host Details & Edit' : 'Add Host'"
      size="lg"
      :no-close-on-backdrop="true"
      ok-title="Validate & Save"
      @ok="handleSubmitAddHost"
    >
      <BForm
        ref="editHostFormRef"
        @submit.stop.prevent="handleValidateAndSave"
      >
        <div class="jm-sub-panel mb-3">
          <h6 class="jm-sub-panel-title">
            Connection
          </h6>
          <div class="row g-3">
            <div class="col-md-6">
              <BFormGroup
                label="Type"
                label-for="type-radio"
              >
                <BFormRadioGroup
                  id="type-radio"
                  v-model="formHost.type"
                  required
                  class="pt-1"
                >
                  <BFormRadio
                    value="local"
                    class="me-3"
                  >
                    Local
                  </BFormRadio>
                  <BFormRadio value="remote">
                    Remote
                  </BFormRadio>
                </BFormRadioGroup>
              </BFormGroup>
            </div>
            <div class="col-md-6">
              <BFormGroup
                label="Hostname"
                label-for="hostname-input"
              >
                <BFormInput
                  id="hostname-input"
                  v-model="formHost.hostname"
                  placeholder="e.g. 'server.mystakepool.io' or '192.168.0.77'"
                  class="jm-mono"
                  required
                />
              </BFormGroup>
            </div>
            <div class="col-md-6">
              <BFormGroup
                :label="userFormLabel"
                label-for="ssh-user-input"
              >
                <BFormInput
                  id="ssh-user-input"
                  v-model="formHost.sshUser"
                  placeholder="e.g. ec2-user"
                  class="jm-mono"
                  :required="isFormRemote"
                />
              </BFormGroup>
            </div>
            <div
              v-if="isFormRemote"
              class="col-md-6"
            >
              <BFormGroup
                label="SSH Port"
                label-for="ssh-port-input"
              >
                <BFormInput
                  id="ssh-port-input"
                  v-model="formHost.sshPort"
                  type="number"
                  placeholder="e.g. 22"
                  class="jm-mono"
                  :required="isFormRemote"
                />
              </BFormGroup>
            </div>
            <div
              v-if="isFormRemote"
              class="col-12"
            >
              <BFormGroup
                label="SSH Key"
                label-for="ssh-key-input"
              >
                <BFormInput
                  id="ssh-key-input"
                  v-model="formHost.sshPemPath"
                  placeholder="e.g. /home/<username>/.ssh/id_rsa"
                  class="jm-mono"
                  :required="isFormRemote"
                />
              </BFormGroup>
            </div>
          </div>
        </div>

        <div
          v-if="isFormRemote"
          class="jm-sub-panel mb-3"
          data-testid="modal-ssh-trust-panel"
        >
          <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
            <h6 class="jm-sub-panel-title mb-0">
              SSH Host Key Trust (TOFU)
            </h6>
            <span
              v-if="formHost.id == null"
              class="jm-pill jm-pill-cyan"
            >
              <font-awesome-icon
                :icon="['fas', 'shield-halved']"
                aria-hidden="true"
              />
              Auto-Pin on First Connect
            </span>
            <span
              v-else-if="activeMismatchFingerprint"
              class="jm-pill jm-pill-rose"
            >
              <font-awesome-icon
                :icon="['fas', 'exclamation-triangle']"
                aria-hidden="true"
              />
              Key Mismatch — Blocked
            </span>
            <span
              v-else-if="activeSavedFingerprint"
              class="jm-pill jm-pill-emerald"
            >
              <font-awesome-icon
                :icon="['fas', 'shield-halved']"
                aria-hidden="true"
              />
              Database-Pinned
            </span>
            <span
              v-else
              class="jm-pill jm-pill-amber"
            >
              <font-awesome-icon
                :icon="['fas', 'clock']"
                aria-hidden="true"
              />
              Pending TOFU
            </span>
          </div>

          <p
            v-if="formHost.id == null"
            class="small text-secondary mb-0"
          >
            JorManager will retrieve and pin the server's <code class="jm-mono">SHA256</code> SSH host key fingerprint in the database during initial validation without relying on local OS <code class="jm-mono">known_hosts</code> files.
          </p>

          <div v-else>
            <div
              v-if="activeMismatchFingerprint"
              class="jm-modal-mismatch-alert mb-3"
              role="alert"
              data-testid="modal-mismatch-warning"
            >
              <div
                class="small fw-semibold mb-2"
                style="color: #ffe4e6;"
              >
                <font-awesome-icon
                  :icon="['fas', 'exclamation-triangle']"
                  class="me-1"
                  aria-hidden="true"
                />
                Remote host presented an untrusted SSH key! Connections are blocked until verified.
              </div>
              <div class="jm-modal-fp-grid mb-2">
                <div class="jm-modal-fp-item">
                  <span class="jm-modal-fp-label">Stored Fingerprint</span>
                  <code class="jm-mono jm-modal-fp-code">{{ activeSavedFingerprint || '—' }}</code>
                </div>
                <div class="jm-modal-fp-item mismatch">
                  <span class="jm-modal-fp-label">Received Fingerprint</span>
                  <code class="jm-mono jm-modal-fp-code">{{ activeMismatchFingerprint }}</code>
                </div>
              </div>
              <div class="d-flex align-items-center gap-2 flex-wrap">
                <BButton
                  size="sm"
                  variant="outline-success"
                  type="button"
                  data-testid="modal-accept-key-btn"
                  :disabled="isUpdatingFingerprint"
                  @click="requestModalFingerprintAction('accept', activeMismatchFingerprint)"
                >
                  <font-awesome-icon
                    :icon="['fas', 'check']"
                    class="me-1"
                    aria-hidden="true"
                  />
                  Accept New Key
                </BButton>
                <BButton
                  size="sm"
                  variant="outline-secondary"
                  type="button"
                  data-testid="modal-reset-key-btn"
                  :disabled="isUpdatingFingerprint"
                  @click="requestModalFingerprintAction('reset')"
                >
                  <font-awesome-icon
                    :icon="['fas', 'rotate']"
                    class="me-1"
                    aria-hidden="true"
                  />
                  Reset Fingerprint
                </BButton>
              </div>
            </div>

            <div class="d-flex align-items-center justify-content-between gap-2 flex-wrap">
              <div
                class="flex-grow-1"
                style="min-width: 0;"
              >
                <label
                  class="form-label mb-1"
                  for="saved-ssh-fingerprint-display"
                >Saved SSH Host Key Fingerprint</label>
                <div class="input-group input-group-sm">
                  <input
                    id="saved-ssh-fingerprint-display"
                    type="text"
                    readonly
                    class="form-control jm-mono"
                    data-testid="saved-ssh-fingerprint-input"
                    :value="activeSavedFingerprint || 'Not yet pinned — will record automatically on first SSH connection'"
                  >
                  <button
                    v-if="activeSavedFingerprint"
                    type="button"
                    class="btn btn-outline-secondary"
                    aria-label="Copy saved SSH fingerprint"
                    @click="copySavedFingerprint"
                  >
                    <font-awesome-icon
                      :icon="['fas', copied ? 'check' : 'copy']"
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    v-if="activeSavedFingerprint && !activeMismatchFingerprint"
                    type="button"
                    class="btn btn-outline-secondary"
                    data-testid="modal-reset-pinned-btn"
                    :disabled="isUpdatingFingerprint"
                    @click="requestModalFingerprintAction('reset')"
                  >
                    <font-awesome-icon
                      :icon="['fas', 'rotate']"
                      class="me-1"
                      aria-hidden="true"
                    />
                    Reset
                  </button>
                </div>
              </div>
            </div>

            <div class="mt-2">
              <button
                type="button"
                class="btn btn-link btn-sm p-0 text-decoration-none"
                data-testid="toggle-manual-fingerprint-btn"
                @click="showManualFingerprint = !showManualFingerprint"
              >
                {{ showManualFingerprint ? 'Hide manual fingerprint entry' : 'Set verified SHA256 fingerprint manually…' }}
              </button>
              <div
                v-if="showManualFingerprint"
                class="input-group input-group-sm mt-2"
              >
                <BFormInput
                  id="manual-fingerprint-input"
                  v-model="manualFingerprint"
                  placeholder="SHA256:43-character-base64-fingerprint"
                  class="jm-mono"
                />
                <BButton
                  size="sm"
                  variant="outline-primary"
                  type="button"
                  data-testid="save-manual-fingerprint-btn"
                  :disabled="isUpdatingFingerprint || !manualFingerprint.trim()"
                  @click="requestModalFingerprintAction('update', manualFingerprint.trim())"
                >
                  Update Key
                </BButton>
              </div>
            </div>
          </div>
        </div>

        <div class="jm-sub-panel">
          <h6 class="jm-sub-panel-title">
            Cardano Binary &amp; Node Paths
          </h6>
          <div class="row g-3">
            <div class="col-12">
              <BFormGroup
                label="cardano-cli Path"
                label-for="cardano-cli-input"
              >
                <BFormInput
                  id="cardano-cli-input"
                  v-model="formHost.cardanoCliPath"
                  placeholder="e.g. /home/<username>/.local/bin/cardano-cli"
                  class="jm-mono"
                  required
                />
              </BFormGroup>
            </div>
            <div class="col-12">
              <BFormGroup
                label="cardano-node Path"
                label-for="cardano-node-input"
              >
                <BFormInput
                  id="cardano-node-input"
                  v-model="formHost.cardanoNodePath"
                  placeholder="e.g. /home/<username>/.local/bin/cardano-node"
                  class="jm-mono"
                  required
                />
              </BFormGroup>
            </div>
            <div class="col-12">
              <BFormGroup
                label="Home Folder for Nodes"
                label-for="node-home-path-input"
              >
                <BFormInput
                  id="node-home-path-input"
                  v-model="formHost.nodeHomePath"
                  placeholder="e.g. /home/<username>/haskell"
                  class="jm-mono"
                  required
                />
              </BFormGroup>
            </div>
            <div class="col-12">
              <BFormGroup
                label="ITN JCLI Path (Optional)"
                label-for="jcli-input"
              >
                <BFormInput
                  id="jcli-input"
                  v-model="formHost.jcliPath"
                  placeholder="e.g. /home/<username>/.cargo/bin/jcli"
                  class="jm-mono"
                />
              </BFormGroup>
            </div>
          </div>
        </div>
      </BForm>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { cloneDeep } from 'lodash-es'
import {
  BButton,
  BModal,
  BForm,
  BFormGroup,
  BFormInput,
  BFormRadioGroup,
  BFormRadio,
  type BvTriggerableEvent
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { generateUUID } from '@/utils/filters'
import type { Host } from '@/types'

interface ModalFingerprintActionPayload {
  requestId: string
  hostId: number
  action: 'reset' | 'accept' | 'update'
  fingerprint?: string | null
}

const MODAL_FINGERPRINT_CONFIRM_ACTION = 'modal-host-fingerprint-action'

const store = useJorManagerStore()
const { hosts, toastSuccess } = storeToRefs(store)
const emitter = useEventBus()

const editHostFormRef = ref<InstanceType<typeof BForm> | null>(null)
const isVisible = ref(false)
const isUpdatingFingerprint = ref(false)
const copied = ref(false)
const showManualFingerprint = ref(false)
const manualFingerprint = ref('')

interface HostForm {
  id?: number
  type: 'local' | 'remote'
  hostname: string
  sshUser: string
  sshPort: number
  sshPemPath: string
  cardanoCliPath: string
  cardanoNodePath: string
  nodeHomePath: string
  jcliPath: string | null
  sshFingerprint?: string | null
  sshMismatchFingerprint?: string | null
}

const defaultFormHost: HostForm = {
  type: 'remote',
  hostname: '',
  sshUser: '',
  sshPort: 22,
  sshPemPath: '',
  cardanoCliPath: '',
  cardanoNodePath: '',
  nodeHomePath: '',
  jcliPath: null,
  sshFingerprint: null,
  sshMismatchFingerprint: null
}

const formHost = ref<HostForm>({ ...defaultFormHost })

const isFormRemote = computed(() => formHost.value.type === 'remote')
const userFormLabel = computed(() => isFormRemote.value ? 'SSH User' : 'User')

const liveHost = computed(() => {
  if (formHost.value.id == null) return null
  return hosts.value.find(h => h.id === formHost.value.id) ?? null
})

const activeSavedFingerprint = computed(
  () => liveHost.value?.sshFingerprint ?? formHost.value.sshFingerprint ?? null
)

const activeMismatchFingerprint = computed(
  () => liveHost.value?.sshMismatchFingerprint ?? formHost.value.sshMismatchFingerprint ?? null
)

function clearFormHost() {
  formHost.value = { ...defaultFormHost }
  showManualFingerprint.value = false
  manualFingerprint.value = ''
  copied.value = false
}

function getFormElement(): HTMLFormElement | null {
  if (!editHostFormRef.value) return null
  if (editHostFormRef.value instanceof HTMLFormElement) return editHostFormRef.value
  return editHostFormRef.value.element ?? null
}

function checkFormValidity(): boolean {
  if (!editHostFormRef.value) return false
  const formEl = getFormElement()
  if (formEl && typeof formEl.checkValidity === 'function') {
    return formEl.checkValidity()
  }
  return true
}

function reportFormValidity() {
  if (!editHostFormRef.value) return
  const formEl = getFormElement()
  if (formEl && typeof formEl.reportValidity === 'function') {
    formEl.reportValidity()
  }
}

function clickAddHost() {
  clearFormHost()
  isVisible.value = true
}

function clickEditHost(host: Host) {
  clearFormHost()
  formHost.value = cloneDeep(host) as HostForm
  isVisible.value = true
}

async function copySavedFingerprint() {
  const fp = activeSavedFingerprint.value
  if (!fp || !navigator?.clipboard?.writeText) return
  try {
    await navigator.clipboard.writeText(fp)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1800)
  } catch {
    // Ignore clipboard failure
  }
}

function requestModalFingerprintAction(
  action: 'reset' | 'accept' | 'update',
  fingerprint?: string | null
) {
  if (formHost.value.id == null) return
  const payload: ModalFingerprintActionPayload = {
    requestId: generateUUID(),
    hostId: formHost.value.id,
    action,
    fingerprint
  }
  emitter.emit('show-spending-password-modal', {
    action: MODAL_FINGERPRINT_CONFIRM_ACTION,
    data: payload
  })
}

async function handleConfirmedModalFingerprint(event: unknown) {
  const confirmed = event as {
    action: string
    password: string
    originalData?: ModalFingerprintActionPayload
  }
  if (confirmed?.action !== MODAL_FINGERPRINT_CONFIRM_ACTION || !confirmed.originalData) return
  const { requestId, hostId, action, fingerprint } = confirmed.originalData
  isUpdatingFingerprint.value = true
  try {
    const result = await store.updateHostFingerprint(requestId, {
      hostId,
      action,
      fingerprint,
      spendingPassword: confirmed.password
    })
    formHost.value.sshFingerprint = result.sshFingerprint
    formHost.value.sshMismatchFingerprint = null
    if (action === 'update') {
      manualFingerprint.value = ''
      showManualFingerprint.value = false
    }
    store.requestHosts()
  } catch {
    // Error toast is already shown by store.handleMessage
  } finally {
    isUpdatingFingerprint.value = false
  }
}

function handleValidateAndSave(event?: Event) {
  if (event) event.preventDefault()

  if (!checkFormValidity()) {
    reportFormValidity()
    return
  }
  store.addHost(formHost.value as Host)
}

function handleSubmitAddHost(event: BvTriggerableEvent | Event) {
  event.preventDefault()
  handleValidateAndSave()
}

watch(toastSuccess, (toast) => {
  if (toast?.title === 'Host Saved') {
    isVisible.value = false
    clearFormHost()
    store.requestHosts()
  }
})

onMounted(() => {
  clearFormHost()
  emitter.on('edit-host', (host) => {
    clickEditHost(host as Host)
  })
  emitter.on('add-host', () => {
    clickAddHost()
  })
  emitter.on('confirm-spending-password-with-action', handleConfirmedModalFingerprint)
})

onUnmounted(() => {
  emitter.off('edit-host')
  emitter.off('add-host')
  emitter.off('confirm-spending-password-with-action', handleConfirmedModalFingerprint)
})
</script>

<style scoped>
.jm-modal-mismatch-alert {
  background: rgba(244, 63, 94, 0.12);
  border: 1px solid rgba(244, 63, 94, 0.45);
  border-radius: var(--jm-radius-sm);
  padding: 0.85rem;
}

.jm-modal-fp-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.6rem;
}

.jm-modal-fp-item {
  background: var(--jm-bg-input);
  border: 1px solid var(--jm-border-subtle);
  border-radius: 7px;
  padding: 0.45rem 0.65rem;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}

.jm-modal-fp-item.mismatch {
  border-color: rgba(244, 63, 94, 0.45);
  background: rgba(244, 63, 94, 0.08);
}

.jm-modal-fp-label {
  font-size: 0.68rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--jm-text-secondary);
}

.jm-modal-fp-item.mismatch .jm-modal-fp-label {
  color: #fda4af;
}

.jm-modal-fp-code {
  font-size: 0.76rem;
  color: var(--jm-text-primary);
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .jm-modal-fp-grid {
    grid-template-columns: 1fr;
  }
}
</style>
