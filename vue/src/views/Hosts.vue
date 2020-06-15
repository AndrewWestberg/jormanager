<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        @click="$root.$emit('add-host')"
        v-b-tooltip.hover.bottom="'Add a connection to a new remote or local server.'"
      >
        <b-icon-plus />&nbsp;Host
      </b-button>
    </div>
    <hr />
    <div>
      <b-table bordered striped head-variant="light" :items="hosts" :fields="fields">
        <template v-slot:cell(sshPort)="data">
          <div v-if="data.item.type==='remote'">{{data.value}}</div>
          <div v-if="data.item.type!=='remote'">---</div>
        </template>
        <!-- A custom formatted column -->
        <template v-slot:cell(edit)="data">
          <font-awesome-icon
            :icon="['fas','edit']"
            class="text-warning"
            v-b-tooltip.hover.v-warning.right="'Edit this Host'"
            @click="$root.$emit('edit-host', hosts[data.index])"
          />
        </template>
      </b-table>
    </div>

    <AddEditHostModal />
  </div>
</template>

<script>
import { mapState, mapActions } from "vuex";
import AddEditHostModal from "@/components/AddEditHostModal";

export default {
  name: "Hosts",
  components: {
    AddEditHostModal
  },
  data() {
    return {
      fields: [
        { key: "id", sortable: true },
        { key: "type", sortable: true },
        { key: "hostname", sortable: true },
        { key: "sshUser", label: "User" },
        { key: "sshPort" },
        { key: "edit", label: "" }
      ]
    };
  },
  methods: {
    ...mapActions(["requestHosts"])
  },
  computed: {
    ...mapState(["hosts"])
  },
  mounted() {
    this.requestHosts();
  }
};
</script>

<style scoped>
.fa-edit:hover {
  cursor: pointer;
}
</style>