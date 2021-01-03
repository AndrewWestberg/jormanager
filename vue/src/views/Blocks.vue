<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        @click="$bvModal.show('modal-leader-logs')"
        v-b-tooltip.hover.bottom="'Calculate Leader Logs.'"
      >
        <font-awesome-icon :icon="['fas', 'clipboard-list']" />&nbsp; Leader
        Logs
      </b-button>
    </div>
    <hr />

    <b-container>
      <b-row>
        <b-col cols="6">
          <b-form-group label="Epoch" label-cols="2" label-align="center">
            <b-form-select
              v-model="selectedEpoch"
              :options="epochSelectOptions"
            >
              <template v-slot:first>
                <b-form-select-option :value="null"
                  >-- Latest --</b-form-select-option
                >
              </template>
            </b-form-select>
          </b-form-group>
        </b-col>
        <b-col cols="6">
          <b-form-group label="Pool" label-cols="2" label-align="center">
            <b-form-select
              v-model="selectedPool"
              :options="coreNodeSelectOptions"
            >
              <template v-slot:first>
                <b-form-select-option :value="null"
                  >-- All --</b-form-select-option
                >
              </template>
            </b-form-select>
          </b-form-group>
        </b-col>
      </b-row>
      <b-table
        bordered
        striped
        head-variant="light"
        :items="blocks"
        :fields="fields"
        filter="true"
        :filter-function="filterBlocks"
        v-show="blocks.length > 0"
        @filtered="onFiltered"
      >
        <template v-slot:cell(num)="data">{{
          totalRows ? totalRows - data.index : "---"
        }}</template>
        <template v-slot:cell(status)="data">
          <font-awesome-icon
            :icon="blockIcon(data.value)"
            :class="blockClass(data.value)"
            v-b-tooltip.hover.right="blockTooltip(data.value)"
          />
        </template>
        <template v-slot:cell(hash)="data">
          <a
            v-show="data.value != ''"
            :href="
              'https://explorer.cardano.org/en/block.html?id=' + data.value
            "
            target="_explorer"
            >{{ data.value.substring(0, 6) }}...</a
          >
        </template>
      </b-table>
    </b-container>
    <b-modal
      id="modal-leader-logs"
      title="Leader Logs Request"
      no-close-on-backdrop
      @ok="handleLeaderLogs"
    >
      <b-form-group label="Request Type">
        <b-form-radio-group v-model="formLeaderLogs.requestType">
          <b-form-radio value="currentEpoch"
            ><font-awesome-icon
              :icon="['fas', 'clipboard-list']"
            />&nbsp;Current Epoch</b-form-radio
          >
          <b-form-radio value="futureEpoch"
            ><font-awesome-icon :icon="['fas', 'hat-wizard']" />&nbsp;Future
            Epoch</b-form-radio
          >
        </b-form-radio-group>
      </b-form-group>
    </b-modal>
  </div>
</template>

<script>
import { mapGetters, mapState, mapActions } from "vuex";
import _ from "lodash";

export default {
  data() {
    return {
      fields: [
        { key: "num" },
        { key: "status", label: "" },
        { key: "at", label: "Timestamp" },
        { key: "epoch" },
        { key: "slotInEpoch", label: "Slot" },
        { key: "slot", label: "Full Slot" },
        { key: "pool" },
        {
          key: "hash",
          formatter: (value) => {
            return value.replace(/"/g, "");
          },
        },
      ],
      selectedEpoch: null,
      selectedPool: null,
      totalRows: -1,
      formLeaderLogs: {
        spendingPassword: null,
        requestType: "currentEpoch",
      },
    };
  },
  methods: {
    ...mapActions(["requestBlocks", "requestNodes", "requestLeaderLogs"]),
    filterBlocks(block) {
      let selectedEpoch =
        this.selectedEpoch == null ? this.epoch : this.selectedEpoch;
      if (
        (this.selectedPool == null || block.pool === this.selectedPool) &&
        (selectedEpoch == null || block.epoch === selectedEpoch)
      ) {
        return true;
      }
      return false;
    },
    onFiltered(filteredItems, length) {
      console.log(
        "onFiltered: filteredItems.length = " +
          filteredItems.length +
          ", length = " +
          length
      );
      this.totalRows = length;
    },
    handleLeaderLogs() {
      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.formLeaderLogs.spendingPassword = spendingPassword;
          this.requestLeaderLogs(this.formLeaderLogs);
          this.formLeaderLogs.spendingPassword = null;
        }
      );
    },
    blockIcon(value) {
      switch (value) {
        case "pending":
          return ["fas", "clock"];
        case "forged":
          return ["fas", "hammer"];
        case "orphaned":
          return ["fas", "ghost"];
        case "completed":
          return ["fas", "cube"];
        case "missed":
        default:
          return ["fas", "dumpster-fire"];
      }
    },
    blockClass(value) {
      switch (value) {
        case "pending":
          return "text-secondary";
        case "forged":
          return "text-success";
        case "orphaned":
          return "text-warning";
        case "completed":
          return "text-primary";
        case "missed":
        default:
          return "text-danger";
      }
    },
    blockTooltip(value) {
      return { title: _.startCase(value), variant: this.blockVariant(value) };
    },
    blockVariant(value) {
      switch (value) {
        case "pending":
          return "secondary";
        case "forged":
          return "success";
        case "orphaned":
          return "warning";
        case "completed":
          return "primary";
        case "missed":
        default:
          return "danger";
      }
    },
  },
  computed: {
    ...mapGetters(["coreNodeSelectOptions", "epochSelectOptions"]),
    ...mapState(["blocks", "epoch"]),
  },
  mounted() {
    this.requestBlocks();
    this.requestNodes();
  },
};
</script>