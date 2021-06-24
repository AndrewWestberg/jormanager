<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <b-button
          variant="outline-primary"
          @click="showAddNodeWizard = true"
          v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
        >
          <b-icon-plus />&nbsp;Node
        </b-button>
      </div>
      <hr />
      <div>
        <b-table
          bordered
          striped
          head-variant="light"
          :items="displayNodes"
          :fields="fields"
          v-if="displayNodes.length > 0"
        >
          <template v-slot:cell(name)="data">
            <div class="clearfix">
              <font-awesome-icon
                :style="{ color: data.item.color }"
                :icon="['fas', 'circle']"
                v-if="!data.item.isDefault"
                v-b-tooltip.hover.left="'Update Color'"
                @click="updateColor(data.item.id, data.item.color)"
              />
              <font-awesome-icon
                :style="{ color: data.item.color }"
                :icon="['fas', 'check-circle']"
                v-if="data.item.isDefault"
                v-b-tooltip.hover.left="'Update Color'"
                @click="updateColor(data.item.id, data.item.color)"
              />
              &nbsp;{{ data.value }}
              <div class="float-right" v-show="data.item.type !== 'relay'">
                <font-awesome-icon
                  :icon="['fas', 'copy']"
                  class="text-secondary"
                  v-b-tooltip.hover.v-secondary.right="
                    'Copy PoolId: ' +
                    (data.item.poolId || '').substring(0, 8) +
                    '... to clipboard'
                  "
                  @click="copyToClipboard(data.item.poolId)"
                />
              </div>
            </div>
          </template>
          <template v-slot:cell(type)="data">
            <div v-show="data.value === 'relay'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Relay Node'"
                class="text-danger text-center"
              />
            </div>
            <div v-show="data.value === 'core'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Core Node'"
                class="text-success"
              />
            </div>
            <div v-show="data.value === 'pool'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Pool Node'"
                class="text-primary"
              />
            </div>
          </template>
          <template v-slot:cell(kesExpireTimeSec)="data">
            <span v-if="data.value > -1">
              {{ data.value | moment("YYYY-MM-DD h:mma UTCZ") }}&nbsp;({{
                data.value | moment("from")
              }})&nbsp;
              <font-awesome-icon
                :icon="['fas', 'key']"
                class="text-warning"
                v-b-tooltip.hover.v-warning.right="'Rotate KES Key'"
                @click="rotateKesKey(displayNodes[data.index].name)"
              />
            </span>
          </template>
          <template v-slot:cell(edit)="data">
            <font-awesome-icon
              v-if="data.item.type !== 'pool'"
              :icon="['fas', 'power-off']"
              class="text-danger"
              v-b-tooltip.hover.v-danger.right="'Restart Node'"
              @click="restartNode(displayNodes[data.index].name)"
            />&nbsp;
            <font-awesome-icon
              v-if="data.item.type !== 'relay'"
              :icon="['fas', 'percent']"
              class="text-warning"
              v-b-tooltip.hover.v-warning.right="'Edit Pool Config'"
              @click="editPoolConfig(data.item.id)"
            />
            &nbsp;
            <font-awesome-icon
              v-if="data.item.type !== 'relay'"
              :icon="['fas', 'info-circle']"
              class="text-primary"
              v-b-tooltip.hover.v-primary.right="'Edit Metadata'"
              @click="editMetadata(data.item.id)"
            />
            &nbsp;
            <font-awesome-icon
              v-if="data.item.type !== 'relay'"
              :icon="['fas', 'project-diagram']"
              class="text-success"
              v-b-tooltip.hover.v-success.right="'Edit Relays'"
              @click="editRelays(data.item.id)"
            />
            &nbsp;
            <font-awesome-icon
              v-if="data.item.type !== 'relay'"
              :icon="['fas', 'skull']"
              class="text-danger"
              v-b-tooltip.hover.v-danger.right="'Retire Pool'"
              @click="retirePool(data.item.id)"
            />
            &nbsp;
            <font-awesome-icon
              v-show="data.item.type === 'core' && mp"
              :icon="['fas', 'plus-circle']"
              class="text-primary"
              v-b-tooltip.hover.v-primary.right="'Add Pool'"
              @click="
                poolParentId = data.item.id;
                showAddNodeWizard = true;
              "
            />
            &nbsp;
          </template>
        </b-table>
      </div>
    </div>
    <AddNodeWizard
      v-if="showAddNodeWizard"
      :parentId="poolParentId"
      @hideAddNodeWizard="showAddNodeWizard = false"
    />
    <b-modal
      id="modal-edit-color"
      title="Edit Color"
      no-close-on-backdrop
      @ok="handleSaveColor"
    >
      <b-form-group label="Color" label-cols-md="2">
        <b-form-input v-model="editColorForm.color" type="color"></b-form-input>
      </b-form-group>
    </b-modal>
    <b-modal
      id="modal-edit-pool-config"
      title="Edit Pool Config"
      no-close-on-backdrop
      @ok="handleSavePoolConfig"
      size="xl"
      scrollable
      ok-title="Save"
    >
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
            v-model="editPoolConfigForm.registrationFeesAccount"
            :options="
              reregistrationFeesSelectOptions($options.filters.currency)
            "
            :state="registrationFeesAccountState"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled
                >-- Please select an option --</b-form-select-option
              >
            </template>
          </b-form-select>
          <b-form-invalid-feedback id="registration-fees-account-live-feedback"
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
            v-model="editPoolConfigForm.ownerStakingAccount"
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
            v-model="editPoolConfigForm.rewardsStakingAccount"
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
            v-model="editPoolConfigForm.poolPledge"
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
            v-model="editPoolConfigForm.poolCost"
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
            v-model="editPoolConfigForm.poolMargin"
            :state="poolMarginState"
            placeholder="e.g. 0.06"
            type="number"
            min="0.00"
            max="1.00"
            step="0.001"
            trim
          />
          <p class="text-center">
            {{ (editPoolConfigForm.poolMargin * 100).toFixed(2) }}%
          </p>
        </b-form-group>
      </b-form-group>
    </b-modal>
    <b-modal
      id="modal-edit-metadata"
      title="Edit Metadata"
      no-close-on-backdrop
      @ok="handleSaveMetadata"
      size="xl"
      scrollable
      ok-title="Save"
    >
      <b-form-group>
        <b-form-group
          label="Fees Account"
          label-for="registration-fees-account-select"
          label-cols-md="2"
          label-align="right"
        >
          <b-form-select
            aria-describedby="registration-fees-account-live-feedback-metadata"
            v-model="editMetadataForm.registrationFeesAccount"
            :options="
              reregistrationFeesSelectOptions($options.filters.currency)
            "
            :state="registrationFeesAccountStateMetadata"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled
                >-- Please select an option --</b-form-select-option
              >
            </template>
          </b-form-select>
          <b-form-invalid-feedback
            id="registration-fees-account-live-feedback-metadata"
            >Account must hold enough to pay pool re-registration
            fees.</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Advanced"
          label-for="metadata-custom-checkbox"
          label-cols-md="2"
        >
          <b-form-checkbox
            id="metadata-custom-checkbox"
            v-model="editMetadataForm.custom"
            >I know what I am doing</b-form-checkbox
          >
        </b-form-group>
        <b-form-group
          label="Metadata URL"
          label-for="metadata-url-input"
          label-cols-md="2"
          v-show="editMetadataForm.custom"
        >
          <b-form-input
            id="metadata-url-input"
            v-model="editMetadataForm.metadataUrl"
          />
        </b-form-group>
        <b-form-group
          label="Extended URL"
          label-for="metadata-extended-url-input"
          label-cols-md="2"
          v-show="editMetadataForm.custom"
        >
          <b-form-input
            id="metadata-extended-url-input"
            v-model="editMetadataForm.extendedMetadataUrl"
          />
        </b-form-group>
        <h5 v-show="!editMetadataForm.custom">Primary (Required)</h5>
        <b-form-group
          v-show="!editMetadataForm.custom"
          label="Ticker"
          label-for="metadata-ticker-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-ticker-input"
            v-model="editMetadataForm.ticker"
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
          v-show="!editMetadataForm.custom"
          label="Name"
          label-for="metadata-name-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-name-input"
            v-model="editMetadataForm.name"
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
          v-show="!editMetadataForm.custom"
          label="Description"
          label-for="metadata-description-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-description-input"
            v-model="editMetadataForm.description"
            :state="metadataDescriptionState"
            aria-describedby="metadata-description-input-live-feedback"
            placeholder="e.g. The best stakepool located in Flippin, Arkansas!"
            :formatter="formatMetadataDescription"
            trim
          />
          <b-form-invalid-feedback id="metadata-description-input-live-feedback"
            >Description must be between 1 and 255 characters in
            length</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          v-show="!editMetadataForm.custom"
          label="Homepage"
          label-for="metadata-homepage-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-homepage-input"
            v-model="editMetadataForm.homepage"
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
      <b-form-group v-show="!editMetadataForm.custom">
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
            v-model="editMetadataForm.extended.itn.privateKey"
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
            v-model="editMetadataForm.extended.itn.publicKey"
            trim
          />
        </b-form-group>
      </b-form-group>
      <b-form-group v-show="!editMetadataForm.custom">
        <h5>Extended (Optional)</h5>
        <b-form-group
          label="Icon 64x64 URL"
          label-for="metadata-icon64-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-icon64-input"
            v-model="editMetadataForm.extended.info.icon64"
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
            v-model="editMetadataForm.extended.info.logo"
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
            v-model="editMetadataForm.extended.info.location"
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
            v-model="editMetadataForm.extended.info.social.twitter"
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
            v-model="editMetadataForm.extended.info.social.telegram"
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
            v-model="editMetadataForm.extended.info.social.facebook"
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
            v-model="editMetadataForm.extended.info.social.youtube"
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
            v-model="editMetadataForm.extended.info.social.twitch"
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
            v-model="editMetadataForm.extended.info.social.discord"
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
            v-model="editMetadataForm.extended.info.social.github"
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
            v-model="editMetadataForm.extended.info.rss"
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
            v-model="editMetadataForm.extended.info.company.name"
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
            v-model="editMetadataForm.extended.info.company.addr"
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
            v-model="editMetadataForm.extended.info.company.city"
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
            v-model="editMetadataForm.extended.info.company.country"
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
            v-model="editMetadataForm.extended.info.company.company_id"
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
            v-model="editMetadataForm.extended.info.company.vat_id"
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
            v-model="editMetadataForm.extended.info.about.me"
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
            v-model="editMetadataForm.extended.info.about.server"
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
            v-model="editMetadataForm.extended.info.about.company"
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
            v-model="editMetadataForm.extended.telegramAdminHandle"
            placeholder="e.g. CottonEyedJoe"
            trim
          />
        </b-form-group>
        <b-form-group
          label="Adapools Verify"
          label-for="metadata-adapoolsverify-input"
          label-cols-md="2"
        >
          <b-form-input
            id="metadata-adapoolsverify-input"
            v-model="editMetadataForm.extended.adapoolsVerify"
            placeholder="e.g. 7e0bf5c354c51252c2a3e3dc61a571ac"
            trim
          />
        </b-form-group>
      </b-form-group>
    </b-modal>
    <b-modal
      id="modal-edit-relays"
      title="Edit Relays"
      no-close-on-backdrop
      size="xl"
      @ok="handleEditRelays"
    >
      <b-form-group
        label="Fees Account"
        label-for="registration-fees-account-select"
        label-cols-md="1"
        label-align="right"
      >
        <b-form-select
          id="registration-fees-account-select"
          aria-describedby="registration-fees-account-live-feedback"
          v-model="editRelaysForm.registrationFeesAccount"
          :options="reregistrationFeesSelectOptions($options.filters.currency)"
          :state="registrationFeesAccountStateRelays"
        >
          <template v-slot:first>
            <b-form-select-option :value="null" disabled
              >-- Please select an option --</b-form-select-option
            >
          </template>
        </b-form-select>
        <b-form-invalid-feedback id="registration-fees-account-live-feedback"
          >Account must hold enough to pay pool registration and delegation
          fees.</b-form-invalid-feedback
        >
      </b-form-group>
      <div v-for="(relay, index) in editRelaysForm.relays" :key="index">
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
    </b-modal>
    <b-modal
      id="modal-retire-pool"
      title="Retire Pool"
      no-close-on-backdrop
      size="lg"
      @ok="handleRetirePool"
    >
      <b-form-group
        label="Fees Account"
        label-for="retire-pool-account-select"
        label-cols-md="2"
        label-align="right"
      >
        <b-form-select
          aria-describedby="retire-pool-account-live-feedback"
          v-model="retirePoolForm.retireFeesAccount"
          :options="reregistrationFeesSelectOptions($options.filters.currency)"
          :state="retireFeesAccountState"
        >
          <template v-slot:first>
            <b-form-select-option :value="null" disabled
              >-- Please select an option --</b-form-select-option
            >
          </template>
        </b-form-select>
        <b-form-invalid-feedback id="retire-pool-account-live-feedback"
          >Account must hold enough to pay retirement
          fees.</b-form-invalid-feedback
        >
      </b-form-group>
      <b-form-group
        label="Retire Epoch"
        label-for="retire-epoch-input"
        label-cols-md="2"
        label-align="right"
      >
        <b-form-input
          id="retire-epoch-input"
          type="number"
          step="1"
          :state="retireEpochState"
          placeholder="e.g. 261"
          aria-describedby="retire-epoch-input-live-feedback"
          v-model="retirePoolForm.retireEpoch"
          trim
        />
        <b-form-invalid-feedback id="retire-epoch-input-live-feedback"
          >Must be at least 1 epoch in the future and maximum of 18 epochs in
          the future.</b-form-invalid-feedback
        >
      </b-form-group>
      <b-form-group
        label="SUDO Password"
        label-for="sudo-input"
        label-cols-md="2"
        label-align="right"
      >
        <b-form-input
          id="sudo-input"
          type="password"
          v-model="retirePoolForm.sudoPassword"
        />
      </b-form-group>
    </b-modal>
  </div>
