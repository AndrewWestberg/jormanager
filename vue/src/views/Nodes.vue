<template>
  <div>
    <div id="nodes-home" v-if="!showAddNodeWizard">
      <div>
        <b-button
          variant="outline-primary"
          @click="showAddNodeWizard=true"
          v-b-tooltip.hover.bottom="'Add a new cardano-node.'"
        >
          <b-icon-plus />&nbsp;Node
        </b-button>
      </div>
      <hr />
      <div>
        <b-table bordered striped head-variant="light" :items="nodes" :fields="fields">
          <template v-slot:cell(type)="data">
            <div v-if="data.value==='relay'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Relay Node'"
                class="text-danger text-center"
              />
            </div>
            <div v-if="data.value==='core'">
              <font-awesome-icon
                :icon="['fas', 'dice-d20']"
                v-b-tooltip.hover.right="'Core Node'"
                class="text-success"
              />
            </div>
          </template>
          <!-- A custom formatted column -->
          <template v-slot:cell(edit)="data">
            <font-awesome-icon
              :icon="['fas','edit']"
              class="text-warning"
              v-b-tooltip.hover.v-warning.right="'Edit this Node'"
              @click="$root.$emit('edit-node', nodes[data.index])"
            />
          </template>
        </b-table>
      </div>
    </div>
    <AddNodeWizard v-if="showAddNodeWizard" @hideAddNodeWizard="showAddNodeWizard = false" />
  </div>
</template>

<script>
import { mapState } from "vuex";
import AddNodeWizard from "@/components/AddNodeWizard";

export default {
  name: "Nodes",
  components: {
    AddNodeWizard
  },
  data() {
    return {
      fields: [
        { key: "id", sortable: true },
        { key: "name", sortable: true },
        { key: "hostId", sortable: true },
        { key: "type", sortable: true },
        { key: "edit", label: "" }
      ],
      showAddNodeWizard: false
    };
  },
  computed: {
    ...mapState(["nodes"])
  }
};
</script>