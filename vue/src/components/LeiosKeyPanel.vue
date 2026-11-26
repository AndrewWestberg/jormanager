<template>
  <section class="jm-leios-panel">
    <div class="jm-sub-panel mb-3 d-flex align-items-center justify-content-between flex-wrap gap-2">
      <div>
        <p
          v-if="node"
          class="mb-0"
        >
          Pool <strong class="jm-mono">{{ node.name }}</strong>
          <span class="text-secondary small ms-2 jm-mono">(node {{ nodeId }}, host {{ node.hostId }}, pool ID {{ node.poolId || 'unavailable' }})</span>
        </p>
        <p
          v-else
          class="text-warning mb-0"
        >
          The selected node is no longer available.
        </p>
      </div>
      <BButton
        variant="outline-primary"
        size="sm"
        :disabled="!node || !store.connected"
        @click="refresh"
      >
        Refresh status
      </BButton>
    </div>

    <div
      v-if="!store.connected"
      class="alert alert-warning py-2 small"
      role="status"
    >
      Disconnected: status cannot be refreshed.
    </div>
    <div
      v-if="store.leiosStatusError"
      class="alert alert-danger py-2 small"
      role="alert"
    >
      {{ store.leiosStatusError }}
    </div>

    <template v-if="status">
      <div class="jm-sub-panel mb-3">
        <div class="d-flex align-items-center justify-content-between mb-2 flex-wrap gap-2">
          <h5 class="jm-sub-panel-title mb-0">
            Observation
          </h5>
          <span
            class="jm-pill"
            :class="statusHistorical ? 'jm-pill-amber' : 'jm-pill-emerald'"
          >
            {{ statusHistorical ? 'historical / stale' : (status.readState?.status || 'unavailable') }}
          </span>
        </div>
        <dl class="jm-dl-grid mb-0">
          <div>
            <dt>Read state</dt>
            <dd class="jm-mono">
              {{ statusHistorical ? 'historical / stale' : (status.readState?.status || 'unavailable') }}
            </dd>
          </div>
          <div>
            <dt>As of</dt>
            <dd class="jm-mono">
              {{ status.readState?.asOf || 'not observed' }}
            </dd>
          </div>
          <div>
            <dt>Observed point</dt>
            <dd class="jm-mono text-break">
              {{ status.readState?.observedPoint ?? 'not observed' }}
            </dd>
          </div>
          <div>
            <dt>Last complete point</dt>
            <dd class="jm-mono text-break">
              {{ status.readState?.lastCompletePoint ?? 'not observed' }}
            </dd>
          </div>
          <div>
            <dt>Reason</dt>
            <dd class="jm-mono">
              {{ status.readState?.reasonCode || 'none reported' }}
            </dd>
          </div>
          <div>
            <dt>Affected data</dt>
            <dd class="jm-mono">
              {{ status.readState?.affectedData ?? 'not reported' }}
            </dd>
          </div>
        </dl>
      </div>

      <div class="jm-sub-panel mb-3">
        <h5 class="jm-sub-panel-title">
          Public key history
        </h5>
        <p
          v-if="!status.keys?.length"
          class="text-secondary small mb-0"
        >
          No public keys returned.
        </p>
        <div
          v-for="key in status.keys"
          :key="key.keyId ?? key.publicKeyHex"
          class="jm-key-entry mb-2"
        >
          <dl class="jm-dl-grid mb-2">
            <div>
              <dt>Key ID</dt>
              <dd class="jm-mono">
                {{ key.keyId }}
              </dd>
            </div>
            <div>
              <dt>Proof validated</dt>
              <dd>
                <span
                  class="jm-pill"
                  :class="key.proofValidated ? 'jm-pill-emerald' : 'jm-pill-amber'"
                >
                  {{ key.proofValidated ? 'yes' : 'not verified' }}
                </span>
              </dd>
            </div>
            <div class="jm-dl-full">
              <dt>Public key</dt>
              <dd class="jm-mono text-break">
                {{ key.publicKeyHex || 'unavailable' }}
              </dd>
            </div>
            <div>
              <dt>Registration{{ statusHistorical ? ' (historical, not current)' : '' }}</dt>
              <dd class="jm-mono text-break">
                {{ key.registration ?? 'not observed' }}
              </dd>
            </div>
            <template v-if="key.registration">
              <div>
                <dt>Effective epoch</dt>
                <dd class="jm-mono">
                  {{ key.registration.effectiveEpoch ?? 'unavailable' }}
                </dd>
              </div>
              <div>
                <dt>Registration epoch</dt>
                <dd class="jm-mono">
                  {{ key.registration.registrationEpoch ?? 'unavailable' }}
                </dd>
              </div>
            </template>
            <div>
              <dt>Eligibility / snapshots{{ statusHistorical ? ' (historical, not current)' : '' }}</dt>
              <dd class="jm-mono text-break">
                {{ key.eligibility ?? 'not observed' }}
              </dd>
            </div>
            <div>
              <dt>Installation / loaded fingerprint</dt>
              <dd class="jm-mono text-break">
                {{ key.installation ?? 'not observed' }}
              </dd>
            </div>
          </dl>
          <BButton
            variant="outline-primary"
            size="sm"
            :disabled="key.keyId == null || !targetReady() || !!pending"
            @click="requestKeyAction('install', key.keyId!)"
          >
            Install key
          </BButton>
        </div>
      </div>
    </template>

    <div class="jm-sub-panel mb-3">
      <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-2">
        <h5 class="jm-sub-panel-title mb-0">
          Key preparation and import
        </h5>
        <span class="jm-pill jm-pill-slate jm-mono">
          Current era: {{ status?.era ?? 'unavailable' }} • Network: {{ status?.target?.networkKey ?? 'unavailable' }}
        </span>
      </div>
      <div class="mb-2">
        <label
          for="leios-signing-key"
          class="form-label"
        >Signing key envelope for import</label>
        <textarea
          id="leios-signing-key"
          v-model="signingKey"
          class="form-control jm-mono"
          autocomplete="off"
          rows="3"
        />
      </div>
      <div class="row g-2 mb-3">
        <div class="col-md-6">
          <label
            for="leios-public-key"
            class="form-label"
          >Claimed public key (optional)</label>
          <input
            id="leios-public-key"
            v-model="publicKeyHex"
            class="form-control jm-mono"
          >
        </div>
        <div class="col-md-6">
          <label
            for="leios-proof"
            class="form-label"
          >Claimed proof (optional)</label>
          <input
            id="leios-proof"
            v-model="proofOfPossessionHex"
            class="form-control jm-mono"
          >
        </div>
      </div>
      <div class="d-flex gap-2 flex-wrap">
        <BButton
          variant="outline-success"
          size="sm"
          :disabled="!canAct()"
          @click="requestKeyAction('prepare')"
        >
          Prepare key
        </BButton>
        <BButton
          variant="outline-success"
          size="sm"
          :disabled="!canAct() || !signingKey"
          @click="requestKeyAction('import')"
        >
          Import key
        </BButton>
      </div>
      <p
        v-if="actionError"
        class="text-danger small mt-2 mb-0"
      >
        {{ actionError }}
      </p>
      <p
        v-if="actionResult"
        class="text-success small mt-2 mb-0"
      >
        {{ actionResult }}
      </p>
    </div>

    <div class="jm-sub-panel mb-3">
      <label class="form-label d-block mb-1">
        Sudo password for installation (if needed)
        <input
          v-model="sudoPassword"
          class="form-control mt-1"
          type="password"
          autocomplete="off"
        >
      </label>
      <p
        v-if="installResult"
        class="small mt-2 mb-0 jm-mono"
      >
        Installation: {{ installResult.state }}; installed fingerprint: {{ installResult.installedFingerprint ?? 'unavailable' }}; loaded fingerprint: {{ installResult.loadedFingerprint ?? 'not observed' }}. {{ installResult.reason }}
      </p>
      <p
        v-if="installError"
        class="text-danger small mt-2 mb-0"
      >
        {{ installError }}
      </p>
    </div>

    <div class="jm-sub-panel">
      <h5 class="jm-sub-panel-title">
        Registration, rotation and retirement cancellation
      </h5>
      <div class="row g-2">
        <div class="col-md-4">
          <label class="form-label d-block">Action
            <select
              v-model="registrationAction"
              class="form-select mt-1"
              :disabled="!!pending || registrationBusy"
            >
              <option value="register">Register pool</option>
              <option value="rotate">Rotate registered key</option>
              <option value="cancel">Cancel scheduled retirement</option>
            </select>
          </label>
        </div>
        <div
          v-if="registrationAction !== 'cancel' && status?.era === 'dijkstra'"
          class="col-md-4"
        >
          <label class="form-label d-block">Prepared key
            <select
              v-model="registrationKeyId"
              class="form-select mt-1"
              :disabled="!!pending || registrationBusy"
            >
              <option :value="null">Select a key</option>
              <option
                v-for="key in status?.keys?.filter(item => item.keyId != null && item.proofValidated)"
                :key="key.keyId!"
                :value="key.keyId"
              >
                {{ key.keyId }} — {{ key.publicKeyHex }}
              </option>
            </select>
          </label>
        </div>
        <div class="col-md-4">
          <label class="form-label d-block">Fee payer
            <select
              v-model="registrationPayer"
              class="form-select mt-1"
              :disabled="!!pending || registrationBusy"
            >
              <option :value="null">Select an account</option>
              <option
                v-for="option in store.registrationFeesSelectOptions()"
                :key="option.value"
                :value="option.value"
              >{{ option.text }}</option>
            </select>
          </label>
        </div>
      </div>
      <BButton
        class="mt-2"
        variant="success"
        size="sm"
        :disabled="!canSubmitRegistration"
        @click="requestRegistration"
      >
        Submit
      </BButton>
      <p
        v-if="registrationError"
        class="text-danger small mt-2 mb-0"
      >
        {{ registrationError }}
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { BButton } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { generateUUID } from '@/utils/filters'
import { useEventBus } from '@/composables/useEventBus'
import type { PoolRegistrationResult } from '@/types'

