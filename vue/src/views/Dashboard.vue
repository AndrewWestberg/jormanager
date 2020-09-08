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
          <b-card-text>
            <br />
            <br />
            <br />
            <h1 v-if="epochTimeRemainingSecs >= 172800" class="text-success">{{epochTimeRemaining}}</h1>
            <h1 v-if="epochTimeRemainingSecs < 172800" class="text-warning">{{epochTimeRemaining}}</h1>
            <h1 v-if="epochTimeRemainingSecs < 86400" class="text-danger">{{epochTimeRemaining}}</h1>
          </b-card-text>
        </b-card>
        <NodeChart title="Block Height" :series="blockHeightSeries" :colors="nodeColors" />
      </b-card-group>
      <br />
      <b-card-group deck>
        <NodeChart title="Connected Peers" :series="peersSeries" :colors="nodeColors" :min="0" />
        <NodeChart title="KES Periods Remaining" :series="remainingKESSeries" :colors="nodeColors" />
      </b-card-group>
    </b-container>
  </div>
</template>

<script>
import { mapState, mapGetters } from "vuex";
import NodeChart from "@/components/NodeChart";

export default {
  data() {
    return {};
  },
  computed: {
    ...mapState([
      "peersSeries",
      "blockHeightSeries",
      "remainingKESSeries",
      "nodeColors",
    ]),
    ...mapGetters(["epochTimeRemaining", "epochTimeRemainingSecs"]),
  },
  components: {
    NodeChart,
  },
};
</script>