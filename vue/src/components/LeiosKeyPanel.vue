<template>
  <section>
    <p v-if="node">
      Pool {{ node.name }} (node {{ nodeId }}, host {{ node.hostId }}, pool ID {{ node.poolId || 'unavailable' }}).
      <template v-if="node.configurationResetId">Reset {{ node.configurationResetId }}; bundle {{ node.configurationBundleId }}.</template>
    </p>
    <p v-else class="text-warning">The selected node is no longer available.</p>
    <BButton variant="outline-info" :disabled="!node || !store.connected" @click="refresh">Refresh status</BButton>
    <p v-if="!store.connected" class="text-warning mt-2">Disconnected: status cannot be refreshed.</p>
    <p v-else-if="!status" class="text-warning mt-2">Status is unavailable or awaiting a response. No registration, eligibility, installation or loaded-key state can be inferred.</p>
    <p v-if="store.leiosStatusError" class="text-danger">{{ store.leiosStatusError }}</p>
    <template v-if="status">
      <h5 class="mt-3">Observation</h5>
      <p class="text-warning">
        <template v-if="statusHistorical">This is a retained historical observation. Registration and eligibility are not current; refresh after chain recovery.</template>
        <template v-else-if="status.readState?.status === 'incomplete'">The observation was refreshed at the point shown, but some fields are unavailable.</template>
        <template v-else>Observations are point-in-time and do not by themselves prove that a producer is loaded or voting.</template>
        Refresh after reconnect or rollback; installation and loaded-key status remain independent of chain registration.
      </p>
      <dl>
        <dt>Read state</dt><dd>{{ statusHistorical ? 'historical / stale' : (status.readState?.status || 'unavailable') }}</dd>
        <dt>As of</dt><dd>{{ status.readState?.asOf || 'not observed' }}</dd>
        <dt>Observed point</dt><dd>{{ status.readState?.observedPoint ?? 'not observed' }}</dd>
        <dt>Last complete point</dt><dd>{{ status.readState?.lastCompletePoint ?? 'not observed' }}</dd>
        <dt>Reason</dt><dd>{{ status.readState?.reasonCode || 'none reported' }}</dd>
        <dt>Affected data</dt><dd>{{ status.readState?.affectedData ?? 'not reported' }}</dd>
      </dl>
      <h5>Public key history</h5>
      <p v-if="!status.keys?.length">No public keys returned. An unavailable observation does not establish that no key is registered.</p>
      <div v-for="key in status.keys" :key="key.keyId ?? key.publicKeyHex" class="border rounded p-2 mb-2">
        <dl class="mb-0">
          <dt>Key ID</dt><dd>{{ key.keyId }}</dd>
          <dt>Public key</dt><dd class="text-break">{{ key.publicKeyHex || 'unavailable' }}</dd>
          <dt>Proof validated</dt><dd>{{ key.proofValidated ? 'yes' : 'not verified' }}</dd>
          <dt>Registration{{ statusHistorical ? ' (historical, not current)' : '' }}</dt><dd>{{ key.registration ?? 'not observed' }}</dd>
          <template v-if="key.registration">
            <dt>Effective epoch</dt><dd>{{ key.registration.effectiveEpoch ?? 'unavailable' }}</dd>
            <dt>Registration epoch</dt><dd>{{ key.registration.registrationEpoch ?? 'unavailable (node reports the effective epoch, not the registration epoch)' }}</dd>
          </template>
          <dt>Eligibility / snapshots{{ statusHistorical ? ' (historical, not current)' : '' }}</dt><dd>{{ key.eligibility ?? 'not observed' }}</dd>
          <dt>Installation / loaded fingerprint</dt><dd>{{ key.installation ?? 'not observed' }}</dd>
        </dl>
        <BButton variant="outline-info" :disabled="key.keyId == null || !verified('leios.key.install') || !!pending || installUncertain"
          @click="previewInstall(key.keyId!)">Review installation</BButton>
      </div>
    </template>
    <h5 class="mt-3">Key preparation and import</h5>
    <p>Current era: {{ capabilities?.eraEvidence?.era ?? 'unavailable' }} ({{ capabilities?.eraEvidence?.freshness ?? 'unavailable' }}). Network: {{ capabilities?.networkKey ?? 'unavailable' }}.</p>
    <p v-for="operation in keyCapabilities" :key="operation.operation">
      {{ operation.operation }}: {{ operation.status }} — {{ operation.reason }}
    </p>
    <p v-if="!capabilities" class="text-warning">Target capabilities have not been verified.</p>
    <div class="border rounded p-3">
      <label for="leios-password">Spending password</label>
      <input id="leios-password" v-model="spendingPassword" class="form-control mb-2" type="password" autocomplete="current-password">
      <label for="leios-signing-key">Signing key envelope for import</label>
      <textarea id="leios-signing-key" v-model="signingKey" class="form-control mb-2" autocomplete="off" rows="3"></textarea>
      <label for="leios-public-key">Claimed public key (optional)</label>
      <input id="leios-public-key" v-model="publicKeyHex" class="form-control mb-2">
      <label for="leios-proof">Claimed proof (optional)</label>
      <input id="leios-proof" v-model="proofOfPossessionHex" class="form-control mb-2">
      <label class="d-block mb-2">
        <input v-model="confirmed" type="checkbox">
        I confirm the displayed node, host, pool and network as the target.
      </label>
      <BButton variant="outline-success" :disabled="!canAct('leios.key.prepare')" @click="prepareOrImport('prepare')">Prepare key</BButton>
      <BButton class="ms-2" variant="outline-success" :disabled="!canAct('leios.key.import') || !signingKey" @click="prepareOrImport('import')">Import key</BButton>
      <p v-if="actionError" class="text-danger mt-2">{{ actionError }}</p>
      <p v-if="actionResult" class="mt-2">{{ actionResult }}</p>
    </div>
    <div v-if="installPreview" class="border rounded p-3 mt-3">
      <h5>Review key installation</h5>
      <p>Target: node {{ installPreview.target.nodeId }}, host {{ installPreview.target.hostId }}, pool {{ installPreview.target.poolId }}, network {{ installPreview.target.networkKey }}; key {{ installPreview.keyId }}.</p>
      <p class="text-break">Public fingerprint: {{ installPreview.publicFingerprint }}<br>Configuration fingerprint: {{ installPreview.configurationFingerprint }}<br>Startup: {{ installPreview.startupPath }}<br>Credential: {{ installPreview.credentialPath }}<br>Recoverable backup: {{ installPreview.backupPath }}</p>
      <p>Mode: {{ installPreview.mode }}. Disruption: {{ installPreview.disruption }}</p>
      <p>A manual restart and a separate loaded-key observation are required; installation does not prove voting eligibility.</p>
      <label>Spending password <input v-model="installPassword" class="form-control" type="password" autocomplete="current-password"></label>
      <label class="ms-2">Sudo password (if needed) <input v-model="sudoPassword" class="form-control" type="password" autocomplete="off"></label>
      <label class="d-block my-2"><input v-model="installApproved" type="checkbox"> I approve the displayed target, file changes and disruption.</label>
      <BButton variant="success" :disabled="!installApproved || !installPassword || !verified('leios.key.install') || !!pending || installUncertain" @click="installKey">Approve installation</BButton>
      <BButton class="ms-2" variant="secondary" :disabled="!!pending" @click="installPreview = null; installPassword = ''; sudoPassword = ''">Close review</BButton>
    </div>
    <p v-if="installResult" class="mt-2">Installation: {{ installResult.state }}; installed fingerprint: {{ installResult.installedFingerprint ?? 'unavailable' }}; loaded fingerprint: {{ installResult.loadedFingerprint ?? 'not observed' }}. {{ installResult.reason }}</p>
    <p v-if="installError" class="text-danger">{{ installError }}</p>
    <div class="border rounded p-3 mt-3">
      <h5>Registration, rotation and retirement cancellation</h5>
      <p>Review the transaction and its consequences before approving submission. Submission does not prove confirmation, eligibility or that a key is loaded.</p>
      <label class="d-block">Action
        <select v-model="registrationAction" class="form-select" :disabled="!!pending || registrationUncertain || reconcilingRegistration">
          <option value="register">Register pool</option>
          <option value="rotate">Rotate registered key</option>
          <option value="cancel">Cancel scheduled retirement</option>
        </select>
      </label>
      <label v-if="registrationAction !== 'cancel' && capabilities?.eraEvidence?.era === 'dijkstra'" class="d-block mt-2">Prepared key
        <select v-model="registrationKeyId" class="form-select" :disabled="!!pending || registrationUncertain || reconcilingRegistration">
          <option :value="null">Select a key</option>
          <option v-for="key in status?.keys?.filter(item => item.keyId != null && item.proofValidated)" :key="key.keyId!" :value="key.keyId">
            {{ key.keyId }} — {{ key.publicKeyHex }}
          </option>
        </select>
      </label>
      <label class="d-block mt-2">Fee payer
        <select v-model="registrationPayer" class="form-select" :disabled="!!pending || registrationUncertain || reconcilingRegistration">
          <option :value="null">Select an account</option>
          <option v-for="option in store.registrationFeesSelectOptions()" :key="option.value" :value="option.value">{{ option.text }}</option>
        </select>
      </label>
      <template v-if="node?.isDraft && registrationAction === 'register'">
        <p class="mt-2">Publish the reviewed pool metadata independently, then provide its URL and hash; this operation does not publish it.</p>
        <label class="d-block">Published metadata URL <input v-model="registrationMetadataUrl" class="form-control" :disabled="!!pending || registrationUncertain || reconcilingRegistration"></label>
        <label class="d-block mt-2">Metadata hash (hex) <input v-model="registrationMetadataHash" class="form-control" :disabled="!!pending || registrationUncertain || reconcilingRegistration"></label>
      </template>
      <label class="d-block mt-2">Spending password
        <input v-model="registrationPassword" class="form-control" type="password" autocomplete="current-password">
      </label>
      <BButton class="mt-2" variant="outline-info" :disabled="!canReviewRegistration" @click="reviewRegistration">Review transaction</BButton>
      <p v-if="registrationError" class="text-danger mt-2">{{ registrationError }}</p>
      <div v-if="registrationResult" class="mt-2">
        <p>Registration state: {{ registrationResultStale ? `${registrationResult.state} (historical / stale)` : registrationResult.state }}; transaction: {{ registrationResult.txId }}; network: {{ registrationResult.networkKey }}.</p>
        <p v-if="registrationResultStale" class="text-warning">This outcome predates a chain invalidation and is not current. Reconciliation is required before relying on it.</p>
        <p v-else-if="registrationResult.state === 'phase2_failed'" class="text-danger">The transaction was included but failed phase 2. Its ordinary effects were not applied; this is not a confirmed registration.</p>
        <p v-else-if="registrationResult.state === 'confirmed'">The transaction was confirmed. Confirmation alone does not establish eligibility or that a key is loaded.</p>
        <p v-else class="text-warning">This transaction is not confirmed. Do not rely on its effects.</p>
        <p v-if="registrationResult.reason">{{ registrationResult.reason }}</p>
      </div>
      <div v-if="registrationPreview" class="border rounded p-3 mt-3">
        <h6>Transaction review</h6>
        <p v-if="previewGeneration !== null && previewGeneration !== networkGeneration(registrationPreview.networkKey)" class="text-warning">This review is historical, not a current confirmation. Reconciliation is required.</p>
        <pre class="text-wrap text-break">{{ JSON.stringify(registrationPreview, null, 2) }}</pre>
        <template v-if="registrationPreview.state === 'previewed' && !registrationUncertain">
          <label class="d-block"><input v-model="registrationApproved" type="checkbox"> I approve the displayed transaction, target and consequences.</label>
          <label class="d-block mt-2">Spending password
            <input v-model="registrationPassword" class="form-control" type="password" autocomplete="current-password">
          </label>
          <BButton class="mt-2" variant="success" :disabled="!registrationApproved || !registrationPassword || !!pending || reconcilingRegistration || !store.connected" @click="submitRegistration">Approve and submit</BButton>
        </template>
        <BButton class="mt-2 ms-2" variant="secondary" :disabled="!!pending || registrationUncertain || reconcilingRegistration" @click="registrationPreview = null; registrationPassword = ''; registrationApproved = false">Close review</BButton>
      </div>
      <div v-if="reconciliationReview" class="mt-3">
        <p>Reconciliation checks the authorized transaction, chain state and mempool; it never resubmits. Transaction: {{ reconciliationReview.txId }}; preview: {{ reconciliationReview.previewId }}; network: {{ reconciliationReview.networkKey }}.</p>
        <label class="d-block">Spending password for reconciliation
          <input v-model="reconciliationPassword" class="form-control" type="password" autocomplete="current-password">
        </label>
        <BButton class="mt-2" variant="warning" :disabled="!reconciliationPassword || !!pending || reconcilingRegistration || !store.connected" @click="reconcileRegistration">Authorize reconciliation</BButton>
      </div>
    </div>
    <div class="alert alert-warning mt-3">
      Registration and rotation require verified target-specific operations and reviewed consequences. Removal remains unavailable without a verified removal contract. Installation alone does not establish eligibility or voting.
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { BButton } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { generateUUID } from '@/utils/filters'

