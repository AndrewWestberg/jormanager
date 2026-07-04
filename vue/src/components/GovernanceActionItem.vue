<template>
  <div class="governance-action-item">
    <div v-if="metadataError" class="alert alert-warning">
      Failed to resolve metadata. 
      <a :href="adastatLink" target="_blank" rel="noopener noreferrer">View on adastat.net</a>
    </div>
    <div v-else>
      <!-- Metadata content -->
      <a :href="adastatLink" target="_blank" rel="noopener noreferrer" class="small text-muted">
        View on adastat.net
      </a>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  action: {
    actionId: string
  }
}>()

const metadataError = computed(() => false) // Mocking logic for now

const adastatLink = computed(() => {
  return `https://adastat.net/governance/${props.action.actionId}`
})
</script>
