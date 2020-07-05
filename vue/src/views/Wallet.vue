<template>
  <div>
    <div v-if="!showAddWalletEntryWizard">
      <div>
        <b-button
          variant="outline-primary"
          @click="showAddWalletEntryWizard=true"
          v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
        >
          <b-icon-plus />&nbsp;Create Entry
        </b-button>
      </div>
      <hr />
      <b-container>
        <b-table
          bordered
          striped
          head-variant="light"
          :items="walletItems"
          :fields="fields"
          v-if="walletItems.length > 0"
        >
          <template v-slot:cell(paymentAddr)="data">{{data.value.substring(0,10)}}...</template>
          <template v-slot:cell(edit)="data">
            <font-awesome-icon
              :icon="['fas','trash-alt']"
              class="text-danger"
              v-b-tooltip.hover.v-danger.right="'Delete this Entry'"
              @click="deleteItem(walletItems[data.index])"
            />
          </template>
        </b-table>
      </b-container>
    </div>
    <AddWalletEntryWizard
      v-if="showAddWalletEntryWizard"
      @hideWalletEntryWizard="showAddWalletEntryWizard = false"
    />
  </div>
</template>

<script>
import { mapState, mapActions } from "vuex";
import AddWalletEntryWizard from "@/components/AddWalletEntryWizard";

export default {
  name: "Wallet",
  components: {
    AddWalletEntryWizard
  },
  data() {
    return {
      fields: [
        { key: "name", label: "Name", sortable: true },
        { key: "type", label: "Item Type", sortable: true },
        { key: "paymentAddr", label: "Payment Address" },
        { key: "paymentAddrLovelace", label: "Lovelace" },
        { key: "stakingAddr", label: "Reward Address" },
        { key: "stakingAddrLovelace", label: "Rewards" },
        { key: "edit", label: "" }
      ],
      showAddWalletEntryWizard: false
    };
  },
  computed: {
    ...mapState(["walletItems"])
  },
  methods: {
    ...mapActions(["fetchWalletItems", "deleteWalletItem"]),
    deleteItem(walletItem) {
      this.$bvModal.msgBoxConfirm("Are you sure?").then(value => {
        if (value) {
          this.deleteWalletItem(walletItem);
        }
      });
    }
  },
  mounted() {
    this.fetchWalletItems();
  }
};
</script>