const props = defineProps<{ nodeId: number }>()
const store = useJorManagerStore()
const node = computed(() => store.nodes.find(item => item.id === props.nodeId) as
  | (typeof store.nodes[number] & { hostId: number; isDraft?: boolean; poolId?: string | null; configurationBundleId?: string | null; configurationResetId?: string | null })
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
  target?: { nodeId?: number; networkKey?: string | null }
  keys?: KeyView[]
  readState?: { status?: string; asOf?: string; observedPoint?: unknown; lastCompletePoint?: unknown; reasonCode?: string; affectedData?: unknown }
}
const status = computed(() => {
  const result = store.leiosStatus as Status | null
  return result?.target?.nodeId === props.nodeId ? result : null
})
const statusHistorical = computed(() => !store.connected || !!store.leiosStatusError ||
  !status.value?.readState || ['stale', 'unavailable'].includes(status.value.readState.status ?? 'unavailable'))
type Capabilities = {
  requestId: string
  networkKey?: string | null
  eraEvidence?: { era?: string | null; freshness?: string }
  operations?: Array<{ operation: string; status: string; reason: string }>
}
type DraftContext = { requestId: string; nodeId: number; hostId: number; observerNodeId: number; networkKey: string; configSha256: string }
const draftContextRequestId = ref('')
const draftContext = ref<DraftContext | null>(null)
const capabilityRequestId = ref('')
const capabilities = computed(() => {
  const result = store.leiosResponses.capabilities?.data as Capabilities | undefined
  return result?.requestId === capabilityRequestId.value ? result : null
})
const keyCapabilities = computed(() =>
  capabilities.value?.operations?.filter(item => ['leios.key.prepare', 'leios.key.import', 'leios.key.install'].includes(item.operation)) ?? []
)
const spendingPassword = ref('')
const signingKey = ref('')
const publicKeyHex = ref('')
const proofOfPossessionHex = ref('')
const confirmed = ref(false)
const pending = ref<{ type: string; requestId: string; networkKey?: string; generation?: number } | null>(null)
const actionError = ref('')
const actionResult = ref('')
type Target = { nodeId: number; hostId: number; poolId?: string; networkKey?: string }
type InstallPreview = {
  requestId: string; previewId: string; target: Target; keyId: number; publicFingerprint: string
  configurationFingerprint: string; startupPath: string; credentialPath: string; mode: string; disruption: string; backupPath: string
}
type InstallResult = { requestId: string; state: string; installedFingerprint: string | null; loadedFingerprint: string | null; reason: string }
const installPreview = ref<InstallPreview | null>(null)
const installResult = ref<InstallResult | null>(null)
const installError = ref('')
const installPassword = ref('')
const sudoPassword = ref('')
const installApproved = ref(false)
const installUncertain = ref(false)
type RegistrationReview = {
  requestId: string; previewId: string; nodeId: number; feePayerAccountId: number; state: string; txId: string; networkKey: string; reason?: string | null
  action: 'register' | 'rotate' | 'cancel'; keyId: number | null
  [key: string]: unknown
}
const registrationAction = ref<'register' | 'rotate' | 'cancel'>('register')
const registrationKeyId = ref<number | null>(null)
const registrationPayer = ref<number | null>(null)
const registrationPassword = ref('')
const registrationMetadataUrl = ref('')
const registrationMetadataHash = ref('')
const registrationApproved = ref(false)
const registrationPreview = ref<RegistrationReview | null>(null)
const registrationResult = ref<RegistrationReview | null>(null)
const registrationError = ref('')
const registrationUncertain = ref(false)
const registrationResultGeneration = ref<number | null>(null)
const registrationResultStale = computed(() => !!registrationResult.value &&
  (registrationResultGeneration.value == null ||
    (store.networkInvalidations[registrationResult.value.networkKey] ?? 0) !== registrationResultGeneration.value))