const props = defineProps<{ nodeId: number }>()
const store = useJorManagerStore()
const emitter = useEventBus()
const node = computed(() => store.nodes.find(item => item.id === props.nodeId) as
  | (typeof store.nodes[number] & { hostId: number; poolId?: string | null })
  | undefined)

type KeyView = {
  keyId: number | null
  publicKeyHex: string
  proofValidated?: boolean
  registration?: { current: boolean; future: boolean; registrationEpoch: string | null; effectiveEpoch: string | null; registrationEpochReason: string | null; scheduledRetirement: string | null } | null
  eligibility?: unknown
  installation?: unknown
}
type Status = {
  target?: { nodeId?: number; hostId?: number; networkKey?: string | null }
  era?: string | null
  keys?: KeyView[]
  readState?: { status?: string; asOf?: string; observedPoint?: unknown; lastCompletePoint?: unknown; reasonCode?: string; affectedData?: unknown }
}
const status = computed(() => {
  const result = store.leiosStatus as Status | null
  return result?.target?.nodeId === props.nodeId && result.target.hostId === node.value?.hostId ? result : null
})
const statusHistorical = computed(() => !store.connected || !!store.leiosStatusError ||
  !status.value?.readState || ['stale', 'unavailable'].includes(status.value.readState.status ?? 'unavailable'))
