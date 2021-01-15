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
      <b-row>
        <b-col cols="2"> <strong>Total:</strong> {{ totalRows }}</b-col>
        <b-col cols="2" class="text-secondary">
          <font-awesome-icon :icon="['fas', 'clock']" />
          <strong> Pending:</strong> {{ pendingRows }}</b-col
        >
        <b-col cols="2" class="text-primary">
          <font-awesome-icon :icon="['fas', 'cube']" />
          <strong> Completed:</strong> {{ completedRows }}</b-col
        >
        <b-col cols="2" class="text-success">
          <font-awesome-icon :icon="['fas', 'hammer']" />
          <strong> Forged:</strong> {{ forgedRows }}</b-col
        >
        <b-col cols="2" class="text-warning">
          <font-awesome-icon :icon="['fas', 'ghost']" />
          <strong> Orphaned:</strong> {{ orphanedRows }}</b-col
        >
        <b-col cols="2" class="text-danger">
          <font-awesome-icon :icon="['fas', 'dumpster-fire']" />
          <strong> Missed:</strong> {{ missedRows }}</b-col
        >
      </b-row>
      <hr />
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
      totalRows: 0,
      pendingRows: 0,
      forgedRows: 0,
      orphanedRows: 0,
      completedRows: 0,
      missedRows: 0,
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
      this.totalRows = length;
      let pendingRows = 0;
      let forgedRows = 0;
      let orphanedRows = 0;
      let completedRows = 0;
      let missedRows = 0;
      for (let i = 0; i < filteredItems.length; i++) {
        const element = filteredItems[i];
        switch (element.status) {
          case "pending":
            pendingRows++;
            break;
          case "forged":
            forgedRows++;
            break;
          case "orphaned":
            orphanedRows++;
            break;
          case "completed":
            completedRows++;
            break;
          case "missed":
          default:
            missedRows++;
            break;
        }
      }
      this.pendingRows = pendingRows;
      this.forgedRows = forgedRows;
      this.orphanedRows = orphanedRows;
      this.completedRows = completedRows;
      this.missedRows = missedRows;
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