const previewGeneration = ref<number | null>(null)
function networkGeneration(networkKey: string) {
  return store.networkInvalidations[networkKey] ?? 0
}
watch(() => store.networkInvalidations, () => {
  const preview = registrationPreview.value
  if (preview && previewGeneration.value !== networkGeneration(preview.networkKey)) {
    registrationApproved.value = false
    registrationPassword.value = ''
    if (pending.value?.type === 'leios_registration_submitted' || reconcilingRegistration.value || preview.state !== 'previewed') {
      registrationUncertain.value = true
    } else {
      registrationPreview.value = null
    }
    registrationError.value = 'Chain status changed. The review is stale; reconcile any submitted transaction before relying on it.'
  }
  if (registrationResultStale.value) registrationUncertain.value = true
}, { deep: true, flush: 'sync' })
const reconciliationPassword = ref('')
const reconcilingRegistration = ref(false)
const reconciliationReview = computed(() => {
  const preview = registrationPreview.value
  const states = ['signing', 'submission_uncertain', 'submitted', 'rolled_back', 'confirmed', 'phase2_failed']
  if (preview && (registrationUncertain.value || states.includes(preview.state))) return preview
  const result = registrationResult.value
  return result && states.includes(result.state) ? result : null
})
const canReviewRegistration = computed(() => !!node.value && store.connected && !pending.value && !registrationUncertain.value && !reconcilingRegistration.value &&
  ['dijkstra', 'conway'].includes(capabilities.value?.eraEvidence?.era ?? '') && capabilities.value?.eraEvidence?.freshness === 'current' &&
  !!capabilities.value.networkKey && registrationPayer.value != null && !!registrationPassword.value &&
  (capabilities.value.eraEvidence.era === 'conway' || registrationAction.value === 'cancel' || registrationKeyId.value != null) &&
  (!node.value.isDraft || registrationAction.value !== 'register' ||
    (!!registrationMetadataUrl.value.trim() && /^[0-9a-fA-F]{64}$/.test(registrationMetadataHash.value.trim()))))