const sudoPassword = ref('')
const signingKey = ref('')
const publicKeyHex = ref('')
const proofOfPossessionHex = ref('')
const pending = ref(false)
const actionError = ref('')
const actionResult = ref('')
type Target = { nodeId: number; hostId: number; poolId?: string; networkKey?: string }
type KeyAction = {
  intent: 'prepare' | 'import' | 'install'; requestId: string; target: Target; generation: number
  keyId?: number; signingKey?: string; publicKeyHex?: string; proofOfPossessionHex?: string; sudoPassword?: string
}
type KeyResult = { requestId: string; target: Target; key: KeyView }
type InstallResult = { requestId: string; target: Target; keyId: number; state: string; installedFingerprint: string | null; loadedFingerprint: string | null; reason: string }
const installResult = ref<InstallResult | null>(null)
const installError = ref('')
let promptedAction: KeyAction | null = null
let lastIntent = ''
let lastRequestId = ''
let clearingSecrets = false
watch([signingKey, publicKeyHex, proofOfPossessionHex], () => {
  if (!clearingSecrets) { lastIntent = ''; lastRequestId = '' }
}, { flush: 'sync' })
type RegistrationRequest = { requestId: string; nodeId: number; hostId: number; poolId: string | null; era: string; networkKey: string; generation: number; feePayerAccountId: number; keyId: number | null; action: 'register' | 'rotate' | 'cancel' }
const registrationAction = ref<'register' | 'rotate' | 'cancel'>('register')
const registrationKeyId = ref<number | null>(null)
const registrationPayer = ref<number | null>(null)
const registrationBusy = ref(false)
const registrationError = ref('')
let registrationRequestId = generateUUID()
watch([registrationAction, registrationKeyId, registrationPayer, () => props.nodeId], () => {
  registrationRequestId = generateUUID()
}, { flush: 'sync' })
const canSubmitRegistration = computed(() => !!node.value && store.connected && !store.leiosStatusError && !pending.value && !registrationBusy.value &&
  ['dijkstra', 'conway'].includes(status.value?.era ?? '') && !!status.value?.target?.networkKey &&
  registrationPayer.value != null &&
  (status.value.era === 'conway' || registrationAction.value === 'cancel' || registrationKeyId.value != null))

