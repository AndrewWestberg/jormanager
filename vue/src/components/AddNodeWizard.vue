<template>
  <div id="add_node">
    <FormWizard
      ref="wizard"
      :key="wizardKey"
      step-size="sm"
      color="#28a745"
      title=""
      subtitle=""
      finish-button-text="Confirm"
      back-button-text="Back"
      next-button-text="Next"
      @on-complete="onComplete"
    >
      <!-- Step 1: Node Basics (Always shown) -->
      <TabContent title="Node Basics" icon="fas fa-dice-d20" :before-change="validateStep1">
        <h4>Node Basics</h4>
        
        <BFormGroup label="Other Node Colors" label-cols-md="2" v-if="nodeColors.length > 0">
          <span v-for="(nodeColor, index) in nodeColors" :key="index">
            <font-awesome-icon :style="{ color: nodeColor }" :icon="['fas', 'circle']" />
          </span>
        </BFormGroup>
        
        <BFormGroup label="Color" label-cols-md="2">
          <BFormInput v-model="formNode.color" type="color" />
        </BFormGroup>
        
        <BFormGroup label="Host" label-cols-md="2">
          <BFormSelect
            v-model="formNode.host"
            :state="hostState"
            :options="hostSelectOptions"
            :disabled="parentId != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Name (TICKER)" label-cols-md="2">
          <BFormInput
            v-model="formNode.name"
            :state="nameState"
            maxlength="6"
            placeholder="e.g. tickr, relay2, etc..."
            trim
          />
          <BFormInvalidFeedback>{{ nameError }}</BFormInvalidFeedback>
        </BFormGroup>
        
        <BFormGroup label="Node Type" label-cols-md="2">
          <BFormRadioGroup v-model="formNode.type" :state="typeState" :disabled="parentId != null">
            <BFormRadio value="relay">
              <font-awesome-icon :icon="['fas', 'dice-d20']" class="text-danger" />&nbsp;Relay
            </BFormRadio>
            <BFormRadio value="core">
              <font-awesome-icon :icon="['fas', 'dice-d20']" class="text-success" />&nbsp;Core
            </BFormRadio>
            <BFormRadio value="pool" v-show="parentId != null">
              <font-awesome-icon :icon="['fas', 'dice-d20']" class="text-primary" />&nbsp;Pool
            </BFormRadio>
          </BFormRadioGroup>
        </BFormGroup>
        
        <BFormGroup label-cols-md="2" v-if="formNode.type === 'relay'">
          <BFormCheckbox v-model="formNode.isDefault">
            Make this node the default for sending transactions
          </BFormCheckbox>
        </BFormGroup>
        
        <BFormGroup label="Listen Address" label-cols-md="2">
          <BFormInput
            v-model="formNode.listen"
            :disabled="parentId != null"
            :state="listenState"
            placeholder="e.g. 0.0.0.0, 127.0.0.1, 192.168.16.12"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Node Port" label-cols-md="2">
          <BFormInput
            type="number"
            step="1"
            min="1024"
            max="65535"
            :state="portState"
            placeholder="e.g. 3001"
            v-model="formNode.port"
            :disabled="parentId != null"
          />
        </BFormGroup>
        
        <BFormGroup label="Processor Threads" label-cols-md="2">
          <BFormInput
            v-model="formNode.processorThreads"
            :state="processorThreadsState"
            :disabled="parentId != null"
            type="range"
            min="0"
            max="8"
            step="1"
          />
          <p class="text-center">{{ formNode.processorThreads }} Threads</p>
        </BFormGroup>

        <BFormGroup label="Prometheus Bind" label-cols-md="2">
          <BFormRadioGroup v-model="formNode.prometheusListen">
            <BFormRadio value="127.0.0.1">127.0.0.1</BFormRadio>
            <BFormRadio value="0.0.0.0">0.0.0.0</BFormRadio>
          </BFormRadioGroup>
        </BFormGroup>

        <BFormGroup label-cols-md="2">
          <BFormCheckbox v-model="formNode.enableTracingListener">
            Enable tracing listener for JorManager
          </BFormCheckbox>
        </BFormGroup>

        <BFormGroup label="Tracing Bind" label-cols-md="2" v-if="formNode.enableTracingListener">
          <BFormRadioGroup v-model="formNode.tracingListen">
            <BFormRadio value="0.0.0.0">0.0.0.0</BFormRadio>
            <BFormRadio value="127.0.0.1">127.0.0.1</BFormRadio>
          </BFormRadioGroup>
        </BFormGroup>
        
        <BFormGroup label="Network configuration" label-cols-md="2">
          <BFormSelect v-model="formNode.bundleId" :disabled="parentId != null">
            <BFormSelectOption :value="null">Legacy configuration (including Conway)</BFormSelectOption>
            <BFormSelectOption v-for="bundle in store.networkBundleOptions.bundles" :key="bundle.bundleId" :value="bundle.bundleId">
              {{ bundle.catalogPrefix }} — reset {{ bundle.resetId }} — bundle {{ bundle.bundleId }}
            </BFormSelectOption>
          </BFormSelect>
          <small>{{ store.networkBundleOptions.status }}</small>
        </BFormGroup>
        <div v-if="selectedBundle" class="alert alert-info">
          Network magic: {{ selectedBundle.networkMagic }}; system start: {{ selectedBundle.systemStart }}.
          Selection does not establish host compatibility or current-era evidence; provisioning requires backend preflight.
        </div>
        <div v-if="formNode.bundleId && !selectedBundle" class="alert alert-warning">
          The selected bundle is not currently available as a verified bundle. Select a verified bundle before continuing.
        </div>
        <BFormGroup v-if="formNode.bundleId && parentId == null" label="Replace existing node" label-cols-md="2">
          <BFormSelect v-model="reconfigurationSource">
            <BFormSelectOption :value="null">No replacement (new node)</BFormSelectOption>
            <BFormSelectOption v-for="node in replacementSources" :key="node.id" :value="node.id">
              {{ node.name }} — {{ node.type }} — reset {{ node.configurationResetId }}
            </BFormSelectOption>
          </BFormSelect>
          <small>Replacement preserves the existing node and requires a separate clean directory and database.</small>
        </BFormGroup>
        <template v-if="!formNode.bundleId">
        <BFormGroup label="Genesis Byron" label-cols-md="2">
          <BFormSelect
            v-model="formNode.genesisByron"
            :state="genesisByronState"
            :options="genesisFileOptions"
            :disabled="parentId != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Genesis Shelley" label-cols-md="2">
          <BFormSelect
            v-model="formNode.genesisShelley"
            :state="genesisShelleyState"
            :options="genesisFileOptions"
            :disabled="parentId != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Genesis Alonzo" label-cols-md="2">
          <BFormSelect
            v-model="formNode.genesisAlonzo"
            :state="genesisAlonzoState"
            :options="genesisFileOptions"
            :disabled="parentId != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Genesis Conway" label-cols-md="2">
          <BFormSelect
            v-model="formNode.genesisConway"
            :state="genesisConwayState"
            :options="genesisFileOptions"
            :disabled="parentId != null"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        </template>
      </TabContent>
      
      <!-- Step 2: Pool Keys (Core/Pool only) -->
      <TabContent 
        v-if="formNode.type !== 'relay'" 
        title="Pool Keys" 
        icon="fas fa-key"
        :before-change="validateStep2"
      >
        <h4>Pool Keys</h4>
        
        <!-- COLD Keys Group -->
        <BFormGroup label="Pool COLD Keys">
          <BFormCheckbox v-model="formNode.generateColdKeys">Generate</BFormCheckbox>
          <BFormGroup label="skey" label-cols-md="1" class="mt-2">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateColdKeys"
              @change="handleFileUpload($event, 'coldSKey')" 
            />
          </BFormGroup>
          <BFormGroup label="vkey" label-cols-md="1">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateColdKeys"
              @change="handleFileUpload($event, 'coldVKey')" 
            />
          </BFormGroup>
          <BFormGroup label="counter" label-cols-md="1">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateColdKeys"
              @change="handleFileUpload($event, 'coldCounter')" 
            />
          </BFormGroup>
        </BFormGroup>
        
        <!-- VRF Keys Group -->
        <BFormGroup label="Pool VRF Keys">
          <BFormCheckbox v-model="formNode.generateVRFKeys">Generate</BFormCheckbox>
          <BFormGroup label="skey" label-cols-md="1" class="mt-2">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateVRFKeys"
              @change="handleFileUpload($event, 'vrfSKey')" 
            />
          </BFormGroup>
          <BFormGroup label="vkey" label-cols-md="1">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateVRFKeys"
              @change="handleFileUpload($event, 'vrfVKey')" 
            />
          </BFormGroup>
        </BFormGroup>
        
        <!-- KES Keys Group -->
        <BFormGroup label="Pool KES Keys">
          <BFormCheckbox v-model="formNode.generateKESKeys">Generate</BFormCheckbox>
          <BFormGroup label="skey" label-cols-md="1" class="mt-2">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateKESKeys"
              @change="handleFileUpload($event, 'kesSKey')" 
            />
          </BFormGroup>
          <BFormGroup label="vkey" label-cols-md="1">
            <input 
              type="file" 
              class="form-control" 
              :disabled="formNode.generateKESKeys"
              @change="handleFileUpload($event, 'kesVKey')" 
            />
          </BFormGroup>
        </BFormGroup>
      </TabContent>
      
      <!-- Step 3: Pool Config (Core/Pool only) -->
      <TabContent 
        v-if="formNode.type !== 'relay'" 
        title="Pool Config" 
        icon="fas fa-percent"
        :before-change="validateStep3"
      >
        <h4>Pool Configuration</h4>
        
        <BFormGroup label="Fees Account" label-cols-md="2">
          <BFormSelect
            v-model="formNode.registrationFeesAccount"
            :state="registrationFeesAccountState"
            :options="feeAccountOptions"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Owner (Pledge) Account" label-cols-md="2">
          <BFormSelect
            v-model="formNode.ownerStakingAccount"
            :state="ownerStakingAccountState"
            :options="stakingAccountOptions"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Rewards Account" label-cols-md="2">
          <BFormSelect
            v-model="formNode.rewardsStakingAccount"
            :state="rewardsStakingAccountState"
            :options="rewardsAccountOptions"
          >
            <template #first>
              <BFormSelectOption :value="null" disabled>-- Please select an option --</BFormSelectOption>
            </template>
          </BFormSelect>
        </BFormGroup>
        
        <BFormGroup label="Pledge" label-cols-md="2">
          <BFormInput
            v-model="formNode.poolPledge"
            :state="poolPledgeState"
            placeholder="e.g. 250000"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Cost" label-cols-md="2">
          <BFormInput
            v-model="formNode.poolCost"
            :state="poolCostState"
            placeholder="e.g. 340"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Margin" label-cols-md="2">
          <BFormInput
            v-model="formNode.poolMargin"
            :state="poolMarginState"
            placeholder="e.g. 0.06"
            type="number"
            min="0.00"
            max="1.00"
            step="0.001"
          />
          <p class="text-center">{{ (formNode.poolMargin * 100).toFixed(2) }}%</p>
        </BFormGroup>
      </TabContent>
      
      <!-- Step 4: Relays (Core/Pool only) -->
      <TabContent 
        v-if="formNode.type !== 'relay'" 
        title="Relays" 
        icon="fas fa-project-diagram"
        :before-change="validateStep4"
      >
        <h4>Relay Configuration</h4>
        
        <div v-for="(relay, index) in formNode.relays" :key="index" class="mb-3">
          <BCard class="bg-dark">
            <div class="d-flex justify-content-between align-items-center mb-2">
              <h5>Relay {{ index + 1 }}</h5>
              <BButton variant="outline-danger" size="sm" @click="formNode.relays.splice(index, 1)">✕</BButton>
            </div>
            <BFormGroup label="Address" label-cols-md="2">
              <BFormInput
                v-model="relay.addr"
                :state="relayAddrState(relay.addr)"
                placeholder="e.g. 240.116.25.34, relay1.mystakepool.com"
                trim
              />
            </BFormGroup>
            <BFormGroup label="Port" label-cols-md="2">
              <BFormInput
                v-model="relay.port"
                type="number"
                step="1"
                min="1024"
                max="65535"
                :state="relayPortState(relay.port)"
                placeholder="e.g. 3001"
              />
            </BFormGroup>
          </BCard>
        </div>
        
        <BButton variant="primary" @click="addRelay">+&nbsp;Add Relay</BButton>
      </TabContent>
      
      <!-- Step 5: Metadata (Core/Pool only) -->
      <TabContent 
        v-if="formNode.type !== 'relay'" 
        title="Metadata" 
        icon="fas fa-info-circle"
        :before-change="validateStep5"
      >
        <h4>Metadata</h4>
        
        <h5>Primary (Required)</h5>
        <BFormGroup label="Ticker" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.ticker"
            :state="tickerState"
            placeholder="e.g. TICKR, ABC1, etc..."
            maxlength="5"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Name" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.name"
            :state="metadataNameState"
            placeholder="e.g. My Awesome Stakepool"
            maxlength="50"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Description" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.description"
            :state="metadataDescriptionState"
            placeholder="e.g. The best stakepool located in Flippin, Arkansas!"
            maxlength="255"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Homepage" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.homepage"
            :state="metadataHomepageState"
            placeholder="e.g. https://flippin-stakes.com"
            trim
          />
        </BFormGroup>
        
        <h5 class="mt-4">ITN Ticker Validation (Optional)</h5>
        <BFormGroup label="ITN Pool prv" label-cols-md="2">
          <input 
            ref="itnPrivateFileInput"
            type="file" 
            class="form-control" 
            @change="handleFileUpload($event, 'itnPrivateKey')" 
          />
          <BButton v-if="fileContents.itnPrivateKey" variant="outline-secondary" class="mt-2" @click="clearItnPrivateKey">Remove private key</BButton>
        </BFormGroup>
        
        <BFormGroup label="ITN Pool pub" label-cols-md="2">
          <input 
            type="file" 
            class="form-control" 
            @change="handleFileUpload($event, 'itnPublicKey')" 
          />
        </BFormGroup>
        
        <h5 class="mt-4">Extended (Optional)</h5>
        <BFormGroup label="Icon 64x64 URL" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.icon64"
            :state="metadataIcon64State"
            placeholder="e.g. https://flippin-stakes.com/icon64.png"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Logo URL" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.logo"
            :state="metadataLogoState"
            placeholder="e.g. https://flippin-stakes.com/logo512.png"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Location" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.location"
            placeholder="e.g. United States, North America"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Twitter" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.twitter"
            placeholder="e.g. IOHK_Charles"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Telegram" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.telegram"
            placeholder="e.g. flippin_stakes_group"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Facebook" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.facebook"
            placeholder="e.g. flippin_stakes"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="YouTube" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.youtube"
            placeholder="e.g. flippin_stakes"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Twitch" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.twitch"
            placeholder="e.g. flippin_stakes"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Discord" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.discord"
            placeholder="e.g. FlippinStakes"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="GitHub" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.social.github"
            placeholder="e.g. FlippinStakes"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="RSS" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.rss"
            placeholder="e.g. https://flippin-stakes/feed.atom"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Company Name" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.name"
            placeholder="e.g. Flippin Stakes, LLC."
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Company Address" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.addr"
            placeholder="e.g. 123 Backflip Lane"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Company City" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.city"
            placeholder="e.g. Flippin, AK"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Company Country" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.country"
            placeholder="e.g. United States"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Company ID" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.company_id"
            placeholder="e.g. 27-0641272"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="VAT ID" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.company.vat_id"
            placeholder="e.g. J-30595991-8"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="About Me" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.about.me"
            placeholder="e.g. 10-year veteran as a DevOps Engineer"
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="About Server" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.about.server"
            placeholder="e.g. Cloud Hosted at AWS around the world."
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="About Company" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.info.about.company"
            placeholder="e.g. Founded in 2020 for stakepool operations..."
            trim
          />
        </BFormGroup>
        
        <BFormGroup label="Telegram Admin" label-cols-md="2">
          <BFormInput
            v-model="formNode.metadata.extended.telegramAdminHandle"
            placeholder="e.g. CottonEyedJoe"
            trim
          />
        </BFormGroup>
      </TabContent>
      
      <!-- Step 6: Review & Confirmation (All node types) -->
      <TabContent 
        title="Review & Confirm" 
        icon="fas fa-check-circle"
      >
        <h4>Review & Confirm</h4>
        
        <div class="mb-4">
          <h5>Node Basics</h5>
          <table class="table table-dark table-sm">
            <tbody>
              <tr>
                <th style="width: 30%">Name (TICKER)</th>
                <td>{{ formNode.name }}</td>
              </tr>
              <tr>
                <th>Type</th>
                <td><span class="text-capitalize">{{ formNode.type }}</span></td>
              </tr>
              <tr>
                <th>Host</th>
                <td>{{ selectedHost?.hostname }}</td>
              </tr>
              <tr v-if="formNode.bundleId">
                <th>Bundle / reset</th>
                <td>{{ formNode.bundleId }} / {{ formNode.expectedResetId }}</td>
              </tr>
              <tr v-if="selectedBundle">
                <th>Network</th>
                <td>{{ selectedBundle.catalogPrefix }}; magic {{ selectedBundle.networkMagic }}; system start {{ selectedBundle.systemStart }}</td>
              </tr>
              <tr v-if="formNode.listen">
                <th>Listen Address</th>
                <td>{{ formNode.listen }}</td>
              </tr>
              <tr v-if="formNode.port">
                <th>Port</th>
                <td>{{ formNode.port }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="formNode.bundleId" class="border rounded p-3 mb-3">
          <h5>Host and network preflight</h5>
          <p>The selected bundle is not current-era or host-capability evidence. Preview the effective role configuration and use a matching connected observer.</p>
          <BButton variant="outline-info" :disabled="!!provisioningPending || !store.connected" @click="previewProvisioning">Preview host preflight</BButton>
          <div v-if="provisioningPreview && provisioningCurrent" class="mt-2">
            <p>Host {{ provisioningPreview.hostId }}; observer node {{ provisioningPreview.observerNodeId }}; network {{ provisioningPreview.networkKey }}.</p>
            <p class="text-break">Effective config SHA-256: {{ provisioningPreview.configSha256 }}; Prometheus port {{ provisioningPreview.prometheusPort }}; tracing port {{ provisioningPreview.tracingPort }}.</p>
            <p v-if="provisioningCapability">Current era: {{ provisioningCapability.eraEvidence?.era ?? 'unavailable' }} ({{ provisioningCapability.eraEvidence?.freshness ?? 'unavailable' }}). node.create {{ provisioningOperation?.status ?? 'unavailable' }}: {{ provisioningOperation?.reason ?? provisioningCapability.reason }}</p>
            <p v-for="operation in provisioningCapability?.operations?.filter(item => item.operation !== 'node.create')" :key="operation.operation">
              {{ operation.operation }} {{ operation.status }}: {{ operation.reason }}
            </p>
            <details>
              <summary>Record independent host qualification evidence</summary>
              <p>Record only evidence actually verified for this host, network and effective configuration. A binary version or selected bundle alone is not qualification.</p>
              <p>Reviewed observer evidence: era {{ provisioningCapability?.eraEvidence?.era ?? 'unavailable' }}, generation {{ provisioningCapability?.eraEvidence?.generation ?? 'unavailable' }}, source {{ provisioningCapability?.eraEvidence?.source ?? 'unavailable' }}, point {{ provisioningCapability?.eraEvidence?.observedPoint ?? 'unavailable' }}, observed {{ provisioningCapability?.eraEvidence?.observedAt ?? 'unavailable' }}.</p>
              <BFormGroup label="Operation">
                <BFormSelect v-model="qualification.operation">
                  <BFormSelectOption value="node.create">Node creation</BFormSelectOption>
                  <BFormSelectOption value="leios.key.prepare">Leios key preparation</BFormSelectOption>
                  <BFormSelectOption value="leios.key.import">Leios key import</BFormSelectOption>
                </BFormSelect>
              </BFormGroup>
              <BFormGroup label="Evidence ID"><BFormInput v-model="qualification.evidenceId" /></BFormGroup>
              <BFormGroup label="Evidence SHA-256"><BFormInput v-model="qualification.evidenceSha256" /></BFormGroup>
              <BFormGroup label="CLI SHA-256"><BFormInput v-model="qualification.cliSha256" /></BFormGroup>
              <BFormGroup label="Node SHA-256"><BFormInput v-model="qualification.nodeSha256" /></BFormGroup>
              <BFormCheckbox v-model="qualificationConfirmed">I reviewed the observer era and generation above and attest the target evidence and artifact hashes.</BFormCheckbox>
              <p v-if="qualificationConfirmed && !qualificationReady" class="text-warning">Evidence or target changed; clear and renew the attestation.</p>
              <BButton class="mt-2" variant="outline-success" :disabled="!qualificationReady || !!provisioningPending" @click="recordHostQualification">Record qualification</BButton>
            </details>
          </div>
          <p v-else-if="provisioningPreview" class="text-warning">Target settings changed; preview the host again.</p>
          <p v-if="provisioningError" class="text-danger">{{ provisioningError }}</p>
        </div>
        <div v-if="!formNode.bundleId && formNode.type !== 'relay'" class="border rounded p-3 mb-3">
          <p>Conway draft preparation requires a current matching observer. The backend checks the selected genesis files against that observer; observer capability does not prove target host compatibility.</p>
          <BButton variant="outline-info" :disabled="!!provisioningPending || !store.connected" @click="checkLegacyObserver">Check Conway observer</BButton>
          <p v-if="legacyObserverCurrent && provisioningCapability">Observer era {{ provisioningCapability.eraEvidence?.era ?? 'unavailable' }} ({{ provisioningCapability.eraEvidence?.freshness ?? 'unavailable' }}): {{ provisioningOperation?.reason ?? provisioningCapability.reason }}</p>
          <p v-if="provisioningError" class="text-danger">{{ provisioningError }}</p>
        </div>
        <p v-if="formNode.type !== 'relay'" class="text-warning">Preparing a draft does not register or deploy a pool. Registration and deployment each require separate review and authorization.</p>
        <div v-if="formNode.type !== 'relay'" class="border rounded p-3 mb-3">
          <p>A pool draft persists protected keys and configuration only. It does not register a pool, install files, start a node or establish eligibility.</p>
          <p v-if="fileContents.itnPrivateKey" class="text-warning">Draft preparation cannot accept an ITN private key. Remove it in the metadata step; metadata must be published independently.</p>
          <BFormCheckbox v-model="draftConfirmed">I approve preparing a draft for the displayed host, role, network, accounts and pool settings.</BFormCheckbox>
          <BButton class="mt-2" variant="outline-info" :disabled="!draftConfirmed || !draftPreflightReady || !!draftPending || !replacementAuthorized || !!fileContents.itnPrivateKey"
            @click="requestDraft">Prepare pool draft</BButton>
          <p v-if="draftPending" class="text-warning">Awaiting the draft result. If disconnected, check the Nodes list before retrying; the outcome may be unknown.</p>
          <p v-if="draftError" class="text-danger">{{ draftError }}</p>
        </div>


        <div v-if="formNode.type !== 'relay'" class="mb-4">
          <h5>Pool Configuration</h5>
          <table class="table table-dark table-sm">
            <tbody>
              <tr>
                <th style="width: 30%">Pledge</th>
                <td>{{ formNode.poolPledge }}</td>
              </tr>
              <tr>
                <th>Fixed Cost</th>
                <td>{{ formNode.poolCost }}</td>
              </tr>
              <tr>
                <th>Margin</th>
                <td>{{ (formNode.poolMargin * 100).toFixed(2) }}%</td>
              </tr>
            </tbody>
          </table>

          <h5>Relays</h5>
          <table class="table table-dark table-sm" v-if="formNode.relays.length > 0">
            <thead>
              <tr>
                <th>Address</th>
                <th>Port</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(relay, idx) in formNode.relays" :key="idx">
                <td>{{ relay.addr }}</td>
                <td>{{ relay.port }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else>No relays configured.</p>
        </div>
        <div v-if="reconfigurationSource != null" class="border rounded p-3 mb-3">
          <h5>Reviewed network reconfiguration</h5>
          <p>Review the replacement before authorizing a clean database and replay. The original node, its keys and prior authorizations remain unchanged.</p>
          <BButton variant="outline-info" :disabled="!!reconfigurationPending || !store.connected" @click="previewReconfiguration">Review replacement</BButton>
          <div v-if="reconfigurationReview && reconfigurationCurrent" class="mt-2">
            <p>Original node {{ reconfigurationReview.sourceNodeId }} on host {{ reconfigurationReview.sourceHostId }} → target host {{ reconfigurationReview.targetHostId }}.</p>
            <p class="text-break">Prior bundle {{ reconfigurationReview.priorBundleId }} / reset {{ reconfigurationReview.priorResetId }}<br>Target bundle {{ reconfigurationReview.targetBundleId }} / reset {{ reconfigurationReview.targetResetId }}</p>
            <p>{{ reconfigurationReview.consequences }}</p>
            <pre class="text-wrap text-break">{{ reconfigurationReview.reviewedRequest }}</pre>
            <p>Review state: {{ reconfigurationReview.state }}. Authorization does not imply installation, startup or synchronization.</p>
            <template v-if="reconfigurationReview.state === 'previewed'">
              <BFormGroup label="Spending password">
                <BFormInput v-model="reconfigurationPassword" type="password" autocomplete="current-password" />
              </BFormGroup>
              <BFormCheckbox v-model="acknowledgeCleanDatabase">I approve this target and a clean database with full replay or supported recovery.</BFormCheckbox>
              <BButton class="mt-2" variant="success" :disabled="!acknowledgeCleanDatabase || !reconfigurationPassword || !!reconfigurationPending"
                @click="authorizeReconfiguration">Authorize replacement</BButton>
            </template>
          </div>
          <p v-else-if="reconfigurationReview" class="text-warning">The form or uploaded files changed; review the replacement again.</p>
          <p v-if="reconfigurationError" class="text-danger">{{ reconfigurationError }}</p>
        </div>


        <h5>SUDO Authentication</h5>
        <p>
          Creating a node requires <b>sudo</b> privileges to configure the systemd and rsyslog scripts.
          Leave empty if your host does not require a sudo password.
        </p>
        <BFormGroup label="SUDO Password" label-cols-md="2">
          <BFormInput
            type="password"
            v-model="formNode.sudoPassword"
            placeholder="Enter sudo password (optional)"
          />
        </BFormGroup>
      </TabContent>
      
      <!-- Custom cancel button -->
      <template #custom-buttons-left>
        <BButton variant="outline-secondary" @click="handleCancel">Cancel</BButton>
      </template>
    </FormWizard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { find } from 'lodash-es'
import { FormWizard, TabContent } from 'vue3-form-wizard'
import 'vue3-form-wizard/dist/style.css'
import {
  BButton,
  BFormGroup,
  BFormInput,
  BFormSelect,
  BFormSelectOption,
  BFormRadioGroup,
  BFormRadio,
  BFormCheckbox,
  BFormInvalidFeedback,
  BCard
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import { isCustomTracingListen, syncTracingListenForHost } from '@/utils/tracing'
import { generateUUID } from '@/utils/filters'

interface Relay {
  addr: string | null
  port: number
}

interface Metadata {
  ticker: string | null
  name: string | null
  description: string | null
  homepage: string | null
  extended: {
    itn: {
      publicKey: string | null
      privateKey: string | null
    }
    info: {
      icon64: string | null
      logo: string | null
      location: string | null
      social: {
        twitter: string | null
        telegram: string | null
        facebook: string | null
        youtube: string | null
        twitch: string | null
        discord: string | null
        github: string | null
      }
      company: {
        name: string | null
        addr: string | null
        city: string | null
        country: string | null
        company_id: string | null
        vat_id: string | null
      }
      about: {
        me: string | null
        server: string | null
        company: string | null
      }
      rss: string | null
    }
    telegramAdminHandle: string | null
  }
}

const props = defineProps<{
  parentId?: number | null
}>()

const emit = defineEmits<{
  (e: 'hide-add-node-wizard'): void
}>()

const store = useJorManagerStore()
const { hostSelectOptions, nodes } = storeToRefs(store)
const emitter = useEventBus()

const wizard = ref<InstanceType<typeof FormWizard> | null>(null)
const fileContents = ref<Record<string, File | null>>({})
const itnPrivateFileInput = ref<HTMLInputElement | null>(null)
const pendingAction = ref<string | null>(null)
const hasCustomizedTracingListen = ref(false)

const formNode = ref({
  spendingPassword: null as string | null,
  sudoPassword: null as string | null,
  parentId: null as number | null,
  color: '#4A412A',
  host: null as number | null,
  name: '',
  type: null as 'relay' | 'core' | 'pool' | null,
  isDefault: false,
  processorThreads: 0,
  listen: '',
  port: '' as string | number,
  prometheusListen: '127.0.0.1',
  enableTracingListener: true,
  tracingListen: '0.0.0.0',
  bundleId: null as string | null,
  expectedResetId: null as string | null,
  genesisByron: null as number | null,
  genesisShelley: null as number | null,
  genesisAlonzo: null as number | null,
  genesisConway: null as number | null,
  generateColdKeys: false,
  coldSKey: null as string | null,
  coldVKey: null as string | null,
  coldCounter: null as string | null,
  generateVRFKeys: false,
  vrfSKey: null as string | null,
  vrfVKey: null as string | null,
  generateKESKeys: false,
  kesSKey: null as string | null,
  kesVKey: null as string | null,
  registrationFeesAccount: null as number | null,
  ownerStakingAccount: null as number | null,
  rewardsStakingAccount: null as number | null,
  poolPledge: null as number | null,
  poolCost: null as number | null,
  poolMargin: 0.05,
  relays: [] as Relay[],
  metadata: {
    ticker: null,
    name: null,
    description: null,
    homepage: null,
    extended: {
      itn: { publicKey: null, privateKey: null },
      info: {
        icon64: null,
        logo: null,
        location: null,
        social: {
          twitter: null, telegram: null, facebook: null,
          youtube: null, twitch: null, discord: null, github: null
        },
        company: {
          name: null, addr: null, city: null,
          country: null, company_id: null, vat_id: null
        },
        about: { me: null, server: null, company: null },
        rss: null
      },
      telegramAdminHandle: null
    }
  } as Metadata
})

const nodeColors = computed(() => nodes.value.map(n => n.color))
const selectedHost = computed(() => store.hosts.find(host => host.id === formNode.value.host) ?? null)
type ReconfigurationReview = {
  id: string; sourceNodeId: number; sourceHostId: number; targetHostId: number
  priorBundleId: string; priorResetId: string; targetBundleId: string; targetResetId: string
  reviewedRequest: string; consequences: string; state: string
}
type ReplacementNode = { id: number; name: string; type: string; isDraft?: boolean; configurationBundleId?: string | null; configurationResetId?: string | null }
const replacementSources = computed(() => (nodes.value as unknown as ReplacementNode[])
  .filter(node => !node.isDraft && node.configurationBundleId && node.configurationResetId && node.type === formNode.value.type && node.type !== 'pool'))
const reconfigurationSource = ref<number | null>(null)
const reconfigurationReview = ref<ReconfigurationReview | null>(null)
const reconfigurationRequest = ref<Record<string, unknown> | null>(null)
const reconfigurationSignature = ref('')
const reconfigurationPending = ref<'network_reconfiguration_preview' | 'network_reconfiguration_authorize' | null>(null)
const reconfigurationError = ref('')
const reconfigurationPassword = ref('')
const acknowledgeCleanDatabase = ref(false)
const fileRevision = ref(0)
const currentSignature = computed(() => {
  const { spendingPassword: _spendingPassword, sudoPassword: _sudoPassword, ...details } = formNode.value
  return JSON.stringify([details, reconfigurationSource.value, fileRevision.value])
})
const reconfigurationCurrent = computed(() => !!reconfigurationRequest.value && reconfigurationSignature.value === currentSignature.value)

async function buildCreateRequest(): Promise<Record<string, unknown>> {
  const request = JSON.parse(JSON.stringify(formNode.value)) as Record<string, any>
  request.spendingPassword = ''
  request.sudoPassword = ''
  for (const field of ['coldSKey', 'coldVKey', 'coldCounter', 'vrfSKey', 'vrfVKey', 'kesSKey', 'kesVKey']) {
    if (fileContents.value[field]) request[field] = await fileContents.value[field]!.text()
  }
  if (fileContents.value.itnPrivateKey) request.metadata.extended.itn.privateKey = await fileContents.value.itnPrivateKey.text()
  if (fileContents.value.itnPublicKey) request.metadata.extended.itn.publicKey = await fileContents.value.itnPublicKey.text()
  if (formNode.value.bundleId) {
    delete request.genesisByron
    delete request.genesisShelley
    delete request.genesisAlonzo
    delete request.genesisConway
  }
  return request
}

async function previewReconfiguration() {
  if (reconfigurationSource.value == null || reconfigurationPending.value || !validateStep1()) return
  const signature = currentSignature.value
  const request = await buildCreateRequest()
  if (signature !== currentSignature.value) {
    reconfigurationError.value = 'The form changed while preparing the review; review it again.'
    return
  }
  reconfigurationSignature.value = signature
  reconfigurationRequest.value = request
  reconfigurationReview.value = null
  reconfigurationError.value = ''
  acknowledgeCleanDatabase.value = false
  store.leiosResponses = { ...store.leiosResponses, network_reconfiguration_preview: {} }
  reconfigurationPending.value = 'network_reconfiguration_preview'
  const publicRequest = JSON.parse(JSON.stringify(request)) as Record<string, any>
  publicRequest.coldSKey = null
  publicRequest.vrfSKey = null
  publicRequest.kesSKey = null
  publicRequest.metadata.extended.itn.privateKey = null
  store.sendMessage('/jormanager/network_reconfiguration_preview', { sourceNodeId: reconfigurationSource.value, request: publicRequest })
}

function authorizeReconfiguration() {
  if (!reconfigurationReview.value || !reconfigurationCurrent.value || !reconfigurationPassword.value ||
      !acknowledgeCleanDatabase.value || reconfigurationPending.value) return
  const password = reconfigurationPassword.value
  reconfigurationPassword.value = ''
  store.leiosResponses = { ...store.leiosResponses, network_reconfiguration_authorize: {} }
  reconfigurationPending.value = 'network_reconfiguration_authorize'
  store.sendMessage('/jormanager/network_reconfiguration_authorize', {
    id: reconfigurationReview.value.id, spendingPassword: password, acknowledgeCleanDatabase: true
  })
}

watch(() => store.leiosResponses, responses => {
  const type = reconfigurationPending.value
  if (!type) return
  const response = responses[type]
  const review = response?.data as ReconfigurationReview | undefined
  if (review && review.sourceNodeId === reconfigurationSource.value &&
      (type === 'network_reconfiguration_preview' || review.id === reconfigurationReview.value?.id)) {
    reconfigurationReview.value = review
    reconfigurationPending.value = null
  } else if (response?.exception) {
    reconfigurationError.value = response.exception.message
    reconfigurationPending.value = null
  }
})
watch(() => formNode.value.type, type => { if (type !== 'relay') formNode.value.isDefault = false })

watch(() => formNode.value.bundleId, bundle => { if (!bundle) reconfigurationSource.value = null })

type ProvisioningPreview = { hostId: number; observerNodeId: number; networkKey: string; bundleId: string; resetId: string; prometheusPort: number; tracingPort: number; configSha256: string }
type ProvisioningCapability = { requestId: string; eraEvidence?: { era?: string | null; freshness?: string; generation?: number | string; source?: string | null; observedPoint?: unknown; observedAt?: string | null }; operations?: Array<{ operation: string; status: string; reason: string }>; reason?: string }
const provisioningPreview = ref<ProvisioningPreview | null>(null)
const provisioningSignature = ref('')
const provisioningPending = ref<'node_provisioning_preview' | 'capabilities' | 'host_capability_qualification' | null>(null)
const provisioningRequestId = ref('')
const provisioningError = ref('')
const provisioningCapability = ref<ProvisioningCapability | null>(null)
const qualification = ref({ operation: 'node.create', evidenceId: '', evidenceSha256: '', cliSha256: '', nodeSha256: '' })
const qualificationConfirmed = ref(false)
const qualificationReview = ref<{ signature: string; era: string; generation: number | string } | null>(null)
const qualificationSignature = computed(() => JSON.stringify([provisioningPreview.value, qualification.value, provisioningCapability.value?.eraEvidence]))
watch(qualificationConfirmed, confirmed => {
  const evidence = provisioningCapability.value?.eraEvidence
  qualificationReview.value = confirmed && evidence?.freshness === 'current' && evidence.era && evidence.generation != null
    ? { signature: qualificationSignature.value, era: evidence.era, generation: evidence.generation }
    : null
})
const qualificationReady = computed(() => qualificationConfirmed.value && qualificationReview.value?.signature === qualificationSignature.value)
const provisioningCurrentSignature = computed(() => JSON.stringify([formNode.value.host, formNode.value.parentId, formNode.value.bundleId, formNode.value.expectedResetId, formNode.value.type, formNode.value.port, formNode.value.prometheusListen, formNode.value.genesisByron, formNode.value.genesisShelley, formNode.value.genesisAlonzo, formNode.value.genesisConway]))
const provisioningCurrent = computed(() => !!provisioningPreview.value && provisioningSignature.value === provisioningCurrentSignature.value)
const provisioningOperation = computed(() => provisioningCapability.value?.operations?.find(item => item.operation === 'node.create'))
const provisioningVerified = computed(() => provisioningCurrent.value &&
  provisioningCapability.value?.eraEvidence?.freshness === 'current' && provisioningOperation.value?.status === 'verified')
const legacyObserverCurrent = computed(() => !formNode.value.bundleId && provisioningSignature.value === provisioningCurrentSignature.value)
const draftPreflightReady = computed(() => formNode.value.bundleId
  ? provisioningVerified.value && provisioningCapability.value?.eraEvidence?.era === 'dijkstra'
  : legacyObserverCurrent.value && provisioningCapability.value?.eraEvidence?.era === 'conway' &&
    provisioningCapability.value.eraEvidence.freshness === 'current' &&
    ['verified', 'unverified'].includes(provisioningOperation.value?.status ?? ''))

function checkLegacyObserver() {
  if (!validateStep1() || provisioningPending.value || !store.connected) return
  const observer = nodes.value.find(node => (node as any).isDefault && !(node as any).isDraft) as
    | (typeof nodes.value[number] & { hostId: number }) | undefined
  if (!observer?.id) {
    provisioningError.value = 'A connected default observer is required.'
    return
  }
  provisioningSignature.value = provisioningCurrentSignature.value
  provisioningCapability.value = null
  provisioningError.value = ''
  provisioningRequestId.value = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, capabilities: {} }
  provisioningPending.value = 'capabilities'
  store.sendMessage('/jormanager/capabilities', { requestId: provisioningRequestId.value, hostId: observer.hostId, nodeId: observer.id })
}

const draftConfirmed = ref(false)
const draftPending = ref(false)
const draftError = ref('')
const draftSignature = ref('')
const replacementAuthorized = computed(() => reconfigurationSource.value == null ||
  (reconfigurationCurrent.value && reconfigurationReview.value?.state === 'authorized'))

function requestDraft() {
  if (!draftConfirmed.value || !draftPreflightReady.value || fileContents.value.itnPrivateKey ||
      !replacementAuthorized.value || draftPending.value || !validateStep1() || !validateStep3() || !validateStep4() || !validateStep5()) return
  draftSignature.value = currentSignature.value
  emitter.emit('show-spending-password-modal', { action: 'create-pool-draft' })
}

watch(() => store.leiosResponses.node_pool_draft, response => {
  if (!draftPending.value || !response) return
  const result = response.data as { id?: number; isDraft?: boolean } | undefined
  if (result?.id != null && result.isDraft) {
    draftPending.value = false
    store.requestNodes()
    store.toastInfo = { title: 'Pool draft prepared', message: `Draft node ${result.id} is not registered or running. Use its Leios panel to prepare credentials.` }
    emit('hide-add-node-wizard')
  } else if (response.exception) {
    draftPending.value = false
    draftError.value = response.exception.message
  }
})


function previewProvisioning() {
  if (!validateStep1() || !formNode.value.host || !formNode.value.bundleId || !formNode.value.expectedResetId || provisioningPending.value) return
  provisioningSignature.value = provisioningCurrentSignature.value
  provisioningPreview.value = null
  provisioningCapability.value = null
  provisioningError.value = ''
  qualificationConfirmed.value = false
  store.leiosResponses = { ...store.leiosResponses, node_provisioning_preview: {} }
  provisioningPending.value = 'node_provisioning_preview'
  store.sendMessage('/jormanager/node/provisioning-preview', {
    hostId: formNode.value.host, bundleId: formNode.value.bundleId, expectedResetId: formNode.value.expectedResetId,
    type: formNode.value.type, parentId: formNode.value.parentId, port: Number(formNode.value.port), prometheusListen: formNode.value.prometheusListen
  })
}

function requestHostCapabilities() {
  const preview = provisioningPreview.value
  if (!preview || !provisioningCurrent.value) return
  provisioningRequestId.value = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, capabilities: {} }
  provisioningPending.value = 'capabilities'
  store.sendMessage('/jormanager/capabilities', { requestId: provisioningRequestId.value, hostId: preview.hostId,
    observerNodeId: preview.observerNodeId, expectedNetworkKey: preview.networkKey, configSha256: preview.configSha256 })
}

function recordHostQualification() {
  const preview = provisioningPreview.value
  if (!preview || !provisioningCurrent.value || !qualificationReady.value || !qualificationReview.value || provisioningPending.value) return
  if (!qualification.value.evidenceId || ![qualification.value.evidenceSha256, qualification.value.cliSha256, qualification.value.nodeSha256].every(hash => /^[0-9a-f]{64}$/.test(hash))) {
    provisioningError.value = 'Provide an evidence ID and lowercase SHA-256 hashes.'
    return
  }
  provisioningRequestId.value = generateUUID()
  store.leiosResponses = { ...store.leiosResponses, host_capability_qualification: {} }
  provisioningPending.value = 'host_capability_qualification'
  const reviewed = qualificationReview.value
  qualificationConfirmed.value = false
  store.sendMessage('/jormanager/capabilities/host-qualification', {
    requestId: provisioningRequestId.value, hostId: preview.hostId, observerNodeId: preview.observerNodeId,
    expectedNetworkKey: preview.networkKey, qualification: { status: 'verified', ...qualification.value, configSha256: preview.configSha256,
      expectedEra: reviewed.era, expectedGeneration: reviewed.generation }
  })
}

watch(() => store.leiosResponses, responses => {
  const type = provisioningPending.value
  if (!type) return
  const response = responses[type]
  if (response?.exception) {
    provisioningError.value = response.exception.message
    provisioningPending.value = null
  } else if (type === 'node_provisioning_preview' && response?.data) {
    const preview = response.data as ProvisioningPreview
    provisioningPending.value = null
    if (provisioningSignature.value !== provisioningCurrentSignature.value ||
        preview.hostId !== formNode.value.host || preview.bundleId !== formNode.value.bundleId ||
        preview.resetId !== formNode.value.expectedResetId) {
      provisioningError.value = 'Preflight response does not match the current target; preview again.'
    } else {
      provisioningPreview.value = preview
      requestHostCapabilities()
    }
  } else if (type === 'capabilities') {
    const result = response?.data as ProvisioningCapability | undefined
    if (result?.requestId === provisioningRequestId.value) {
      provisioningCapability.value = result
      provisioningPending.value = null
    }
  } else if (type === 'host_capability_qualification') {
    const result = response?.data as { requestId: string; recorded: boolean; reason?: string } | undefined
    if (result?.requestId === provisioningRequestId.value) {
      provisioningPending.value = null
      if (result.recorded) requestHostCapabilities()
      else provisioningError.value = result.reason || 'Qualification was not recorded.'
    }
  }
})


// Key for FormWizard - changes when type changes to force re-render with correct tabs
const wizardKey = computed(() => `wizard-${formNode.value.type || 'none'}`)

const feeAccountOptions = computed(() => store.registrationFeesSelectOptions())
const stakingAccountOptions = computed(() => store.stakingSelectOptions())
const rewardsAccountOptions = computed(() => store.rewardsSelectOptions())
const selectedBundle = computed(() =>
  store.networkBundleOptions.bundles.find(bundle => bundle.bundleId === formNode.value.bundleId) ?? null
)

watch(selectedBundle, bundle => {
  if (props.parentId == null) formNode.value.expectedResetId = bundle?.resetId ?? null
})

const genesisFileOptions = computed(() => store.genesisFiles || [])

const nameError = ref('Name must be 1-6 characters, letters and numbers only')

// Validation computed properties
const hostState = computed(() => formNode.value.host != null)
const nameState = computed(() => formNode.value.name.length >= 1 && formNode.value.name.length <= 6)
const typeState = computed(() => formNode.value.type != null)
const listenState = computed(() => formNode.value.listen?.length > 0 || null)
const portState = computed(() => {
  const port = Number(formNode.value.port)
  return !isNaN(port) && port >= 1024 && port <= 65535
})
const processorThreadsState = computed(() => formNode.value.processorThreads >= 2)
const genesisByronState = computed(() => formNode.value.genesisByron != null)
const genesisShelleyState = computed(() => formNode.value.genesisShelley != null)
const genesisAlonzoState = computed(() => formNode.value.genesisAlonzo != null)
const genesisConwayState = computed(() => formNode.value.genesisConway != null)
const registrationFeesAccountState = computed(() => formNode.value.registrationFeesAccount != null)
const ownerStakingAccountState = computed(() => formNode.value.ownerStakingAccount != null)
const rewardsStakingAccountState = computed(() => formNode.value.rewardsStakingAccount != null)
const poolPledgeState = computed(() => formNode.value.poolPledge != null && formNode.value.poolPledge >= 0)
const poolCostState = computed(() => formNode.value.poolCost != null && formNode.value.poolCost >= 0)
const poolMarginState = computed(() => formNode.value.poolMargin >= 0 && formNode.value.poolMargin <= 1)
const tickerState = computed(() => {
  const ticker = formNode.value.metadata.ticker
  return ticker != null && /^[A-Z0-9]{3,5}$/.test(ticker.toUpperCase())
})
const metadataNameState = computed(() => {
  const name = formNode.value.metadata.name
  return name != null && name.length >= 1 && name.length <= 50
})
const metadataDescriptionState = computed(() => {
  const desc = formNode.value.metadata.description
  return desc != null && desc.length >= 1 && desc.length <= 255
})
const metadataHomepageState = computed(() => {
  const hp = formNode.value.metadata.homepage
  return hp != null && hp.startsWith('https://') && hp.length <= 64
})
const metadataIcon64State = computed(() => {
  const icon = formNode.value.metadata.extended.info.icon64
  return icon != null && icon.length > 0 && icon.startsWith('https://')
})
const metadataLogoState = computed(() => {
  const logo = formNode.value.metadata.extended.info.logo
  return logo != null && logo.length > 0 && logo.startsWith('https://')
})

watch(
  selectedHost,
  () => {
    formNode.value.tracingListen = syncTracingListenForHost(
      formNode.value.tracingListen,
      selectedHost.value,
      hasCustomizedTracingListen.value
    )
  },
  { immediate: true }
)

watch(
  () => formNode.value.tracingListen,
  (nextValue, previousValue) => {
    if (previousValue === undefined) {
      return
    }

    if (isCustomTracingListen(nextValue, selectedHost.value)) {
      hasCustomizedTracingListen.value = true
    }
  }
)

function handleFileUpload(event: Event, field: string) {
  const target = event.target as HTMLInputElement
  fileContents.value[field] = target.files?.[0] || null
  fileRevision.value++
}
function clearItnPrivateKey() {
  fileContents.value.itnPrivateKey = null
  if (itnPrivateFileInput.value) itnPrivateFileInput.value.value = ''
  fileRevision.value++
}


function relayAddrState(addr: string | null): boolean | null {
  if (!addr) return null
  const ipRegex = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
  const dnsRegex = /^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9])$/
  return ipRegex.test(addr) || dnsRegex.test(addr)
}

