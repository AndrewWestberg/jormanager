<template>
  <div>
    <b-modal
      id="modal-edit-host"
      title="Add/Edit Host"
      scrollable
      no-close-on-backdrop
      ok-title="Validate &amp; Save"
      @ok="handleValidateAndSave"
    >
      <b-form ref="editHostForm" @submit.stop.prevent="handleValidateAndSave">
        <b-form-group label="Type" label-for="type-radio">
          <b-form-radio-group id="type-radio" v-model="formHost.type" required>
            <b-form-radio value="local">Local</b-form-radio>
            <b-form-radio value="remote">Remote</b-form-radio>
          </b-form-radio-group>
        </b-form-group>
        <b-form-group label="Hostname" label-for="hostname-input">
          <b-form-input
            id="hostname-input"
            placeholder="e.g. 'server.mystakepool.io' or '192.168.0.77'"
            v-model="formHost.hostname"
            required
          />
        </b-form-group>
        <b-form-group label="SSH Port" label-for="ssh-port-input" v-if="isFormRemote">
          <b-form-input
            id="ssh-port-input"
            type="number"
            placeholder="e.g. 22"
            v-model="formHost.sshPort"
            :required="isFormRemote"
          />
        </b-form-group>
        <b-form-group :label="userFormLabel" label-for="ssh-user-input">
          <b-form-input
            id="ssh-user-input"
            placeholder="e.g. ec2-user"
            v-model="formHost.sshUser"
            :required="isFormRemote"
          />
        </b-form-group>
        <b-form-group label="SSH Key" label-for="ssh-key-input" v-if="isFormRemote">
          <b-form-input
            id="ssh-key-input"
            placeholder="e.g. /home/<username>/.ssh/id_rsa"
            v-model="formHost.sshPemPath"
            :required="isFormRemote"
          />
        </b-form-group>
        <b-form-group label="cardano-cli Path" label-for="cardano-cli-input">
          <b-form-input
            id="cardano-cli-input"
            placeholder="e.g. /home/<username>/.local/bin/cardano-cli"
            v-model="formHost.cardanoCliPath"
            required
          />
        </b-form-group>
        <b-form-group label="cardano-node Path" label-for="cardano-node-input">
          <b-form-input
            id="cardano-node-input"
            placeholder="e.g. /home/<username>/.local/bin/cardano-node"
            v-model="formHost.cardanoNodePath"
            required
          />
        </b-form-group>
        <b-form-group label="Home Folder for Nodes" label-for="node-home-path-input">
          <b-form-input
            id="node-home-path-input"
            placeholder="e.g. /home/<username>/haskell"
            v-model="formHost.nodeHomePath"
            required
          />
        </b-form-group>
      </b-form>
    </b-modal>
  </div>
</template>

<script>
import _ from "lodash";
import { mapActions, mapState } from "vuex";
export default {
  name: "AddEditHostModal",
  data() {
    return {
      formHost: {}
    };
  },
  methods: {
    ...mapActions(["addHost", "requestHosts"]),
    clearFormHost() {
      this.formState = null;
      this.formHost = {
        type: "remote",
        hostname: "",
        sshUser: "",
        sshPort: 22,
        sshPemPath: "",
        cardanoCliPath: "",
        cardanoNodePath: "",
        nodeHomePath: ""
      };
    },
    checkFormValidity() {
      const valid = this.$refs.editHostForm.checkValidity();
      return valid;
    },
    clickAddHost() {
      this.clearFormHost();
      this.$bvModal.show("modal-edit-host");
    },
    clickEditHost(host) {
      this.clearFormHost();
      this.formHost = _.cloneDeep(host);
      this.$bvModal.show("modal-edit-host");
    },
    handleValidateAndSave(bvModalEvt) {
      bvModalEvt.preventDefault();
      this.handleSubmitAddHost();
    },
    handleSubmitAddHost() {
      if (!this.checkFormValidity()) {
        return;
      }

      this.addHost(this.formHost);
    }
  },
  computed: {
    ...mapState(["toastSuccess"]),
    isFormRemote() {
      return this.formHost.type === "remote";
    },
    userFormLabel() {
      return this.isFormRemote ? "SSH User" : "User";
    }
  },
  watch: {
    toastSuccess(toast) {
      if (toast.title === "Host Saved") {
        this.$bvModal.hide("modal-edit-host");
        this.clearFormHost();
        this.requestHosts();
      }
    }
  },
  mounted() {
    this.clearFormHost();
    this.$root.$on("edit-host", host => {
      // received edit host message from parent component
      this.clickEditHost(host);
    });
    this.$root.$on("add-host", () => {
      // received add host message from parent component
      this.clickAddHost();
    });
  }
};
</script>