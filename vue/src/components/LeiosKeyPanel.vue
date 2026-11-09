<template>
  <section>
    <p v-if="node">
      Pool {{ node.name }} (node {{ nodeId }}, host {{ node.hostId }}, pool ID {{ node.poolId || 'unavailable' }}).
    </p>
    <p v-else class="text-warning">The selected node is no longer available.</p>
    <BButton variant="outline-info" :disabled="!node || !store.connected" @click="refresh">Refresh status</BButton>
    <p v-if="!store.connected" class="text-warning mt-2">Disconnected: status cannot be refreshed.</p>
    <p v-if="store.leiosStatusError" class="text-danger">{{ store.leiosStatusError }}</p>
    <template v-if="status">
      <h5 class="mt-3">Observation</h5>
      <dl>
        <dt>Read state</dt><dd>{{ statusHistorical ? 'historical / stale' : (status.readState?.status || 'unavailable') }}</dd>
        <dt>As of</dt><dd>{{ status.readState?.asOf || 'not observed' }}</dd>
        <dt>Observed point</dt><dd>{{ status.readState?.observedPoint ?? 'not observed' }}</dd>
        <dt>Last complete point</dt><dd>{{ status.readState?.lastCompletePoint ?? 'not observed' }}</dd>
        <dt>Reason</dt><dd>{{ status.readState?.reasonCode || 'none reported' }}</dd>
        <dt>Affected data</dt><dd>{{ status.readState?.affectedData ?? 'not reported' }}</dd>
      </dl>
      <h5>Public key history</h5>
      <p v-if="!status.keys?.length">No public keys returned.</p>
      <div v-for="key in status.keys" :key="key.keyId ?? key.publicKeyHex" class="border rounded p-2 mb-2">
        <dl class="mb-0">
          <dt>Key ID</dt><dd>{{ key.keyId }}</dd>
          <dt>Public key</dt><dd class="text-break">{{ key.publicKeyHex || 'unavailable' }}</dd>
          <dt>Proof validated</dt><dd>{{ key.proofValidated ? 'yes' : 'not verified' }}</dd>
          <dt>Registration{{ statusHistorical ? ' (historical, not current)' : '' }}</dt><dd>{{ key.registration ?? 'not observed' }}</dd>
          <template v-if="key.registration">
            <dt>Effective epoch</dt><dd>{{ key.registration.effectiveEpoch ?? 'unavailable' }}</dd>
            <dt>Registration epoch</dt><dd>{{ key.registration.registrationEpoch ?? 'unavailable' }}</dd>
          </template>
          <dt>Eligibility / snapshots{{ statusHistorical ? ' (historical, not current)' : '' }}</dt><dd>{{ key.eligibility ?? 'not observed' }}</dd>
          <dt>Installation / loaded fingerprint</dt><dd>{{ key.installation ?? 'not observed' }}</dd>
        </dl>
        <BButton variant="outline-info" :disabled="key.keyId == null || !targetReady() || !!pending"
          @click="requestKeyAction('install', key.keyId!)">Install key</BButton>
      </div>
    </template>
    <h5 class="mt-3">Key preparation and import</h5>
    <p>Current era: {{ status?.era ?? 'unavailable' }}. Network: {{ status?.target?.networkKey ?? 'unavailable' }}.</p>
    <div class="border rounded p-3">
      <label for="leios-signing-key">Signing key envelope for import</label>
      <textarea id="leios-signing-key" v-model="signingKey" class="form-control mb-2" autocomplete="off" rows="3"></textarea>
      <label for="leios-public-key">Claimed public key (optional)</label>
      <input id="leios-public-key" v-model="publicKeyHex" class="form-control mb-2">
      <label for="leios-proof">Claimed proof (optional)</label>
      <input id="leios-proof" v-model="proofOfPossessionHex" class="form-control mb-2">
      <BButton variant="outline-success" :disabled="!canAct()" @click="requestKeyAction('prepare')">Prepare key</BButton>
      <BButton class="ms-2" variant="outline-success" :disabled="!canAct() || !signingKey" @click="requestKeyAction('import')">Import key</BButton>
      <p v-if="actionError" class="text-danger mt-2">{{ actionError }}</p>
      <p v-if="actionResult" class="mt-2">{{ actionResult }}</p>
    </div>
    <label class="d-block mt-3">Sudo password for installation (if needed)
      <input v-model="sudoPassword" class="form-control" type="password" autocomplete="off">
    </label>
    <p v-if="installResult" class="mt-2">Installation: {{ installResult.state }}; installed fingerprint: {{ installResult.installedFingerprint ?? 'unavailable' }}; loaded fingerprint: {{ installResult.loadedFingerprint ?? 'not observed' }}. {{ installResult.reason }}</p>
    <p v-if="installError" class="text-danger">{{ installError }}</p>
    <div class="border rounded p-3 mt-3">
      <h5>Registration, rotation and retirement cancellation</h5>
      <label class="d-block">Action
        <select v-model="registrationAction" class="form-select" :disabled="!!pending || registrationBusy">
          <option value="register">Register pool</option>
          <option value="rotate">Rotate registered key</option>
          <option value="cancel">Cancel scheduled retirement</option>
        </select>
      </label>
      <label v-if="registrationAction !== 'cancel' && status?.era === 'dijkstra'" class="d-block mt-2">Prepared key
        <select v-model="registrationKeyId" class="form-select" :disabled="!!pending || registrationBusy">
          <option :value="null">Select a key</option>
          <option v-for="key in status?.keys?.filter(item => item.keyId != null && item.proofValidated)" :key="key.keyId!" :value="key.keyId">
            {{ key.keyId }} — {{ key.publicKeyHex }}
          </option>
        </select>
      </label>
      <label class="d-block mt-2">Fee payer
        <select v-model="registrationPayer" class="form-select" :disabled="!!pending || registrationBusy">
          <option :value="null">Select an account</option>
          <option v-for="option in store.registrationFeesSelectOptions()" :key="option.value" :value="option.value">{{ option.text }}</option>
        </select>
      </label>
      <BButton class="mt-2" variant="success" :disabled="!canSubmitRegistration" @click="requestRegistration">Submit</BButton>
      <p v-if="registrationError" class="text-danger mt-2">{{ registrationError }}</p>
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
  | (typeof store.nodes[number] & { hostId: number; isDraft?: boolean; poolId?: string | null })
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
function walletReady(networkKey: string) {
  const state = store.networkReadStates[networkKey]
  return store.connected && state?.networkKey === networkKey && state.walletReady === true
}
const canSubmitRegistration = computed(() => !!node.value && store.connected && !store.leiosStatusError && !pending.value && !registrationBusy.value &&
  ['dijkstra', 'conway'].includes(status.value?.era ?? '') && !!status.value?.target?.networkKey &&
  walletReady(status.value.target.networkKey) && registrationPayer.value != null &&
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
      request.hostId !== node.value?.hostId || request.poolId !== (node.value?.poolId ?? null) || request.era !== status.value?.era || request.networkKey !== status.value?.target?.networkKey ||
      (store.networkInvalidations[request.networkKey] ?? 0) !== request.generation) return
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