function relayPortState(port: number): boolean {
  return port >= 1024
}

function addRelay() {
  formNode.value.relays.push({ addr: null, port: 3000 })
}

// Validation functions for beforeChange
function validateStep1(): boolean {
  if (!hostState.value || !nameState.value || !typeState.value ||
      !processorThreadsState.value ||
      (formNode.value.bundleId
        ? !selectedBundle.value || selectedBundle.value.resetId !== formNode.value.expectedResetId
        : !genesisByronState.value || !genesisShelleyState.value ||
          !genesisAlonzoState.value || !genesisConwayState.value)) {
    store.toastError = { title: 'Error', message: 'You must fill out all required fields.' }
    return false
  }
  return true
}

function validateStep2(): boolean {
  // Pool keys step - no strict validation, files are optional if generating
  return true
}

function validateStep3(): boolean {
  if (!registrationFeesAccountState.value || !ownerStakingAccountState.value ||
      !rewardsStakingAccountState.value || !poolPledgeState.value ||
      !poolCostState.value || !poolMarginState.value) {
    store.toastError = { title: 'Error', message: 'You must fill out all required fields.' }
    return false
  }
  return true
}

function validateStep4(): boolean {
  for (const relay of formNode.value.relays) {
    if (!relayAddrState(relay.addr) || !relayPortState(relay.port)) {
      store.toastError = { title: 'Error', message: 'Invalid relay configuration.' }
      return false
    }
  }
  return true
}

