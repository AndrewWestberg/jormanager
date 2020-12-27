<template>
  <div>
    <div v-if="!showAddWalletEntryWizard">
      <div>
        <b-button
          variant="outline-primary"
          @click="showAddWalletEntryWizard = true"
          v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
        >
          <b-icon-plus />&nbsp;Create Entry </b-button
        >&nbsp;
        <b-button
          variant="outline-primary"
          @click="backupClicked()"
          v-b-tooltip.hover.bottom="
            'Download all of your wallet and pool keys.'
          "
        >
          <font-awesome-icon :icon="['fas', 'file-archive']" />&nbsp;Save Backup
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
            {{ data.value.substring(0, 14) }}...
            <font-awesome-icon
              :icon="['fas', 'copy']"
              class="text-secondary"
              v-b-tooltip.hover.v-secondary.right="'Copy to clipboard'"
              @click="copyToClipboard(data.value)"
            />
          </template>
          <template v-slot:cell(paymentAddrLovelace)="data">
            <div class="text-right">
              {{ (data.value / 1000000) | currency("₳", 6) }}
              <font-awesome-icon
                :icon="['fas', 'hand-holding-usd']"
                class="text-success"
                v-if="
                  data.item.type != 'address' &&
                  data.item.type != 'pledge' &&
                  data.value > 0
                "
                v-b-tooltip.hover.v-success.bottom="'Send'"
                @click="$root.$emit('send-ada', walletItems[data.index])"
              />
            </div>
            <hr
              v-if="
                Object.keys(walletItems[data.index].nativeAssetMap).length > 0
              "
            />
            <div
              v-for="(name, index) in Object.keys(
                walletItems[data.index].nativeAssetMap
              ).sort()"
              :key="index"
            >
              {{ name.substring(0, name.indexOf(".")) }} -
              {{ walletItems[data.index].nativeAssetMap[name] }}
              <font-awesome-icon
                :icon="['fas', 'coins']"
                class="text-warning"
                v-b-tooltip.hover.v-warning.bottom="'Native Asset'"
              />
            </div>
          </template>
          <template v-slot:cell(stakingAddr)="data">
            <div v-if="data.value.length > 0">
              {{ data.value.substring(0, 15) }}... &nbsp;
              <font-awesome-icon
                :icon="['fas', 'link']"
                class="text-success"
                v-if="data.item.stakingAddrRegistered"
                v-b-tooltip.hover.v-success.bottom="
                  'Registered on chain. Click to de-register.'
                "
                @click="deregisterStakingAddress(walletItems[data.index])"
              />
              <font-awesome-icon
                :icon="['fas', 'unlink']"
                class="text-warning"
                v-if="!data.item.stakingAddrRegistered"
                v-b-tooltip.hover.v-warning.bottom="
                  'Not registered on chain. Click to register.'
                "
                @click="registerStakingAddress(walletItems[data.index])"
              />
            </div>
            <div v-else class="text-center">---</div>
          </template>
          <template v-slot:cell(stakingAddrLovelace)="data">
            <div class="text-right" v-if="data.item.type === 'stake'">
              {{ (data.value / 1000000) | currency("₳", 6) }}
              <font-awesome-icon
                :icon="['fas', 'cash-register']"
                class="text-success"
                v-if="data.value > 0"
                v-b-tooltip.hover.v-success.bottom="'Claim Rewards'"
                @click="$root.$emit('claim-ada', walletItems[data.index])"
              />
            </div>
            <div class="text-center" v-else>---</div>
          </template>
          <template v-slot:cell(edit)="data">
            <font-awesome-icon
              :icon="['fas', 'trash-alt']"
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
    <b-modal
      id="modal-staking-address"
      :title="stakingAddressModalTitle"
      no-close-on-backdrop
      size="lg"
      @ok="handleStakingAddress"
    >
      <b-form-group
        label="Fees Account"
        label-for="staking-fees-account-select"
        label-cols-md="2"
        label-align="right"
      >
        <b-form-select
          aria-describedby="staking-fees-account-live-feedback"
          v-model="stakingAddressForm.stakingFeesAccount"
          :options="stakingFeesSelectOptions($options.filters.currency)"
          :state="stakingFeesAccountState"
        >
          <template v-slot:first>
            <b-form-select-option :value="null" disabled
              >-- Please select an option --</b-form-select-option
            >
          </template>
        </b-form-select>
        <b-form-invalid-feedback id="staking-fees-account-live-feedback"
          >Account must hold enough to pay fees and/or
          deposit.</b-form-invalid-feedback
        >
      </b-form-group>
    </b-modal>
  </div>
