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
      <template #modal-footer="{ ok, cancel }">
        <b-button
          style="margin-right: 4em"
          variant="outline-primary"
          @click="addPaymentEntry()"
          v-b-tooltip.hover.v-primary.top="'Add a new payment entry.'"
        >
          <b-icon-plus />&nbsp;Add Entry
        </b-button>

        <b-button variant="secondary" @click="cancel()">Cancel</b-button>
        <b-button variant="primary" @click="ok()">Send</b-button>
      </template>
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
                  <span
                    class="float-left"
                    v-show="toAccount.isFeePayer"
                    style="margin: 0 1em 0 0"
                  >
                    <font-awesome-icon
                      :icon="['fas', 'hand-holding-usd']"
                      class="text-warning"
                      v-b-tooltip.hover.v-warning.bottom="'Fee Payer'"
                    />
                  </span>
                  <span class="float-left">
                    {{ headerLabel(index, toAccount) }}
                  </span>
                  <b-icon-x
                    class="float-right"
                    v-show="index > 0"
                    @click="formSendAda.toAccounts.splice(index, 1)"
                  />
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
                      prepareCalculateSendAdaFees();
                    "
                  >
                  </b-form-select>
                </b-form-group>
                <b-form-group label="Destination" label-cols-md="2">
                  <b-form-radio-group
                    v-model="toAccount.destination"
                    :state="destinationState(toAccount.destination)"
                    @input="
                      if (toAccount.destination === 'account') {
                        toAccount.address = '';
                      } else {
                        toAccount.account = -1;
                      }
                    "
                  >
                    <b-form-radio value="account">Account</b-form-radio>
                    <b-form-radio value="address">Address</b-form-radio>
                  </b-form-radio-group>
                </b-form-group>
                <b-form-group
                  label="To Account"
                  label-cols-md="2"
                  v-show="toAccount.destination === 'account'"
                >
                  <b-form-select
                    v-model="toAccount.account"
                    :state="accountState(toAccount)"
                    :options="paymentSelectOptions($options.filters.currency)"
                    @input="prepareCalculateSendAdaFees()"
                  >
                    <template v-slot:first>
                      <b-form-select-option :value="null" disabled
                        >-- Please select an option --</b-form-select-option
                      >
                    </template>
                  </b-form-select>
                </b-form-group>
                <b-form-group
                  label="To Address"
                  label-cols-md="2"
                  v-show="toAccount.destination === 'address'"
                >
                  <b-form-input
                    id="to-address-input"
                    v-model="toAccount.address"
                    :state="addressState(toAccount)"
                    aria-describedby="to-address-input-live-feedback"
                    placeholder="e.g. addr1v805z8cn8z...xrrqj4t30l"
                    trim
                  ></b-form-input>
                  <b-form-invalid-feedback id="to-address-input-live-feedback"
                    >Enter a valid wallet address.</b-form-invalid-feedback
                  >
                </b-form-group>
                <b-form-group label="Entry Type" label-cols-md="2">
                  <b-form-radio-group
                    v-model="toAccount.type"
                    :state="typeState(index, toAccount.type)"
                    @input="
                      if (toAccount.type === 'amount') {
                        toAccount.percent = 0;
                      } else {
                        toAccount.amount = null;
                      }
                    "
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
                    >Enter an amount between
                    {{
                      toAccount.currency === "ada"
                        ? formatCurrency(1000000, toAccount.currency)
                        : formatCurrency(1, toAccount.currency)
                    }}
                    and
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
          <b-card
            border-variant="dark"
            header-border-variant="dark"
            no-body
            class="mb-1"
          >
            <b-card-header header-tag="header" class="p-1" role="tab">
              <b-button
                block
                v-b-toggle="'accordion-metadata'"
                :variant="headerVariantMetadata()"
              >
                <div class="clearfix">
                  <span class="float-left"> Metadata </span>
                  <b-icon-caret-up class="float-right when-open" />
                  <b-icon-caret-down class="float-right when-closed" />
                </div>
              </b-button>
            </b-card-header>
            <b-collapse
              id="accordion-metadata"
              visible
              accordion="accounts-accordion"
              role="tabpanel"
            >
              <b-card-body>
                <b-form-group label="Metadata" label-cols-md="2">
                  <b-form-textarea
                    v-model="formSendAda.metadata"
                    debounce="500"
                    aria-describedby="metadata-input-live-feedback"
                    :state="metadataState()"
                    :placeholder="'{\n  &quot;411&quot;: [\n    &quot;Strings must be less than 64&quot;,\n    &quot;Characters in length&quot;\n  ],\n  &quot;500&quot;: {\n    &quot;The&quot;: &quot;first level must be a number&quot;,\n    &quot;and&quot;: &quot;the max size of metadata is&quot;,\n    &quot;sixteen&quot;: &quot;kilobytes.&quot;\n  }\n}'"
                    rows="11"
                  />
                  <b-form-invalid-feedback id="metadata-input-live-feedback">{{
                    formSendAda.metadataError
                  }}</b-form-invalid-feedback>
                </b-form-group>
              </b-card-body>
            </b-collapse>
          </b-card>
        </div>
      </b-form>
    </b-modal>
  </div>
