<template>
  <div>
    <div>
      <b-button
        variant="outline-primary"
        @click="$root.$emit('add-wallet-entry')"
        v-b-tooltip.hover.bottom="'Create a new wallet entry.'"
      >
        <b-icon-plus />&nbsp;Create Entry
      </b-button>
    </div>
    <hr />
  </div>
</template>

<script>
export default {};
</script>