function reviewRegistration() {
  if (!canReviewRegistration.value) return
  const requestId = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, leios_registration_preview: {} }
  pending.value = { type: 'leios_registration_preview', requestId, networkKey: capabilities.value!.networkKey!, generation: networkGeneration(capabilities.value!.networkKey!) }
  registrationPreview.value = null
  registrationError.value = ''
  registrationApproved.value = false
  const payload = { requestId, nodeId: props.nodeId, feePayerAccountId: registrationPayer.value,
    keyId: registrationAction.value === 'cancel' || capabilities.value?.eraEvidence?.era === 'conway' ? null : registrationKeyId.value,
    action: registrationAction.value, spendingPassword: registrationPassword.value,
    ...(node.value?.isDraft && registrationAction.value === 'register' ?
      { changes: { metadata: { url: registrationMetadataUrl.value.trim(), hash: registrationMetadataHash.value.trim().toLowerCase() } } } : {}) }
  registrationPassword.value = ''
  store.sendMessage('/jormanager/leios/registration/preview', payload)
}

function submitRegistration() {
  const preview = registrationPreview.value
  if (!preview || preview.state !== 'previewed' || !registrationApproved.value || !registrationPassword.value || pending.value || registrationUncertain.value || reconcilingRegistration.value || !store.connected) return
  const requestId = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, leios_registration_submitted: {} }
  pending.value = { type: 'leios_registration_submitted', requestId, networkKey: preview.networkKey, generation: networkGeneration(preview.networkKey) }
  const payload = { requestId, previewId: preview.previewId, spendingPassword: registrationPassword.value, approved: true }
  registrationPassword.value = ''
  registrationApproved.value = false
  store.sendMessage('/jormanager/leios/registration/submit', payload)
}

