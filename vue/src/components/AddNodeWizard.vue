<template>
  <div id="add_node">
    <h2>Add Node</h2>
    <vue-good-wizard :steps="steps" :onNext="nextClicked" :onBack="backClicked">
      <div slot="page1">
        <h4>Node Basics</h4>
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
          <b-form-select id="genesis-select" v-model="formNode.genesis" :state="genesisState">
            <b-form-select-option :value="null">Please select an option</b-form-select-option>
            <b-form-select-option value="htn">Haskell Testnet</b-form-select-option>
            <b-form-select-option value="mainnet">Mainnet</b-form-select-option>
          </b-form-select>
        </b-form-group>
      </div>
      <div slot="page2">
        <h4>Core Node Keys</h4>
        <p>This is step 2</p>
      </div>
      <div slot="page3">
        <h4>Owners &amp; Rewards</h4>
        <p>This is step 3</p>
      </div>
      <div slot="page4">
        <h4>Confirmation</h4>
        <p>This is step 4</p>
      </div>
    </vue-good-wizard>
  </div>
</template>

<script>
import { GoodWizard } from "vue-good-wizard";
import { mapMutations } from "vuex";

export default {
  name: "AddNodeWizard",
  components: {
    "vue-good-wizard": GoodWizard
  },
  data() {
    return {
      formNode: {
        name: "",
        isDefault: false,
        type: null,
        listen: "",
        port: "",
        genesis: null
      }
    };
  },
  computed: {
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
            label: "Owners & Rewards",
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
    nameState() {
      return this.formNode.name.length > 2;
    },
    typeState() {
      return this.formNode.type != null;
    },
    listenState() {
      // matches an ip address
      return this.formNode.listen.match(/(\d{1,3}\.){3}\d{1,3}/) != null;
    },
    portState() {
      return this.formNode.port > 0;
    },
    genesisState() {
      return this.formNode.genesis != null;
    }
  },
  methods: {
    ...mapMutations(["toastError"]),
    nextClicked(currentPage) {
      if (currentPage === 0) {
        // validate form
        if (
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
      }
      console.log("next clicked", currentPage);
      return true; //return false if you want to prevent moving to next page
    },
    backClicked(currentPage) {
      console.log("back clicked", currentPage);
      return true; //return false if you want to prevent moving to previous page
    }
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