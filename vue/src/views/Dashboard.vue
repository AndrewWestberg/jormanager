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
  </div>
</template>

<script>
import { mapState } from "vuex";
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
  },
  components: {
    NodeChart,
  },
};
</script>