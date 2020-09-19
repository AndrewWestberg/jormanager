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
                v-b-tooltip.hover.left="'Update Color'"
                @click="updateColor(data.item.id, data.item.color)"
              />
              <font-awesome-icon
                :style="{color: data.item.color}"
                :icon="['fas','check-circle']"
                v-if="data.item.isDefault"
                v-b-tooltip.hover.left="'Update Color'"
                @click="updateColor(data.item.id, data.item.color)"
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
          <template v-slot:cell(kesExpireTimeSec)="data">
            <span v-if="data.value > -1">
              {{data.value | moment("YYYY-MM-DD h:mma UTCZ")}}&nbsp;({{data.value | moment("from")}})&nbsp;
              <font-awesome-icon
                :icon="['fas','key']"
                class="text-warning"
                v-b-tooltip.hover.v-warning.right="'Rotate KES Key'"
                @click="rotateKesKey(displayNodes[data.index].name)"
              />
            </span>
          </template>
          <template v-slot:cell(edit)="data">
            <font-awesome-icon
              :icon="['fas','power-off']"
              class="text-danger"
              v-b-tooltip.hover.v-danger.right="'Restart Node'"
              @click="restartNode(displayNodes[data.index].name)"
            />&nbsp;
            <font-awesome-icon
              v-if="data.item.type === 'core'"
              :icon="['fas','percent']"
              class="text-warning"
              v-b-tooltip.hover.v-warning.right="'Edit Pool Config'"
              @click="editPoolConfig(data.item.id)"
            />
          </template>
        </b-table>
      </div>
    </div>
    <AddNodeWizard v-if="showAddNodeWizard" @hideAddNodeWizard="showAddNodeWizard = false" />
    <b-modal id="modal-edit-color" title="Edit Color" no-close-on-backdrop @ok="handleSaveColor">
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
            :options="registrationFeesSelectOptions($options.filters.currency)"
            :state="registrationFeesAccountState"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
            </template>
          </b-form-select>
          <b-form-invalid-feedback
            id="registration-fees-account-live-feedback"
          >Account must hold enough to pay pool registration and delegation fees.</b-form-invalid-feedback>
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
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
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
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
            </template>
          </b-form-select>
          <b-form-invalid-feedback
            id="rewards-staking-account-live-feedback"
          >May be the same as owner account.</b-form-invalid-feedback>
        </b-form-group>
      </b-form-group>
      <b-form-group label="Pledge &amp; Fees">
        <b-form-group label="Pledge" label-for="pledge-input" label-cols-md="1" label-align="right">
          <b-form-input
            id="pledge-input"
            v-model="editPoolConfigForm.poolPledge"
            placeholder="e.g. ₳250,000.000000"
            :state="poolPledgeState"
            trim
            v-currency
          />
        </b-form-group>
        <b-form-group label="Cost" label-for="cost-input" label-cols-md="1" label-align="right">
          <b-form-input
            id="cost-input"
            v-model="editPoolConfigForm.poolCost"
            :state="poolCostState"
            placeholder="e.g. ₳340.000000"
            trim
            v-currency
          />
        </b-form-group>
        <b-form-group label="Margin" label-for="margin-input" label-cols-md="1" label-align="right">
          <b-form-input
            id="margin-input"
            v-model="editPoolConfigForm.poolMargin"
            :state="poolMarginState"
            placeholder="e.g. 0.06"
            type="range"
            min="0.00"
            max="1.00"
            step="0.0025"
            trim
          />
          <p class="text-center">{{(editPoolConfigForm.poolMargin * 100).toFixed(2)}}%</p>
        </b-form-group>
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
    };
  },
  methods: {
    ...mapActions([
      "requestHosts",
      "requestNodes",
      "requestFileOptions",
      "restartNodeByName",
      "createNode",
      "rotateKesByName",
      "updateNodeColor",
    ]),
    ...mapMutations(["toastError"]),
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
              this.editPoolConfigForm.poolPledge = this.$root.$parseCurrency(
                this.editPoolConfigForm.poolPledge
              );
            }
            if (isNaN(this.editPoolConfigForm.poolCost)) {
              this.editPoolConfigForm.poolCost = this.$root.$parseCurrency(
                this.editPoolConfigForm.poolCost
              );
            }
            // this.updatePoolConfig(this.editPoolConfigForm);
            alert(JSON.stringify(this.editPoolConfigForm));
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
  },
  computed: {
    ...mapGetters([
      "displayNodes",
      "registrationFeesSelectOptions",
      "stakingSelectOptions",
      "rewardsSelectOptions",
    ]),
    ...mapState(["nodes"]),
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
        return (
          this.$root.$parseCurrency(this.editPoolConfigForm.poolPledge) > 0
        );
      } else {
        return this.editPoolConfigForm.poolPledge > 0;
      }
    },
    poolCostState() {
      if (this.editPoolConfigForm.poolCost == null) {
        return false;
      }
      if (isNaN(this.editPoolConfigForm.poolCost)) {
        return this.$root.$parseCurrency(this.editPoolConfigForm.poolCost) > 0;
      } else {
        return this.editPoolConfigForm.poolCost > 0;
      }
    },
    poolMarginState() {
      return (
        this.editPoolConfigForm.poolMargin >= 0.01 &&
        this.editPoolConfigForm.poolMargin <= 1.0
      );
    },
  },
  mounted() {
    this.requestHosts();
    this.requestNodes();
    this.requestFileOptions();
  },
};
</script>

<style scoped>
.fa-percent:hover,
.fa-circle:hover,
.fa-check-circle:hover,
.fa-power-off:hover,
.fa-key:hover {
  cursor: pointer;
}
</style>