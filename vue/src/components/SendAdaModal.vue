<template>
  <div>
    <b-modal
      id="modal-send-ada"
      :title="modalTitle"
      size="lg"
      scrollable
      no-close-on-backdrop
      ok-title="Send"
      @ok="handleValidateAndSend"
    >
      <b-form ref="sendAdaForm" @submit.stop.prevent="handleValidateAndSend">
        <b-form-group label="Amount" label-for="amount-input">
          <b-form-input
            id="amount-input"
            :state="amountState"
            aria-describedby="amount-input-live-feedback"
            v-model="formSendAda.amount"
            placeholder="e.g. ₳1,230.987000"
            trim
            v-currency
          />
          <b-form-invalid-feedback
            id="amount-input-live-feedback"
          >Enter a non-zero amount up to {{fromWalletItem.paymentAddrLovelace / 1000000 | currency('₳', 6)}}</b-form-invalid-feedback>
        </b-form-group>
        <b-form-group label="To Account(s)">
          <b-form-select
            v-for="(toAccount,counter) in formSendAda.toAccounts"
            v-bind:key="counter"
            v-model="toAccount.account"
            :state="toAccountState"
            :options="paymentSelectOptions($options.filters.currency)"
          >
            <template v-slot:first>
              <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
            </template>
          </b-form-select>
        </b-form-group>
      </b-form>
    </b-modal>
  </div>
</template>

<script>
import _ from "lodash";
import { mapActions, mapState, mapGetters } from "vuex";

export default {
  name: "SendAdaModal",
  data() {
    return {
      fromWalletItem: { name: null, paymentAddrLovelace: null },
      formSendAda: {
        fromId: null,
        amount: null,
        toAccounts: [
          {
            account: null
          }
        ]
      }
    };
  },
  computed: {
    ...mapState(["walletItems"]),
    ...mapGetters(["paymentSelectOptions"]),
    amountState() {
      return (
        this.formSendAda.amount != null &&
        this.$root.$parseCurrency(this.formSendAda.amount) > 0 &&
        this.$root.$parseCurrency(this.formSendAda.amount) <=
          this.fromWalletItem.paymentAddrLovelace
      );
    },
    toAccountState(value) {
      console.log("toAccountState: " + value);
      return null;
    },
    modalTitle() {
      return (
        "Send Ada (" +
        this.fromWalletItem.name +
        " - " +
        this.$options.filters.currency(
          this.fromWalletItem.paymentAddrLovelace / 1000000,
          "₳",
          6
        ) +
        ")"
      );
    }
  },
  methods: {
    ...mapActions(["calculateSendAdaFees"]),
    clearFormSendAda() {
      this.fromWalletItem = { name: null, paymentAddrLovelace: null };
      this.formSendAda = null;
      this.formSendAda = {
        fromId: null,
        amount: null,
        toAccounts: [
          {
            account: null
          }
        ]
      };
    },
    clickSendAda(walletItem) {
      this.clearFormSendAda();
      this.formSendAda.fromId = walletItem.id;
      this.fromWalletItem = _.cloneDeep(walletItem);
      this.$bvModal.show("modal-send-ada");
    },
    handleValidateAndSend(bvModalEvt) {
      bvModalEvt.preventDefault();
      console.log("Saving...");
    }
  },
  mounted() {
    this.clearFormSendAda();
    this.$root.$on("send-ada", walletItem => {
      // received send-ada message from parent component
      this.clickSendAda(walletItem);
    });
  }
};
</script>