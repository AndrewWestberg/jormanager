<template>
  <div>
    <b-modal
      id="modal-send-ada"
      :title="modalTitle"
      size="xl"
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
              <b-form-radio-group
                v-model="toAccount.type"
                :state="typeState(index, toAccount.type)"
              >
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
              v-show="toAccount.type === 'amount'"
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
              >Enter a non-zero amount up to {{calculateSpentLovelace(index) / 1000000 | currency('₳', 6)}}</b-form-invalid-feedback>
            </b-form-group>
            <b-form-group
              label="Percent"
              label-for="percent-input"
              label-cols-md="2"
              v-show="toAccount.type === 'percent'"
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
    <b-modal
      id="modal-spending-password"
      size="sm"
      title="Enter Spending password to confirm"
      @ok="passwordConfirmed()"
    >
      <b-form-group label="Spending Password" label-for="sudo-input">
        <b-form-input
          id="spending-password-input"
          type="password"
          v-model="formSendAda.spendingPassword"
        />
      </b-form-group>
    </b-modal>
  </div>
</template>

<script>
import _ from "lodash";
import { mapActions, mapState, mapGetters, mapMutations } from "vuex";

export default {
  name: "SendAdaModal",
  data() {
    return {
      remainingLovelace: 1,
      fromWalletItem: {
        name: null,
        paymentAddrLovelace: null,
        stakingAddrLovelace: null,
      },
      formSendAda: {
        spendingPassword: null,
        fromId: null,
        isClaim: false,
        toAccounts: [
          {
            account: null,
            type: null,
            amount: null,
            percent: 0,
          },
        ],
      },
    };
  },
  watch: {
    toastSuccess(toast) {
      if (toast.title === "Ada Sent") {
        this.$bvModal.hide("modal-send-ada");
        this.clearFormSendAda();
      }
    },
    remainingLovelace(newValue, oldValue) {
      if (
        (oldValue == 0 && newValue != 0) ||
        (oldValue != 0 && newValue == 0)
      ) {
        // Recalculate fees anytime we have no change to return or
        // if we previously had no change to return.
        this.prepareCalculateSendAdaFees();
      }
    },
  },
  computed: {
    ...mapState(["walletItems", "txFee", "toastSuccess"]),
    ...mapGetters(["paymentSelectOptions"]),
    modalTitle() {
      return (
        (this.formSendAda.isClaim ? "Claim Rewards (" : "Send Ada (") +
        this.fromWalletItem.name +
        " - " +
        this.$options.filters.currency(
          (this.formSendAda.isClaim
            ? this.fromWalletItem.stakingAddrLovelace
            : this.fromWalletItem.paymentAddrLovelace) / 1000000,
          "₳",
          6
        ) +
        "), Fee: " +
        this.$options.filters.currency(this.txFee / 1000000, "₳", 6) +
        ", Remaining: " +
        this.$options.filters.currency(this.remainingLovelace / 1000000, "₳", 6)
      );
    },
  },
  methods: {
    ...mapActions(["calculateSendAdaFees", "submitTransaction"]),
    ...mapMutations(["toastError"]),
    accountState(account) {
      return account != null;
    },
    typeState(index, type) {
      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      if (type !== "amount") {
        this.formSendAda.toAccounts[index].amount = null;
      }
      if (type !== "percent") {
        this.formSendAda.toAccounts[index].percent = 0;
      }
      return type != null;
    },
    amountState(index, amount) {
      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      if (amount != null) {
        let lovelaces = this.$root.$parseCurrency(amount);
        let spentLovelace = this.calculateSpentLovelace(index + 1);
        return lovelaces > 0 && spentLovelace >= 0;
      }
      return false;
    },
    percentState(index, percent) {
      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );
      if (percent != null && percent > 0) {
        let spentLovelace = this.calculateSpentLovelace(index + 1);
        return spentLovelace >= 0;
      }
      return false;
    },
    addPaymentEntry() {
      this.formSendAda.toAccounts.push({
        account: null,
        type: null,
        amount: null,
        percent: 0,
      });

      this.prepareCalculateSendAdaFees();
    },
    clearFormSendAda() {
      this.fromWalletItem = {
        name: null,
        paymentAddrLovelace: null,
        stakingAddrLovelace: null,
      };
      this.formSendAda = null;
      this.formSendAda = {
        fromId: null,
        isClaim: false,
        toAccounts: [
          {
            account: null,
            type: null,
            amount: null,
            percent: 0,
          },
        ],
      };
    },
    showSendAdaModal(walletItem, isClaim) {
      this.clearFormSendAda();
      this.formSendAda.fromId = walletItem.id;
      this.formSendAda.isClaim = isClaim;
      this.fromWalletItem = _.cloneDeep(walletItem);
      if (isClaim) {
        // Fully claim to same account by default
        (this.formSendAda.toAccounts[0].account = walletItem.id),
          (this.formSendAda.toAccounts[0].type = "percent"),
          (this.formSendAda.toAccounts[0].percent = 100);
        this.prepareCalculateSendAdaFees();
      }
      this.$bvModal.show("modal-send-ada");
    },
    handleValidateAndSend(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (this.txFee <= 0) {
        return;
      }
      let isValidForm = true;
      for (let i = 0; i < this.formSendAda.toAccounts.length; i++) {
        let toAccount = this.formSendAda.toAccounts[i];
        if (
          !this.accountState(toAccount.account) ||
          !this.typeState(i, toAccount.type) ||
          (toAccount.type === "amount" &&
            !this.amountState(i, toAccount.amount)) ||
          (toAccount.type === "percent" &&
            !this.percentState(i, toAccount.percent))
        ) {
          isValidForm = false;
          break;
        }
      }

      if (!isValidForm) {
        this.toastError({
          title: "Invalid Form",
          message: "Check your transaction for completeness.",
        });
        return;
      }

      if (this.formSendAda.isClaim && this.remainingLovelace !== 0) {
        this.toastError({
          title: "Invalid Form",
          message:
            "You must claim 100% of rewards Ada. Your remaining balance needs to be ₳0.000000",
        });
        return;
      }

      this.$bvModal.show("modal-spending-password");
    },
    passwordConfirmed() {
      this.submitTransaction({
        spendingPassword: this.formSendAda.spendingPassword,
        fromId: this.formSendAda.fromId,
        isClaim: this.formSendAda.isClaim,
        txFee: this.txFee,
        toAccounts: _.map(this.formSendAda.toAccounts, (toAccount) => {
          return {
            account: toAccount.account,
            type: toAccount.type,
            amount:
              toAccount.amount == null
                ? null
                : this.$root.$parseCurrency(toAccount.amount),
            percent: toAccount.percent,
          };
        }),
      });
    },
    prepareCalculateSendAdaFees() {
      this.remainingLovelace = this.calculateSpentLovelace(
        this.formSendAda.toAccounts.length
      );

      if (
        this.remainingLovelace == 0 &&
        this.formSendAda.toAccounts[this.formSendAda.toAccounts.length - 1]
          .type === "amount"
      ) {
        // We're trying to spend everything with an amount value. Change it to a percentage
        let percent = 100;
        let i = this.formSendAda.toAccounts.length - 2;
        while (i >= 0 && this.formSendAda.toAccounts[i].type === "percent") {
          percent -= this.formSendAda.toAccounts[i].percent;
          i--;
        }
        this.formSendAda.toAccounts[
          this.formSendAda.toAccounts.length - 1
        ].amount = null;
        this.formSendAda.toAccounts[
          this.formSendAda.toAccounts.length - 1
        ].percent = percent;
        this.formSendAda.toAccounts[
          this.formSendAda.toAccounts.length - 1
        ].type = "percent";
      }

      let returnChangeTxOut = this.remainingLovelace > 0 ? 1 : 0;
      let request = {
        fromId: this.fromWalletItem.id,
        toAccounts: _.map(this.formSendAda.toAccounts, "account"),
        txOut: this.formSendAda.toAccounts.length + returnChangeTxOut,
        isClaim: this.formSendAda.isClaim,
      };
      if (request.fromId) {
        this.calculateSendAdaFees(request);
      }
    },
    calculateClaimRewardsFeePayer() {
      for (let i = 0; i < this.formSendAda.toAccounts.length; i++) {
        let account = this.formSendAda.toAccounts[i];
        let walletItem = _.find(this.walletItems, (walletItem) => {
          return walletItem.id === account.account;
        });
        if (
          walletItem &&
          walletItem.type !== "address" &&
          walletItem.paymentAddrLovelace > 1000000
        ) {
          return walletItem.id;
        }
      }
      return -1;
    },
    calculateSpentLovelace(index) {
      let feePayerAccountId = -1;
      if (this.formSendAda.isClaim) {
        feePayerAccountId = this.calculateClaimRewardsFeePayer();
      } else {
        feePayerAccountId = this.fromWalletItem.id;
      }
      let baseAmount = this.formSendAda.isClaim
        ? this.fromWalletItem.stakingAddrLovelace
        : this.fromWalletItem.paymentAddrLovelace - this.txFee;
      let alreadySpentPercentages = 0;
      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        let amount = 0;
        if (account.type === "amount" && account.amount != null) {
          amount = this.$root.$parseCurrency(account.amount);
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId
          ) {
            // Reimburse payer for the txFee when claiming rewards
            amount += this.txFee;
          }
          baseAmount -= amount;
          // reset percentages since this is an amount
          alreadySpentPercentages = 0;
        } else if (
          account.type === "percent" &&
          account.percent != null &&
          account.percent > 0
        ) {
          let percent = parseInt(account.percent);
          amount = Math.round(
            baseAmount * (percent / (100.0 - alreadySpentPercentages))
          );
          baseAmount -= amount;
          alreadySpentPercentages += percent;
          if (alreadySpentPercentages == 100) {
            alreadySpentPercentages = 0;
          }
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId
          ) {
            if (baseAmount >= this.txFee) {
              amount += this.txFee;
              baseAmount -= this.txFee;
            }
          }
        }
      }
      return baseAmount;
    },
    calculateSpentLovelaceAt(index) {
      let feePayerAccountId = -1;
      if (this.formSendAda.isClaim) {
        feePayerAccountId = this.calculateClaimRewardsFeePayer();
      } else {
        feePayerAccountId = this.fromWalletItem.id;
      }
      let baseAmount = this.formSendAda.isClaim
        ? this.fromWalletItem.stakingAddrLovelace
        : this.fromWalletItem.paymentAddrLovelace - this.txFee;
      let alreadySpentPercentages = 0;
      let amount = 0;
      for (let i = 0; i <= index; i++) {
        let account = this.formSendAda.toAccounts[i];
        amount = 0;
        if (account.type === "amount" && account.amount != null) {
          amount = this.$root.$parseCurrency(account.amount);
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId
          ) {
            // Reimburse payer for the txFee when claiming rewards
            amount += this.txFee;
          }
          baseAmount -= amount;
          // reset percentages since this is an amount
          alreadySpentPercentages = 0;
        } else if (
          account.type === "percent" &&
          account.percent != null &&
          account.percent > 0
        ) {
          let percent = parseInt(account.percent);
          amount = Math.round(
            baseAmount * (percent / (100.0 - alreadySpentPercentages))
          );
          baseAmount -= amount;
          alreadySpentPercentages += percent;
          if (alreadySpentPercentages == 100) {
            alreadySpentPercentages = 0;
          }
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId
          ) {
            if (baseAmount >= this.txFee) {
              amount += this.txFee;
              baseAmount -= this.txFee;
            }
          }
        }
      }
      return amount;
    },
    percentLabel(index, percent) {
      let lovelace = this.calculateSpentLovelaceAt(index);
      return (
        percent +
        "% - " +
        this.$options.filters.currency(lovelace / 1000000, "₳", 6)
      );
    },
  },
  beforeCreate() {
    this.$root.$on("send-ada", (walletItem) => {
      // received send-ada message from parent component
      this.showSendAdaModal(walletItem, false);
    });
    this.$root.$on("claim-ada", (walletItem) => {
      // received claim-ada message from parent component
      this.showSendAdaModal(walletItem, true);
    });
  },
  mounted() {
    this.clearFormSendAda();
  },
  beforeDestroy() {
    this.$root.$off("send-ada");
    this.$root.$off("claim-ada");
  },
};
</script>