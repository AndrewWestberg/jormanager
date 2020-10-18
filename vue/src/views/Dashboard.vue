<template>
  <div>
    <b-container>
      <b-card-group deck>
        <b-card
          no-body
          border-variant="secondary"
          header="Epoch Time Remaining"
          header-border-variant="secondary"
          align="center"
        >
          <b-card-text style="padding: 25% 0">
            <h1 v-if="epochTimeRemainingSecs > 172800" class="text-success">
              {{ epochTimeRemaining }}
            </h1>
            <h1
              v-if="
                epochTimeRemainingSecs <= 172800 &&
                epochTimeRemainingSecs >= 86400
              "
              class="text-warning"
            >
              {{ epochTimeRemaining }}
            </h1>
            <h1 v-if="epochTimeRemainingSecs < 86400" class="text-danger">
              {{ epochTimeRemaining }}
            </h1>
          </b-card-text>
        </b-card>
        <NodeChart
          title="Block Height"
          :series="blockHeightSeries"
          :colors="nodeColors"
        />
      </b-card-group>
      <br />
      <b-card-group deck>
        <NodeChart
          title="Outgoing Peers"
          :series="peersSeries"
          :colors="nodeColors"
          :min="0"
        />
        <StackedBarChart
          title="KES Days Remaining"
          :series="remainingKESSeries"
          :categories="remainingKESSeriesCategoryLabels"
        />
      </b-card-group>
      <br />
      <b-card-group deck>
        <NodeChart
          title="Incoming Peers"
          :series="incomingPeersSeries"
          :colors="nodeColors"
          :min="0"
        />
        <NodeChart
          title="Transactions Processed"
          :series="txsProcessedSeries"
          :colors="nodeColors"
          :min="0"
        />
      </b-card-group>
    </b-container>
  </div>
</template>

<script>
import { mapState, mapGetters } from "vuex";
import NodeChart from "@/components/NodeChart";
import StackedBarChart from "@/components/StackedBarChart";

export default {
  data() {
    return {};
  },
  computed: {
    ...mapState([
      "peersSeries",
      "incomingPeersSeries",
      "blockHeightSeries",
      "remainingKESSeries",
      "remainingKESSeriesCategoryLabels",
      "nodeColors",
      "txsProcessedSeries",
    ]),
    ...mapGetters(["epochTimeRemaining", "epochTimeRemainingSecs"]),
  },
  components: {
    NodeChart,
    StackedBarChart,
  },
};
</script>