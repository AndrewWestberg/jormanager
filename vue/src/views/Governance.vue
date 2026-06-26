<template>
  <div class="governance-dashboard">
    <h2>Governance Dashboard</h2>
    
    <BTabs>
      <BTab title="Active Proposals" active>
        <div v-for="action in activeActions" :key="action.id" class="mb-3 border p-3">
          <h4>{{ action.actionId }}</h4>
          <p>Status: {{ action.status }}</p>
          <GovernanceActionItem :action="action" />
          <BButton variant="primary" @click="openVoteModal(action)">Vote</BButton>
          <BButton variant="secondary" @click="openVoteModal(action)" class="ms-2" v-if="action.voted">Change Vote</BButton>
        </div>
      </BTab>
      
      <BTab title="History">
        <div v-for="action in historyActions" :key="action.id" class="mb-3 border p-3 text-muted">
          <h4>{{ action.actionId }}</h4>
          <p>Status: {{ action.status }}</p>
          <GovernanceActionItem :action="action" />
        </div>
      </BTab>

      <BTab title="Registration">
        <GovernanceRegistration />
      </BTab>
    </BTabs>

    <GovernanceVoteModal v-model="showVoteModal" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { BTabs, BTab, BButton } from 'bootstrap-vue-next'
import GovernanceActionItem from '@/components/GovernanceActionItem.vue'
import GovernanceRegistration from '@/components/GovernanceRegistration.vue'
import GovernanceVoteModal from '@/components/GovernanceVoteModal.vue'

const showVoteModal = ref(false)

// Mocked data for dashboard
const actions = ref([
  { id: 1, actionId: 'gov_action1...', status: 'Active', voted: false },
  { id: 2, actionId: 'gov_action2...', status: 'Expired', voted: true }
])

const activeActions = computed(() => actions.value.filter(a => a.status === 'Active'))
const historyActions = computed(() => actions.value.filter(a => a.status !== 'Active'))

function openVoteModal(_action: any) {
  showVoteModal.value = true
}
</script>
