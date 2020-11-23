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
        v-if="blocks.length > 0"
        @filtered="onFiltered"
      >
        <template v-slot:cell(num)="data">{{
          totalRows ? totalRows - data.index : "---"
        }}</template>
        <template v-slot:cell(status)="data">
          <font-awesome-icon
            v-if="data.value === 'pending'"
            :icon="['fas', 'clock']"
            class="text-secondary"
            v-b-tooltip.hover.v-secondary.right="'Pending'"
          />
          <font-awesome-icon
            v-if="data.value === 'missed'"
            :icon="['fas', 'dumpster-fire']"
            class="text-danger"
            v-b-tooltip.hover.v-danger.right="'Missed'"
          />
          <font-awesome-icon
            v-if="data.value === 'completed'"
            :icon="['fas', 'cube']"
            class="text-primary"
            v-b-tooltip.hover.v-primary.right="'Completed'"
          />
          <font-awesome-icon
            v-if="data.value === 'forged'"
            :icon="['fas', 'hammer']"
            class="text-success"
            v-b-tooltip.hover.v-success.right="'Forged!'"
          />
          <font-awesome-icon
            v-if="data.value === 'orphaned'"
            :icon="['fas', 'ghost']"
            class="text-warning"
            v-b-tooltip.hover.v-warning.right="'Orphaned'"
          />
        </template>
        <template v-slot:cell(hash)="data">
          <a
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
    onFiltered(filteredItems) {
      this.totalRows = filteredItems.length;
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