function validateStep5(): boolean {
  if (!tickerState.value || !metadataNameState.value ||
      !metadataDescriptionState.value || !metadataHomepageState.value ||
      !metadataIcon64State.value || !metadataLogoState.value) {
    store.toastError = { title: 'Error', message: 'You must fill out all required fields.' }
    return false
  }
  return true
}

function handleCancel() {
  emit('hide-add-node-wizard')
}

async function onComplete() {
  // For relay nodes, this is called from Step 1. For core/pool, from Step 6.
  if (!validateStep1()) return
  if (formNode.value.type !== 'relay') {
    store.toastError = { title: 'Creation unavailable', message: 'Producer and pool creation require a durable registration review.' }
    return
  }
  if (formNode.value.bundleId && formNode.value.type === 'relay' && !provisioningVerified.value) {
    provisioningError.value = 'A current, verified host preflight is required before creation.'
    return
  }
  if (reconfigurationSource.value != null &&
      (!reconfigurationCurrent.value || reconfigurationReview.value?.state !== 'authorized')) {
    reconfigurationError.value = 'Review and authorize the current replacement before creating it.'
    return
  }
  pendingAction.value = 'create-node'
  emitter.emit('show-spending-password-modal', { action: 'create-node' })
}

async function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action === 'create-pool-draft') {
    if (!draftConfirmed.value || !draftPreflightReady.value || fileContents.value.itnPrivateKey ||
        !replacementAuthorized.value || draftPending.value || draftSignature.value !== currentSignature.value) return
    const request = reconfigurationSource.value != null
      ? { ...reconfigurationRequest.value!, reconfigurationId: reconfigurationReview.value!.id }
      : await buildCreateRequest()
    if (draftSignature.value !== currentSignature.value) {
      draftError.value = 'The form changed; review the draft again.'
      return
    }
    request.spendingPassword = data.password
    request.sudoPassword = formNode.value.sudoPassword || ''
    store.leiosResponses = { ...store.leiosResponses, node_pool_draft: {} }
    draftPending.value = true
    draftConfirmed.value = false
    store.sendMessage('/jormanager/node/pool-draft', request)
    formNode.value.sudoPassword = null
    fileContents.value = {}
    return
  }
  if (data.action !== 'create-node') return
  if (!validateStep1()) return
  if (formNode.value.type !== 'relay') return
  if (formNode.value.bundleId && formNode.value.type === 'relay' && !provisioningVerified.value) {
    provisioningError.value = 'Host preflight is no longer current.'
    return
  }
  if (reconfigurationSource.value != null &&
      (!reconfigurationCurrent.value || reconfigurationReview.value?.state !== 'authorized')) {
    reconfigurationError.value = 'The replacement review is no longer current.'
    return
  }
  const request = reconfigurationSource.value != null
    ? { ...reconfigurationRequest.value!, reconfigurationId: reconfigurationReview.value!.id }
    : await buildCreateRequest()
  request.spendingPassword = data.password
  request.sudoPassword = formNode.value.sudoPassword || ''
  store.createNode(request)
  formNode.value.sudoPassword = null
  reconfigurationRequest.value = null
  reconfigurationPassword.value = ''
  fileContents.value = {}
  emit('hide-add-node-wizard')
}

