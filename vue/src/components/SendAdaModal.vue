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
        <div v-for="(toAccount, index) in formSendAda.toAccounts" :key="index">
          <b-card border-variant="secondary">
            <b-form-group label="Account" label-cols-md="2">
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
            <b-form-group label="Entry Type" label-cols-md="2">
              <b-form-radio-group v-model="toAccount.type" :state="typeState(toAccount.type)">
                <b-form-radio value="amount">
                  <font-awesome-icon :icon="['fas', 'weight-hanging']" />&nbsp;Amount
                </b-form-radio>
                <b-form-radio value="percent">
                  <font-awesome-icon :icon="['fas', 'balance-scale-right']" />&nbsp;Percent
                </b-form-radio>
              </b-form-radio-group>
            </b-form-group>
            <b-form-group
              label="Amount"
              label-for="amount-input"
              label-cols-md="2"
              v-if="toAccount.type === 'amount'"
            >
              <b-form-input
                id="amount-input"
                :state="amountState(index, toAccount.amount)"
                aria-describedby="amount-input-live-feedback"
                v-model="toAccount.amount"
                placeholder="e.g. ₳1,230.987000"
                trim
                v-currency
              />
              <b-form-invalid-feedback
                id="amount-input-live-feedback"
              >Enter a non-zero amount up to {{calculateMaxLovelace(index) / 1000000 | currency('₳', 6)}}</b-form-invalid-feedback>
            </b-form-group>
            <b-form-group
              label="Percent"
              label-for="percent-input"
              label-cols-md="2"
              v-if="toAccount.type === 'percent'"
            >
              <b-form-input
                id="percent-input"
                v-model="toAccount.percent"
                :state="percentState(index, toAccount.percent)"
                placeholder="e.g. 2"
                type="range"
                min="0"
                max="100"
                step="1"
                trim
              />
              <p class="text-center">{{percentLabel(index, toAccount.percent)}}</p>
            </b-form-group>
          </b-card>
          <hr />
        </div>
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
      remainingLovelace: 1,
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
    ...mapState(["walletItems", "txFee"]),
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
    amountState(index, amount) {
      if (amount != null) {
        let lovelaces = this.$root.$parseCurrency(amount);
        return lovelaces > 0 && lovelaces <= this.calculateMaxLovelace(index);
      }
      return false;
    },
    percentState(index, percent) {
      if (percent != null && percent > 0) {
        let maxLovelace = this.calculateMaxLovelace(index);
        let lovelaces = Math.floor(maxLovelace * (percent / 100.0));
        let spentLovelace = this.calculateSpentLovelace(index);
        return lovelaces <= spentLovelace;
      }
      return false;
    },
    modalTitle() {
      let wasMaxed = this.remainingLovelace == 0;
      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      if (
        (wasMaxed && this.remainingLovelace > 0) ||
        (!wasMaxed && this.remainingLovelace == 0)
      ) {
        // re-calculate fees because we have a change in number of output transactions
        this.calculateSendAdaFees({
          fromAddress: this.fromWalletItem.paymentAddr,
          txOut:
            this.formSendAda.toAccounts.length +
            (this.remainingLovelace == 0 ? 0 : 1)
        });
      }

      return (
        "Send Ada (" +
        this.fromWalletItem.name +
        " - " +
        this.$options.filters.currency(
          this.fromWalletItem.paymentAddrLovelace / 1000000,
          "₳",
          6
        ) +
        "), Fee: " +
        this.$options.filters.currency(this.txFee / 1000000, "₳", 6) +
        ", Remaining: " +
        this.$options.filters.currency(this.remainingLovelace / 1000000, "₳", 6)
      );
    },
    addPaymentEntry() {
      this.formSendAda.toAccounts.push({
        account: null,
        type: null,
        amount: null,
        percent: 0
      });

      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      let returnChangeTxOut = this.remainingLovelace > 0 ? 1 : 0;
      this.calculateSendAdaFees({
        fromAddress: this.fromWalletItem.paymentAddr,
        txOut: this.formSendAda.toAccounts.length + returnChangeTxOut
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

      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      let returnChangeTxOut = this.remainingLovelace > 0 ? 1 : 0;
      this.calculateSendAdaFees({
        fromAddress: this.fromWalletItem.paymentAddr,
        txOut: this.formSendAda.toAccounts.length + returnChangeTxOut
      });
    },
    handleValidateAndSend(bvModalEvt) {
      bvModalEvt.preventDefault();
      console.log("Saving...");
    },
    calculateMaxLovelace(index) {
      let baseAmount = this.fromWalletItem.paymentAddrLovelace - this.txFee;
      let alreadySpentPercentages = 0;
      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        if (account.type === "amount" && account.amount != null) {
          let alreadySpent = this.$root.$parseCurrency(account.amount);
          if (alreadySpent) {
            baseAmount -= alreadySpent;
          }
          if (alreadySpentPercentages > 0) {
            baseAmount -= alreadySpentPercentages;
            alreadySpentPercentages = 0;
          }
        } else if (
          account.type === "percent" &&
          account.percent != null &&
          account.percent > 0
        ) {
          alreadySpentPercentages += Math.floor(
            baseAmount * (account.percent / 100.0)
          );
        }
      }
      return baseAmount;
    },
    calculateSpentLovelace(index) {
      let baseAmount = this.fromWalletItem.paymentAddrLovelace - this.txFee;
      let alreadySpentPercentages = 0;
      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        if (account.type === "amount" && account.amount != null) {
          let alreadySpent = this.$root.$parseCurrency(account.amount);
          if (alreadySpent) {
            baseAmount -= alreadySpent;
          }
          if (alreadySpentPercentages > 0) {
            baseAmount -= alreadySpentPercentages;
            alreadySpentPercentages = 0;
          }
        } else if (
          account.type === "percent" &&
          account.percent != null &&
          account.percent > 0
        ) {
          alreadySpentPercentages += Math.floor(
            baseAmount * (account.percent / 100.0)
          );
        }
      }
      return baseAmount - alreadySpentPercentages;
    },
    percentLabel(index, percent) {
      let maxLovelace = this.calculateMaxLovelace(index);
      return (
        percent +
        "% - " +
        this.$options.filters.currency(
          Math.floor(maxLovelace * (percent / 100.0)) / 1000000,
          "₳",
          6
        )
      );
    }
  },
  beforeCreate() {
    this.$root.$on("send-ada", walletItem => {
      // received send-ada message from parent component
      this.clickSendAda(walletItem);
    });
  },
  mounted() {
    this.clearFormSendAda();
  },
  beforeDestroy() {
    this.$root.$off("send-ada");
  }
};
</script>