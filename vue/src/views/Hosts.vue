<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        v-b-tooltip.hover.bottom="'Add a connection to a new remote or local server.'"
      >
        <b-icon-plus />&nbsp;Host
      </b-button>
    </div>
    <hr />
    <div>
      <b-table bordered striped head-variant="light" :items="hosts" :fields="fields">
        <!-- A custom formatted column -->
        <template v-slot:cell(edit)="data">
          <b-icon-pencil-square
            variant="warning"
            v-b-tooltip.hover.v-warning.right="'Edit this Host'"
            @click="editHost(data.index)"
          />
        </template>
      </b-table>
    </div>
  </div>
</template>

<script>
import { mapState, mapGetters, mapActions } from "vuex";

export default {
  name: "Hosts",
  data() {
    return {
      fields: [
        { key: "id", sortable: true },
        { key: "type", sortable: true },
        { key: "hostname", sortable: true },
        { key: "sshUser" },
        { key: "sshPort" },
        { key: "edit", label: "" }
      ]
    };
  },
  methods: {
    ...mapActions(["requestHosts"]),
    editHost(index) {
      alert("Editing index " + index);
    }
  },
  computed: {
    ...mapState(["hosts"]),
    ...mapGetters(["hostsCount"])
  },
  mounted() {
    this.requestHosts();
  }
};
</script>

<style scoped>
.bi-pencil-square:hover {
  cursor: pointer;
}
</style>