async function reconcileRegistration() {
  const review = reconciliationReview.value
  if (!review || !reconciliationPassword.value || pending.value || reconcilingRegistration.value || !store.connected) return
  const generation = networkGeneration(review.networkKey)
  reconcilingRegistration.value = true
  registrationError.value = ''
  const secret = reconciliationPassword.value
  reconciliationPassword.value = ''
  try {
    const result = await store.reconcileWallet(review.previewId, secret)
    if (result.previewId !== review.previewId || result.txId !== review.txId || result.networkKey !== review.networkKey) {
      throw new Error('Reconciliation response did not match the reviewed transaction and network')
    }
    const updated = { ...review, state: result.state, reason: result.reason }
    if (registrationPreview.value?.previewId === review.previewId) registrationPreview.value = updated
    if (registrationResult.value?.previewId === review.previewId || result.state !== 'previewed') {
      registrationResult.value = updated
      registrationResultGeneration.value = generation
    }
    previewGeneration.value = generation
    registrationUncertain.value = generation !== networkGeneration(review.networkKey)
    if (registrationUncertain.value) registrationError.value = 'Chain status changed during reconciliation. The outcome is historical; reconcile again.'
    registrationApproved.value = false
    refresh()
  } catch (e) {
    registrationError.value = `${e instanceof Error ? e.message : String(e)}. Reconciliation did not establish the outcome; inspect the transaction before retrying.`
  } finally {
    reconcilingRegistration.value = false
  }
}

