<template>
  <div>
    <b-modal
      id="modal-spending-password-confirm"
      title="Confirm spending password to continue"
      size="xs"
      no-close-on-backdrop
      ok-title="Confirm"
      ok-variant="danger"
      @ok="confirmClicked()"
    >
      <b-form-input
        id="spending-password-input"
        type="password"
        v-model="spendingPassword"
        @keydown.native="handleKeydown"
      />
    </b-modal>
  </div>
</template>

<script>
export default {
  name: "SpendingPasswordConfirm",
  data() {
    return {
      callback: null,
      spendingPassword: null,
    };
  },
  methods: {
    show(callback) {
      this.spendingPassword = null;
      this.callback = callback;
      this.$bvModal.show("modal-spending-password-confirm");
    },
    confirmClicked() {
      this.callback(this.spendingPassword);
      this.spendingPassword = null;
    },
    handleKeydown(event) {
      if (event.which === 13) {
        this.$bvModal.hide("modal-spending-password-confirm");
        this.confirmClicked();
      }
    },
  },
};
</script>