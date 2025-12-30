<template>
  <div>
    <div>
      <BButton
        variant="outline-primary"
        @click="addHostClick"
        v-b-tooltip.hover.bottom="'Add a connection to a new remote or local server.'"
      >
        +&nbsp;Host
      </BButton>
    </div>
    <hr />
    <div>
      <BTable
        small
        hover
        dark
        bordered
        striped
        head-variant="light"
        :items="hosts"
        :fields="fields"
        v-if="hosts.length > 0"
      >
        <template #cell(sshPort)="data">
          <div v-if="data.item.type === 'remote'">{{ data.value }}</div>
          <div v-else>---</div>
        </template>
        <template #cell(edit)="data">
          <div
            class="text-warning edit-icon d-inline-block"
            v-b-tooltip.hover.right="{ title: 'Edit this Host', variant: 'warning' }"
            @click="editHostClick(data.index)"
          >
            <font-awesome-icon :icon="['fas', 'edit']" />
          </div>
        </template>
      </BTable>
    </div>

    <AddEditHostModal />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { BButton, BTable } from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import AddEditHostModal from '@/components/AddEditHostModal.vue'

const store = useJorManagerStore()
const { hosts } = storeToRefs(store)
const emitter = useEventBus()

const fields = [
  { key: 'hostname', sortable: true },
  { key: 'type', sortable: true },
  { key: 'sshUser', label: 'User' },
  { key: 'sshPort' },
  { key: 'edit', label: '' }
]

function addHostClick() {
  emitter.emit('add-host')
}

function editHostClick(index: number) {
  emitter.emit('edit-host', hosts.value[index])
}

onMounted(() => {
  store.requestHosts()
})
</script>

<style scoped>
.edit-icon:hover {
  cursor: pointer;
}
</style>