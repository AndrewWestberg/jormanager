<template>
  <div id="header">
    <BNavbar>
      <BNav tabs>
        <BNavItem disabled>
          <b>{{ appVersion }}</b>
        </BNavItem>
        <BNavItem to="/" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'chart-line']" />&nbsp;Dashboard
        </BNavItem>
        <BNavItem to="/hosts" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'server']" />&nbsp;Hosts
        </BNavItem>
        <BNavItem to="/nodes" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'dice-d20']" />&nbsp;Nodes
        </BNavItem>
        <BNavItem to="/blocks" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'cubes']" />&nbsp;Blocks
        </BNavItem>
        <BNavItem to="/wallet" exact exact-active-class="active">
          <font-awesome-icon :icon="['fas', 'wallet']" />&nbsp;Wallet
        </BNavItem>
      </BNav>
    </BNavbar>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { BNavbar, BNav, BNavItem } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'

const store = useJorManagerStore()
const { appVersion } = storeToRefs(store)

onMounted(() => {
  store.requestAppVersion()
})
</script>
