<template>
  <div id="add_node">
    <h2>Add Node</h2>
    <vue-good-wizard :steps="steps" :onNext="nextClicked" :onBack="backClicked">
      <div slot="page1">
        <h4>Node Basics</h4>
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
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
            </template>
          </b-form-select>
        </b-form-group>
        <b-form-group label="Name (TICKER)" label-for="name-input" label-cols-md="2">
          <b-form-input
            id="name-input"
            v-model="formNode.name"
            :state="nameState"
            maxlength="6"
            aria-describedby="name-input-live-feedback"
            placeholder="e.g. tickr, relay2, etc..."
            trim
          ></b-form-input>
          <b-form-invalid-feedback id="name-input-live-feedback">Enter at least 3 letters</b-form-invalid-feedback>
        </b-form-group>
        <b-form-group label-cols-md="2">
          <b-form-checkbox
            id="default-checkbox"
            v-model="formNode.isDefault"
          >Make this node the default for sending transactions</b-form-checkbox>
        </b-form-group>
        <b-form-group label="Node Type" label-for="type-radio" label-cols-md="2">
          <b-form-radio-group id="type-radio" v-model="formNode.type" :state="typeState">
            <b-form-radio value="relay">
              <font-awesome-icon :icon="['fas', 'dice-d20']" class="text-danger text-center" />&nbsp;Relay
            </b-form-radio>
            <b-form-radio value="core">
              <font-awesome-icon :icon="['fas', 'dice-d20']" class="text-success" />&nbsp;Core
            </b-form-radio>
          </b-form-radio-group>
        </b-form-group>
        <b-form-group label="Processor Threads" label-for="threads-input" label-cols-md="2">
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
          <p class="text-center">{{formNode.processorThreads}} Threads</p>
        </b-form-group>
        <b-form-group label="Listen Address" label-for="listen-input" label-cols-md="2">
          <b-form-input
            id="listen-input"
            v-model="formNode.listen"
            :state="listenState"
            aria-describedby="listen-input-live-feedback"
            placeholder="e.g. 0.0.0.0, 127.0.0.1, 192.168.16.12"
            trim
          ></b-form-input>
          <b-form-invalid-feedback
            id="listen-input-live-feedback"
          >Listen ip address for incoming connections</b-form-invalid-feedback>
        </b-form-group>
        <b-form-group label="Node Port" label-for="port-input" label-cols-md="2">
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
          <b-form-invalid-feedback
            id="port-input-live-feedback"
          >The port number the node will listen for connections on</b-form-invalid-feedback>
        </b-form-group>
        <b-form-group label="Genesis Byron" label-for="genesis-byron-select" label-cols-md="2">
          <b-form-select
            id="genesis-byron-select"
            v-model="formNode.genesisByron"
            :state="genesisByronState"
            :options="genesisFiles"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
            </template>
          </b-form-select>
        </b-form-group>
        <b-form-group label="Genesis Shelley" label-for="genesis-shelley-select" label-cols-md="2">
          <b-form-select
            id="genesis-shelley-select"
            v-model="formNode.genesisShelley"
            :state="genesisShelleyState"
            :options="genesisFiles"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
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
          >Generate</b-form-checkbox>
          <b-form-group
            label="skey"
            label-for="cold-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="cold-skey-file"
              :disabled="formNode.generateColdKeys"
              :placeholder="formNode.generateColdKeys ? '---' : 'Choose file or drop it here...'"
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
              :placeholder="formNode.generateColdKeys ? '---' : 'Choose file or drop it here...'"
              drop-placeholder="Drop file here..."
              v-model="formNode.coldVKey"
              :state="coldVKeyState"
              trim
            />
          </b-form-group>
        </b-form-group>
        <b-form-group label="Pool VRF Keys">
          <b-form-checkbox
            id="vrf-skey-generate-checkbox"
            v-model="formNode.generateVRFKeys"
          >Generate</b-form-checkbox>
          <b-form-group
            label="skey"
            label-for="vrf-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="vrf-skey-file"
              :disabled="formNode.generateVRFKeys"
              :placeholder="formNode.generateVRFKeys ? '---' : 'Choose file or drop it here...'"
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
              :placeholder="formNode.generateVRFKeys ? '---' : 'Choose file or drop it here...'"
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
          >Generate</b-form-checkbox>
          <b-form-group
            label="skey"
            label-for="kes-skey-file"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-file
              id="kes-skey-file"
              :disabled="formNode.generateKESKeys"
              :placeholder="formNode.generateKESKeys ? '---' : 'Choose file or drop it here...'"
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
              :placeholder="formNode.generateKESKeys ? '---' : 'Choose file or drop it here...'"
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
            label="Owner Account"
            label-for="owner-staking-account-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="owner-staking-account-select"
              aria-describedby="owner-staking-account-live-feedback"
              v-model="formNode.ownerStakingAccount"
              :options="stakingSelectOptions($options.filters.currency)"
              :state="ownerStakingAccountState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
              </template>
            </b-form-select>
            <b-form-invalid-feedback
              id="owner-staking-account-live-feedback"
            >Account must hold enough to pay fees.</b-form-invalid-feedback>
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
                <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
              </template>
            </b-form-select>
            <b-form-invalid-feedback
              id="rewards-staking-account-live-feedback"
            >May be the same as owner account.</b-form-invalid-feedback>
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
          <b-form-group label="Cost" label-for="cost-input" label-cols-md="1" label-align="right">
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
            <p class="text-center">{{(formNode.poolMargin * 100).toFixed(2)}}%</p>
          </b-form-group>
        </b-form-group>
      </div>
      <div slot="page4">
        <h4>Relays</h4>
      </div>
      <div slot="page5">
        <h4>Metadata</h4>
      </div>
      <div slot="page6">
        <h4>Confirmation</h4>
        <p>
          Creating a node requires
          <b>sudo</b> privileges to configure the systemd and rsyslog scripts. Leave empty if your host does not require a sudo password.
        </p>
        <b-form-group label="SUDO Password" label-for="sudo-input">
          <b-form-input id="sudo-input" type="password" v-model="formNode.sudoPassword" />
        </b-form-group>
      </div>
    </vue-good-wizard>
  </div>
