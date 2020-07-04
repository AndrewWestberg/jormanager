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
          <b-form-invalid-feedback
            id="name-input-live-feedback"
          >Enter at least 3 letters with no spaces</b-form-invalid-feedback>
        </b-form-group>
        <b-form-group label="Entry Type" label-for="type-radio" label-cols-md="2">
          <b-form-radio-group id="type-radio" v-model="formWallet.type" :state="typeState">
            <b-form-radio value="address">Simple Address</b-form-radio>
            <b-form-radio value="payment">Payment</b-form-radio>
            <b-form-radio value="stake">Stake</b-form-radio>
          </b-form-radio-group>
        </b-form-group>
        <b-form-group
          label="Address"
          label-for="address-input"
          label-cols-md="2"
          v-if="formWallet.type=='address'"
        >
          <b-form-input
            id="address-input"
            v-model="formWallet.paymentAddr"
            :state="paymentAddrState"
            aria-describedby="address-input-live-feedback"
            placeholder="e.g. 60f9a5546c..., 003159a5bc7489bbdd..."
            trim
          ></b-form-input>
          <b-form-invalid-feedback
            id="address-input-live-feedback"
          >Wallet address that can only receive payments or monitor funds it holds</b-form-invalid-feedback>
        </b-form-group>
      </div>
    </vue-good-wizard>
  </div>
</template>

<script>
import { GoodWizard } from "vue-good-wizard";
import { mapActions } from "vuex";

export default {
  name: "AddWalletEntryWizard",
  components: {
    "vue-good-wizard": GoodWizard
  },
  data() {
    return {
      formWallet: {
        name: "",
        type: null,
        paymentAddr: ""
      },
      steps: [
        {
          label: "Address/Key Info",
          slot: "page1",
          options: {
            backEnabled: true
          }
        }
      ]
    };
  },
  methods: {
    ...mapActions(["createWalletEntry"]),
    nextClicked(currentPage) {
      if (currentPage === 0) {
        if (this.nameState && this.typeState && this.paymentAddrState) {
          // core node save!
          this.createWalletEntry(this.formWallet);
          this.$emit("hideWalletEntryWizard");
          return true;
        } else {
          this.toastError({
            title: "Error",
            message: "You must fill out all fields."
          });
          return false;
        }
      }
    },
    backClicked(/*currentPage*/) {
      // console.log("back clicked", currentPage);
      return true; //return false if you want to prevent moving to previous page
    }
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
        this.formWallet.paymentAddr.match(
          /^((60|61)[0-9a-fA-F]{56}|(00|01)[0-9a-fA-F]{112})$/
        ) != null
      );
    }
  }
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