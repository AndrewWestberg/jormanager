<template>
  <div id="add_wallet">
    <h2>Add Wallet Entry</h2>
    <vue-good-wizard :steps="steps" :onNext="nextClicked" :onBack="backClicked">
      <div slot="page1">
        <h4>Address/Key Info</h4>
        <b-form-group label="Name" label-for="name-input" label-cols-md="2">
          <b-form-input
            id="name-input"
            v-model="formWallet.name"
            :state="nameState"
            aria-describedby="name-input-live-feedback"
            placeholder="e.g. tickr_owner, funds, etc..."
            trim
          ></b-form-input>
          <b-form-invalid-feedback id="name-input-live-feedback"
            >Enter at least 3 letters with no spaces</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Entry Type"
          label-for="type-radio"
          label-cols-md="2"
        >
          <b-form-radio-group
            id="type-radio"
            v-model="formWallet.type"
            :state="typeState"
          >
            <b-form-radio value="address">Simple Address</b-form-radio>
            <b-form-radio value="payment">Payment</b-form-radio>
            <b-form-radio value="stake">Stake</b-form-radio>
            <b-form-radio value="pledge">Pledge-Only</b-form-radio>
          </b-form-radio-group>
        </b-form-group>
        <b-form-group
          label="Address"
          label-for="address-input"
          label-cols-md="2"
          v-if="formWallet.type == 'address' || formWallet.type == 'pledge'"
        >
          <b-form-input
            id="address-input"
            v-model="formWallet.paymentAddr"
            :state="paymentAddrState"
            aria-describedby="address-input-live-feedback"
            placeholder="e.g. addr1v805z8cn8z...xrrqj4t30l"
            trim
          ></b-form-input>
          <b-form-invalid-feedback id="address-input-live-feedback"
            >Wallet address that can only receive payments or monitor funds it
            holds</b-form-invalid-feedback
          >
        </b-form-group>
        <b-form-group
          label="Keys"
          v-if="formWallet.type != null && formWallet.type != 'address'"
        >
          <b-form-checkbox
            id="keys-generate-checkbox"
            v-model="formWallet.generateKeys"
            v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
            >Generate</b-form-checkbox
          >
          <b-form-group
            label="payment skey"
            label-for="payment-skey-file"
            label-cols-md="1"
            label-align="right"
            v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
          >
            <b-form-file
              id="payment-skey-file"
              :disabled="formWallet.generateKeys"
              :placeholder="
                formWallet.generateKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formWallet.paymentSKey"
              :state="paymentSKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="payment vkey"
            label-for="payment-vkey-file"
            label-cols-md="1"
            label-align="right"
            v-if="formWallet.type === 'payment' || formWallet.type === 'stake'"
          >
            <b-form-file
              id="payment-vkey-file"
              :disabled="formWallet.generateKeys"
              :placeholder="
                formWallet.generateKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formWallet.paymentVKey"
              :state="paymentVKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="staking skey"
            label-for="staking-skey-file"
            label-cols-md="1"
            label-align="right"
            v-if="formWallet.type === 'stake' || formWallet.type === 'pledge'"
          >
            <b-form-file
              id="staking-skey-file"
              :disabled="formWallet.generateKeys"
              :placeholder="
                formWallet.generateKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formWallet.stakingSKey"
              :state="stakingSKeyState"
              trim
            />
          </b-form-group>
          <b-form-group
            label="staking vkey"
            label-for="staking-vkey-file"
            label-cols-md="1"
            label-align="right"
            v-if="formWallet.type == 'stake' || formWallet.type === 'pledge'"
          >
            <b-form-file
              id="staking-vkey-file"
              :disabled="formWallet.generateKeys"
              :placeholder="
                formWallet.generateKeys
                  ? '---'
                  : 'Choose file or drop it here...'
              "
              drop-placeholder="Drop file here..."
              v-model="formWallet.stakingVKey"
              :state="stakingVKeyState"
              trim
            />
          </b-form-group>
        </b-form-group>
      </div>
    </vue-good-wizard>
  </div>
</template>

<script>
import bs58 from "bs58";
import { GoodWizard } from "vue-good-wizard";
import { mapActions, mapMutations } from "vuex";