onMounted(() => {
  store.requestHosts()
  store.requestFileOptions()
  store.requestNetworkBundleOptions()
  
  if (props.parentId) {
    store.requestMetadata(props.parentId)
    formNode.value.parentId = props.parentId
    const parent = find(nodes.value, { id: props.parentId })
    if (parent) {
      formNode.value.color = parent.color
      formNode.value.type = 'pool'
      formNode.value.host = (parent as any).hostId as number
      formNode.value.processorThreads = (parent as any).processorThreads || 2
      formNode.value.listen = (parent as any).listen || '0.0.0.0'
      formNode.value.port = (parent as any).port || 3001
      formNode.value.genesisByron = (parent as any).genesisByronFileId
      formNode.value.genesisShelley = (parent as any).genesisShelleyFileId
      formNode.value.genesisAlonzo = (parent as any).genesisAlonzoFileId
      formNode.value.genesisConway = (parent as any).genesisConwayFileId
      formNode.value.bundleId = (parent as any).configurationBundleId ?? null
      formNode.value.expectedResetId = (parent as any).configurationResetId ?? null
      formNode.value.poolPledge = (parent as any).poolPledge || 0
      formNode.value.poolCost = (parent as any).poolCost || 340
      formNode.value.poolMargin = (parent as any).poolMargin || 0.03
    }
  }

  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
  reconfigurationPassword.value = ''
  reconfigurationRequest.value = null
})
</script>

<style scoped>
#add_node {
  padding: 1rem;
}

/* Override vue3-form-wizard styles for dark theme */
:deep(.wizard-header) {
  display: none;
}

:deep(.wizard-nav-pills) {
  background: transparent;
}

:deep(.wizard-icon-circle) {
  background: #333 !important;
  border-color: #28a745 !important;
}

:deep(.wizard-icon-circle .wizard-icon) {
  color: #fff !important;
}

:deep(.stepTitle) {
  color: #ccc !important;
}

:deep(.wizard-tab-content) {
  background: #333;
  padding: 1.5rem;
  border-radius: 0.5rem;
  margin-top: 1rem;
}

:deep(.wizard-footer-left),
:deep(.wizard-footer-right) {
  margin-top: 1rem;
}

:deep(.wizard-btn) {
  background-color: #28a745 !important;
  border-color: #28a745 !important;
}

:deep(.wizard-btn:hover) {
  background-color: #218838 !important;
  border-color: #1e7e34 !important;
}
</style>
