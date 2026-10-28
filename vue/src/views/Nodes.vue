<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <BButton
          variant="outline-primary"
          @click="poolParentId = null; showAddNodeWizard = true"
          v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
        >
          +&nbsp;Node
        </BButton>
        <BButton variant="outline-secondary" class="ms-2" @click="showConfigurationRestore = true">
          Restore configuration backup
        </BButton>
        <BButton
          v-if="hasCoreNodes"
          variant="outline-success"
          class="ms-2"
          @click="showGovernanceVoteModal = true"
          v-b-tooltip.hover.bottom="'Submit governance vote for all core nodes'"
        >
          🗳️ Vote
        </BButton>
      </div>
      <p v-if="nodes.some(node => (node as any).configurationBundleId)" class="small text-warning mt-2">
        Bundle selections below identify the stored configuration, not the remote upgrade, synchronization or voting state.
        Use the new-node wizard to review a replacement on a different bundle; the original node remains unchanged. A new reset requires a clean database and replay; catalog refresh does not reconfigure nodes.
      </p>
      <hr />
      <div>
        <BTable
          small
          hover
          dark
          bordered
          striped
          head-variant="light"
          :items="displayNodes as any[]"
          :fields="fields"
          v-if="(displayNodes as any[]).length > 0"
        >
          <template #cell(name)="{ item, value }">
            <div class="clearfix">
              <div
                class="clickable d-inline-block"
                v-b-tooltip.hover.left="'Update Color'"
                @click="openColorModal((item as any).id, (item as any).color)"
              >
                <font-awesome-icon
                  :style="{ color: (item as any).color }"
                  :icon="(item as any).isDefault ? ['fas', 'check-circle'] : ['fas', 'circle']"
                />
              </div>
              &nbsp;{{ value }}
              <div
                class="float-end clickable text-secondary d-inline-block"
                v-show="(item as any).type !== 'relay'"
                v-b-tooltip.hover.right="{ title: 'Copy PoolId to clipboard', variant: 'secondary' }"
                @click="copyToClipboard((item as any).poolId)"
              >
                <font-awesome-icon :icon="['fas', 'copy']" />
              </div>
            </div>
            <div v-if="isDraftNode((item as any).id)" class="small text-warning">
              Draft: deployment and startup are not established. Registration is tracked separately.
            </div>
            <div v-if="nodeBundle((item as any).id)" class="small text-muted">
              Bundle: {{ nodeBundle((item as any).id)?.configurationBundleId }}<br />
              Reset: {{ nodeBundle((item as any).id)?.configurationResetId }}
            </div>
          </template>
          <template #cell(type)="{ value }">
            <div
              v-show="value === 'relay'"
              class="text-danger text-center"
              v-b-tooltip.hover.right="{ title: 'Relay Node', variant: 'danger' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
            <div
              v-show="value === 'core'"
              class="text-success text-center"
              v-b-tooltip.hover.right="{ title: 'Core Node', variant: 'success' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
            <div
              v-show="value === 'pool'"
              class="text-primary text-center"
              v-b-tooltip.hover.right="{ title: 'Pool Node', variant: 'primary' }"
            >
              <font-awesome-icon :icon="['fas', 'dice-d20']" />
            </div>
          </template>
          <template #cell(networkIndex)="{ item }">
            <div class="small">
              <strong>JorManager network index</strong>
              <div v-if="isDraftNode((item as any).id)">Draft network only; not a running-process observation.</div>
              <template v-if="store.nodeIndexStatus[(item as any).id]?.readState">
                <div>Network: {{ store.nodeIndexStatus[(item as any).id]?.readState?.networkKey }}</div>
                <div>Status: {{ store.nodeIndexStatus[(item as any).id]?.readState?.status }}</div>
                <div>Reason: {{ store.nodeIndexStatus[(item as any).id]?.readState?.reason ?? 'not reported' }}</div>
                <div>Observed tip: {{ indexPoint(store.nodeIndexStatus[(item as any).id]?.readState?.observedTip) }}</div>
                <div>Last complete: {{ indexPoint(store.nodeIndexStatus[(item as any).id]?.readState?.lastCompletePoint) }}</div>
                <div v-if="store.nodeIndexStatus[(item as any).id]?.replay">
                  Recovery at last inspection: {{ store.nodeIndexStatus[(item as any).id]?.replay?.recovery }}
                </div>
              </template>
              <div v-else-if="store.nodeIndexStatus[(item as any).id]?.error" class="text-warning">
                Unavailable: {{ store.nodeIndexStatus[(item as any).id]?.error }}
              </div>
              <div v-else class="text-warning">{{ store.connected ? 'Awaiting index status' : 'Index status unavailable while disconnected' }}</div>
            </div>
          </template>
          <template #cell(kesExpireTimeSec)="{ item, value }">
            <span v-if="(value as number) > -1 && !isDraftNode((item as any).id)">
              {{ formatKesExpiry(value as number) }}&nbsp;
              <div
                class="text-warning clickable d-inline-block"
                v-b-tooltip.hover.right="{ title: 'Rotate KES Key', variant: 'warning' }"
                @click="rotateKesKey((item as any).name)"
              >
                <font-awesome-icon :icon="['fas', 'key']" />
              </div>
            </span>
          </template>
          <template #cell(edit)="{ item }">
            <div
              v-if="(item as any).type !== 'pool' && !isDraftNode((item as any).id)"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Restart Node', variant: 'danger' }"
              @click="restartNode((item as any).name)"
            >
              <font-awesome-icon :icon="['fas', 'power-off']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-warning clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Pool Config', variant: 'warning' }"
              @click="openPoolConfigModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'percent']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-primary clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Metadata', variant: 'primary' }"
              @click="openMetadataModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'info-circle']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-success clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Edit Relays', variant: 'success' }"
              @click="openRelaysModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'project-diagram']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay'"
              class="text-info clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Leios key status', variant: 'info' }"
              @click="leiosNodeId = (item as any).id"
            >
              <font-awesome-icon :icon="['fas', 'key']" />
            </div>&nbsp;
            <div
              v-if="isDraftNode((item as any).id)"
              class="text-info clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Review draft deployment', variant: 'info' }"
              @click="openDraftDeployment((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'check-circle']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type !== 'relay' && !isDraftNode((item as any).id)"
              class="text-danger clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Retire Pool', variant: 'danger' }"
              @click="openRetireModal((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'skull']" />
            </div>&nbsp;
            <div
              v-if="(item as any).type === 'core' && mp && !isDraftNode((item as any).id)"
              class="text-primary clickable d-inline-block"
              v-b-tooltip.hover.right="{ title: 'Add Pool', variant: 'primary' }"
              @click="addPool((item as any).id)"
            >
              <font-awesome-icon :icon="['fas', 'plus-circle']" />
            </div>
          </template>
        </BTable>
      </div>
    </div>
    
    <AddNodeWizard
      v-if="showAddNodeWizard"
      :parent-id="poolParentId"
      @hide-add-node-wizard="showAddNodeWizard = false"
    />
    <BModal :model-value="leiosNodeId !== null" title="Leios key lifecycle" size="xl" hide-footer
      @update:model-value="value => { if (!value) leiosNodeId = null }">
      <LeiosKeyPanel v-if="leiosNodeId !== null" :node-id="leiosNodeId" />
    </BModal>
    <BModal v-model="showDraftDeployment" title="Review draft deployment" size="xl" hide-footer
      :no-close-on-backdrop="true" :no-close-on-esc="deploymentBusy" :hide-header-close="deploymentBusy"
      @hidden="deploymentPassword = ''; deploymentSudoPassword = ''">
      <p>Target draft node {{ deploymentNodeId }}. Deployment requires a registered pool with the selected key, a current matching observer and verified host capability.</p>
      <div v-if="!deploymentPreview">
        <label class="d-block mb-2"><input v-model="deploymentWithoutLeios" type="checkbox"> Conway deployment without a Leios key</label>
        <BFormGroup label="Registered Leios key ID">
          <BFormInput v-model="deploymentKeyId" type="number" min="1" step="1" :disabled="deploymentWithoutLeios" />
        </BFormGroup>
        <BButton variant="outline-info" :disabled="deploymentBusy || !store.connected || (!deploymentWithoutLeios && !deploymentKeyId)" @click="previewDraftDeployment">Review deployment</BButton>
      </div>
      <div v-else>
        <p>Node {{ deploymentPreview.nodeId }}; host {{ deploymentPreview.hostId }}; parent {{ deploymentPreview.parentId ?? 'none' }}; network {{ deploymentPreview.networkKey }}; pool {{ deploymentPreview.poolId }}; key {{ deploymentPreview.keyId ?? 'none (Conway)' }}.</p>
        <p class="text-break">Public fingerprint: {{ deploymentPreview.publicFingerprint ?? 'not applicable' }}<br>Configuration fingerprint: {{ deploymentPreview.configurationFingerprint }}</p>
        <p>Mode: {{ deploymentPreview.mode }}. {{ deploymentPreview.consequences }}</p>
        <pre class="text-wrap text-break">{{ JSON.stringify(deploymentPreview.paths, null, 2) }}</pre>
        <p>Installation does not prove startup, synchronization, loaded key or voting eligibility.</p>
        <template v-if="!deploymentResult && !deploymentUncertain">
          <BFormGroup label="Spending password"><BFormInput v-model="deploymentPassword" type="password" autocomplete="current-password" /></BFormGroup>
          <BFormGroup label="Sudo password (if needed)"><BFormInput v-model="deploymentSudoPassword" type="password" autocomplete="off" /></BFormGroup>
          <BButton variant="success" :disabled="deploymentBusy || !deploymentPassword" @click="installDraftDeployment">Approve reviewed deployment</BButton>
          <BButton class="ms-2" variant="outline-secondary" :disabled="deploymentBusy" @click="deploymentPreview = null; deploymentPassword = ''; deploymentSudoPassword = ''">Change key</BButton>
        </template>
        <p v-if="deploymentResult">State: {{ deploymentResult.state }}; installed fingerprint: {{ deploymentResult.installedFingerprint ?? 'unavailable' }}; loaded fingerprint: {{ deploymentResult.loadedFingerprint ?? 'not observed' }}. {{ deploymentResult.reason }}</p>
      </div>
      <p v-if="deploymentError" class="text-danger mt-2">{{ deploymentError }}</p>
      <BButton class="mt-2" variant="secondary" :disabled="deploymentBusy" @click="showDraftDeployment = false">Close</BButton>
    </BModal>

    <BModal v-model="showConfigurationRestore" title="Restore configuration backup" hide-footer
      :no-close-on-backdrop="true" :no-close-on-esc="configurationRestoreBusy"
      :hide-header-close="configurationRestoreBusy" @hidden="clearConfigurationRestore">
      <p>Restores validated configuration bundles and snapshots only for matching existing nodes. It does not create nodes, change their remote files, start services, or restore wallet keys.</p>
      <label for="configuration-backup">Backup ZIP</label>
      <input id="configuration-backup" ref="configurationBackupInput" type="file" accept=".zip,application/zip"
        class="form-control mb-2" :disabled="configurationRestoreBusy" @change="selectConfigurationBackup">
      <BFormGroup label="Spending password">
        <BFormInput v-model="configurationRestorePassword" type="password" autocomplete="current-password" :disabled="configurationRestoreBusy" />
      </BFormGroup>
      <p v-if="configurationRestoreError" class="text-danger">{{ configurationRestoreError }}</p>
      <p v-if="configurationRestoreResult">Restored {{ configurationRestoreResult.bundles }} bundles and {{ configurationRestoreResult.snapshots }} snapshots. Node operation and synchronization are not implied.</p>
      <BButton variant="primary" :disabled="configurationRestoreBusy || !store.connected || !configurationBackup || !configurationRestorePassword" @click="restoreConfiguration">Restore matching configuration records</BButton>
      <BButton class="ms-2" variant="secondary" :disabled="configurationRestoreBusy" @click="showConfigurationRestore = false">Close</BButton>
    </BModal>


    
    <!-- Edit Color Modal -->
    <BModal
      v-model="showColorModal"
      title="Edit Color"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveColor"
    >
      <BFormGroup label="Color" label-cols-md="2">
        <BFormInput v-model="editColorForm.color" type="color" />
      </BFormGroup>
    </BModal>
    
    <!-- Edit Pool Config Modal -->
    <BModal
      v-model="showPoolConfigModal"
      :title="poolConfigStep === 1 ? 'Edit Pool Config' : 'Review Pool Config'"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSavePoolConfig"
      :ok-title="poolConfigStep === 1 ? 'Review' : 'Preview transaction'"
      :cancel-title="poolConfigStep === 1 ? 'Cancel' : 'Back'"
      @cancel.prevent="handleCancelPoolConfig"
    >
      <p class="text-warning">Changing the owner set replaces all current owners. Review any required account registrations, delegations and deposits in the transaction effects before authorization.</p>
      <div v-if="poolConfigStep === 1">
        <h5>Account Config</h5>
        <BFormGroup label="Fees Account" label-cols-md="2">
          <BFormSelect
            v-model="editPoolConfigForm.registrationFeesAccount"
            :options="feeAccountOptions"
            :state="editPoolConfigForm.registrationFeesAccount != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        <label class="d-block mb-2"><input v-model="replacePoolOwners" type="checkbox"> Replace the entire owner set with the selected accounts</label>
        <BFormGroup label="Owner (Pledge) Accounts" label-cols-md="2">
          <BFormSelect
            v-model="selectedPoolOwners"
            :options="stakingAccountOptions"
            :state="!replacePoolOwners || selectedPoolOwners.length > 0"
            :disabled="!replacePoolOwners"
            multiple
          >
          </BFormSelect>
        </BFormGroup>
        <label class="d-block mb-2"><input v-model="changePoolReward" type="checkbox"> Change the reward account</label>
        <BFormGroup label="Rewards Account" label-cols-md="2">
          <BFormSelect
            v-model="editPoolConfigForm.rewardsStakingAccount"
            :options="rewardsAccountOptions"
            :state="editPoolConfigForm.rewardsStakingAccount != null"
            :disabled="!changePoolReward"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <h5 class="mt-4">Pledge & Fees</h5>
        <BFormGroup label="Pledge (lovelace)" label-cols-md="2">
          <BFormInput v-model="editPoolConfigForm.poolPledge" placeholder="e.g. 250000000000" />
        </BFormGroup>
        <BFormGroup label="Cost (lovelace)" label-cols-md="2">
          <BFormInput v-model="editPoolConfigForm.poolCost" placeholder="e.g. 340000000" />
        </BFormGroup>
        <BFormGroup label="Margin" label-cols-md="2">
          <BFormInput
            v-model="editPoolConfigForm.poolMargin"
            type="number"
            min="0.00"
            max="1.00"
            step="0.001"
          />
          <p class="text-center">{{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%</p>
        </BFormGroup>
      </div>
      <div v-else>
        <p>Target pool: {{ nodes.find(node => node.id === editPoolConfigForm.nodeId)?.name }} ({{ editPoolConfigForm.nodeId }}).</p>
        <p>Leios keys are managed separately; preservation of a scheduled Leios key requires a verified pool-update workflow. KES rotation does not rotate a Leios key.</p>
        <h5>Please Review Your Changes</h5>
        <p>Owner set: {{ replacePoolOwners ? selectedPoolOwners.join(', ') : 'preserve current owners' }}. Reward account: {{ changePoolReward ? editPoolConfigForm.rewardsStakingAccount : 'preserve current reward account' }}.</p>
        <table class="table table-dark table-sm">
          <tbody>
            <tr>
              <th style="width: 30%">Pledge</th>
              <td>{{ editPoolConfigForm.poolPledge }}</td>
            </tr>
            <tr>
              <th>Fixed Cost</th>
              <td>{{ editPoolConfigForm.poolCost }}</td>
            </tr>
            <tr>
              <th>Margin</th>
              <td>{{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </BModal>
    
    <!-- Edit Metadata Modal -->
    <BModal
      v-model="showMetadataModal"
      title="Edit Metadata"
      size="xl"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveMetadata"
      ok-title="Preview transaction"
    >
      <p>Supply an already published metadata URL and its verified 32-byte hash. Existing content fields below are informational and are not uploaded by this review.</p>
      <BFormGroup label="Fees Account"><BFormSelect v-model="poolChangeFeeAccount" :options="feeAccountOptions" /></BFormGroup>
      <BFormGroup label="Metadata URL"><BFormInput v-model="metadataReference.url" /></BFormGroup>
      <BFormGroup label="Metadata hash (hex)"><BFormInput v-model="metadataReference.hash" /></BFormGroup>
      <fieldset disabled>
      <h5>Primary (Required)</h5>
      <BFormGroup label="Ticker" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.ticker" placeholder="e.g. TICKR" maxlength="5" />
      </BFormGroup>
      <BFormGroup label="Name" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.name" placeholder="e.g. My Awesome Pool" maxlength="50" />
      </BFormGroup>
      <BFormGroup label="Description" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.description" placeholder="Pool description" maxlength="255" />
      </BFormGroup>
      <BFormGroup label="Homepage" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.homepage" placeholder="e.g. https://mypool.com" />
      </BFormGroup>
      
      <h5 class="mt-4">Extended (Optional)</h5>
      <BFormGroup label="Location" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.location" placeholder="e.g. United States" />
      </BFormGroup>
      <BFormGroup label="Twitter" label-cols-md="2">
        <BFormInput v-model="editMetadataForm.twitter" placeholder="e.g. mypool" />
      </BFormGroup>
      </fieldset>
    </BModal>
    
    <!-- Edit Relays Modal -->
    <BModal
      v-model="showRelaysModal"
      :title="relaysStep === 1 ? 'Edit Relays' : 'Review Relays'"
      size="lg"
      :no-close-on-backdrop="true"
      @ok.prevent="handleSaveRelays"
      :ok-title="relaysStep === 1 ? 'Review' : 'Preview transaction'"
      :cancel-title="relaysStep === 1 ? 'Cancel' : 'Back'"
      @cancel.prevent="handleCancelRelays"
    >
      <p>Relay changes are submitted only after a durable transaction review.</p>
      <BFormGroup label="Fees Account"><BFormSelect v-model="poolChangeFeeAccount" :options="feeAccountOptions" /></BFormGroup>
      <div v-if="relaysStep === 1">
        <div v-for="(relay, index) in editRelaysForm.relays" :key="index" class="mb-3">
          <BCard class="bg-dark">
            <div class="d-flex justify-content-between align-items-center mb-2">
              <h5>Relay {{ index + 1 }}</h5>
              <BButton variant="outline-danger" size="sm" @click="editRelaysForm.relays.splice(index, 1)">✕</BButton>
            </div>
            <BFormGroup label="Address" label-cols-md="2">
              <BFormInput v-model="relay.addr" placeholder="e.g. relay1.mypool.com" />
            </BFormGroup>
            <BFormGroup label="Port" label-cols-md="2">
              <BFormInput v-model="relay.port" type="number" placeholder="e.g. 3001" />
            </BFormGroup>
          </BCard>
        </div>
        <BButton variant="primary" @click="editRelaysForm.relays.push({ addr: '', port: 3001 })">
          + Add Relay
        </BButton>
      </div>
      <div v-else>
        <h5>Please Review Your Relays</h5>
        <table class="table table-dark table-sm" v-if="editRelaysForm.relays.length > 0">
          <thead>
            <tr>
              <th>Address</th>
              <th>Port</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(relay, idx) in editRelaysForm.relays" :key="idx">
              <td>{{ relay.addr }}</td>
              <td>{{ relay.port }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else>No relays configured.</p>
      </div>
    </BModal>
    <BModal v-model="showPoolChangeReview" title="Review pool transaction" size="xl" hide-footer
      :no-close-on-backdrop="true" :no-close-on-esc="poolChangeBusy" :hide-header-close="poolChangeBusy"
      @hidden="poolChangePassword = ''">
      <template v-if="poolChangeReview">
        <p>Pool node {{ poolChangeReview.nodeId }}; observer {{ poolChangeReview.observerNodeId }}; host {{ poolChangeReview.hostId }}; fee payer {{ poolChangeReview.feePayerAccountId }}.</p>
        <p>Network {{ poolChangeReview.networkKey }}; era {{ poolChangeReview.era }}; state {{ poolChangeReview.state }}<span v-if="poolChangeStale"> (historical; needs reconciliation)</span>.</p>
        <p class="text-break">Preview {{ poolChangeReview.previewId }}<br>Transaction {{ poolChangeReview.txId }}</p>
        <p>Fee {{ poolChangeReview.fee }} lovelace; deposit {{ poolChangeReview.deposit }} lovelace; refund {{ poolChangeReview.refund }} lovelace. Validity {{ poolChangeReview.validFrom ?? 'unspecified' }} – {{ poolChangeReview.validUntil ?? 'unspecified' }}.</p>
        <p>Reviewed changes, transaction effects and observed pool state:</p>
        <pre class="text-wrap text-break">{{ JSON.stringify({ changes: poolChangeReview.changes, effects: poolChangeReview.effects, poolState: poolChangeReview.poolState }, null, 2) }}</pre>
        <p v-if="poolChangeReview.reason">{{ poolChangeReview.reason }}</p>
        <p v-if="poolChangeStale" class="alert alert-warning" role="alert">Chain status changed or became unavailable for this network. The displayed state and effects are historical; reconcile before relying on them. If not submitted, review the change again.</p>
        <p v-if="poolChangeReview.state === 'phase2_failed'" class="text-danger">The transaction was included but failed phase 2; its ordinary effects were not applied.</p>
        <BFormGroup v-if="(poolChangeReview.state === 'previewed' && !poolChangeUncertain && !poolChangeStale) || canReconcilePoolChange" label="Spending password">
          <BFormInput v-model="poolChangePassword" type="password" autocomplete="current-password" />
        </BFormGroup>
        <BButton v-if="poolChangeReview.state === 'previewed' && !poolChangeUncertain && !poolChangeStale" variant="success" :disabled="poolChangeBusy || !poolChangePassword" @click="submitPoolChange">Approve and submit reviewed transaction</BButton>
        <div v-if="canReconcilePoolChange" class="mt-2">
          <p>Reconciliation checks chain and mempool status; it does not resubmit the transaction.</p>
          <BButton variant="warning" :disabled="poolChangeBusy || !poolChangePassword" @click="reconcilePoolChange">Authorize reconciliation</BButton>
        </div>
      </template>
      <p v-if="poolChangeError" class="text-danger">{{ poolChangeError }}</p>
      <p>Submission is not confirmation. Configuration is not changed locally until chain effects are observed.</p>
      <BButton class="mt-2" variant="secondary" :disabled="poolChangeBusy" @click="showPoolChangeReview = false">Close</BButton>
    </BModal>

    
    <!-- Retire Pool Modal -->
    <BModal
      v-model="showRetireModal"
      title="Retire Pool"
      :no-close-on-backdrop="true"
      hide-footer
      :no-close-on-esc="retirementBusy"
      :hide-header-close="retirementBusy"
      @hidden="retirementPassword = ''"
    >
      <p>Target pool: {{ nodes.find(node => node.id === retirePoolForm.id)?.name }} ({{ retirePoolForm.id }}).</p>
      <p class="text-warning">Reviewed retirement requires current pool state and qualified capabilities. Cancellation is not available. Submission does not mean confirmation.</p>
      <template v-if="!retirementReview">
      <BFormGroup label="Fees Account" label-cols-md="4">
        <BFormSelect
          v-model="retirePoolForm.retireFeesAccount"
          :options="retireFeeAccountOptions"
          :state="retirePoolForm.retireFeesAccount !== null"
        >
          <template #first>
            <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
          </template>
        </BFormSelect>
      </BFormGroup>
      <p class="text-danger">
        <font-awesome-icon :icon="['fas', 'exclamation-triangle']" />
        Warning: This action will retire your pool!
      </p>
      <BFormGroup label="Retire in Epoch" label-cols-md="4">
        <BFormInput
          v-model="retirePoolForm.retireEpoch"
          type="number"
          placeholder="e.g. Current epoch + 2"
          :state="retirePoolForm.retireEpoch > 0"
        />
      </BFormGroup>
      <p class="text-muted">The refund, if any, occurs at retirement and is not an immediate balance increase.</p>
      <BButton variant="outline-info" :disabled="retirementBusy" @click="reviewRetirement">Review retirement</BButton>
      </template>
      <div v-else>
        <h5>Review retirement consequences</h5>
        <p>Network: {{ retirementReview.networkKey }}; era: {{ retirementReview.era }}; state: {{ retirementReview.state }}<span v-if="retirementStale"> (historical; needs reconciliation)</span></p>
        <p>Pool node: {{ retirementReview.nodeId }}; observer node: {{ retirementReview.observerNodeId }}; host: {{ retirementReview.hostId }}; fee payer account: {{ retirementReview.feePayerAccountId }}.</p>
        <p class="text-break">Preview: {{ retirementReview.previewId }}<br>Transaction: {{ retirementReview.txId }}</p>
        <p>Fee: {{ retirementReview.fee }} lovelace; transaction deposit: {{ retirementReview.deposit }} lovelace; immediate refund: {{ retirementReview.refund }} lovelace. The held pool deposit is shown in the observed state below; any later refund and destination are contingent, not a confirmed reward.</p>
        <p>Retirement epoch: {{ retirementReview.retireEpoch }}; validity: {{ retirementReview.validFrom ?? 'not specified' }} – {{ retirementReview.validUntil ?? 'not specified' }}</p>
        <p>Observed pool state and current/future keys:</p>
        <pre class="text-wrap text-break">{{ JSON.stringify(retirementReview.poolState, null, 2) }}</pre>
        <p v-if="retirementReview.reason">{{ retirementReview.reason }}</p>
        <p v-if="retirementStale" class="alert alert-warning" role="alert">Chain status changed or became unavailable for this network. The displayed state and effects are historical; reconcile before relying on them. If not submitted, review the retirement again.</p>
        <p v-if="retirementReview.state === 'phase2_failed'" class="text-danger">The transaction was included but failed phase 2; retirement was not applied.</p>
        <BFormGroup v-if="(retirementReview.state === 'previewed' && !retirementUncertain && !retirementStale) || canReconcileRetirement" label="Spending password">
          <BFormInput v-model="retirementPassword" type="password" autocomplete="current-password" />
        </BFormGroup>
        <BButton v-if="retirementReview.state === 'previewed' && !retirementUncertain && !retirementStale" variant="success"
          :disabled="retirementBusy || !retirementPassword" @click="submitRetirement">Approve and submit reviewed retirement</BButton>
        <div v-if="canReconcileRetirement" class="mt-2">
          <p>Reconciliation checks chain and mempool status; it does not resubmit the transaction.</p>
          <BButton variant="warning" :disabled="retirementBusy || !retirementPassword" @click="reconcileRetirement">Authorize reconciliation</BButton>
        </div>
        <BButton v-if="!retirementUncertain && retirementReview.state === 'previewed'" class="ms-2" variant="outline-secondary"
          :disabled="retirementBusy" @click="retirementReview = null; retirementPassword = ''">Change details</BButton>
      </div>
      <p v-if="retirementError" class="text-danger mt-2">{{ retirementError }}</p>
      <BButton class="mt-2" variant="secondary" :disabled="retirementBusy" @click="showRetireModal = false; retirementPassword = ''">Close</BButton>
    </BModal>
    
    <!-- Governance Vote Modal -->
    <GovernanceVoteModal v-model="showGovernanceVoteModal" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import moment from 'moment-timezone'
import { find } from 'lodash-es'
import {
  BButton,
  BTable,
  BModal,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption,
  BCard
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import LeiosKeyPanel from '@/components/LeiosKeyPanel.vue'
import AddNodeWizard from '@/components/AddNodeWizard.vue'
import GovernanceVoteModal from '@/components/GovernanceVoteModal.vue'

interface Relay {
  addr: string
  port: number
}

const store = useJorManagerStore()
const { displayNodes, mp, nodes } = storeToRefs(store)
const emitter = useEventBus()

const showAddNodeWizard = ref(false)
const poolParentId = ref<number | null>(null)
const leiosNodeId = ref<number | null>(null)

// Modal visibility
const showColorModal = ref(false)
const showPoolConfigModal = ref(false)
const showMetadataModal = ref(false)
const showRelaysModal = ref(false)
const showRetireModal = ref(false)
const showGovernanceVoteModal = ref(false)

const poolConfigStep = ref(1)
const relaysStep = ref(1)

// Form data
const editColorForm = ref({ nodeId: 0, color: '#000000' })
const editPoolConfigForm = ref({
  nodeId: 0,
  registrationFeesAccount: null as number | null,
  ownerStakingAccount: null as number | null,
  rewardsStakingAccount: null as number | null,
  poolPledge: 0,
  poolCost: 340,
  poolMargin: 0.03
})
const editMetadataForm = ref({
  nodeId: 0,
  ticker: '',
  name: '',
  description: '',
  homepage: '',
  location: '',
  twitter: ''
})
const editRelaysForm = ref({
  nodeId: 0,
  relays: [] as Relay[]
})
type PoolChangeReview = {
  previewId: string; txId: string; networkKey: string; era: string; state: string
  nodeId: number; observerNodeId: number; hostId: number; feePayerAccountId: number
  fee: string; deposit: string; refund: string; validFrom: string | null; validUntil: string | null
  changes: unknown; effects: unknown; poolState: unknown
  reason?: string | null
}
type PoolChangeRequest = { nodeId: number; feePayerAccountId: number; changes: Record<string, unknown> }
const replacePoolOwners = ref(false)
const selectedPoolOwners = ref<number[]>([])
const changePoolReward = ref(false)
const poolChangeFeeAccount = ref<number | null>(null)
const metadataReference = ref({ url: '', hash: '' })
const showPoolChangeReview = ref(false)
const poolChangeReview = ref<PoolChangeReview | null>(null)
const poolChangePassword = ref('')
const poolChangeBusy = ref(false)
const poolChangeUncertain = ref(false)
const poolChangeError = ref('')
const poolChangeStale = ref(false)
watch(() => poolChangeReview.value ? store.networkInvalidations[poolChangeReview.value.networkKey] ?? 0 : 0, (value, oldValue) => {
  if (poolChangeReview.value && value !== oldValue) poolChangeStale.value = true
}, { flush: 'sync' })
const reconciliationStates = ['signing', 'submission_uncertain', 'submitted', 'rolled_back', 'confirmed', 'phase2_failed']
function chainUnavailable(networkKey: string) {
  return !store.connected || (store.walletNetworkKey === networkKey &&
    (store.chainReadState?.networkKey !== networkKey || store.chainReadState.status !== 'complete'))
}
watch(() => poolChangeReview.value && chainUnavailable(poolChangeReview.value.networkKey), unavailable => {
  if (unavailable) poolChangeStale.value = true
}, { flush: 'sync' })
const canReconcilePoolChange = computed(() => !!poolChangeReview.value &&
  (poolChangeUncertain.value || (poolChangeStale.value && poolChangeReview.value.state !== 'previewed') || reconciliationStates.includes(poolChangeReview.value.state)))

function requestPoolChange(nodeId: number, feePayerAccountId: number | null, changes: Record<string, unknown>) {
  if (feePayerAccountId == null || poolChangeBusy.value) {
    store.toastError = { title: 'Pool review', message: 'Select a fee payer account.' }
    return
  }
  emitter.emit('show-spending-password-modal', {
    action: 'preview-pool-change', data: { nodeId, feePayerAccountId, changes }
  })
}

async function previewPoolChange(request: PoolChangeRequest, password: string) {
  if (poolChangeBusy.value) return
  poolChangeBusy.value = true
  poolChangeError.value = ''
  poolChangeReview.value = null
  poolChangeUncertain.value = false
  poolChangeStale.value = false
  const invalidations = { ...store.networkInvalidations }
  try {
    const result = await store.socketRequest<PoolChangeReview>('/jormanager/leios/registration/preview', 'leios_registration_preview', {
      ...request, keyId: null, action: 'update', spendingPassword: password
    })
    if (result.nodeId !== request.nodeId || result.feePayerAccountId !== request.feePayerAccountId) throw new Error('Preview target does not match')
    poolChangeReview.value = result
    poolChangeStale.value = (store.networkInvalidations[result.networkKey] ?? 0) !== (invalidations[result.networkKey] ?? 0) || chainUnavailable(result.networkKey)
    showPoolConfigModal.value = false
    showRelaysModal.value = false
    showMetadataModal.value = false
    showPoolChangeReview.value = true
  } catch (error) {
    store.toastError = { title: 'Pool review unavailable', message: error instanceof Error ? error.message : String(error) }
  } finally {
    poolChangeBusy.value = false
  }
}

async function submitPoolChange() {
  const review = poolChangeReview.value
  if (!review || !poolChangePassword.value || poolChangeBusy.value || poolChangeUncertain.value || poolChangeStale.value) return
  const password = poolChangePassword.value
  poolChangePassword.value = ''
  poolChangeBusy.value = true
  poolChangeError.value = ''
  const invalidation = store.networkInvalidations[review.networkKey] ?? 0
  try {
    const result = await store.socketRequest<PoolChangeReview>('/jormanager/leios/registration/submit', 'leios_registration_submitted', {
      previewId: review.previewId, spendingPassword: password, approved: true
    })
    if (result.previewId !== review.previewId || result.nodeId !== review.nodeId ||
        result.txId !== review.txId || result.networkKey !== review.networkKey) throw new Error('Submission does not match the reviewed target')
    poolChangeReview.value = result
    if ((store.networkInvalidations[review.networkKey] ?? 0) !== invalidation || chainUnavailable(review.networkKey)) poolChangeStale.value = true
  } catch (error) {
    poolChangeError.value = `${error instanceof Error ? error.message : String(error)}. The outcome may be uncertain; check transaction status before retrying.`
    poolChangeUncertain.value = true
  } finally {
    poolChangeBusy.value = false
  }
}

async function reconcilePoolChange() {
  const review = poolChangeReview.value
  if (!review || !canReconcilePoolChange.value || !poolChangePassword.value || poolChangeBusy.value) return
  const password = poolChangePassword.value
  poolChangePassword.value = ''
  poolChangeBusy.value = true
  poolChangeError.value = ''
  const invalidation = store.networkInvalidations[review.networkKey] ?? 0
  try {
    const result = await store.reconcileWallet(review.previewId, password)
    if (result.previewId !== review.previewId || result.txId !== review.txId || result.networkKey !== review.networkKey) {
      throw new Error('Reconciliation response did not match the reviewed transaction')
    }
    poolChangeReview.value = { ...review, state: result.state, reason: result.reason }
    poolChangeUncertain.value = result.state === 'previewed' || result.state === 'signing'
    poolChangeStale.value = (store.networkInvalidations[review.networkKey] ?? 0) !== invalidation || chainUnavailable(review.networkKey)
  } catch (error) {
    poolChangeError.value = `${error instanceof Error ? error.message : String(error)}. Reconciliation outcome may be uncertain; inspect the transaction before retrying.`
    poolChangeUncertain.value = true
  } finally {
    poolChangeBusy.value = false
  }
}

const retirePoolForm = ref({
  id: 0,
  retireFeesAccount: null as number | null,
  retireEpoch: 0
})
type RetirementReview = {
  previewId: string; txId: string; networkKey: string; era: string; state: string
  nodeId: number; observerNodeId: number; hostId: number; feePayerAccountId: number
  fee: string; deposit: string; refund: string; validFrom: string | null; validUntil: string | null
  retireEpoch: string; poolState: unknown
  reason?: string | null
}
const retirementReview = ref<RetirementReview | null>(null)
const retirementPassword = ref('')
const retirementBusy = ref(false)
const retirementUncertain = ref(false)
const retirementError = ref('')
const retirementStale = ref(false)
watch(() => retirementReview.value ? store.networkInvalidations[retirementReview.value.networkKey] ?? 0 : 0, (value, oldValue) => {
  if (retirementReview.value && value !== oldValue) retirementStale.value = true
}, { flush: 'sync' })
watch(() => retirementReview.value && chainUnavailable(retirementReview.value.networkKey), unavailable => {
  if (unavailable) retirementStale.value = true
}, { flush: 'sync' })
const canReconcileRetirement = computed(() => !!retirementReview.value &&
  (retirementUncertain.value || (retirementStale.value && retirementReview.value.state !== 'previewed') || reconciliationStates.includes(retirementReview.value.state)))
type DeploymentPreview = {
  previewId: string; nodeId: number; hostId: number; parentId: number | null; networkKey: string; poolId: string
  keyId: number | null; publicFingerprint: string | null; configurationFingerprint: string; paths: unknown; mode: string; consequences: string
}
type DeploymentResult = { previewId: string; nodeId: number; state: string; installedFingerprint: string | null; loadedFingerprint: string | null; reason: string }
const showDraftDeployment = ref(false)
const deploymentNodeId = ref(0)
const deploymentKeyId = ref<number | null>(null)
const deploymentWithoutLeios = ref(false)
const deploymentPreview = ref<DeploymentPreview | null>(null)
const deploymentResult = ref<DeploymentResult | null>(null)
const deploymentPassword = ref('')
const deploymentSudoPassword = ref('')
const deploymentBusy = ref(false)
const deploymentUncertain = ref(false)
const deploymentError = ref('')

function openDraftDeployment(nodeId: number) {
  deploymentNodeId.value = nodeId
  deploymentKeyId.value = null
  deploymentWithoutLeios.value = false
  deploymentPreview.value = null
  deploymentResult.value = null
  deploymentPassword.value = ''
  deploymentSudoPassword.value = ''
  deploymentUncertain.value = false
  deploymentError.value = ''
  showDraftDeployment.value = true
}

async function previewDraftDeployment() {
  const keyId = deploymentWithoutLeios.value ? null : Number(deploymentKeyId.value)
  if ((keyId !== null && (!Number.isSafeInteger(keyId) || keyId <= 0)) || deploymentBusy.value || !isDraftNode(deploymentNodeId.value)) return
  deploymentBusy.value = true
  deploymentError.value = ''
  try {
    const result = await store.socketRequest<DeploymentPreview>('/jormanager/node/pool-deployment-preview', 'node_pool_deployment_preview', {
      nodeId: deploymentNodeId.value, keyId
    })
    if (result.nodeId !== deploymentNodeId.value || result.keyId !== keyId) throw new Error('Deployment preview target does not match')
    deploymentPreview.value = result
  } catch (error) {
    deploymentError.value = error instanceof Error ? error.message : String(error)
  } finally {
    deploymentBusy.value = false
  }
}

async function installDraftDeployment() {
  const preview = deploymentPreview.value
  if (!preview || !deploymentPassword.value || deploymentBusy.value || deploymentUncertain.value) return
  const password = deploymentPassword.value
  const sudo = deploymentSudoPassword.value
  deploymentPassword.value = ''
  deploymentSudoPassword.value = ''
  deploymentBusy.value = true
  deploymentError.value = ''
  try {
    const result = await store.socketRequest<DeploymentResult>('/jormanager/node/pool-deployment-install', 'node_pool_deployment_install', {
      previewId: preview.previewId, nodeId: preview.nodeId, keyId: preview.keyId,
      approved: true, spendingPassword: password, sudoPassword: sudo || undefined
    })
    if (result.previewId !== preview.previewId || result.nodeId !== preview.nodeId) throw new Error('Deployment response does not match the reviewed target')
    deploymentResult.value = result
    store.requestNodes()
  } catch (error) {
    deploymentError.value = `${error instanceof Error ? error.message : String(error)}. The outcome may be uncertain; inspect the target before retrying.`
    deploymentUncertain.value = true
  } finally {
    deploymentBusy.value = false
  }
}

const showConfigurationRestore = ref(false)
const configurationBackup = ref<File | null>(null)
const configurationBackupInput = ref<HTMLInputElement | null>(null)
const configurationRestorePassword = ref('')
const configurationRestoreBusy = ref(false)
const configurationRestoreError = ref('')
const configurationRestoreResult = ref<{ bundles: number; snapshots: number } | null>(null)

function selectConfigurationBackup(event: Event) {
  configurationBackup.value = (event.target as HTMLInputElement).files?.[0] ?? null
  configurationRestoreResult.value = null
  configurationRestoreError.value = ''
}

function clearConfigurationRestore() {
  configurationBackup.value = null
  configurationRestorePassword.value = ''
  configurationRestoreResult.value = null
  configurationRestoreError.value = ''
  if (configurationBackupInput.value) configurationBackupInput.value.value = ''
}

async function restoreConfiguration() {
  const file = configurationBackup.value
  if (!file || !configurationRestorePassword.value || configurationRestoreBusy.value || !store.connected) return
  if (file.size === 0 || file.size > 64 * 1024 * 1024) {
    configurationRestoreError.value = 'The backup must be a nonempty ZIP no larger than 64 MiB.'
    return
  }
  configurationRestoreBusy.value = true
  configurationRestoreError.value = ''
  configurationRestoreResult.value = null
  try {
    const encoded = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result.split(',', 2)[1] || '') : reject(new Error('Unable to read backup'))
      reader.onerror = () => reject(new Error('Unable to read backup'))
      reader.readAsDataURL(file)
    })
    if (!encoded || !store.connected || !configurationRestoreBusy.value || !configurationRestorePassword.value) throw new Error('Backup could not be sent; reconnect and retry.')
    const password = configurationRestorePassword.value
    configurationRestorePassword.value = ''
    store.leiosResponses = { ...store.leiosResponses, network_configuration_restore: {} }
    store.sendMessage('/jormanager/network_configuration_restore', { backupBase64: encoded, spendingPassword: password })
  } catch (error) {
    configurationRestorePassword.value = ''
    configurationRestoreError.value = error instanceof Error ? error.message : String(error)
    configurationRestoreBusy.value = false
  }
}

watch(() => store.leiosResponses.network_configuration_restore, response => {
  if (!configurationRestoreBusy.value || !response) return
  const result = response.data as { bundles?: number; snapshots?: number } | undefined
  if (result && typeof result.bundles === 'number' && typeof result.snapshots === 'number') {
    configurationRestoreResult.value = { bundles: result.bundles, snapshots: result.snapshots }
    configurationBackup.value = null
    if (configurationBackupInput.value) configurationBackupInput.value.value = ''
    configurationRestoreBusy.value = false
    store.requestNetworkBundleOptions()
    store.requestNodes()
  } else if (response.exception) {
    configurationRestoreError.value = response.exception.message
    configurationRestoreBusy.value = false
  }
})

watch(() => store.connected, connected => {
  if (!connected && configurationRestoreBusy.value) {
    configurationRestorePassword.value = ''
    configurationRestoreBusy.value = false
    configurationRestoreError.value = 'Connection lost; restore outcome is unknown. Refresh configuration records before retrying.'
  }
})


// Select options
const feeAccountOptions = computed(() => store.registrationFeesSelectOptions())

const retireFeeAccountOptions = computed(() => store.reregistrationFeesSelectOptions())

const stakingAccountOptions = computed(() => store.stakingSelectOptions())

const rewardsAccountOptions = computed(() => store.rewardsSelectOptions())

// Check if there are any core/pool nodes (non-relay)
const hasCoreNodes = computed(() => {
  return nodes.value.some((node) => (node as any).type !== 'relay')
})

const fields = [
  { key: 'name', sortable: true },
  { key: 'type', label: '', sortable: true },
  { key: 'host', sortable: true },
  { key: 'networkIndex', label: 'Network index' },
  { key: 'kesExpireTimeSec', label: 'KES Expiry' },
  { key: 'edit', label: '' }
]

function indexPoint(point: { slot: string; hash: string } | null | undefined): string {
  return point ? `slot ${point.slot}, hash ${point.hash}` : 'unavailable'
}

function formatKesExpiry(timestamp: number): string {
  const date = moment.unix(timestamp)
  return `${date.format('YYYY-MM-DD h:mma UTCZ')} (${date.fromNow()})`
}

function isDraftNode(nodeId: number) {
  return !!(nodes.value.find(node => node.id === nodeId) as { isDraft?: boolean } | undefined)?.isDraft
}

function nodeBundle(nodeId: number) {
  const node = nodes.value.find(node => node.id === nodeId) as
    | (typeof nodes.value[number] & { configurationBundleId?: string | null; configurationResetId?: string | null })
    | undefined
  return node?.configurationBundleId ? node : null
}

function openColorModal(nodeId: number, currentColor: string) {
  editColorForm.value = { nodeId, color: currentColor }
  showColorModal.value = true
}

function handleSaveColor() {
  emitter.emit('show-spending-password-modal', { action: 'update-color', data: editColorForm.value })
}

function openPoolConfigModal(nodeId: number) {
  const node = find(nodes.value, { id: nodeId })
  if (node) {
    editPoolConfigForm.value = {
      nodeId,
      registrationFeesAccount: (node as any).registrationFeesAccountId || null,
      ownerStakingAccount: (node as any).ownerStakingAccountId || null,
      rewardsStakingAccount: (node as any).rewardsStakingAccountId || null,
      poolPledge: (node as any).poolPledge ?? 0,
      poolCost: (node as any).poolCost ?? 0,
      poolMargin: (node as any).poolMargin ?? 0
    }
  }
  poolConfigStep.value = 1
  replacePoolOwners.value = false
  selectedPoolOwners.value = []
  changePoolReward.value = false
  showPoolConfigModal.value = true
}

function handleSavePoolConfig() {
  if (poolConfigStep.value === 1) {
    poolConfigStep.value = 2
    return
  }
  const pledge = String(editPoolConfigForm.value.poolPledge)
  const cost = String(editPoolConfigForm.value.poolCost)
  const margin = String(editPoolConfigForm.value.poolMargin)
  if (!/^(0|[1-9]\d*)$/.test(pledge) || !/^(0|[1-9]\d*)$/.test(cost) || !/^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(margin)) {
    store.toastError = { title: 'Pool review', message: 'Use whole lovelace amounts and a margin between 0 and 1.' }
    return
  }
  const changes: Record<string, unknown> = { pledge, cost, margin }
  if (replacePoolOwners.value) {
    if (selectedPoolOwners.value.length === 0) {
      store.toastError = { title: 'Pool review', message: 'Select at least one replacement owner account.' }
      return
    }
    changes.ownerAccountIds = [...selectedPoolOwners.value]
  }
  if (changePoolReward.value) {
    if (editPoolConfigForm.value.rewardsStakingAccount == null) {
      store.toastError = { title: 'Pool review', message: 'Select the replacement reward account.' }
      return
    }
    changes.rewardAccountId = editPoolConfigForm.value.rewardsStakingAccount
  }
  requestPoolChange(editPoolConfigForm.value.nodeId, editPoolConfigForm.value.registrationFeesAccount, changes)
}

function handleCancelPoolConfig() {
  if (poolConfigStep.value === 2) {
    poolConfigStep.value = 1
  } else {
    showPoolConfigModal.value = false
    poolConfigStep.value = 1
  }
}

function openMetadataModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  metadataReference.value = { url: '', hash: '' }
  store.requestMetadata(nodeId)
  const metadata = store.editorMetadata as any
  editMetadataForm.value = {
    nodeId,
    ticker: metadata?.ticker || '',
    name: metadata?.name || '',
    description: metadata?.description || '',
    homepage: metadata?.homepage || '',
    location: metadata?.extended?.info?.location || '',
    twitter: metadata?.extended?.info?.social?.twitter || ''
  }
  showMetadataModal.value = true
}
function handleSaveMetadata() {
  const { url, hash } = metadataReference.value
  if (!url.startsWith('https://') || !/^[0-9a-f]{64}$/.test(hash)) {
    store.toastError = { title: 'Pool review', message: 'Provide an HTTPS metadata URL and its lowercase 32-byte hash.' }
    return
  }
  requestPoolChange(editMetadataForm.value.nodeId, poolChangeFeeAccount.value, { metadata: { url, hash } })
}



function openRelaysModal(nodeId: number) {
  poolChangeFeeAccount.value = null
  const node = find(nodes.value, { id: nodeId })
  editRelaysForm.value = {
    nodeId,
    relays: (node as any)?.relays?.map((r: any) => ({ addr: r.addr, port: r.port })) || []
  }
  relaysStep.value = 1
  showRelaysModal.value = true
}

function handleSaveRelays() {
  if (relaysStep.value === 1) {
    relaysStep.value = 2
    return
  }
  const relays: Record<string, unknown>[] = []
  for (const relay of editRelaysForm.value.relays) {
    const address = relay.addr.trim()
    const port = Number(relay.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      store.toastError = { title: 'Pool review', message: 'Each relay needs a valid port.' }
      return
    }
    if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) {
      if (address.split('.').some(part => Number(part) > 255)) {
        store.toastError = { title: 'Pool review', message: 'Invalid IPv4 relay address.' }
        return
      }
      relays.push({ 'single host address': { port, IPv4: address, IPv6: null } })
    } else if (/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(address)) {
      relays.push({ 'single host name': { port, dnsName: address } })
    } else {
      store.toastError = { title: 'Pool review', message: 'Use an IPv4 address or DNS name for each relay.' }
      return
    }
  }
  requestPoolChange(editRelaysForm.value.nodeId, poolChangeFeeAccount.value, { relays })
}

function handleCancelRelays() {
  if (relaysStep.value === 2) {
    relaysStep.value = 1
  } else {
    showRelaysModal.value = false
    relaysStep.value = 1
  }
}

function openRetireModal(nodeId: number) {
  retirementReview.value = null
  retirementPassword.value = ''
  retirementUncertain.value = false
  retirementStale.value = false
  retirementError.value = ''
  retirePoolForm.value = {
    id: nodeId,
    retireFeesAccount: null,
    retireEpoch: 0
  }
  showRetireModal.value = true
}

async function reviewRetirement() {
  if (retirementBusy.value || retirePoolForm.value.retireFeesAccount === null ||
      !Number.isSafeInteger(Number(retirePoolForm.value.retireEpoch)) || Number(retirePoolForm.value.retireEpoch) <= 0) {
    retirementError.value = 'Select a fee account and a valid retirement epoch.'
    return
  }
  retirementBusy.value = true
  retirementError.value = ''
  const invalidations = { ...store.networkInvalidations }
  try {
    const result = await store.socketRequest<RetirementReview>(
      '/jormanager/pool/retirement/review', 'pool_retirement_review', {
        nodeId: retirePoolForm.value.id, feePayerAccountId: retirePoolForm.value.retireFeesAccount,
        retireEpoch: String(retirePoolForm.value.retireEpoch)
      })
    retirementReview.value = result
    retirementStale.value = (store.networkInvalidations[result.networkKey] ?? 0) !== (invalidations[result.networkKey] ?? 0) || chainUnavailable(result.networkKey)
  } catch (error) {
    retirementError.value = error instanceof Error ? error.message : String(error)
  } finally {
    retirementBusy.value = false
  }
}

async function submitRetirement() {
  const review = retirementReview.value
  if (!review || !retirementPassword.value || retirementBusy.value || retirementUncertain.value || retirementStale.value) return
  const password = retirementPassword.value
  retirementPassword.value = ''
  retirementBusy.value = true
  retirementError.value = ''
  const invalidation = store.networkInvalidations[review.networkKey] ?? 0
  try {
    const result = await store.socketRequest<RetirementReview>(
      '/jormanager/pool/retirement/submit', 'pool_retirement_submit', { previewId: review.previewId, spendingPassword: password, approved: true })
    if (result.previewId !== review.previewId || result.txId !== review.txId || result.networkKey !== review.networkKey || result.nodeId !== review.nodeId) {
      throw new Error('Submission response did not match the reviewed transaction')
    }
    retirementReview.value = result
    if ((store.networkInvalidations[review.networkKey] ?? 0) !== invalidation || chainUnavailable(review.networkKey)) retirementStale.value = true
  } catch (error) {
    retirementError.value = `${error instanceof Error ? error.message : String(error)}. The outcome may be uncertain; check transaction status before retrying.`
    retirementUncertain.value = true
  } finally {
    retirementBusy.value = false
  }
}

async function reconcileRetirement() {
  const review = retirementReview.value
  if (!review || !canReconcileRetirement.value || !retirementPassword.value || retirementBusy.value) return
  const password = retirementPassword.value
  retirementPassword.value = ''
  retirementBusy.value = true
  retirementError.value = ''
  const invalidation = store.networkInvalidations[review.networkKey] ?? 0
  try {
    const result = await store.reconcileWallet(review.previewId, password)
    if (result.previewId !== review.previewId || result.txId !== review.txId || result.networkKey !== review.networkKey) {
      throw new Error('Reconciliation response did not match the reviewed transaction')
    }
    retirementReview.value = { ...review, state: result.state, reason: result.reason }
    retirementUncertain.value = result.state === 'previewed' || result.state === 'signing'
    retirementStale.value = (store.networkInvalidations[review.networkKey] ?? 0) !== invalidation || chainUnavailable(review.networkKey)
  } catch (error) {
    retirementError.value = `${error instanceof Error ? error.message : String(error)}. Reconciliation outcome may be uncertain; inspect the transaction before retrying.`
    retirementUncertain.value = true
  } finally {
    retirementBusy.value = false
  }
}


function copyToClipboard(value: string) {
  if (!value) return
  navigator.clipboard.writeText(value).then(
    () => { store.toastInfo = { title: 'Pool ID', message: 'Copied to clipboard' } },
    () => { store.toastError = { title: 'Pool ID', message: 'Copy to clipboard failed.' } }
  )
}

function rotateKesKey(nodeName: string) {
  emitter.emit('show-spending-password-modal', { action: 'rotate-kes', data: nodeName })
}

function restartNode(nodeName: string) {
  store.restartNodeByName(nodeName)
}

function addPool(parentId: number) {
  poolParentId.value = parentId
  showAddNodeWizard.value = true
}

function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: any }) {
  switch (data.action) {
    case 'update-color':
      store.updateNodeColor({ ...data.originalData, spendingPassword: data.password })
      showColorModal.value = false
      break
    case 'preview-pool-change':
      void previewPoolChange(data.originalData as PoolChangeRequest, data.password)
      break
    case 'rotate-kes':
      store.rotateKesByName({ name: data.originalData as string, spendingPassword: data.password })
      break
  }
}

onMounted(() => {
  store.requestNodes()
  store.requestHosts()
  store.fetchWalletItems()
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  poolChangePassword.value = ''
  retirementPassword.value = ''
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})
</script>

<style scoped>
.clickable:hover {
  cursor: pointer;
}
</style>