export default {
  name: "AddWalletEntryWizard",
  components: {
    "vue-good-wizard": GoodWizard,
  },
  data() {
    return {
      formWallet: {
        spendingPassword: null,
        name: "",
        type: null,
        paymentAddr: "",
        generateKeys: false,
        paymentSKey: null,
        paymentVKey: null,
        stakingSKey: null,
        stakingVKey: null,
      },
      steps: [
        {
          label: "Address/Key Info",
          slot: "page1",
          options: {
            backEnabled: true,
          },
        },
      ],
    };
  },
  watch: {
    formWallet: {
      deep: true,
      handler(formWallet) {
        if (formWallet.type === "address" || formWallet.type === "pledge") {
          this.formWallet.generateKeys = false;
        }
      },
    },
  },
  methods: {
    ...mapActions(["createWalletEntry"]),
    ...mapMutations(["toastError"]),
    async nextClicked(currentPage) {
      if (currentPage === 0) {
        if (
          this.nameState &&
          this.typeState &&
          this.paymentAddrState &&
          this.paymentSKeyState &&
          this.paymentVKeyState &&
          this.stakingSKeyState &&
          this.stakingVKeyState
        ) {
          if (this.formWallet.generateKeys) {
            this.formWallet.paymentSKey = null;
            this.formWallet.paymentVKey = null;
            this.formWallet.stakingSKey = null;
            this.formWallet.stakingVKey = null;
          }
          if (this.formWallet.type === "payment") {
            this.formWallet.stakingSKey = null;
            this.formWallet.stakingVKey = null;
          }
          if (this.formWallet.paymentSKey != null) {
            this.formWallet.paymentSKey = await this.formWallet.paymentSKey.text();
          }
          if (this.formWallet.paymentVKey != null) {
            this.formWallet.paymentVKey = await this.formWallet.paymentVKey.text();
          }
          if (this.formWallet.stakingSKey != null) {
            this.formWallet.stakingSKey = await this.formWallet.stakingSKey.text();
          }
          if (this.formWallet.stakingVKey != null) {
            this.formWallet.stakingVKey = await this.formWallet.stakingVKey.text();
          }

          this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
            (spendingPassword) => {
              this.formWallet.spendingPassword = spendingPassword;
              this.createWalletEntry(this.formWallet);
              this.formWallet.spendingPassword = null;
              this.$emit("hideWalletEntryWizard");
            }
          );
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields.",
          });
          return false;
        }
      }
    },
    backClicked(/*currentPage*/) {
      // console.log("back clicked", currentPage);
      return true; //return false if you want to prevent moving to previous page
    },
    isByronAddress(address) {
      try {
        // check for a valid byron address
        bs58.decode(address);
        return true;
      } catch (e) {
        return false;
      }
    },
  },
  computed: {
    nameState() {
      return this.formWallet.name.length > 2;
    },
    typeState() {
      return this.formWallet.type != null;
    },
    paymentAddrState() {
      return (
        this.formWallet.type === "payment" ||
        this.formWallet.type === "stake" ||
        this.formWallet.paymentAddr.match(
          /^.*1(?=[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)(?:.{53}|.{98})$/
        ) != null ||
        this.isByronAddress(this.formWallet.paymentAddr)
      );
    },
    paymentSKeyState() {
      return (
        this.formWallet.type === "address" ||
        this.formWallet.type === "pledge" ||
        this.formWallet.generateKeys ||
        this.formWallet.paymentSKey != null
      );
    },
    paymentVKeyState() {
      return (
        this.formWallet.type === "address" ||
        this.formWallet.type === "pledge" ||
        this.formWallet.generateKeys ||
        this.formWallet.paymentVKey != null
      );
    },
    stakingSKeyState() {
      return (
        this.formWallet.type === "address" ||
        this.formWallet.type === "payment" ||
        this.formWallet.generateKeys ||
        this.formWallet.stakingSKey != null
      );
    },
    stakingVKeyState() {
      return (
        this.formWallet.type === "address" ||
        this.formWallet.type === "payment" ||
        this.formWallet.generateKeys ||
        this.formWallet.stakingVKey != null
      );
    },
  },
};
</script>

<style>
#add_wallet > div > div.wizard__body {
  background-color: #333;
}
#add_wallet > div > span.wizard__arrow {
  background-color: #333;
}
#add_wallet > div > div > div.wizard__body__actions > .wizard__next {
  background-color: #007bff;
  border-bottom-right-radius: 5px;
  border-top-left-radius: 5px;
}
#add_wallet > div > div > div.wizard__body__actions > .wizard__back {
  background-color: #555;
  border-bottom-left-radius: 5px;
  border-top-right-radius: 5px;
}
#add_wallet > div > div > div.wizard__body__actions {
  background-color: #333;
  border-radius: 5px;
  border-top-style: hidden;
  border-bottom-style: hidden;
}
</style>