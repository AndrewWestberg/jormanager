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
        <b-form-group label="Genesis" label-for="genesis-select" label-cols-md="2">
          <b-form-select
            id="genesis-select"
            v-model="formNode.genesis"
            :state="genesisState"
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
        <b-form-group label="Owner STAKING Keys">
          <b-form-group
            label="skey"
            label-for="owner-staking-skey-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="owner-staking-skey-select"
              v-model="formNode.ownerStakingSKey"
              :options="stakingSKeys"
              :state="ownerStakingSKeyState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
              </template>
            </b-form-select>
          </b-form-group>
          <b-form-group
            label="vkey"
            label-for="owner-staking-vkey-select"
            label-cols-md="1"
            label-align="right"
          >
            <b-form-select
              id="owner-staking-vkey-select"
              v-model="formNode.ownerStakingVKey"
              :options="stakingVKeys"
              :state="ownerStakingVKeyState"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
              </template>
            </b-form-select>
          </b-form-group>
          <b-form-group label="Pledge &amp; Fees">
            <b-form-group
              label="Pledge (lovelace)"
              label-for="pledge-input"
              label-cols-md="1"
              label-align="right"
            >
              <b-form-input
                id="pledge-input"
                v-model="formNode.poolPledge"
                placeholder="e.g. 250000000000"
                :state="poolPledgeState"
                type="number"
                trim
              />
            </b-form-group>
            <b-form-group
              label="Cost (lovelace)"
              label-for="cost-input"
              label-cols-md="1"
              label-align="right"
            >
              <b-form-input
                id="cost-input"
                v-model="formNode.poolCost"
                :state="poolCostState"
                placeholder="e.g. 200000000"
                type="number"
                trim
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
                min="0.0"
                max="1.0"
                step="0.005"
                trim
              />
              <p class="text-center">{{(formNode.poolMargin * 100).toFixed(1)}}%</p>
            </b-form-group>
          </b-form-group>
        </b-form-group>
      </div>
      <div slot="page4">
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
    "vue-good-wizard": GoodWizard
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
        genesis: null,
        generateColdKeys: false,
        coldSKey: null,
        coldVKey: null,
        generateVRFKeys: false,
        vrfSKey: null,
        vrfVKey: null,
        generateKESKeys: false,
        kesSKey: null,
        kesVKey: null,
        ownerStakingSKey: null,
        ownerStakingVKey: null,
        poolPledge: null,
        poolCost: null,
        poolMargin: 0.1,
        sudoPassword: null
      }
    };
  },
  computed: {
    ...mapGetters([
      "hostSelectOptions",
      "stakingSKeys",
      "stakingVKeys",
      "paymentSKeys",
      "paymentVKeys",
      "genesisFiles"
    ]),
    steps() {
      if (this.formNode.type === "core") {
        return [
          {
            label: "Node Basics",
            slot: "page1",
            options: {
              backEnabled: true
            }
          },
          {
            label: "Core Node Keys",
            slot: "page2"
          },
          {
            label: "Pool Config",
            slot: "page3"
          },
          {
            label: "Confirmation",
            slot: "page4"
          }
        ];
      }

      return [
        {
          label: "Node Basics",
          slot: "page1",
          options: {
            backEnabled: true
          }
        },
        {
          label: "Confirmation",
          slot: "page4"
        }
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
    genesisState() {
      return this.formNode.genesis != null;
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
    ownerStakingSKeyState() {
      return this.formNode.ownerStakingSKey != null;
    },
    ownerStakingVKeyState() {
      return this.formNode.ownerStakingVKey != null;
    },
    poolPledgeState() {
      return this.formNode.poolPledge > 0;
    },
    poolCostState() {
      return this.formNode.poolCost > 0;
    },
    poolMarginState() {
      return this.formNode.poolMargin >= 0.0 && this.formNode.poolMargin <= 1.0;
    }
  },
  methods: {
    ...mapActions(["requestHosts", "requestFileOptions", "createNode"]),
    ...mapMutations(["toastError"]),
    nextClicked(currentPage) {
      if (currentPage === 0) {
        if (
          this.hostState &&
          this.nameState &&
          this.typeState &&
          this.listenState &&
          this.portState &&
          this.genesisState
        ) {
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields."
          });
          return false;
        }
      } else if (currentPage === 1) {
        if (this.formNode.type === "core") {
          if (
            this.coldSKeyState &&
            this.coldVKeyState &&
            this.vrfSKeyState &&
            this.vrfVKeyState &&
            this.kesSKeyState &&
            this.kesVKeyState
          ) {
            return true;
          } else {
            this.toastError({
              title: "Error",
              message: "You must fill out all fields."
            });
            return false;
          }
        } else {
          // relay node save!
          this.createNode(this.formNode);
          this.$emit("hideAddNodeWizard");
        }
      } else if (currentPage === 2) {
        if (
          this.ownerStakingSKeyState &&
          this.ownerStakingVKeyState &&
          this.poolPledgeState &&
          this.poolCostState &&
          this.poolMarginState
        ) {
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields."
          });
          return false;
        }
      } else if (currentPage === 3) {
        // core node save!
        this.createNode(this.formNode);
        this.$emit("hideAddNodeWizard");
      }

      // console.log("next clicked", currentPage);
      return true; //return false if you want to prevent moving to next page
    },
    backClicked(/*currentPage*/) {
      // console.log("back clicked", currentPage);
      return true; //return false if you want to prevent moving to previous page
    }
  },
  mounted() {
    this.requestHosts();
    this.requestFileOptions();
  }
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
/* #007bff */
</style>