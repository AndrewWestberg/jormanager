<template>
  <div>
    <h1>Hosts!</h1>

    <b-button variant="danger" @click="error()">Show Error</b-button>
  </div>
</template>

<script>
export default {
  name: "Hosts",
  methods: {
    error() {
      this.$root.$bvToast.toast("This is an error message!", {
        title: "Error!",
        // autoHideDelay: 5000,
        variant: "danger",
        appendToast: true
      });
    }
  }
};
</script>