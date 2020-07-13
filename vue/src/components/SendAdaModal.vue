<template>
  <div>
    <b-modal
      id="modal-send-ada"
      :title="modalTitle()"
      size="lg"
      scrollable
      no-close-on-backdrop
      ok-title="Send"
      @ok="handleValidateAndSend"
    >
      <b-form ref="sendAdaForm" @submit.stop.prevent="handleValidateAndSend">
        <b-card
          border-variant="secondary"
          v-for="(toAccount, index) in formSendAda.toAccounts"
          :key="index"
        >
          <b-form-group label="Account">
            <b-form-select
              v-model="toAccount.account"
              :state="accountState(toAccount.account)"
              :options="paymentSelectOptions($options.filters.currency)"
            >
              <template v-slot:first>
                <b-form-select-option :value="null" disabled>-- Please select an option --</b-form-select-option>
              </template>
            </b-form-select>
          </b-form-group>
          <b-form-group label="Entry Type" label-for="type-radio">
            <b-form-radio-group
              id="type-radio"
              v-model="toAccount.type"
              :state="typeState(toAccount.type)"
            >
              <b-form-radio value="amount">
                <font-awesome-icon :icon="['fas', 'dice-d20']" />&nbsp;Amount
              </b-form-radio>
              <b-form-radio value="percent">
                <font-awesome-icon :icon="['fas', 'dice-d20']" />&nbsp;Percent
              </b-form-radio>
            </b-form-radio-group>
          </b-form-group>
          <b-form-group label="Amount" label-for="amount-input" v-if="toAccount.type === 'amount'">
            <b-form-input
              id="amount-input"
              :state="amountState(toAccount.amount)"
              aria-describedby="amount-input-live-feedback"
              v-model="toAccount.amount"
              placeholder="e.g. ₳1,230.987000"
              trim
              v-currency
            />
            <b-form-invalid-feedback
              id="amount-input-live-feedback"
            >Enter a non-zero amount up to {{fromWalletItem.paymentAddrLovelace / 1000000 | currency('₳', 6)}}</b-form-invalid-feedback>
          </b-form-group>
          <b-form-group
            label="Percent"
            label-for="percent-input"
            v-if="toAccount.type === 'percent'"
          >
            <b-form-input
              id="percent-input"
              v-model="toAccount.percent"
              :state="percentState(toAccount.percent)"
              placeholder="e.g. 2"
              type="range"
              min="0"
              max="100"
              step="0.5"
              trim
            />
            <p class="text-center">{{toAccount.percent}} %</p>
          </b-form-group>
        </b-card>
      </b-form>
      <b-button
        variant="primary"
        @click="addPaymentEntry()"
        v-b-tooltip.hover.right="'Add a new payment entry.'"
      >
        <b-icon-plus />&nbsp;Add Entry
      </b-button>
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
        toAccounts: [
          {
            account: null,
            type: null,
            amount: null,
            percent: 0
          }
        ]
      }
    };
  },
  computed: {
    ...mapState(["walletItems"]),
    ...mapGetters(["paymentSelectOptions"])
  },
  methods: {
    ...mapActions(["calculateSendAdaFees"]),
    accountState(account) {
      return account != null;
    },
    typeState(type) {
      return type != null;
    },
    amountState(amount) {
      return amount != null;
      //   return (
      //     this.formSendAda.amount != null &&
      //     this.$root.$parseCurrency(this.formSendAda.amount) > 0 &&
      //     this.$root.$parseCurrency(this.formSendAda.amount) <=
      //       this.fromWalletItem.paymentAddrLovelace
      //   );
    },
    percentState(percent) {
      return percent != null;
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
    },
    addPaymentEntry() {
      this.formSendAda.toAccounts.push({
        account: null,
        type: null,
        amount: null,
        percent: 0
      });
    },
    clearFormSendAda() {
      this.fromWalletItem = { name: null, paymentAddrLovelace: null };
      this.formSendAda = null;
      this.formSendAda = {
        fromId: null,
        amount: null,
        toAccounts: [
          {
            account: null,
            type: null,
            amount: null,
            percent: 0
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