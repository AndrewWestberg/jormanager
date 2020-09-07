<template>
  <div>
    <b-container>
      <b-form-group label="Pool">
        <b-form-radio-group
          id="pool-radio-group"
          v-model="selectedPool"
          :options="poolOptions"
          name="radio-options"
        ></b-form-radio-group>
      </b-form-group>
      <b-table
        bordered
        striped
        head-variant="light"
        :items="blocks"
        :fields="fields"
        filter="true"
        :filter-function="filterBlocks"
        v-if="blocks.length > 0"
      >
        <template v-slot:cell(hash)="data">
          <a
            :href="'https://explorer.cardano.org/en/block.html?id=' + data.value"
            target="_explorer"
          >{{data.value.substring(0,6)}}...</a>
        </template>
      </b-table>
    </b-container>
  </div>
</template>

<script>
import { mapGetters, mapState, mapActions } from "vuex";

export default {
  data() {
    return {
      fields: [
        { key: "at", label: "Timestamp" },
        { key: "epoch", sortable: true },
        { key: "slotInEpoch", label: "Slot" },
        { key: "pool", sortable: true },
        {
          key: "hash",
          formatter: (value) => {
            return value.replace(/"/g, "");
          },
        },
      ],
      selectedPool: null,
      /* FIXME: temporary hardcoding for dev purposes */
      poolOptions: [
        { text: "All", value: null },
        { text: "BCSH", value: "bcsh" },
        { text: "BCSH0", value: "bcsh0" },
        { text: "BCSH1", value: "bcsh1" },
        { text: "BCSH2", value: "bcsh2" },
      ],
    };
  },
  methods: {
    ...mapActions(["requestBlocks"]),
    filterBlocks(block) {
      if (this.selectedPool == null || block.pool === this.selectedPool) {
        return true;
      }
      return false;
    },
  },
  computed: {
    ...mapGetters(["blocksCount"]),
    ...mapState(["blocks"]),
  },
  mounted() {
    this.requestBlocks();
  },
};
</script>