function requestRegistration() {
  if (!canSubmitRegistration.value || !node.value || !status.value?.target?.networkKey || registrationPayer.value == null) return
  const networkKey = status.value.target.networkKey
  const data: RegistrationRequest = {
    requestId: registrationRequestId, nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId ?? null, era: status.value.era!, networkKey,
    generation: store.networkInvalidations[networkKey] ?? 0, feePayerAccountId: registrationPayer.value,
    keyId: registrationAction.value === 'cancel' || status.value.era === 'conway' ? null : registrationKeyId.value,
    action: registrationAction.value
  }
  emitter.emit('show-spending-password-modal', { action: 'submit-leios-registration', data })
}

async function submitRegistration(request: RegistrationRequest, password: string) {
  if (!canSubmitRegistration.value || request.requestId !== registrationRequestId || request.nodeId !== props.nodeId ||
      request.hostId !== node.value?.hostId || request.poolId !== (node.value?.poolId ?? null) || request.era !== status.value?.era || request.networkKey !== status.value?.target?.networkKey) return
  registrationBusy.value = true
  registrationError.value = ''
  try {
    const { hostId: _hostId, poolId: _poolId, era: _era, networkKey, generation: _generation, ...payload } = request
    const result = await store.socketRequest<PoolRegistrationResult>(
      '/jormanager/leios/registration/submit', 'leios_registration_submitted', { ...payload, spendingPassword: password })
    if (result.requestId !== request.requestId || result.nodeId !== request.nodeId || result.networkKey !== networkKey ||
        result.action !== request.action || result.keyId !== request.keyId) throw new Error('Submission target does not match')
    if (!['submitted', 'confirmed'].includes(result.state)) throw new Error(`Transaction outcome: ${result.state}`)
    store.toastSuccess = { title: 'Pool action submitted', message: result.txId }
    if (registrationRequestId === request.requestId) registrationRequestId = generateUUID()
    refresh()
  } catch (error) {
    registrationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    registrationBusy.value = false
  }
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action === 'submit-leios-registration') void submitRegistration(data.originalData as RegistrationRequest, data.password)
  if (data.action === 'submit-leios-key' && promptedAction && data.originalData === promptedAction.requestId) {
    const request = promptedAction
    promptedAction = null
    void submitKeyAction(request, data.password)
  }
}

function targetReady() {
  return !!node.value && store.connected && !store.leiosStatusError && !registrationBusy.value && status.value?.era === 'dijkstra' && !!status.value.target?.networkKey
}

function selectedTarget(): Target | null {
  if (!node.value || !status.value?.target?.networkKey) return null
  return { nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId || undefined, networkKey: status.value.target.networkKey }
}

function canAct() {
  return targetReady() && !pending.value
}

