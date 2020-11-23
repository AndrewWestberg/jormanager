<template>
  <div id="add_node">
    <h2>Add Node</h2>
    <vue-good-wizard :steps="steps" :onNext="nextClicked" :onBack="backClicked">
      <div slot="page1">
        <h4>Node Basics</h4>
        <b-form-group
          label="Other Node Colors"
          label-cols-md="2"
          v-if="nodeColors.length > 0"
        >
          <span v-for="(nodeColor, index) in nodeColors" :key="index">
            <font-awesome-icon
              :style="{ color: nodeColor }"
              :icon="['fas', 'circle']"
            />
          </span>
        </b-form-group>
        <b-form-group label="Color" label-for="color-input" label-cols-md="2">
          <b-form-input v-model="formNode.color" type="color"></b-form-input>
        </b-form-group>
        <b-form-group label="Host" label-for="host-select" label-cols-md="2">
          <b-form-select
            id="host-select"
            v-model="formNode.host"
            :state="hostState"
            :options="hostSelectOptions"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled
                >-- Please select an option --</b-form-select-option
              >
            </template>
          </b-form-select>
        </b-form-group>
        <b-form-group
          label="Name (TICKER)"
          label-for="name-input"
          label-cols-md="2"
        >
          <b-form-input
            id="name-input"
            v-model="formNode.name"
            :state="nameState"
            maxlength="6"
            aria-describedby="name-input-live-feedback"
            placeholder="e.g. tickr, relay2, etc..."
            trim
          ></b-form-input>
          <b-form-invalid-feedback id="name-input-live-feedback"
            >Enter at least 3 letters</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Node Type"
          label-for="type-radio"
          label-cols-md="2"
        >
          <b-form-radio-group
            id="type-radio"
            v-model="formNode.type"
            :state="typeState"
          >
            <b-form-radio value="relay">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                class="text-danger text-center"
              />&nbsp;Relay
            </b-form-radio>
            <b-form-radio value="core">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                class="text-success"
              />&nbsp;Core
            </b-form-radio>
          </b-form-radio-group>
        </b-form-group>
        <b-form-group label-cols-md="2" v-if="formNode.type == 'relay'">
          <b-form-checkbox id="default-checkbox" v-model="formNode.isDefault"
            >Make this node the default for sending
            transactions</b-form-checkbox
          >
        </b-form-group>
        <b-form-group
          label="Processor Threads"
          label-for="threads-input"
          label-cols-md="2"
        >
          <b-form-input
            id="threads-input"
            v-model="formNode.processorThreads"
            :state="processorThreadsState"
            placeholder="e.g. 2"
            type="range"
            min="0"
            max="8"
            step="1"
            trim
          />
          <p class="text-center">{{ formNode.processorThreads }} Threads</p>
        </b-form-group>
        <b-form-group
          label="Listen Address"
          label-for="listen-input"
          label-cols-md="2"
        >
          <b-form-input
            id="listen-input"
            v-model="formNode.listen"
            :state="listenState"
            aria-describedby="listen-input-live-feedback"
            placeholder="e.g. 0.0.0.0, 127.0.0.1, 192.168.16.12"
            trim
          ></b-form-input>
          <b-form-invalid-feedback id="listen-input-live-feedback"
            >Listen ip address for incoming connections</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Node Port"
          label-for="port-input"
          label-cols-md="2"
        >
          <b-form-input
            id="port-input"
            type="number"
            step="1"
            min="1024"
            max="65535"
            :state="portState"
            placeholder="e.g. 3001"
            aria-describedby="port-input-live-feedback"
            v-model="formNode.port"
            trim
          />
          <b-form-invalid-feedback id="port-input-live-feedback"
            >The port number the node will listen for connections
            on</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="EKG Port"
          label-for="ekg-port-input"
          label-cols-md="2"
        >
          <b-form-input
            id="ekg-port-input"
            type="number"
            step="1"
            min="1024"
            max="65535"
            :state="ekgPortState"
            placeholder="e.g. 12788"
            aria-describedby="ekg-port-input-live-feedback"
            v-model="formNode.ekgPort"
            trim
          />
          <b-form-invalid-feedback id="ekg-port-input-live-feedback"
            >The port number the node will run EKG monitoring on. -1 to
            auto-generate it.</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Prometheus Port"
          label-for="prom-port-input"
          label-cols-md="2"
        >
          <b-form-input
            id="prom-port-input"
            type="number"
            step="1"
            min="1024"
            max="65535"
            :state="promPortState"
            placeholder="e.g. 12789"
            aria-describedby="prom-port-input-live-feedback"
            v-model="formNode.promPort"
            trim
          />
          <b-form-invalid-feedback id="ekg-port-input-live-feedback"
            >The port number the node will run Prometheus monitoring on. -1 to
            auto-generate it.</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Genesis Byron"
          label-for="genesis-byron-select"
          label-cols-md="2"
        >
          <b-form-select
            id="genesis-byron-select"
            v-model="formNode.genesisByron"
            :state="genesisByronState"
            :options="genesisFiles"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled
                >-- Please select an option --</b-form-select-option
              >
            </template>
          </b-form-select>
        </b-form-group>
        <b-form-group
          label="Genesis Shelley"
          label-for="genesis-shelley-select"
          label-cols-md="2"
        >
          <b-form-select
            id="genesis-shelley-select"
            v-model="formNode.genesisShelley"
            :state="genesisShelleyState"
            :options="genesisFiles"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled
                >-- Please select an option --</b-form-select-option
              >
            </template>
          </b-form-select>
        </b-form-group>
      </div>
      <div slot="page2">
        <h4>Core Node Keys</h4>
        <b-form-group label="Pool COLD Keys">
          <b-form-checkbox
            id="cold-skey-generate-checkbox"
            v-model="formNode.generateColdKeys"
            >Generate</b-form-checkbox
          >
          <b-form-group
            label="skey"
            label-for="cold-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="cold-skey-file"
              :disabled="formNode.generateColdKeys"
              :placeholder="
                formNode.generateColdKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.coldSKey"
              :state="coldSKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="vkey"
            label-for="cold-vkey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="cold-vkey-file"
              :disabled="formNode.generateColdKeys"
              :placeholder="
                formNode.generateColdKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.coldVKey"
              :state="coldVKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="counter"
            label-for="cold-counter-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="cold-counter-file"
              :disabled="formNode.generateColdKeys"
              :placeholder="
                formNode.generateColdKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.coldCounter"
              :state="coldCounterState"
              trim
            />
          </b-form-group>
        </b-form-group>
        <b-form-group label="Pool VRF Keys">
          <b-form-checkbox
            id="vrf-skey-generate-checkbox"
            v-model="formNode.generateVRFKeys"
            >Generate</b-form-checkbox
          >
          <b-form-group
            label="skey"
            label-for="vrf-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="vrf-skey-file"
              :disabled="formNode.generateVRFKeys"
              :placeholder="
                formNode.generateVRFKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.vrfSKey"
              :state="vrfSKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="vkey"
            label-for="vrf-vkey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="vrf-vkey-file"
              :disabled="formNode.generateVRFKeys"
              :placeholder="
                formNode.generateVRFKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.vrfVKey"
              :state="vrfVKeyState"
              trim
            />
          </b-form-group>
        </b-form-group>
        <b-form-group label="Pool KES Keys">
          <b-form-checkbox
            id="kes-skey-generate-checkbox"
            v-model="formNode.generateKESKeys"
            >Generate</b-form-checkbox
          >
          <b-form-group
            label="skey"
            label-for="kes-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="kes-skey-file"
              :disabled="formNode.generateKESKeys"
              :placeholder="
                formNode.generateKESKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.kesSKey"
              :state="kesSKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="vkey"
            label-for="kes-vkey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="kes-vkey-file"
              :disabled="formNode.generateKESKeys"
              :placeholder="
                formNode.generateKESKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formNode.kesVKey"
              :state="kesVKeyState"
              trim
            />
          </b-form-group>
        </b-form-group>
      </div>
      <div slot="page3">
        <h4>Pool Config</h4>
        <b-form-group label="Account Config">
          <b-form-group
            label="Fees Account"
            label-for="registration-fees-account-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="registration-fees-account-select"
              aria-describedby="registration-fees-account-live-feedback"
              v-model="formNode.registrationFeesAccount"
              :options="
                registrationFeesSelectOptions($options.filters.currency)
              "
              :state="registrationFeesAccountState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled
                  >-- Please select an option --</b-form-select-option
                >
              </template>
            </b-form-select>
            <b-form-invalid-feedback
              id="registration-fees-account-live-feedback"
              >Account must hold enough to pay pool registration and delegation
              fees.</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Owner (Pledge) Account"
            label-for="owner-staking-account-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="owner-staking-account-select"
              v-model="formNode.ownerStakingAccount"
              :options="stakingSelectOptions($options.filters.currency)"
              :state="ownerStakingAccountState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled
                  >-- Please select an option --</b-form-select-option
                >
              </template>
            </b-form-select>
          </b-form-group>
          <b-form-group
            label="Rewards Account"
            label-for="rewards-staking-account-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="rewards-staking-account-select"
              aria-describedby="rewards-staking-account-live-feedback"
              v-model="formNode.rewardsStakingAccount"
              :options="rewardsSelectOptions($options.filters.currency)"
              :state="rewardsStakingAccountState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled
                  >-- Please select an option --</b-form-select-option
                >
              </template>
            </b-form-select>
            <b-form-invalid-feedback id="rewards-staking-account-live-feedback"
              >May be the same as owner account.</b-form-invalid-feedback
            >
          </b-form-group>
        </b-form-group>
        <b-form-group label="Pledge &amp; Fees">
          <b-form-group
            label="Pledge"
            label-for="pledge-input"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-input
              id="pledge-input"
              v-model="formNode.poolPledge"
              placeholder="e.g. ₳250,000.000000"
              :state="poolPledgeState"
              trim
              v-currency
            />
          </b-form-group>
          <b-form-group
            label="Cost"
            label-for="cost-input"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-input
              id="cost-input"
              v-model="formNode.poolCost"
              :state="poolCostState"
              placeholder="e.g. ₳340.000000"
              trim
              v-currency
            />
          </b-form-group>
          <b-form-group
            label="Margin"
            label-for="margin-input"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-input
              id="margin-input"
              v-model="formNode.poolMargin"
              :state="poolMarginState"
              placeholder="e.g. 0.06"
              type="range"
              min="0.00"
              max="1.00"
              step="0.0025"
              trim
            />
            <p class="text-center">
              {{ (formNode.poolMargin * 100).toFixed(2) }}%
            </p>
          </b-form-group>
        </b-form-group>
      </div>
      <div slot="page4">
        <h4>Relays</h4>
        <div v-for="(relay, index) in formNode.relays" :key="index">
          <b-card border-variant="secondary">
            <b-form-group
              label="Address"
              label-for="relay-address-input"
              label-cols-md="1"
            >
              <b-form-input
                id="relay-address-input"
                v-model="relay.addr"
                :state="relayAddrState(relay.addr)"
                aria-describedby="relay-address-input-live-feedback"
                placeholder="e.g. 240.116.25.34, relay1.mystakepool.com"
                trim
              ></b-form-input>
              <b-form-invalid-feedback id="relay-address-input-live-feedback"
                >Enter a valid dns name or ip address for your relay
                server.</b-form-invalid-feedback
              >
            </b-form-group>
            <b-form-group
              label="Port"
              label-for="relay-port-input"
              label-cols-md="1"
            >
              <b-form-input
                id="relay-port-input"
                type="number"
                step="1"
                min="1024"
                max="65535"
                :state="relayPortState(relay.port)"
                placeholder="e.g. 3001"
                aria-describedby="relay-port-input-live-feedback"
                v-model="relay.port"
                trim
              />
              <b-form-invalid-feedback id="relay-port-input-live-feedback"
                >The port number of the relay node.</b-form-invalid-feedback
              >
            </b-form-group>
          </b-card>
          <hr />
        </div>
        <b-button variant="primary" @click="addRelay()">
          <b-icon-plus />&nbsp;Add Relay
        </b-button>
      </div>
      <div slot="page5">
        <h4>Metadata</h4>
        <b-form-group>
          <h5>Primary (Required)</h5>
          <b-form-group
            label="Ticker"
            label-for="metadata-ticker-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-ticker-input"
              v-model="formNode.metadata.ticker"
              :state="tickerState"
              aria-describedby="metadata-ticker-input-live-feedback"
              placeholder="e.g. TICKR, ABC1, etc..."
              :formatter="formatTicker"
              trim
            />
            <b-form-invalid-feedback id="metadata-ticker-input-live-feedback"
              >Ticker must only contain 'A-Z', '0-9' and be 3 to 5 characters in
              length</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Name"
            label-for="metadata-name-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-name-input"
              v-model="formNode.metadata.name"
              :state="metadataNameState"
              aria-describedby="metadata-name-input-live-feedback"
              placeholder="e.g. My Awesome Stakepool"
              :formatter="formatMetadataName"
              trim
            />
            <b-form-invalid-feedback id="metadata-name-input-live-feedback"
              >Name must be between 1 and 50 characters in
              length</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Description"
            label-for="metadata-description-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-description-input"
              v-model="formNode.metadata.description"
              :state="metadataDescriptionState"
              aria-describedby="metadata-description-input-live-feedback"
              placeholder="e.g. The best stakepool located in Flippin, Arkansas!"
              :formatter="formatMetadataDescription"
              trim
            />
            <b-form-invalid-feedback
              id="metadata-description-input-live-feedback"
              >Description must be between 1 and 255 characters in
              length</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Homepage"
            label-for="metadata-homepage-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-homepage-input"
              v-model="formNode.metadata.homepage"
              :state="metadataHomepageState"
              aria-describedby="metadata-homepage-input-live-feedback"
              placeholder="e.g. https://flippin-stakes.com"
              trim
            />
            <b-form-invalid-feedback id="metadata-homepage-input-live-feedback"
              >Homepage must be https and 64 characters or less in
              length</b-form-invalid-feedback
            >
          </b-form-group>
        </b-form-group>
        <b-form-group>
          <h5>ITN Ticker Validation (Optional)</h5>
          <b-form-group
            label="ITN Pool prv"
            label-for="itn-prv-file"
            label-cols-md="2"
          >
            <b-form-file
              id="itn-prv-file"
              placeholder="Choose file or drop it here..."
              drop-placeholder="Drop file here..."
              v-model="formNode.metadata.extended.itn.privateKey"
              trim
            />
          </b-form-group>
          <b-form-group
            label="ITN Pool pub"
            label-for="itn-pub-file"
            label-cols-md="2"
          >
            <b-form-file
              id="itn-pub-file"
              placeholder="Choose file or drop it here..."
              drop-placeholder="Drop file here..."
              v-model="formNode.metadata.extended.itn.publicKey"
              trim
            />
          </b-form-group>
        </b-form-group>
        <b-form-group>
          <h5>Extended (Optional)</h5>
          <b-form-group
            label="Icon 64x64 URL"
            label-for="metadata-icon64-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-icon64-input"
              v-model="formNode.metadata.extended.info.icon64"
              :state="metadataIcon64State"
              aria-describedby="metadata-icon64-input-live-feedback"
              placeholder="e.g. https://flippin-stakes.com/icon64.png"
              trim
            />
            <b-form-invalid-feedback id="metadata-icon64-input-live-feedback"
              >Icon url must be a url</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Logo URL"
            label-for="metadata-logo-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-logo-input"
              v-model="formNode.metadata.extended.info.logo"
              :state="metadataLogoState"
              aria-describedby="metadata-logo-input-live-feedback"
              placeholder="e.g. https://flippin-stakes.com/logo512.png"
              trim
            />
            <b-form-invalid-feedback id="metadata-logo-input-live-feedback"
              >Logo url must be a url</b-form-invalid-feedback
            >
          </b-form-group>
          <b-form-group
            label="Location"
            label-for="metadata-location-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-location-input"
              v-model="formNode.metadata.extended.info.location"
              placeholder="e.g. United States, North America"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Twitter"
            label-for="metadata-twitter-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-twitter-input"
              v-model="formNode.metadata.extended.info.social.twitter"
              placeholder="e.g. IOHK_Charles"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Telegram"
            label-for="metadata-telegram-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-telegram-input"
              v-model="formNode.metadata.extended.info.social.telegram"
              placeholder="e.g. flippin_stakes_group"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Facebook"
            label-for="metadata-facebook-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-facebook-input"
              v-model="formNode.metadata.extended.info.social.facebook"
              placeholder="e.g. flippin_stakes"
              trim
            />
          </b-form-group>
          <b-form-group
            label="YouTube"
            label-for="metadata-youtube-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-youtube-input"
              v-model="formNode.metadata.extended.info.social.youtube"
              placeholder="e.g. flippin_stakes"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Twitch"
            label-for="metadata-twitch-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-twitch-input"
              v-model="formNode.metadata.extended.info.social.twitch"
              placeholder="e.g. flippin_stakes"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Discord"
            label-for="metadata-discord-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-discord-input"
              v-model="formNode.metadata.extended.info.social.discord"
              placeholder="e.g. FlippinStakes"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Github"
            label-for="metadata-github-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-github-input"
              v-model="formNode.metadata.extended.info.social.github"
              placeholder="e.g. FlippinStakes"
              trim
            />
          </b-form-group>
          <b-form-group
            label="RSS"
            label-for="metadata-rss-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-rss-input"
              v-model="formNode.metadata.extended.info.rss"
              placeholder="e.g. https://flippin-stakes/feed.atom"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Company Name"
            label-for="metadata-companyname-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-companyname-input"
              v-model="formNode.metadata.extended.info.company.name"
              placeholder="e.g. Flippin Stakes, LLC."
              trim
            />
          </b-form-group>
          <b-form-group
            label="Company Address"
            label-for="metadata-companyaddress-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-companyaddress-input"
              v-model="formNode.metadata.extended.info.company.addr"
              placeholder="e.g. 123 Backflip Lane"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Company City"
            label-for="metadata-companycity-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-companycity-input"
              v-model="formNode.metadata.extended.info.company.city"
              placeholder="e.g. Flippin, AK"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Company Country"
            label-for="metadata-companycountry-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-companycountry-input"
              v-model="formNode.metadata.extended.info.company.country"
              placeholder="e.g. United States"
              trim
            />
          </b-form-group>
          <b-form-group
            label="Company ID"
            label-for="metadata-companyid-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-companyid-input"
              v-model="formNode.metadata.extended.info.company.company_id"
              placeholder="e.g. 27-0641272"
              trim
            />
          </b-form-group>
          <b-form-group
            label="VAT ID"
            label-for="metadata-vatid-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-vatid-input"
              v-model="formNode.metadata.extended.info.company.vat_id"
              placeholder="e.g. J-30595991-8"
              trim
            />
          </b-form-group>
          <b-form-group
            label="About Me"
            label-for="metadata-aboutme-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-aboutme-input"
              v-model="formNode.metadata.extended.info.about.me"
              placeholder="e.g. 10-year veteran as a DevOps Engineer"
              trim
            />
          </b-form-group>
          <b-form-group
            label="About Server"
            label-for="metadata-aboutserver-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-aboutserver-input"
              v-model="formNode.metadata.extended.info.about.server"
              placeholder="e.g. Cloud Hosted at AWS around the world."
              trim
            />
          </b-form-group>
          <b-form-group
            label="About Company"
            label-for="metadata-aboutcompany-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-aboutcompany-input"
              v-model="formNode.metadata.extended.info.about.company"
              placeholder="e.g. Founded in 2020 for stakepool operations, Flippin Stakes, LLC has grown to 3 people."
              trim
            />
          </b-form-group>
          <b-form-group
            label="Telegram Admin"
            label-for="metadata-telegramadmin-input"
            label-cols-md="2"
          >
            <b-form-input
              id="metadata-telegramadmin-input"
              v-model="formNode.metadata.extended.telegramAdminHandle"
              placeholder="e.g. CottonEyedJoe"
              trim
            />
          </b-form-group>
        </b-form-group>
      </div>
      <div slot="page6">
        <h4>Confirmation</h4>
        <p>
          Creating a node requires
          <b>sudo</b> privileges to configure the systemd and rsyslog scripts.
          Leave empty if your host does not require a sudo password.
        </p>
        <b-form-group label="SUDO Password" label-for="sudo-input">
          <b-form-input
            id="sudo-input"
            type="password"
            v-model="formNode.sudoPassword"
          />
        </b-form-group>
      </div>
    </vue-good-wizard>
  </div>
