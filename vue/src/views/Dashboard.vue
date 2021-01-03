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
            <h1 :class="epochRemainingClass()">
              <div>Epoch: {{ epoch }}</div>
              <div>{{ epochTimeRemaining() }}</div>
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
import { mapState } from "vuex";
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
      "epoch",
      "slot",
    ]),
  },
  components: {
    NodeChart,
    StackedBarChart,
  },
  methods: {
    epochRemainingClass() {
      let epochTimeRemainingSecs = 432000 - this.slot;
      if (epochTimeRemainingSecs > 172800) {
        return "text-success";
      }
      if (epochTimeRemainingSecs > 86400) {
        return "text-warning";
      }
      return "text-danger";
    },
    epochTimeRemaining() {
      let time = 432000 - this.slot;
      // console.log("time: " + time);
      let days = Math.floor(time / 60 / 60 / 24);
      // console.log("days: " + days);
      let hours = Math.floor(time / 60 / 60) % 24;
      // console.log("hours: " + hours);
      let minutes = Math.floor(time / 60) % 60;
      // console.log("minutes: " + minutes);
      let seconds = Math.floor(time % 60);
      // console.log("seconds: " + seconds);

      return days + "d " + hours + "h " + minutes + "m " + seconds + "s ";
    },
  },
};
</script>