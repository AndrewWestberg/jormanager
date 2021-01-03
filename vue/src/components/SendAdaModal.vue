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
        <div class="accordion" role="tablist">
          <b-card
            border-variant="dark"
            header-border-variant="dark"
            no-body
            class="mb-1"
            v-for="(toAccount, index) in formSendAda.toAccounts"
            :key="index"
          >
            <b-card-header header-tag="header" class="p-1" role="tab">
              <b-button
                block
                v-b-toggle="'accordion-' + index"
                :variant="headerVariant(index, toAccount)"
              >
                <div class="clearfix">
                  <span class="float-left">{{
                    headerLabel(index, toAccount)
                  }}</span>
                  <b-icon-caret-up class="float-right when-open" />
                  <b-icon-caret-down class="float-right when-closed" />
                </div>
              </b-button>
            </b-card-header>
            <b-collapse
              :id="'accordion-' + index"
              visible
              accordion="accounts-accordion"
              role="tabpanel"
            >
              <b-card-body>
                <b-form-group label="Currency" label-cols-md="2">
                  <b-form-select
                    v-model="toAccount.currency"
                    :state="currencyState(toAccount.currency)"
                    :options="currencySelectOptions(fromWalletItem)"
                    @input="
                      toAccount.amount = null;
                      toAccount.percent = 0;
                    "
                  >
                  </b-form-select>
                </b-form-group>
                <b-form-group label="To Account" label-cols-md="2">
                  <b-form-select
                    v-model="toAccount.account"
                    :state="accountState(toAccount.account)"
                    :options="paymentSelectOptions($options.filters.currency)"
                  >
                    <template v-slot:first>
                      <b-form-select-option :value="null" disabled
                        >-- Please select an option --</b-form-select-option
                      >
                    </template>
                  </b-form-select>
                </b-form-group>
                <b-form-group label="Entry Type" label-cols-md="2">
                  <b-form-radio-group
                    v-model="toAccount.type"
                    :state="typeState(index, toAccount.type)"
                  >
                    <b-form-radio value="amount">
                      <font-awesome-icon
                        :icon="['fas', 'weight-hanging']"
                      />&nbsp;Amount
                    </b-form-radio>
                    <b-form-radio value="percent">
                      <font-awesome-icon
                        :icon="['fas', 'balance-scale-right']"
                      />&nbsp;Percent
                    </b-form-radio>
                  </b-form-radio-group>
                </b-form-group>
                <b-form-group
                  label="Amount"
                  :label-for="'amount-input-' + index"
                  label-cols-md="2"
                  v-show="toAccount.type === 'amount'"
                >
                  <b-form-input
                    :id="'amount-input-' + index"
                    :state="
                      amountState(index, toAccount.amount, toAccount.currency)
                    "
                    :aria-describedby="'amount-input-live-feedback-' + index"
                    v-model="toAccount.amount"
                    :placeholder="amountPlaceholder(toAccount.currency)"
                    trim
                    v-currency="amountCurrencyOptions(toAccount.currency)"
                  />
                  <b-form-invalid-feedback
                    :id="'amount-input-live-feedback-' + index"
                    >Enter a non-zero amount up to
                    {{
                      amountRemainingLabel(index, toAccount.currency)
                    }}</b-form-invalid-feedback
                  >
                </b-form-group>
                <b-form-group
                  label="Percent"
                  :label-for="'percent-input-' + index"
                  label-cols-md="2"
                  v-show="toAccount.type === 'percent'"
                >
                  <b-form-input
                    :id="'percent-input-' + index"
                    v-model="toAccount.percent"
                    :state="
                      percentState(index, toAccount.percent, toAccount.currency)
                    "
                    placeholder="e.g. 2"
                    type="range"
                    min="0"
                    max="100"
                    step="1"
                    trim
                  />
                  <p class="text-center">
                    {{
                      percentLabel(index, toAccount.percent, toAccount.currency)
                    }}
                  </p>
                </b-form-group>
              </b-card-body>
            </b-collapse>
          </b-card>
        </div>
      </b-form>
      <hr />
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
            currency: "ada",
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
    ...mapGetters([
      "currencySelectOptions",
      "paymentSelectOptions",
      "walletItemById",
    ]),
    modalTitle() {
      let tokenKeepFee = this.calculateTokenKeepFee();
      return (
        (this.formSendAda.isClaim ? "Claiming: " : "Sending: ") +
        this.formatCurrency(
          this.formSendAda.isClaim
            ? this.fromWalletItem.stakingAddrLovelace
            : this.fromWalletItem.paymentAddrLovelace,
          "ada"
        ) +
        ", TxFee: " +
        this.formatCurrency(this.txFee, "ada") +
        (tokenKeepFee > 0
          ? ", TokenKeep: " + this.formatCurrency(tokenKeepFee, "ada")
          : "") +
        ", Remaining: " +
        this.formatCurrency(this.remainingLovelace, "ada")
      );
    },
  },
  methods: {
    ...mapActions(["calculateSendAdaFees", "submitTransaction"]),
    ...mapMutations(["toastError"]),
    currencyState(currency) {
      return currency != null;
    },
    accountState(account) {
      return account != null;
    },
    typeState(index, type) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      if (type !== "amount") {
        this.formSendAda.toAccounts[index].amount = null;
      }
      if (type !== "percent") {
        this.formSendAda.toAccounts[index].percent = 0;
      }
      return type != null;
    },
    amountState(index, amount, currency) {
      let spent = this.calculateSpent(this.formSendAda.toAccounts.length);
      this.remainingLovelace = spent.remaining["ada"];
      if (amount != null) {
        let tokens = this.$ci.parse(
          amount,
          this.amountCurrencyOptions(currency)
        );
        let spent = this.calculateSpent(index + 1);
        let remainingTokens = spent.remaining[currency];
        return tokens > 0 && remainingTokens >= 0;
      }
      return false;
    },
    percentState(index, percent, currency) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      if (percent != null && percent > 0) {
        let spent = this.calculateSpent(index + 1);
        let tokens = spent.amount[currency];
        let remainingTokens = spent.remaining[currency];
        return tokens > 0 && remainingTokens >= 0;
      }
      return false;
    },
    headerLabel(index, toAccount) {
      let label = "";
      if (toAccount.account) {
        label += this.walletItemById(toAccount.account).name;
        label += " - ";
      }
      let spent = this.calculateSpent(index);
      label += "Available: ";
      label += this.formatCurrency(
        spent.remaining[toAccount.currency],
        toAccount.currency
      );

      if (toAccount.type === "amount" && toAccount.amount != null) {
        label +=
          ", Amount: " +
          this.formatCurrency(toAccount.amount, toAccount.currency);
      } else if (toAccount.type === "percent") {
        label +=
          ", Amount: " +
          this.percentLabel(index, toAccount.percent, toAccount.currency);
      }

      if (toAccount.currency !== "ada") {
        label +=
          ", TokenFee: " +
          this.formatCurrency(
            this.calculateTokenFee(index, toAccount.account),
            "ada"
          );
      }

      label += ", Remaining: ";
      label += this.formatCurrency(
        this.calculateSpent(index + 1).remaining[toAccount.currency],
        toAccount.currency
      );

      return label;
    },
    headerVariant(index, toAccount) {
      return this.currencyState(toAccount.account) &&
        this.accountState(toAccount.account) &&
        this.typeState(index, toAccount.type) &&
        (this.amountState(index, toAccount.amount, toAccount.currency) ||
          this.percentState(index, toAccount.percent, toAccount.currency))
        ? "outline-success"
        : "outline-danger";
    },
    formatCurrency(amount, currency) {
      let tokens = 0;
      if (isNaN(amount)) {
        tokens = this.$ci.parse(amount, this.amountCurrencyOptions(currency));
      } else {
        tokens = amount;
      }

      if (currency === "ada") {
        return this.$options.filters.currency(tokens / 1000000, "₳", 6);
      }

      return this.$options.filters.currency(
        tokens,
        "(" + currency.split(".")[0] + ") ",
        0
      );
    },
    amountCurrencyOptions(currency) {
      if (currency === "ada") {
        return {
          currency: {
            prefix: "₳",
          },
          precision: 6,
          valueAsInteger: true,
          allowNegative: false,
          distractionFree: {
            hideNegligibleDecimalDigits: true,
            hideCurrencySymbol: false,
            hideGroupingSymbol: true,
          },
        };
      }
      return {
        currency: {
          prefix: "(" + currency.split(".")[0] + ") ",
          suffix: null,
        },
        allowNegative: false,
        precision: 0,
        distractionFree: {
          hideNegligibleDecimalDigits: true,
          hideCurrencySymbol: false,
          hideGroupingSymbol: true,
        },
      };
    },
    amountPlaceholder(currency) {
      if (currency === "ada") return "e.g. ₳1,230.987000";
      return "e.g. (" + currency.split(".")[0] + ") 1,230";
    },
    amountRemainingLabel(index, currency) {
      let remaining = this.calculateSpent(index).remaining[currency];
      if (currency === "ada") {
        return this.$options.filters.currency(remaining / 1000000, "₳", 6);
      } else {
        return this.$options.filters.currency(
          remaining,
          "(" + currency.split(".")[0] + ") ",
          0
        );
      }
    },
    addPaymentEntry() {
      this.formSendAda.toAccounts.push({
        currency: "ada",
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
            currency: "ada",
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
          !this.currencyState(toAccount.currency) ||
          !this.accountState(toAccount.account) ||
          !this.typeState(i, toAccount.type) ||
          (toAccount.type === "amount" &&
            !this.amountState(i, toAccount.amount, toAccount.currency)) ||
          (toAccount.type === "percent" &&
            !this.percentState(i, toAccount.percent, toAccount.currency))
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

      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.passwordConfirmed(spendingPassword);
        }
      );
    },
    passwordConfirmed(spendingPassword) {
      this.submitTransaction({
        spendingPassword: spendingPassword,
        fromId: this.formSendAda.fromId,
        isClaim: this.formSendAda.isClaim,
        txFee: this.txFee,
        toAccounts: _.map(this.formSendAda.toAccounts, (toAccount, index) => {
          return {
            currency: toAccount.currency,
            account: toAccount.account,
            type: toAccount.type,
            amount:
              toAccount.amount == null
                ? this.calculateSpent(index + 1).amount[toAccount.currency]
                : this.$ci.parse(toAccount.amount),
            percent: toAccount.percent,
          };
        }),
      });
    },
    prepareCalculateSendAdaFees() {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];

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
    calculateTokenFee(index, accountId) {
      let idx = 0;
      let totalAdaSentToAccount = _.sumBy(
        this.formSendAda.toAccounts,
        (account) => {
          let index = idx;
          idx++;
          if (account.account !== accountId) {
            return 0;
          }
          if (account.currency !== "ada") {
            return 0;
          }
          return this.calculateSpent(index + 1, true).amount[account.currency];
        }
      );

      let tokenFee = 2000000;
      let minUtxoMet = false;
      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        if (account.account !== accountId || account.currency === "ada") {
          continue;
        }

        // there is a token above us, it will contain the minutxo 1 ada
        if (!minUtxoMet) {
          minUtxoMet = true;
          tokenFee -= 1000000;
          totalAdaSentToAccount -= 2000000; // minutxo and token fee come out of our total ada
        } else {
          totalAdaSentToAccount -= 1000000; // just token fee come out of our total ada
        }
      }

      if (totalAdaSentToAccount - tokenFee >= 0) {
        // enough left in ada tx to pay for our fee
        return 0;
      }
      if (totalAdaSentToAccount > 0) {
        return tokenFee - totalAdaSentToAccount;
      }
      return tokenFee;
    },
    calculateTokenKeepFee() {
      // minimum amount of ada we need to keep on the sending address to retain unspent tokens
      // 1 ada minutxo + 1 ada for each token that isn't completely spent
      let unspentTokensCount = _.sum(
        _.map(
          this.calculateSpent(this.formSendAda.toAccounts.length, true)
            .remaining,
          (amount, currency) => {
            if (currency === "ada") {
              return 0;
            }
            if (amount > 0) {
              return 1;
            }
            return 0;
          }
        )
      );

      if (unspentTokensCount > 0) {
        return unspentTokensCount * 1000000 + 1000000;
      }
      return 0;
    },
    calculateSpent(index, skipTokenFees) {
      let feePayerAccountId = -1;
      if (this.formSendAda.isClaim) {
        feePayerAccountId = this.calculateClaimRewardsFeePayer();
      } else {
        feePayerAccountId = this.fromWalletItem.id;
      }
      let tokenKeepFee = skipTokenFees ? 0 : this.calculateTokenKeepFee();
      let baseAmount = this.fromWalletItem.nativeAssetMap
        ? _.clone(this.fromWalletItem.nativeAssetMap)
        : {};
      baseAmount["ada"] = this.formSendAda.isClaim
        ? this.fromWalletItem.stakingAddrLovelace - tokenKeepFee
        : this.fromWalletItem.paymentAddrLovelace - this.txFee - tokenKeepFee;
      let alreadySpentPercentages = {};
      let amount = {};
      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        amount[account.currency] = 0;
        if (account.type === "amount" && account.amount != null) {
          amount[account.currency] = this.$ci.parse(
            account.amount,
            this.amountCurrencyOptions(account.currency)
          );
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId &&
            account.currency === "ada"
          ) {
            // Reimburse payer for the txFee when claiming rewards
            amount[account.currency] += this.txFee;
          }
          baseAmount[account.currency] -= amount[account.currency];
          // reset percentages since this is an amount
          alreadySpentPercentages[account.currency] = 0;
        } else if (
          account.type === "percent" &&
          account.percent != null &&
          account.percent > 0
        ) {
          if (alreadySpentPercentages[account.currency] === undefined) {
            alreadySpentPercentages[account.currency] = 0;
          }
          let percent = parseInt(account.percent);
          if (alreadySpentPercentages[account.currency] == 100) {
            amount[account.currency] = 0;
          } else {
            amount[account.currency] = Math.round(
              baseAmount[account.currency] *
                (percent / (100.0 - alreadySpentPercentages[account.currency]))
            );
            alreadySpentPercentages[account.currency] += percent;
          }
          baseAmount[account.currency] -= amount[account.currency];
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId &&
            account.currency === "ada"
          ) {
            if (baseAmount[account.currency] >= this.txFee) {
              amount[account.currency] += this.txFee;
              baseAmount[account.currency] -= this.txFee;
            }
          }
        }

        if (!skipTokenFees && account.currency !== "ada") {
          // subtract any token fees from our total available ada
          baseAmount["ada"] -= this.calculateTokenFee(i, account.account);
        }
      }
      return { remaining: baseAmount, amount: amount };
    },
    percentLabel(index, percent, currency) {
      let amount = this.calculateSpent(index + 1).amount[currency] || 0;
      return percent + "% - " + this.formatCurrency(amount, currency);
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

<style scoped>
.collapsed .when-open,
.not-collapsed .when-closed {
  display: none;
}
</style>