</template>

<script>
import { mapState, mapActions, mapMutations, mapGetters } from "vuex";
import AddWalletEntryWizard from "@/components/AddWalletEntryWizard";
import SendAdaModal from "@/components/SendAdaModal";

export default {
  name: "Wallet",
  components: {
    AddWalletEntryWizard,
    SendAdaModal,
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
        { key: "edit", label: "" },
      ],
      showAddWalletEntryWizard: false,
      sendAdaFrom: null,
      stakingAddressModalTitle: null,
      stakingAddressForm: {
        id: -1,
        spendingPassword: null,
        stakingFeesAccount: null,
        isRegistration: false,
      },
    };
  },
  computed: {
    ...mapState(["walletItems"]),
    ...mapGetters(["stakingFeesSelectOptions"]),
    stakingFeesAccountState() {
      return this.stakingAddressForm.stakingFeesAccount != null;
    },
  },
  methods: {
    ...mapActions([
      "fetchWalletItems",
      "deleteWalletItem",
      "downloadBackup",
      "updateStakingAddress",
    ]),
    ...mapMutations(["toastInfo", "toastError"]),
    deleteItem(walletItem) {
      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.deleteWalletItem({
            id: walletItem.id,
            spendingPassword: spendingPassword,
          });
        }
      );
    },
    copyToClipboard(value) {
      this.$copyText(value).then(
        () => {
          this.toastInfo({
            title: "Payment Address",
            message: "Copied to clipboard",
          });
        },
        () => {
          this.toastError({
            title: "Payment Address",
            message: "Copy to clipboard failed.",
          });
        }
      );
    },
    backupClicked() {
      this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
        (spendingPassword) => {
          this.downloadBackup(spendingPassword);
        }
      );
    },
    registerStakingAddress(walletItem) {
      this.stakingAddressForm.id = walletItem.id;
      this.stakingAddressForm.isRegistration = true;
      this.stakingAddressModalTitle = "Register Staking Address";
      this.$bvModal.show("modal-staking-address");
    },
    deregisterStakingAddress(walletItem) {
      this.stakingAddressForm.id = walletItem.id;
      this.stakingAddressForm.isRegistration = false;
      this.stakingAddressModalTitle =
        "Deregister Staking Address (note: Ensure any 500 ada pool deposits have already been refunded)";
      this.$bvModal.show("modal-staking-address");
    },
    handleStakingAddress(bvModalEvt) {
      bvModalEvt.preventDefault();
      if (this.stakingFeesAccountState) {
        this.$root.$children[0].$refs.SpendingPasswordConfirmModal.show(
          async (spendingPassword) => {
            this.stakingAddressForm.spendingPassword = spendingPassword;
            this.updateStakingAddress(this.stakingAddressForm);
            this.stakingAddressForm.spendingPassword = null;
            this.$bvModal.hide("modal-staking-address");
          }
        );
      } else {
        this.toastError({
          title: "Error",
          message: "You must fill out all fields.",
        });
      }
    },
  },
  mounted() {
    this.fetchWalletItems();
  },
};
</script>

<style scoped>
.fa-copy:hover,
.fa-trash-alt:hover,
.fa-cash-register:hover,
.fa-hand-holding-usd:hover,
.fa-link:hover,
.fa-unlink:hover,
span.text-success:hover {
  cursor: pointer;
}
</style>