<template>
  <div id="header">
    <b-navbar>
      <b-nav tabs>
        <b-nav-item disabled>
          <b>{{ appVersion }}</b>
        </b-nav-item>
        <b-nav-item to="/" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'chart-line']" />&nbsp;Dashboard
        </b-nav-item>
        <b-nav-item to="/hosts" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'server']" />&nbsp;Hosts
        </b-nav-item>
        <b-nav-item to="/nodes" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'dice-d20']" />&nbsp;Nodes
        </b-nav-item>
        <b-nav-item to="/blocks" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'cubes']" />&nbsp;Blocks
        </b-nav-item>
        <b-nav-item to="/wallet" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'wallet']" />&nbsp;Wallet
        </b-nav-item>
      </b-nav>
    </b-navbar>
  </div>
</template>

<script>
import { mapState, mapActions } from "vuex";

export default {
  name: "Header",
  computed: {
    ...mapState(["appVersion"]),
  },
  methods: {
    ...mapActions(["requestAppVersion"]),
  },
  mounted() {
    this.requestAppVersion();
  },
};
</script>

<style>
/*#header {*/
/*    background-color: #382640;*/
/*}*/
/*.nav-tabs a.nav-link.disabled {*/
/*    color: #A69281;*/
/*}*/
/*.nav-tabs a.nav-link.active {*/
/*    color: #261B11;*/
/*}*/
/*.nav-tabs a.nav-link {*/
/*    color: #4C8C6E;*/
/*}*/
</style>