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
          <template v-slot:cell(paymentAddr)="data">
            {{data.value.substring(0,10)}}...
            <font-awesome-icon
              :icon="['fas','copy']"
              class="text-secondary"
              v-b-tooltip.hover.v-secondary.right="'Copy to clipboard'"
              @click="copyToClipboard(data.value)"
            />
          </template>
          <template v-slot:cell(paymentAddrLovelace)="data">
            <div class="text-right">
              {{data.value / 1000000 | currency('₳', 6)}}
              <font-awesome-icon
                :icon="['fas','hand-holding-usd']"
                class="text-success"
                v-if="data.item.type != 'address' && data.value > 0"
                v-b-tooltip.hover.v-success.bottom="'Send Ada'"
                @click="$root.$emit('send-ada', walletItems[data.index])"
              />
            </div>
          </template>
          <template v-slot:cell(stakingAddr)="data">
            <div v-if="data.value.length>0">{{data.value.substring(0,10)}}...</div>
            <div v-else class="text-center">---</div>
          </template>
          <template v-slot:cell(stakingAddrLovelace)="data">
            <div class="text-right" v-if="data.item.type==='stake'">
              {{data.value / 1000000 | currency('₳', 6)}}
              <font-awesome-icon
                :icon="['fas','cash-register']"
                class="text-success"
                v-if="data.value > 0"
                v-b-tooltip.hover.v-success.bottom="'Claim Rewards'"
              />
            </div>
            <div class="text-center" v-else>---</div>
          </template>
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
    <SendAdaModal />
  </div>
</template>

<script>
import { mapState, mapActions, mapMutations } from "vuex";
import AddWalletEntryWizard from "@/components/AddWalletEntryWizard";
import SendAdaModal from "@/components/SendAdaModal";

export default {
  name: "Wallet",
  components: {
    AddWalletEntryWizard,
    SendAdaModal
  },
  data() {
    return {
      fields: [
        { key: "name", label: "Name", sortable: true },
        { key: "type", label: "Item Type", sortable: true },
        { key: "paymentAddr", label: "Payment Address" },
        { key: "paymentAddrLovelace", label: "Balance", class: "text-right" },
        { key: "stakingAddr", label: "Reward Address" },
        { key: "stakingAddrLovelace", label: "Rewards" },
        { key: "edit", label: "" }
      ],
      showAddWalletEntryWizard: false,
      sendAdaFrom: null
    };
  },
  computed: {
    ...mapState(["walletItems"])
  },
  methods: {
    ...mapActions(["fetchWalletItems", "deleteWalletItem"]),
    ...mapMutations(["toastInfo", "toastError"]),
    deleteItem(walletItem) {
      this.$bvModal.msgBoxConfirm("Are you sure?").then(value => {
        if (value) {
          this.deleteWalletItem(walletItem);
        }
      });
    },
    copyToClipboard(value) {
      this.$copyText(value).then(
        () => {
          this.toastInfo({
            title: "Payment Address",
            message: "Copied to clipboard"
          });
        },
        () => {
          this.toastError({
            title: "Payment Address",
            message: "Copy to clipboard failed."
          });
        }
      );
    }
  },
  mounted() {
    this.fetchWalletItems();
  }
};
</script>

<style scoped>
.fa-copy:hover,
.fa-trash-alt:hover,
.fa-cash-register:hover,
.fa-hand-holding-usd:hover,
span.text-success:hover {
  cursor: pointer;
}
</style>