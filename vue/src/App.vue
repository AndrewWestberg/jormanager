<template>
  <div id="app" v-if="connected">
    <Header />
    <b-container>
      <router-view />
    </b-container>
    <SpendingPasswordConfirmModal ref="SpendingPasswordConfirmModal" />
  </div>
</template>

<script>
import Header from "@/components/Header";
import SpendingPasswordConfirmModal from "@/components/SpendingPasswordConfirmModal";
// import Body from "@/components/Body";

import { mapState, mapActions } from "vuex";

export default {
  name: "App",
  components: { Header, SpendingPasswordConfirmModal },
  methods: {
    ...mapActions(["connectToServer"]),
  },
  computed: {
    ...mapState([
      "connected",
      "toastError",
      "toastWarn",
      "toastInfo",
      "toastSuccess",
    ]),
  },
  watch: {
    toastError(toast) {
      // console.log("Error: " + toast);
      this.$root.$bvToast.toast(toast.message, {
        title: toast.title,
        noAutoHide: true,
        variant: "danger",
        appendToast: true,
      });
    },
    toastWarn(toast) {
      // console.log("Warn: " + toast);
      this.$root.$bvToast.toast(toast.message, {
        title: toast.title,
        variant: "warning",
        appendToast: true,
      });
    },
    toastInfo(toast) {
      this.$root.$bvToast.toast(toast.message, {
        title: toast.title,
        variant: "info",
        appendToast: true,
      });
    },
    toastSuccess(toast) {
      this.$root.$bvToast.toast(toast.message, {
        title: toast.title,
        variant: "success",
        appendToast: true,
      });
    },
  },
  mounted() {
    this.connectToServer();
  },
};
</script>

<style>
/*#app {*/
/*font-family: Inconsolata,monospace;*/
/*-webkit-font-smoothing: antialiased;*/
/*-moz-osx-font-smoothing: grayscale;*/
/*text-align: center;*/
/*color: #A69281;*/
/*background-color: #261B11;*/
/*
  Pallette:
  #591C4F
  #73296E
  #382640
  #261B11
  #A69281
  #4C8C6E
   */

/*}*/
</style>
