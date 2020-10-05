<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        @click="$bvModal.show('modal-leader-logs')"
        v-b-tooltip.hover.bottom="'Calculate Leader Logs.'"
      >
        Leader Logs
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
      <b-form-group label="Epoch Nonce" label-cols-md="2">
        <b-form-input v-model="formLeaderLogs.epochNonce"></b-form-input>
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
        epochNonce: null,
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