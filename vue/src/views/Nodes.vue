<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <b-button
          variant="outline-primary"
          @click="showAddNodeWizard=true"
          v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
        >
          <b-icon-plus />&nbsp;Node
        </b-button>
        <b-button @click="testCreateNode()">Test createNode</b-button>
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
            <div>
              <font-awesome-icon
                :style="{color: data.item.color}"
                :icon="['fas','circle']"
                v-if="!data.item.isDefault"
              />
              <font-awesome-icon
                :style="{color: data.item.color}"
                :icon="['fas','check-circle']"
                v-if="data.item.isDefault"
              />
              &nbsp;{{data.value}}
            </div>
          </template>
          <template v-slot:cell(type)="data">
            <div v-if="data.value==='relay'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Relay Node'"
                class="text-danger text-center"
              />
            </div>
            <div v-if="data.value==='core'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Core Node'"
                class="text-success"
              />
            </div>
          </template>
          <!-- A custom formatted column -->
          <template v-slot:cell(edit)="data">
            <!--
            <font-awesome-icon
              :icon="['fas','edit']"
              class="text-warning"
              v-b-tooltip.hover.v-warning.right="'Edit this Node'"
              @click="$root.$emit('edit-node', displayNodes[data.index])"
            />&nbsp;
            -->
            <font-awesome-icon
              :icon="['fas','power-off']"
              class="text-danger"
              v-b-tooltip.hover.v-danger.right="'Restart Node'"
              @click="restartNode(displayNodes[data.index].name)"
            />
          </template>
        </b-table>
      </div>
    </div>
    <AddNodeWizard v-if="showAddNodeWizard" @hideAddNodeWizard="showAddNodeWizard = false" />
  </div>
</template>

<script>
import { mapActions, mapGetters } from "vuex";
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
        { key: "edit", label: "" },
      ],
      showAddNodeWizard: false,
    };
  },
  methods: {
    ...mapActions([
      "requestHosts",
      "requestNodes",
      "restartNodeByName",
      "createNode",
    ]),
    restartNode(node) {
      this.$bvModal
        .msgBoxConfirm("Restart " + node + ". Are you sure?")
        .then((value) => {
          if (value) {
            this.restartNodeByName(node);
          }
        });
    },
    testCreateNode() {
      //DELETE ME
      let formNode = JSON.parse(`
{
  "color": "#4A412A",
  "host": 23,
  "name": "tickr",
  "isDefault": false,
  "type": "core",
  "processorThreads": "2",
  "listen": "127.0.0.1",
  "port": "6001",
  "genesisByron": 207,
  "genesisShelley": 208,
  "generateColdKeys": true,
  "coldSKey": null,
  "coldVKey": null,
  "coldCounter": null,
  "generateVRFKeys": true,
  "vrfSKey": null,
  "vrfVKey": null,
  "generateKESKeys": true,
  "kesSKey": null,
  "kesVKey": null,
  "registrationFeesAccount": 313,
  "ownerStakingAccount": 421,
  "rewardsStakingAccount": 427,
  "poolPledge": 321000000,
  "poolCost": 340000000,
  "poolMargin": "0.5",
  "relays": [
    {
      "addr": "relay1.bluecheesestakehouse.com",
      "port": "5001"
    },
    {
      "addr": "50.39.169.116",
      "port": "5002"
    }
  ],
  "metadata": {
    "ticker": "TICKR",
    "name": "Flippin Stakes",
    "description": "The best stakepool located in Flippin, Arkansas!",
    "homepage": "https://flippin-stakes.com",
    "extended": {
      "itn": {
        "publicKey": null,
        "privateKey": null
      },
      "info": {
        "icon64": "https://flippin-stakes.com/icon64.png",
        "logo": "https://flippin-stakes.com/logo512.png",
        "location": "United States, North America",
        "social": {
          "twitter": "twitter_flippinstakes",
          "telegram": "telegram_flippinstakes",
          "facebook": "facebook_flippinstakes",
          "youtube": "youtube_flippinstakes",
          "discord": "discord_flippinstakes",
          "github": "github_flippinstakes",
          "twitch": "twitch_flippinstakes"
        },
        "company": {
          "name": "Flippin Stakes, LLC",
          "addr": "123 Backflip Ln.",
          "city": "Flippin, AK",
          "country": "United States",
          "company_id": "12-2393949",
          "vat_id": "J-392039"
        },
        "about": {
          "me": "10-year veteran as a devops engineer",
          "server": "Cloud Hosted at AWS",
          "company": "Founded in 2020 for stakepool operations."
        },
        "rss": "https://flippin-stakes.com/atom.xml"
      },
      "telegramAdminHandle": "telegram_admin_flippinstakes"
    }
  },
  "sudoPassword": ""
}
      `);
      this.createNode(formNode);
    },
  },
  computed: {
    ...mapGetters(["displayNodes"]),
  },
  watch: {
    toastSuccess(toast) {
      if (toast.title === "Node Created") {
        this.requestNodes();
      }
    },
  },
  mounted() {
    this.requestHosts();
    this.requestNodes();
  },
};
</script>

<style scoped>
.fa-power-off:hover {
  cursor: pointer;
}
</style>