<template>
  <div>
    <div>
      <b-card-group deck>
        <NodeChart title="Block Height" :series="blockHeightSeries" :colors="nodeColors" />
      </b-card-group>
    </div>
    <div></div>
    <h1>Total Blocks: {{ blocksCount }}</h1>
  </div>
</template>

<script>
import { mapGetters, mapState } from "vuex";
import NodeChart from "@/components/NodeChart";

export default {
  data() {
    return {};
  },
  methods: {},
  computed: {
    ...mapGetters(["blocksCount"]),
    ...mapState(["blockHeightSeries", "nodeColors"])
  },
  components: {
    NodeChart
  }
};
</script>