</template>

<script>
import bs58 from "bs58";
import _ from "lodash";
import { mapActions, mapState, mapGetters, mapMutations } from "vuex";

export default {
  name: "SendAdaModal",
  data() {
    return {
      remainingLovelace: 1,
      tokenKeepFee: 0,
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
            destination: "account",
            account: null,
            address: "",
            type: null,
            amount: null,
            percent: 0,
            tokenFee: 0,
          },
        ],
        metadata: null,
        metadataError: null,
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
    tokenKeepFee() {
      this.prepareCalculateSendAdaFees();
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
        this.formatCurrency(this.remainingLovelace + tokenKeepFee, "ada")
      );
    },
  },
  methods: {
    ...mapActions(["calculateSendAdaFees", "submitTransaction"]),
    ...mapMutations(["toastError"]),
    currencyState(currency) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();
      return currency != null;
    },
    destinationState(destination) {
      return destination != null;
    },
    accountState(toAccount) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();
      if (toAccount.destination === "address") {
        return true;
      } else if (toAccount.destination === "account") {
        return toAccount.account != null;
      }
      return false;
    },
    addressState(toAccount) {
      if (toAccount.destination === "account") {
        return true;
      } else if (
        toAccount.destination === "address" &&
        toAccount.address != null
      ) {
        if (
          toAccount.address.match(
            /^.*1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{53}|.{98})$/
          ) != null
        ) {
          return true;
        }
        try {
          // check for a valid byron address
          bs58.decode(toAccount.address);
          return true;
        } catch (e) {
          return false;
        }
      }

      return false;
    },
    typeState(index, type) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();
      return type != null;
    },
    amountState(index, amount, currency) {
      let spent = this.calculateSpent(this.formSendAda.toAccounts.length);
      this.remainingLovelace = spent.remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();
      if (amount != null) {
        let tokens = this.$ci.parse(
          amount,
          this.amountCurrencyOptions(currency)
        );
        let spent = this.calculateSpent(index + 1);
        let remainingTokens = spent.remaining[currency];
        return (
          tokens >= (currency === "ada" ? 1000000 : 1) && remainingTokens >= 0
        );
      }
      return false;
    },
    percentState(index, percent, currency) {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();
      if (percent != null && percent > 0) {
        let spent = this.calculateSpent(index + 1);
        let tokens = spent.amount[currency];
        let remainingTokens = spent.remaining[currency];
        return (
          tokens >= (currency === "ada" ? 1000000 : 1) && remainingTokens >= 0
        );
      }
      return false;
    },
    validateMetadataItem(field) {
      if (Array.isArray(field)) {
        // Check all items in the array recursively
        for (let i = 0; i < field.length; i++) {
          let errorMessage = this.validateMetadataItem(field[i]);
          if (errorMessage !== null) {
            return errorMessage;
          }
        }
      } else if (typeof field === "string" || field instanceof String) {
        if (field.length >= 64) {
          return "Metadata strings must be less than 64 characters.";
        }
      } else if (field === Object(field)) {
        // Check all items in the object recursively
        for (let propertyName in field) {
          let errorMessage = this.validateMetadataItem(field[propertyName]);
          if (errorMessage !== null) {
            return errorMessage;
          }
        }
      }

      // no validation errors
      return null;
    },
    metadataState() {
      if (
        this.formSendAda.metadata === null ||
        this.formSendAda.metadata.trim().length === 0
      ) {
        this.prepareCalculateSendAdaFees();
        return true;
      }
      try {
        let metadata = JSON.parse(this.formSendAda.metadata);
        if (typeof metadata !== "object" || Array.isArray(metadata)) {
          this.formSendAda.metadataError =
            "Top level metadata must be a map and start with {";
          return false;
        }
        for (let propertyName in metadata) {
          if (isNaN(parseInt(propertyName))) {
            this.formSendAda.metadataError =
              "Metadata first level fields must be integer: '" +
              propertyName +
              "'";
            return false;
          }
          let errorMessage = this.validateMetadataItem(metadata[propertyName]);
          if (errorMessage !== null) {
            this.formSendAda.metadataError = errorMessage;
            return false;
          }
        }

        this.prepareCalculateSendAdaFees();
        return true;
      } catch (e) {
        this.formSendAda.metadataError = e.message;
        return false;
      }
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
          this.formatCurrency(this.calculateTokenFee(index, toAccount), "ada");
      }

      label += ", Remaining: ";
      label += this.formatCurrency(
        this.calculateSpent(index + 1).remaining[toAccount.currency],
        toAccount.currency
      );

      return label;
    },
    headerVariant(index, toAccount) {
      return this.currencyState(toAccount.currency) &&
        this.destinationState(toAccount.destination) &&
        this.accountState(toAccount) &&
        this.addressState(toAccount) &&
        this.typeState(index, toAccount.type) &&
        (this.amountState(index, toAccount.amount, toAccount.currency) ||
          this.percentState(index, toAccount.percent, toAccount.currency))
        ? "outline-success"
        : "danger";
    },
    headerVariantMetadata() {
      return this.metadataState() ? "outline-success" : "danger";
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
        "(" + currency.split(".")[1] + ") ",
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
          prefix: "(" + currency.split(".")[1] + ") ",
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
      return "e.g. (" + currency.split(".")[1] + ") 1,230";
    },
    amountRemainingLabel(index, currency) {
      return this.formatCurrency(
        this.calculateSpent(index).remaining[currency],
        currency
      );
    },
    addPaymentEntry() {
      this.formSendAda.toAccounts.push({
        currency: "ada",
        destination: "account",
        account: null,
        address: "",
        type: null,
        amount: null,
        percent: 0,
        tokenFee: 0,
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
            destination: "account",
            account: null,
            address: "",
            type: null,
            amount: null,
            percent: 0,
            tokenFee: 0,
          },
        ],
        metadata: null,
        metadataError: null,
      };
    },
    showSendAdaModal(walletItem, isClaim) {
      this.clearFormSendAda();
      this.formSendAda.fromId = walletItem.id;
      this.formSendAda.isClaim = isClaim;
      this.fromWalletItem = _.cloneDeep(walletItem);
      if (isClaim) {
        // for claims, we ignore native assets
        this.fromWalletItem.nativeAssetMap = undefined;
      }
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
        this.toastError({
          title: "Calculate Fee Error",
          message: "No Account found capable of covering the claim fee!",
        });
        return;
      }
      let isValidForm = true;
      for (let i = 0; i < this.formSendAda.toAccounts.length; i++) {
        let toAccount = this.formSendAda.toAccounts[i];
        if (
          !this.currencyState(toAccount.currency) ||
          !this.destinationState(toAccount.destination) ||
          !this.accountState(toAccount) ||
          !this.addressState(toAccount) ||
          !this.typeState(i, toAccount.type) ||
          (toAccount.type === "amount" &&
            !this.amountState(i, toAccount.amount, toAccount.currency)) ||
          (toAccount.type === "percent" &&
            !this.percentState(i, toAccount.percent, toAccount.currency)) ||
          !this.metadataState()
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
        tokenKeepFee: this.calculateTokenKeepFee(),
        toAccounts: _.map(this.formSendAda.toAccounts, (toAccount, index) => {
          return {
            currency: toAccount.currency,
            account: toAccount.account,
            address: toAccount.address,
            type: toAccount.type,
            amount:
              toAccount.amount == null
                ? this.calculateSpent(index + 1).amount[toAccount.currency]
                : this.$ci.parse(
                    toAccount.amount,
                    this.amountCurrencyOptions(toAccount.currency)
                  ),
            percent: toAccount.percent,
            tokenFee: toAccount.tokenFee,
          };
        }),
        metadata: this.formSendAda.metadata,
      });
    },
    prepareCalculateSendAdaFees() {
      this.remainingLovelace = this.calculateSpent(
        this.formSendAda.toAccounts.length
      ).remaining["ada"];
      this.tokenKeepFee = this.calculateTokenKeepFee();

      let uniqueToAccounts = Object.keys(
        _.countBy(this.formSendAda.toAccounts, (toAccount) => {
          if (toAccount.account < 0) {
            return toAccount.address;
          }
          return toAccount.account;
        })
      ).length;
      let returnChangeTxOut =
        this.remainingLovelace + this.tokenKeepFee > 0 ? 1 : 0;
      let request = {
        fromId: this.fromWalletItem.id,
        toAccounts: _.filter(
          _.map(this.formSendAda.toAccounts, "account"),
          (account) => {
            return account > -1;
          }
        ),
        txOut: uniqueToAccounts + returnChangeTxOut,
        isClaim: this.formSendAda.isClaim,
        metadata: this.formSendAda.metadata,
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
          walletItem.type !== "pledge" &&
          walletItem.paymentAddrLovelace >= 1000000 + this.txFee
        ) {
          return walletItem.id;
        }
      }
      return -1;
    },
    calculateTokenFee(index, toAccount) {
      let idx = 0;
      let totalAdaSentToAccount = _.sumBy(
        this.formSendAda.toAccounts,
        (account) => {
          let index = idx;
          idx++;
          if (
            account.account !== toAccount.account ||
            account.address !== toAccount.address
          ) {
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
        if (
          account.account !== toAccount.account ||
          account.address !== toAccount.address ||
          account.currency === "ada"
        ) {
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
      let remaining = this.calculateSpent(
        this.formSendAda.toAccounts.length,
        true
      ).remaining;
      let unspentTokensCount = _.sum(
        _.map(remaining, (amount, currency) => {
          if (currency === "ada") {
            return 0;
          }
          if (amount > 0) {
            return 1;
          }
          return 0;
        })
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
        ? this.fromWalletItem.stakingAddrLovelace
        : this.fromWalletItem.paymentAddrLovelace - this.txFee - tokenKeepFee;
      let alreadySpentPercentages = {};
      let amount = {};

      for (let i = 0; i < index; i++) {
        let account = this.formSendAda.toAccounts[i];
        amount[account.currency] = 0;
        if (feePayerAccountId === account.account) {
          account.isFeePayer = true;
        } else {
          account.isFeePayer = false;
        }
        if (account.type === "amount" && account.amount != null) {
          amount[account.currency] = this.$ci.parse(
            account.amount,
            this.amountCurrencyOptions(account.currency)
          );
          baseAmount[account.currency] -= amount[account.currency];
          if (
            this.formSendAda.isClaim &&
            account.account === feePayerAccountId &&
            account.currency === "ada"
          ) {
            // We'll reimburse the payer for the txFee when claiming rewards
            // so take it out of the base amount now
            baseAmount[account.currency] -= this.txFee;
          }
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
              // Only reimburse the fee payer if we have some left. If they are sending 100%, there won't be any left
              baseAmount[account.currency] -= this.txFee;
            } else {
              // Show that this account will be paying the txFee
              amount[account.currency] -= this.txFee;
            }
          }
        }

        if (!skipTokenFees && account.currency !== "ada") {
          // subtract any token fees from our total available ada
          account.tokenFee = this.calculateTokenFee(i, account);
          baseAmount["ada"] -= account.tokenFee;
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