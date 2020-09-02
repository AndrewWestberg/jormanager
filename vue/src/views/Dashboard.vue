<template>
  <div>
    <b-container>
      <b-card-group deck>
        <NodeChart title="Connected Peers" :series="peersSeries" :colors="nodeColors" :min="0" />
        <NodeChart title="Block Height" :series="blockHeightSeries" :colors="nodeColors" />
      </b-card-group>
      <br />
      <b-row>
        <b-col cols="6" offset="3">
          <NodeChart
            title="KES Periods Remaining"
            :series="remainingKESSeries"
            :colors="nodeColors"
          />
        </b-col>
      </b-row>
    </b-container>
    <hr />
    <b-container>
      <b-table
        bordered
        striped
        head-variant="light"
        :items="blocks"
        :fields="fields"
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
import NodeChart from "@/components/NodeChart";

export default {
  data() {
    return {
      fields: [
        { key: "at", label: "Timestamp" },
        { key: "pool", sortable: true },
        { key: "host", sortable: true },
        { key: "slot", sortable: true },
        {
          key: "hash",
          formatter: (value) => {
            return value.replace(/"/g, "");
          },
        },
      ],
    };
  },
  methods: {
    ...mapActions(["requestBlocks"]),
  },
  computed: {
    ...mapGetters(["blocksCount"]),
    ...mapState([
      "blocks",
      "peersSeries",
      "blockHeightSeries",
      "remainingKESSeries",
      "nodeColors",
    ]),
  },
  components: {
    NodeChart,
  },
  mounted() {
    this.requestBlocks();
  },
};
</script>