watch([registrationAction, registrationKeyId, registrationPayer, registrationMetadataUrl, registrationMetadataHash], () => {
  registrationPreview.value = null
  registrationApproved.value = false
})

function verified(operation: string) {
  return !!node.value && store.connected && capabilities.value?.eraEvidence?.era === 'dijkstra' &&
    capabilities.value?.eraEvidence?.freshness === 'current' && !!capabilities.value.networkKey &&
    capabilities.value.operations?.some(item => item.operation === operation && item.status === 'verified') === true
}

function selectedTarget(): Target | null {
  if (!node.value || !capabilities.value?.networkKey) return null
  return { nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId || undefined, networkKey: capabilities.value.networkKey }
}

function previewInstall(keyId: number) {
  const target = selectedTarget()
  if (!target || !verified('leios.key.install') || pending.value || installUncertain.value) return
  const requestId = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, leios_key_install_preview: {} }
  pending.value = { type: 'leios_key_install_preview', requestId }
  installPreview.value = null
  installResult.value = null
  installError.value = ''
  installApproved.value = false
  store.sendMessage('/jormanager/leios/key/install-preview', { requestId, target, keyId })
}

function installKey() {
  const preview = installPreview.value
  if (!preview || !verified('leios.key.install') || !installApproved.value || !installPassword.value || pending.value || installUncertain.value) return
  const requestId = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, leios_key_installation: {} }
  pending.value = { type: 'leios_key_installation', requestId }
  const payload = { requestId, target: preview.target, keyId: preview.keyId, previewId: preview.previewId,
    approved: true, spendingPassword: installPassword.value, sudoPassword: sudoPassword.value || undefined }
  installPassword.value = ''
  sudoPassword.value = ''
  installApproved.value = false
  store.sendMessage('/jormanager/leios/key/install', payload)
}


function canAct(operation: string) {
  return verified(operation) && !pending.value && confirmed.value && !!spendingPassword.value
}

function prepareOrImport(intent: 'prepare' | 'import') {
  if (!node.value || !canAct(`leios.key.${intent}`) || (intent === 'import' && !signingKey.value)) return
  const requestId = generateUUID()
  const type = intent === 'prepare' ? 'leios_key_prepared' : 'leios_key_imported'
  store.leiosResponses = { ...store.leiosResponses, [type]: {} }
  pending.value = { type, requestId }
  actionError.value = ''
  actionResult.value = ''
  const payload = {
    requestId,
    target: { nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId || undefined, networkKey: capabilities.value?.networkKey },
    observerNodeId: node.value.isDraft ? draftContext.value?.observerNodeId : undefined,
    intent, confirmed: true, spendingPassword: spendingPassword.value,
    ...(intent === 'import' ? {
      signingKey: signingKey.value,
      publicKeyHex: publicKeyHex.value || undefined,
      proofOfPossessionHex: proofOfPossessionHex.value || undefined
    } : {})
  }
  spendingPassword.value = ''
  signingKey.value = ''
  confirmed.value = false
  store.sendMessage(`/jormanager/leios/key/${intent}`, payload)
}

