<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        @click="$root.$emit('add-wallet-entry')"
        v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
      >
        <b-icon-plus />&nbsp;Create Entry
      </b-button>
    </div>
    <hr />
    <hr />
    <b-container>
      <b-table bordered striped head-variant="light" :items="walletItems" :fields="fields">
        <template v-slot:cell(paymentAddr)="data">{{data.value.substring(0,10)}}...</template>
      </b-table>
    </b-container>
  </div>
</template>

<script>
import { mapState } from "vuex";
export default {
  data() {
    return {
      fields: [
        { key: "type", label: "Item Type", sortable: true },
        { key: "paymentAddr", label: "Payment Address" },
        { key: "paymentAddrLovelace", label: "Lovelace" },
        { key: "stakingAddr", label: "Reward Address" },
        { key: "stakingAddrLovelace", label: "Rewards" },
        { key: "edit", label: "" }
      ]
    };
  },
  computed: {
    ...mapState(["walletItems"])
  }
};
</script>