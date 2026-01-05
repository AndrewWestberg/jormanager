<template>
  <div id="add_node">
    <FormWizard
      ref="wizard"
      :key="wizardKey"
      step-size="sm"
      color="#28a745"
      title=""
      subtitle=""
      finish-button-text="Create Node"
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
        
        <BFormGroup label="EKG Port" label-cols-md="2">
          <BFormInput
            v-model="formNode.ekgPort"
            type="number"
            step="1"
            min="-1"
            max="65535"
            :state="ekgPortState"
            placeholder="e.g. 12788 (-1 for auto)"
            :disabled="parentId != null"
          />
        </BFormGroup>
        
        <BFormGroup label="Prometheus Port" label-cols-md="2">
          <BFormInput
            v-model="formNode.promPort"
            type="number"
            step="1"
            min="-1"
            max="65535"
            :state="promPortState"
            placeholder="e.g. 12798 (-1 for auto)"
            :disabled="parentId != null"
          />
        </BFormGroup>
        
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
            type="file" 
            class="form-control" 
            @change="handleFileUpload($event, 'itnPrivateKey')" 
          />
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
      
      <!-- Step 6: Confirmation (All node types) -->
      <TabContent 
        title="Confirmation" 
        icon="fas fa-check-circle"
      >
        <h4>Confirmation</h4>
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
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
import { lovelaceToAda } from '@/utils/filters'

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
const pendingAction = ref<string | null>(null)

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
  ekgPort: '' as string | number,
  promPort: '' as string | number,
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

// Key for FormWizard - changes when type changes to force re-render with correct tabs
const wizardKey = computed(() => `wizard-${formNode.value.type || 'none'}`)

const feeAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.registrationFeesSelectOptions(formatter)
})

const stakingAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.stakingSelectOptions(formatter)
})

const rewardsAccountOptions = computed(() => {
  const formatter = (val: number) => lovelaceToAda(val * 1000000)
  return store.rewardsSelectOptions(formatter)
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
const ekgPortState = computed(() => {
  const port = Number(formNode.value.ekgPort)
  return !isNaN(port) && port >= -1 && port <= 65535
})
const promPortState = computed(() => {
  const port = Number(formNode.value.promPort)
  return !isNaN(port) && port >= -1 && port <= 65535
})
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

function handleFileUpload(event: Event, field: string) {
  const target = event.target as HTMLInputElement
  fileContents.value[field] = target.files?.[0] || null
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
      !processorThreadsState.value || !ekgPortState.value || !promPortState.value ||
      !genesisByronState.value || !genesisShelleyState.value ||
      !genesisAlonzoState.value || !genesisConwayState.value) {
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
  pendingAction.value = 'create-node'
  emitter.emit('show-spending-password-modal', { action: 'create-node' })
}

async function onSpendingPasswordConfirmed(data: { action: string; password: string; originalData: unknown }) {
  if (data.action !== 'create-node') return
  const spendingPassword = data.password
  
  formNode.value.spendingPassword = spendingPassword
  
  // Read file contents for core/pool nodes
  for (const field of ['coldSKey', 'coldVKey', 'coldCounter', 'vrfSKey', 'vrfVKey', 'kesSKey', 'kesVKey']) {
    if (fileContents.value[field]) {
      (formNode.value as any)[field] = await fileContents.value[field]!.text()
    }
  }
  
  // Read ITN key files for extended metadata
  if (fileContents.value['itnPrivateKey']) {
    formNode.value.metadata.extended.itn.privateKey = await fileContents.value['itnPrivateKey']!.text()
  }
  if (fileContents.value['itnPublicKey']) {
    formNode.value.metadata.extended.itn.publicKey = await fileContents.value['itnPublicKey']!.text()
  }
  
  store.createNode(formNode.value)
  formNode.value.spendingPassword = null
  emit('hide-add-node-wizard')
}

onMounted(() => {
  store.requestHosts()
  store.requestFileOptions()
  
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
      formNode.value.ekgPort = (parent as any).ekgPort || 12788
      formNode.value.promPort = (parent as any).promPort || 12798
      formNode.value.genesisByron = (parent as any).genesisByronFileId
      formNode.value.genesisShelley = (parent as any).genesisShelleyFileId
      formNode.value.genesisAlonzo = (parent as any).genesisAlonzoFileId
      formNode.value.genesisConway = (parent as any).genesisConwayFileId
      formNode.value.poolPledge = (parent as any).poolPledge || 0
      formNode.value.poolCost = (parent as any).poolCost || 340
      formNode.value.poolMargin = (parent as any).poolMargin || 0.03
    }
  }
  
  emitter.on('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
})

onUnmounted(() => {
  emitter.off('confirm-spending-password-with-action', onSpendingPasswordConfirmed)
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