</template>

<script>
import _ from "lodash";
import { mapActions, mapGetters, mapState, mapMutations } from "vuex";
import AddNodeWizard from "@/components/AddNodeWizard";

export default {
  name: "Nodes",
  components: {
    AddNodeWizard,
  },
  data() {
    return {
      fields: [
        { key: "name", sortable: true },
        { key: "host", sortable: true },
        { key: "type", sortable: true },
        { key: "kesExpireTimeSec", sortable: true, label: "KES Expiry" },
        { key: "edit", label: "" },
      ],
      poolParentId: null,
      showAddNodeWizard: false,
      editColorForm: {
        id: -1,
        color: null,
      },
      editPoolConfigForm: {
        id: -1,
        spendingPassword: null,
        registrationFeesAccount: null,
        ownerStakingAccount: null,
        rewardsStakingAccount: null,
        poolPledge: null,
        poolCost: null,
        poolMargin: 0.05,
      },
      editMetadataForm: {
        id: -1,
        spendingPassword: null,
        registrationFeesAccount: null,
        custom: false,
        metadataUrl: null,
        extendedMetadataUrl: null,
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
          adapoolsVerify: null,
        },
      },
      editRelaysForm: {
        id: -1,
        spendingPassword: null,
        registrationFeesAccount: null,
        relays: [],
      },
      retirePoolForm: {
        id: null,
        sudoPassword: null,
        spendingPassword: null,
        retireFeesAccount: null,
        retireEpoch: null,
      },
    };
  },
  methods: {
    ...mapActions([
      "requestHosts",
      "requestNodes",
      "requestFileOptions",
      "requestMetadata",
      "restartNodeByName",
      "createNode",
      "rotateKesByName",
      "updateNodeColor",
      "updatePoolConfig",
      "updateMetadata",
      "fetchWalletItems",
      "sendEditRelays",
      "sendRetirePool",
    ]),
    ...mapMutations(["toastInfo", "toastError"]),
    restartNode(node) {
      this.$bvModal
        .msgBoxConfirm("Restart " + node + ". Are you sure?")
        .then((value) => {
          if (value) {
            this.restartNodeByName(node);
          }
        });
    },
    rotateKesKey(node) {
      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.rotateKesByName({
            name: node,
            spendingPassword: spendingPassword,
          });
        }
      );
    },
    updateColor(nodeId, nodeColor) {
      this.editColorForm.id = nodeId;
      this.editColorForm.color = nodeColor;
      this.$bvModal.show("modal-edit-color");
    },
    copyToClipboard(value) {
      this.$copyText(value).then(
        () => {
          this.toastInfo({
            title: "PoolId",
            message: "Copied to clipboard",
          });
        },
        () => {
          this.toastError({
            title: "PoolId",
            message: "Copy to clipboard failed.",
          });
        }
      );
    },
    handleSaveColor() {
      this.updateNodeColor(this.editColorForm);
    },
    editPoolConfig(nodeId) {
      this.$bvModal.show("modal-edit-pool-config");
      let node = _.find(this.nodes, ["id", nodeId]);
      this.editPoolConfigForm.id = node.id;
      this.editPoolConfigForm.ownerStakingAccount = node.ownerStakingAccountId;
      this.editPoolConfigForm.rewardsStakingAccount =
        node.rewardsStakingAccountId;
      this.editPoolConfigForm.poolPledge = node.poolPledge;
      this.editPoolConfigForm.poolCost = node.poolCost;
      this.editPoolConfigForm.poolMargin = node.poolMargin;
    },
    handleSavePoolConfig(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (
        this.registrationFeesAccountState &&
        this.ownerStakingAccountState &&
        this.rewardsStakingAccountState &&
        this.poolPledgeState &&
        this.poolCostState &&
        this.poolMarginState
      ) {
        this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
          (spendingPassword) => {
            this.editPoolConfigForm.spendingPassword = spendingPassword;
            if (isNaN(this.editPoolConfigForm.poolPledge)) {
              this.editPoolConfigForm.poolPledge = this.$ci.parse(
                this.editPoolConfigForm.poolPledge
              );
            }
            if (isNaN(this.editPoolConfigForm.poolCost)) {
              this.editPoolConfigForm.poolCost = this.$ci.parse(
                this.editPoolConfigForm.poolCost
              );
            }
            this.updatePoolConfig(this.editPoolConfigForm);
            this.editPoolConfigForm.spendingPassword = null;
            this.$bvModal.hide("modal-edit-pool-config");
          }
        );
      } else {
        this.toastError({
          title: "Error",
          message: "You must fill out all fields.",
        });
      }
    },
    editMetadata(nodeId) {
      this.$bvModal.show("modal-edit-metadata");
      this.editMetadataForm.id = nodeId;
      this.requestMetadata(nodeId);
    },
    async handleSaveMetadata(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (
        this.editMetadataForm.custom ||
        (this.registrationFeesAccountStateMetadata &&
          this.tickerState &&
          this.metadataNameState &&
          this.metadataDescriptionState &&
          this.metadataHomepageState &&
          this.metadataIcon64State &&
          this.metadataLogoState)
      ) {
        this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
          async (spendingPassword) => {
            this.editMetadataForm.spendingPassword = spendingPassword;

            if (this.editMetadataForm.extended.itn.privateKey != null) {
              this.editMetadataForm.extended.itn.privateKey =
                await this.editMetadataForm.extended.itn.privateKey.text();
            }
            if (this.editMetadataForm.extended.itn.publicKey != null) {
              this.editMetadataForm.extended.itn.publicKey =
                await this.editMetadataForm.extended.itn.publicKey.text();
            }
            this.updateMetadata(this.editMetadataForm);
            this.editMetadataForm.spendingPassword = null;
            this.$bvModal.hide("modal-edit-metadata");
          }
        );
      } else {
        this.toastError({
          title: "Error",
          message: "You must fill out all fields.",
        });
      }
    },
    editRelays(nodeId) {
      this.$bvModal.show("modal-edit-relays");
      this.editRelaysForm.id = nodeId;
    },
    handleEditRelays(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (!this.registrationFeesAccountStateRelays) {
        this.toastError({
          title: "Error",
          message: "You must fill out all fields.",
        });
        return;
      }
      for (let i = 0; i < this.editRelaysForm.relays.length; i++) {
        let relay = this.editRelaysForm.relays[i];
        if (
          !this.relayAddrState(relay.addr) ||
          !this.relayPortState(relay.port)
        ) {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields.",
          });
          return;
        }
      }

      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.editRelaysForm.spendingPassword = spendingPassword;

          this.sendEditRelays(this.editRelaysForm);
          this.editRelaysForm.spendingPassword = null;
          this.$bvModal.hide("modal-edit-relays");
        }
      );
    },
    addRelay() {
      this.editRelaysForm.relays.push({
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
    retirePool(nodeId) {
      this.retirePoolForm.id = nodeId;
      this.retirePoolForm.retireEpoch = this.epoch + 1;
      this.$bvModal.show("modal-retire-pool");
    },
    handleRetirePool(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (
        this.retireFeesAccountState &&
        this.retireEpochState &&
        this.retireSudoPasswordState
      ) {
        this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
          (spendingPassword) => {
            this.retirePoolForm.spendingPassword = spendingPassword;

            this.sendRetirePool(this.retirePoolForm);
            this.retirePoolForm.spendingPassword = null;
            this.retirePoolForm.sudoPassword = null;
            this.$bvModal.hide("modal-retire-pool");
          }
        );
      } else {
        this.toastError({
          title: "Error",
          message: "You must fill out all fields.",
        });
      }
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
  computed: {
    ...mapGetters([
      "displayNodes",
      "reregistrationFeesSelectOptions",
      "stakingSelectOptions",
      "rewardsSelectOptions",
    ]),
    ...mapState(["nodes", "editorMetadata", "epoch", "mp"]),
    registrationFeesAccountState() {
      return this.editPoolConfigForm.registrationFeesAccount != null;
    },
    ownerStakingAccountState() {
      return this.editPoolConfigForm.ownerStakingAccount != null;
    },
    rewardsStakingAccountState() {
      return this.editPoolConfigForm.rewardsStakingAccount != null;
    },
    poolPledgeState() {
      if (this.editPoolConfigForm.poolPledge == null) {
        return false;
      }
      if (isNaN(this.editPoolConfigForm.poolPledge)) {
        return this.$ci.parse(this.editPoolConfigForm.poolPledge) > 0;
      } else {
        return this.editPoolConfigForm.poolPledge > 0;
      }
    },
    poolCostState() {
      if (this.editPoolConfigForm.poolCost == null) {
        return false;
      }
      if (isNaN(this.editPoolConfigForm.poolCost)) {
        return this.$ci.parse(this.editPoolConfigForm.poolCost) > 0;
      } else {
        return this.editPoolConfigForm.poolCost > 0;
      }
    },
    poolMarginState() {
      return (
        this.editPoolConfigForm.poolMargin >= 0.0 &&
        this.editPoolConfigForm.poolMargin <= 1.0
      );
    },
    registrationFeesAccountStateMetadata() {
      return this.editMetadataForm.registrationFeesAccount != null;
    },
    registrationFeesAccountStateRelays() {
      return this.editRelaysForm.registrationFeesAccount != null;
    },
    tickerState() {
      return (
        this.editMetadataForm.ticker != null &&
        this.editMetadataForm.ticker.match(/^[A-Z0-9]{3,5}$/) != null
      );
    },
    metadataNameState() {
      return (
        this.editMetadataForm.name != null &&
        this.editMetadataForm.name.length > 0
      );
    },
    metadataDescriptionState() {
      return (
        this.editMetadataForm.description != null &&
        this.editMetadataForm.name.length > 0
      );
    },
    metadataHomepageState() {
      return (
        this.editMetadataForm.homepage != null &&
        this.editMetadataForm.homepage.length < 65 &&
        this.editMetadataForm.homepage.match(/^https:\/\/.*/) != null
      );
    },
    metadataIcon64State() {
      return (
        this.editMetadataForm.extended.info.icon64 == null ||
        this.editMetadataForm.extended.info.icon64.length == 0 ||
        this.editMetadataForm.extended.info.icon64.match(/^https?:\/\/.*/) !=
          null
      );
    },
    metadataLogoState() {
      return (
        this.editMetadataForm.extended.info.logo == null ||
        this.editMetadataForm.extended.info.logo.length == 0 ||
        this.editMetadataForm.extended.info.logo.match(/^https?:\/\/.*/) != null
      );
    },
    retireFeesAccountState() {
      return this.retirePoolForm.retireFeesAccount != null;
    },
    retireEpochState() {
      return this.retirePoolForm.retireEpoch != null;
    },
    retireSudoPasswordState() {
      return this.retirePoolForm.sudoPassword != null;
    },
  },
  watch: {
    editorMetadata(data) {
      this.editMetadataForm.ticker = data.metadata.ticker;
      this.editMetadataForm.name = data.metadata.name;
      this.editMetadataForm.description = data.metadata.description;
      this.editMetadataForm.homepage = data.metadata.homepage;
      this.editMetadataForm.extended.info.icon64 =
        data.extendedMetadata.info.urlPngIcon64x64;
      this.editMetadataForm.extended.info.logo =
        data.extendedMetadata.info.urlPngLogo;
      this.editMetadataForm.extended.info.location =
        data.extendedMetadata.info.location;
      this.editMetadataForm.extended.info.social.twitter =
        data.extendedMetadata.info.social.twitterHandle;
      this.editMetadataForm.extended.info.social.telegram =
        data.extendedMetadata.info.social.telegramHandle;
      this.editMetadataForm.extended.info.social.facebook =
        data.extendedMetadata.info.social.facebookHandle;
      this.editMetadataForm.extended.info.social.youtube =
        data.extendedMetadata.info.social.youtubeHandle;
      this.editMetadataForm.extended.info.social.discord =
        data.extendedMetadata.info.social.discordHandle;
      this.editMetadataForm.extended.info.social.github =
        data.extendedMetadata.info.social.githubHandle;
      this.editMetadataForm.extended.info.company.name =
        data.extendedMetadata.info.company.name;
      this.editMetadataForm.extended.info.company.addr =
        data.extendedMetadata.info.company.addr;
      this.editMetadataForm.extended.info.company.city =
        data.extendedMetadata.info.company.city;
      this.editMetadataForm.extended.info.company.country =
        data.extendedMetadata.info.company.country;
      this.editMetadataForm.extended.info.company.company_id =
        data.extendedMetadata.info.company.companyId;
      this.editMetadataForm.extended.info.company.vat_id =
        data.extendedMetadata.info.company.vatId;
      this.editMetadataForm.extended.info.about.me =
        data.extendedMetadata.info.about.me;
      this.editMetadataForm.extended.info.about.server =
        data.extendedMetadata.info.about.server;
      this.editMetadataForm.extended.info.about.company =
        data.extendedMetadata.info.about.company;
      this.editMetadataForm.extended.info.rss = data.extendedMetadata.info.rss;
      if (
        data.extendedMetadata.telegramAdminHandle != null &&
        data.extendedMetadata.telegramAdminHandle.length > 0
      ) {
        this.editMetadataForm.extended.telegramAdminHandle =
          data.extendedMetadata.telegramAdminHandle[0];
      }
      this.editMetadataForm.extended.adapoolsVerify =
        data.extendedMetadata.adapoolsVerify;
    },
  },
  mounted() {
    this.requestHosts();
    this.requestNodes();
    this.requestFileOptions();
    this.fetchWalletItems();
  },
};
</script>

<style scoped>
.fa-copy:hover,
.fa-info-circle:hover,
.fa-percent:hover,
.fa-circle:hover,
.fa-check-circle:hover,
.fa-plus-circle:hover,
.fa-power-off:hover,
.fa-skull:hover,
.fa-key:hover {
  cursor: pointer;
}
</style>