function requestKeyAction(intent: KeyAction['intent'], keyId?: number) {
  const target = selectedTarget()
  if (!target || !canAct() || (intent === 'import' && !signingKey.value)) return
  const request: KeyAction = {
    intent, target, keyId, requestId: '', generation: store.networkInvalidations[target.networkKey!] ?? 0,
    ...(intent === 'import' ? { signingKey: signingKey.value, publicKeyHex: publicKeyHex.value || undefined,
      proofOfPossessionHex: proofOfPossessionHex.value || undefined } : {}),
    ...(intent === 'install' ? { sudoPassword: sudoPassword.value || undefined } : {})
  }
  const signature = JSON.stringify({ intent, target, keyId, generation: request.generation,
    publicKeyHex: request.publicKeyHex, proofOfPossessionHex: request.proofOfPossessionHex })
  if (signature !== lastIntent) {
    lastIntent = signature
    lastRequestId = generateUUID()
  }
  request.requestId = lastRequestId
  promptedAction = request
  emitter.emit('show-spending-password-modal', { action: 'submit-leios-key', data: request.requestId })
}

async function submitKeyAction(request: KeyAction, password: string) {
  const network = request.target.networkKey!
  if (!password || !canAct() || JSON.stringify(request.target) !== JSON.stringify(selectedTarget()) ||
      (store.networkInvalidations[network] ?? 0) !== request.generation ||
      (request.intent === 'import' && (request.signingKey !== signingKey.value ||
        request.publicKeyHex !== (publicKeyHex.value || undefined) ||
        request.proofOfPossessionHex !== (proofOfPossessionHex.value || undefined))) ||
      (request.intent === 'install' && request.sudoPassword !== (sudoPassword.value || undefined))) return
  pending.value = true
  actionError.value = ''; actionResult.value = ''; installError.value = ''; installResult.value = null
  const { generation: _generation, ...payload } = request
  const responseType = request.intent === 'install' ? 'leios_key_installation' :
    request.intent === 'prepare' ? 'leios_key_prepared' : 'leios_key_imported'
  const { intent, ...installPayload } = payload
  try {
    const response = store.socketRequest<KeyResult | InstallResult>(`/jormanager/leios/key/${intent}`, responseType,
      { ...(intent === 'install' ? installPayload : payload), spendingPassword: password })
    payload.signingKey = undefined; payload.sudoPassword = undefined
    installPayload.signingKey = undefined; installPayload.sudoPassword = undefined
    clearingSecrets = true
    signingKey.value = ''; sudoPassword.value = ''
    clearingSecrets = false
    request.signingKey = undefined; request.sudoPassword = undefined
    const result = await response
    if (result.requestId !== request.requestId || result.target.nodeId !== request.target.nodeId ||
        result.target.hostId !== request.target.hostId || result.target.poolId !== request.target.poolId ||
        result.target.networkKey !== network || (intent === 'install' && (result as InstallResult).keyId !== request.keyId)) {
      throw new Error('Action target does not match')
    }
    if (intent === 'install') installResult.value = result as InstallResult
    else actionResult.value = `Key ${(result as KeyResult).key?.keyId ?? 'unavailable'} prepared.`
    lastIntent = ''; lastRequestId = ''
    refresh()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (intent === 'install') installError.value = message
    else actionError.value = message
  } finally {
    pending.value = false
  }
}

function refresh() {
  if (!node.value || !store.connected) return
  store.requestLeiosStatus({ nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId || undefined })
}

function onPasswordModalHidden(data: { action: string }) {
  if (data.action === 'submit-leios-key') {
    promptedAction = null
    signingKey.value = ''
    sudoPassword.value = ''
  }
}

onMounted(() => {
  refresh()
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
  emitter.on('spending-password-modal-hidden', onPasswordModalHidden)
})
watch(() => store.connected, connected => { if (connected) refresh() })
onUnmounted(() => {
  emitter.off('spending-password-modal-hidden', onPasswordModalHidden)
  promptedAction = null; signingKey.value = ''; sudoPassword.value = ''
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.jm-dl-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.65rem 1rem;
}

.jm-dl-full {
  grid-column: 1 / -1;
}

.jm-dl-grid dt {
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--jm-text-muted);
  font-weight: 600;
}

.jm-dl-grid dd {
  margin: 0.1rem 0 0;
  font-size: 0.83rem;
  color: var(--jm-text-primary);
}

.jm-key-entry {
  background: var(--jm-bg-surface);
  border: 1px solid var(--jm-border-subtle);
  border-radius: var(--jm-radius-sm);
  padding: 0.9rem;
}

@media (max-width: 720px) {
  .jm-dl-grid {
    grid-template-columns: 1fr;
  }
}
</style>