watch(() => store.leiosResponses, responses => {
  if (!pending.value) return
  const response = responses[pending.value.type]
  const result = response?.data as { requestId?: string; key?: KeyView } | undefined
  if (result?.requestId === pending.value.requestId) {
    if (pending.value.type === 'leios_key_install_preview') {
      installPreview.value = response.data as InstallPreview
    } else if (pending.value.type === 'leios_key_installation') {
      installResult.value = response.data as InstallResult
      installPreview.value = null
      refresh()
    } else if (pending.value.type === 'leios_registration_preview') {
      const review = response.data as RegistrationReview
      if (pending.value.networkKey !== review.networkKey || pending.value.generation !== networkGeneration(review.networkKey)) {
        registrationError.value = 'Chain status changed during review. Request a new transaction review.'
      } else if (review.nodeId === props.nodeId && review.networkKey === capabilities.value?.networkKey && review.action === registrationAction.value &&
          review.feePayerAccountId === registrationPayer.value && (review.action === 'cancel' ||
            (capabilities.value?.eraEvidence?.era === 'conway' ? review.keyId == null : review.keyId === registrationKeyId.value))) {
        registrationPreview.value = review
        previewGeneration.value = pending.value.generation ?? null
      } else registrationError.value = 'The reviewed transaction does not match the selected target or action.'
    } else if (pending.value.type === 'leios_registration_submitted') {
      const result = response.data as RegistrationReview
      if (result.previewId === registrationPreview.value?.previewId && result.nodeId === props.nodeId &&
          result.txId === registrationPreview.value.txId && result.networkKey === registrationPreview.value.networkKey) {
        registrationResult.value = result
        registrationResultGeneration.value = pending.value.generation ?? null
        if (registrationResultStale.value) {
          registrationUncertain.value = true
          registrationError.value = 'Chain status changed during submission. The outcome is historical; reconcile before relying on it.'
        }
        registrationPreview.value = null
        refresh()
      } else {
        registrationUncertain.value = true
        registrationError.value = 'The submission response did not match the review. Inspect the chain before retrying.'
      }
    } else {
      actionResult.value = `Key ${result.key?.keyId ?? 'unavailable'} prepared. Registration and installation are separate operations.`
      refresh()
    }
    pending.value = null
  } else if (response?.exception?.requestId === pending.value.requestId) {
    if (pending.value.type.startsWith('leios_key_install')) {
      installError.value = response.exception.message
      if (pending.value.type === 'leios_key_installation') {
        installUncertain.value = true
        installError.value += ' The outcome may be uncertain; inspect the target before another installation.'
      }
    } else if (pending.value.type.startsWith('leios_registration_')) {
      registrationError.value = response.exception.message
      if (pending.value.type === 'leios_registration_submitted') {
        registrationUncertain.value = true
        registrationError.value += ' Submission may be uncertain; inspect the chain before retrying.'
      }
    } else actionError.value = response.exception.message
    pending.value = null
  }
})
watch(() => store.leiosResponses.node_pool_draft_context, response => {
  const context = response?.data as DraftContext | undefined
  if (context?.requestId === draftContextRequestId.value && context.nodeId === props.nodeId && context.hostId === node.value?.hostId) {
    draftContext.value = context
    capabilityRequestId.value = generateUUID()
    store.sendMessage('/jormanager/capabilities', { requestId: capabilityRequestId.value, hostId: context.hostId,
      observerNodeId: context.observerNodeId, expectedNetworkKey: context.networkKey, configSha256: context.configSha256 })
  } else if (response?.exception?.requestId === draftContextRequestId.value) {
    actionError.value = response.exception.message
  }
})



function refresh() {
  if (!node.value || !store.connected) return
  capabilityRequestId.value = ''
  draftContext.value = null
  if (node.value.isDraft) {
    draftContextRequestId.value = generateUUID()
    store.sendMessage('/jormanager/node/pool-draft-context', { requestId: draftContextRequestId.value, nodeId: props.nodeId })
  } else {
    capabilityRequestId.value = generateUUID()
    store.sendMessage('/jormanager/capabilities', { requestId: capabilityRequestId.value, nodeId: props.nodeId, hostId: node.value.hostId })
  }
  store.requestLeiosStatus({ nodeId: props.nodeId, hostId: node.value.hostId, poolId: node.value.poolId || undefined })
}

onMounted(refresh)
watch(() => store.connected, connected => { if (connected) refresh() })
onUnmounted(() => { spendingPassword.value = ''; signingKey.value = ''; installPassword.value = ''; sudoPassword.value = ''; registrationPassword.value = ''; reconciliationPassword.value = '' })
</script>