</template>

<script>
import { GoodWizard } from "vue-good-wizard";
import { mapMutations, mapGetters, mapActions } from "vuex";

export default {
  name: "AddNodeWizard",
  components: {
    "vue-good-wizard": GoodWizard,
  },
  data() {
    return {
      formNode: {
        color: "#4A412A",
        host: null,
        name: "",
        isDefault: false,
        type: null,
        processorThreads: 0,
        listen: "",
        port: "",
        genesisByron: null,
        genesisShelley: null,
        generateColdKeys: false,
        coldSKey: null,
        coldVKey: null,
        generateVRFKeys: false,
        vrfSKey: null,
        vrfVKey: null,
        generateKESKeys: false,
        kesSKey: null,
        kesVKey: null,
        ownerStakingAccount: null,
        rewardsStakingAccount: null,
        poolPledge: null,
        poolCost: null,
        poolMargin: 0.1,
        sudoPassword: null,
      },
    };
  },
  computed: {
    ...mapGetters([
      "hostSelectOptions",
      "stakingSelectOptions",
      "rewardsSelectOptions",
      "genesisFiles",
    ]),
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
          slot: "page4",
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
      return this.formNode.listen.match(/(\d{1,3}\.){3}\d{1,3}/) != null;
    },
    portState() {
      return this.formNode.port > 1023;
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
    ownerStakingAccountState() {
      return this.formNode.ownerStakingAccount != null;
    },
    rewardsStakingAccountState() {
      return this.formNode.rewardsStakingAccount != null;
    },
    poolPledgeState() {
      return this.formNode.poolPledge > 0;
    },
    poolCostState() {
      return this.formNode.poolCost > 0;
    },
    poolMarginState() {
      return this.formNode.poolMargin >= 0.0 && this.formNode.poolMargin <= 1.0;
    },
  },
  methods: {
    ...mapActions(["requestHosts", "requestFileOptions", "createNode"]),
    ...mapMutations(["toastError"]),
    nextClicked(currentPage) {
      // if (currentPage === 0) {
      //   if (
      //     this.hostState &&
      //     this.nameState &&
      //     this.typeState &&
      //     this.listenState &&
      //     this.portState &&
      //     this.genesisByronState &&
      //     this.genesisShelleyState
      //   ) {
      //     return true;
      //   } else {
      //     this.toastError({
      //       title: "Error",
      //       message: "You must fill out all fields.",
      //     });
      //     return false;
      //   }
      // } else if (currentPage === 1) {
      //   if (this.formNode.type === "core") {
      //     if (
      //       this.coldSKeyState &&
      //       this.coldVKeyState &&
      //       this.vrfSKeyState &&
      //       this.vrfVKeyState &&
      //       this.kesSKeyState &&
      //       this.kesVKeyState
      //     ) {
      //       return true;
      //     } else {
      //       this.toastError({
      //         title: "Error",
      //         message: "You must fill out all fields.",
      //       });
      //       return false;
      //     }
      //   } else {
      //     // relay node save!
      //     this.createNode(this.formNode);
      //     this.$emit("hideAddNodeWizard");
      //   }
      // } else if (currentPage === 2) {
      //   if (
      //     this.ownerStakingSKeyState &&
      //     this.ownerStakingVKeyState &&
      //     this.poolPledgeState &&
      //     this.poolCostState &&
      //     this.poolMarginState
      //   ) {
      //     return true;
      //   } else {
      //     this.toastError({
      //       title: "Error",
      //       message: "You must fill out all fields.",
      //     });
      //     return false;
      //   }
      // } else if (currentPage === 3) {
      //   // core node save!
      //   this.createNode(this.formNode);
      //   this.$emit("hideAddNodeWizard");
      // }

      console.log("next clicked", currentPage);
      return true; //return false if you want to prevent moving to next page
    },
    backClicked(/*currentPage*/) {
      // console.log("back clicked", currentPage);
      return true; //return false if you want to prevent moving to previous page
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