</template>

<script>
import { GoodWizard } from "vue-good-wizard";
import { mapMutations, mapGetters, mapActions, mapState } from "vuex";

export default {
  name: "AddNodeWizard",
  components: {
    "vue-good-wizard": GoodWizard,
  },
  data() {
    return {
      formNode: {
        spendingPassword: null,
        color: "#4A412A",
        host: null,
        name: "",
        isDefault: false,
        type: null,
        processorThreads: 0,
        listen: "",
        port: "",
        ekgPort: "",
        promPort: "",
        genesisByron: null,
        genesisShelley: null,
        generateColdKeys: false,
        coldSKey: null,
        coldVKey: null,
        coldCounter: null,
        generateVRFKeys: false,
        vrfSKey: null,
        vrfVKey: null,
        generateKESKeys: false,
        kesSKey: null,
        kesVKey: null,
        registrationFeesAccount: null,
        ownerStakingAccount: null,
        rewardsStakingAccount: null,
        poolPledge: null,
        poolCost: null,
        poolMargin: 0.05,
        relays: [],
        metadata: {
          ticker: null,
          name: null,
          description: null,
          homepage: null,
          extended: {
            itn: {
              publicKey: null,
              privateKey: null,
            },
            info: {
              icon64: null,
              logo: null,
              location: null,
              social: {
                twitter: null,
                telegram: null,
                facebook: null,
                youtube: null,
                discord: null,
                github: null,
              },
              company: {
                name: null,
                addr: null,
                city: null,
                country: null,
                company_id: null,
                vat_id: null,
              },
              about: {
                me: null,
                server: null,
                company: null,
              },
              rss: null,
            },
            telegramAdminHandle: null,
          },
        },
        sudoPassword: null,
      },
    };
  },
  watch: {
    toastSuccess(toast) {
      if (toast.title === "Node Created") {
        this.$emit("hideAddNodeWizard");
      }
    },
  },
  computed: {
    ...mapGetters([
      "hostSelectOptions",
      "registrationFeesSelectOptions",
      "stakingSelectOptions",
      "rewardsSelectOptions",
      "genesisFiles",
    ]),
    ...mapState(["toastSuccess", "nodeColors"]),
    steps() {
      if (this.formNode.type === "core") {
        return [
          {
            label: "Node Basics",
            slot: "page1",
            options: {
              backEnabled: true,
            },
          },
          {
            label: "Core Node Keys",
            slot: "page2",
          },
          {
            label: "Pool Config",
            slot: "page3",
          },
          {
            label: "Relays",
            slot: "page4",
          },
          {
            label: "Metadata",
            slot: "page5",
          },
          {
            label: "Confirmation",
            slot: "page6",
          },
        ];
      }

      return [
        {
          label: "Node Basics",
          slot: "page1",
          options: {
            backEnabled: true,
          },
        },
        {
          label: "Confirmation",
          slot: "page6",
        },
      ];
    },
    hostState() {
      return this.formNode.host != null;
    },
    nameState() {
      return this.formNode.name.length > 2;
    },
    typeState() {
      return this.formNode.type != null;
    },
    processorThreadsState() {
      return this.formNode.processorThreads > 1;
    },
    listenState() {
      // matches an ip address
      return (
        this.formNode.listen.match(
          /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
        ) != null
      );
    },
    portState() {
      return this.formNode.port > 1023;
    },
    ekgPortState() {
      return this.formNode.ekgPort > 1023 || this.formNode.ekgPort == -1;
    },
    promPortState() {
      return this.formNode.promPort > 1023 || this.formNode.promPort == -1;
    },
    genesisByronState() {
      return this.formNode.genesisByron != null;
    },
    genesisShelleyState() {
      return this.formNode.genesisShelley != null;
    },
    coldSKeyState() {
      return this.formNode.generateColdKeys || this.formNode.coldSKey != null;
    },
    coldVKeyState() {
      return this.formNode.generateColdKeys || this.formNode.coldVKey != null;
    },
    coldCounterState() {
      return (
        this.formNode.generateColdKeys || this.formNode.coldCounter != null
      );
    },
    vrfSKeyState() {
      return this.formNode.generateVRFKeys || this.formNode.vrfSKey != null;
    },
    vrfVKeyState() {
      return this.formNode.generateVRFKeys || this.formNode.vrfVKey != null;
    },
    kesSKeyState() {
      return this.formNode.generateKESKeys || this.formNode.kesSKey != null;
    },
    kesVKeyState() {
      return this.formNode.generateKESKeys || this.formNode.kesVKey != null;
    },
    registrationFeesAccountState() {
      return this.formNode.registrationFeesAccount != null;
    },
    ownerStakingAccountState() {
      return this.formNode.ownerStakingAccount != null;
    },
    rewardsStakingAccountState() {
      return this.formNode.rewardsStakingAccount != null;
    },
    poolPledgeState() {
      if (this.formNode.poolPledge == null) {
        return false;
      }
      if (isNaN(this.formNode.poolPledge)) {
        return this.$ci.parse(this.formNode.poolPledge) > 0;
      } else {
        return this.formNode.poolPledge > 0;
      }
    },
    poolCostState() {
      if (this.formNode.poolCost == null) {
        return false;
      }
      if (isNaN(this.formNode.poolCost)) {
        return this.$ci.parse(this.formNode.poolCost) > 0;
      } else {
        return this.formNode.poolCost > 0;
      }
    },
    poolMarginState() {
      return (
        this.formNode.poolMargin >= 0.01 && this.formNode.poolMargin <= 1.0
      );
    },
    tickerState() {
      return (
        this.formNode.metadata.ticker != null &&
        this.formNode.metadata.ticker.match(/^[A-Z0-9]{3,5}$/) != null
      );
    },
    metadataNameState() {
      return (
        this.formNode.metadata.name != null &&
        this.formNode.metadata.name.length > 0
      );
    },
    metadataDescriptionState() {
      return (
        this.formNode.metadata.description != null &&
        this.formNode.metadata.name.length > 0
      );
    },
    metadataHomepageState() {
      return (
        this.formNode.metadata.homepage != null &&
        this.formNode.metadata.homepage.length < 65 &&
        this.formNode.metadata.homepage.match(/^https:\/\/.*/) != null
      );
    },
    metadataIcon64State() {
      return (
        this.formNode.metadata.extended.info.icon64 == null ||
        this.formNode.metadata.extended.info.icon64.length == 0 ||
        this.formNode.metadata.extended.info.icon64.match(/^https?:\/\/.*/) !=
          null
      );
    },
    metadataLogoState() {
      return (
        this.formNode.metadata.extended.info.logo == null ||
        this.formNode.metadata.extended.info.logo.length == 0 ||
        this.formNode.metadata.extended.info.logo.match(/^https?:\/\/.*/) !=
          null
      );
    },
  },
  methods: {
    ...mapActions(["requestHosts", "requestFileOptions", "createNode"]),
    ...mapMutations(["toastError"]),
    async nextClicked(currentPage) {
      if (currentPage === 0) {
        if (
          this.hostState &&
          this.nameState &&
          this.typeState &&
          this.listenState &&
          this.portState &&
          this.ekgPortState &&
          this.promPortState &&
          this.genesisByronState &&
          this.genesisShelleyState
        ) {
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields.",
          });
          return false;
        }
      } else if (currentPage === 1) {
        if (this.formNode.type === "core") {
          if (
            this.coldSKeyState &&
            this.coldVKeyState &&
            this.coldCounterState &&
            this.vrfSKeyState &&
            this.vrfVKeyState &&
            this.kesSKeyState &&
            this.kesVKeyState
          ) {
            return true;
          } else {
            this.toastError({
              title: "Error",
              message: "You must fill out all fields.",
            });
            return false;
          }
        } else {
          // relay node save!
          this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
            (spendingPassword) => {
              this.formNode.spendingPassword = spendingPassword;
              this.createNode(this.formNode);
              this.formNode.spendingPassword = null;
            }
          );
        }
      } else if (currentPage === 2) {
        if (
          this.registrationFeesAccountState &&
          this.ownerStakingAccountState &&
          this.rewardsStakingAccountState &&
          this.poolPledgeState &&
          this.poolCostState &&
          this.poolMarginState
        ) {
          if (isNaN(this.formNode.poolPledge)) {
            this.formNode.poolPledge = this.$ci.parse(this.formNode.poolPledge);
          }
          if (isNaN(this.formNode.poolCost)) {
            this.formNode.poolCost = this.$ci.parse(this.formNode.poolCost);
          }
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields.",
          });
          return false;
        }
      } else if (currentPage === 3) {
        if (this.formNode.relays.length === 0) {
          return true;
        } else {
          for (let i = 0; i < this.formNode.relays.length; i++) {
            let relay = this.formNode.relays[i];
            if (
              !this.relayAddrState(relay.addr) ||
              !this.relayPortState(relay.port)
            ) {
              this.toastError({
                title: "Error",
                message: "You must fill out all fields.",
              });
              return false;
            }
          }
          return true;
        }
      } else if (currentPage === 4) {
        if (
          this.tickerState &&
          this.metadataNameState &&
          this.metadataDescriptionState &&
          this.metadataHomepageState &&
          this.metadataIcon64State &&
          this.metadataLogoState
        ) {
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields.",
          });
          return false;
        }
      } else if (currentPage === 5) {
        // core node save!
        if (isNaN(this.formNode.poolPledge)) {
          this.formNode.poolPledge = this.$ci.parse(this.formNode.poolPledge);
        }
        if (isNaN(this.formNode.poolCost)) {
          this.formNode.poolCost = this.$ci.parse(this.formNode.poolCost);
        }
        if (this.formNode.coldSKey != null) {
          this.formNode.coldSKey = await this.formNode.coldSKey.text();
        }
        if (this.formNode.coldVKey != null) {
          this.formNode.coldVKey = await this.formNode.coldVKey.text();
        }
        if (this.formNode.coldCounter != null) {
          this.formNode.coldCounter = await this.formNode.coldCounter.text();
        }
        if (this.formNode.vrfSKey != null) {
          this.formNode.vrfSKey = await this.formNode.vrfSKey.text();
        }
        if (this.formNode.vrfVKey != null) {
          this.formNode.vrfVKey = await this.formNode.vrfVKey.text();
        }
        if (this.formNode.kesSKey != null) {
          this.formNode.kesSKey = await this.formNode.kesSKey.text();
        }
        if (this.formNode.kesVKey != null) {
          this.formNode.kesVKey = await this.formNode.kesVKey.text();
        }
        if (this.formNode.metadata.extended.itn.privateKey != null) {
          this.formNode.metadata.extended.itn.privateKey = await this.formNode.metadata.extended.itn.privateKey.text();
        }
        if (this.formNode.metadata.extended.itn.publicKey != null) {
          this.formNode.metadata.extended.itn.publicKey = await this.formNode.metadata.extended.itn.publicKey.text();
        }
        this.formNode.isDefault = false;

        this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
          (spendingPassword) => {
            this.formNode.spendingPassword = spendingPassword;
            this.createNode(this.formNode);
            this.formNode.spendingPassword = null;
          }
        );
      }

      // console.log("next clicked", currentPage);
      return true; //return false if you want to prevent moving to next page
    },
    backClicked(currentPage) {
      // console.log("back clicked", currentPage);
      if (currentPage === 2) {
        if (this.poolPledgeState && isNaN(this.formNode.poolPledge)) {
          this.formNode.poolPledge = this.$ci.parse(this.formNode.poolPledge);
        }
        if (this.poolCostState && isNaN(this.formNode.poolCost)) {
          this.formNode.poolCost = this.$ci.parse(this.formNode.poolCost);
        }
      }
      return true; //return false if you want to prevent moving to previous page
    },
    addRelay() {
      this.formNode.relays.push({
        addr: null,
        port: 3000,
      });
    },
    relayAddrState(relayAddr) {
      return (
        relayAddr != null &&
        (relayAddr.match(
          /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
        ) != null ||
          relayAddr.match(
            /^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9])$/
          ) != null)
      );
    },
    relayPortState(port) {
      return port > 1023;
    },
    formatTicker(value) {
      return value.substring(0, 5).toUpperCase();
    },
    formatMetadataName(value) {
      return value.substring(0, 50);
    },
    formatMetadataDescription(value) {
      return value.substring(0, 255);
    },
  },
  mounted() {
    this.requestHosts();
    this.requestFileOptions();
  },
};
</script>

<style>
#add_node > div > div.wizard__body {
  background-color: #333;
}
#add_node > div > span.wizard__arrow {
  background-color: #333;
}
#add_node > div > div > div.wizard__body__actions > .wizard__next {
  background-color: #007bff;
  border-bottom-right-radius: 5px;
  border-top-left-radius: 5px;
}
#add_node > div > div > div.wizard__body__actions > .wizard__back {
  background-color: #555;
  border-bottom-left-radius: 5px;
  border-top-right-radius: 5px;
}
#add_node > div > div > div.wizard__body__actions {
  background-color: #333;
  border-radius: 5px;
  border-top-style: hidden;
  border-bottom-style: hidden;
}
</style>