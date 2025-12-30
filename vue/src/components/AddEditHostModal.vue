<template>
  <div>
  <BModal
      id="modal-edit-host"
      v-model="isVisible"
      title="Add/Edit Host"
      :no-close-on-backdrop="true"
      ok-title="Validate &amp; Save"
      @ok="handleSubmitAddHost"
    >
      <BForm ref="editHostFormRef" @submit.stop.prevent="handleValidateAndSave">
        <!-- Form Fields -->
        <BFormGroup label="Type" label-for="type-radio">
          <BFormRadioGroup id="type-radio" v-model="formHost.type" required>
            <BFormRadio value="local">Local</BFormRadio>
            <BFormRadio value="remote">Remote</BFormRadio>
          </BFormRadioGroup>
        </BFormGroup>
        <BFormGroup label="Hostname" label-for="hostname-input">
          <BFormInput
            id="hostname-input"
            placeholder="e.g. 'server.mystakepool.io' or '192.168.0.77'"
            v-model="formHost.hostname"
            required
          />
        </BFormGroup>
        <BFormGroup v-if="isFormRemote" label="SSH Port" label-for="ssh-port-input">
          <BFormInput
            id="ssh-port-input"
            type="number"
            placeholder="e.g. 22"
            v-model="formHost.sshPort"
            :required="isFormRemote"
          />
        </BFormGroup>
        <BFormGroup :label="userFormLabel" label-for="ssh-user-input">
          <BFormInput
            id="ssh-user-input"
            placeholder="e.g. ec2-user"
            v-model="formHost.sshUser"
            :required="isFormRemote"
          />
        </BFormGroup>
        <BFormGroup v-if="isFormRemote" label="SSH Key" label-for="ssh-key-input">
          <BFormInput
            id="ssh-key-input"
            placeholder="e.g. /home/<username>/.ssh/id_rsa"
            v-model="formHost.sshPemPath"
            :required="isFormRemote"
          />
        </BFormGroup>
        <BFormGroup label="cardano-cli Path" label-for="cardano-cli-input">
          <BFormInput
            id="cardano-cli-input"
            placeholder="e.g. /home/<username>/.local/bin/cardano-cli"
            v-model="formHost.cardanoCliPath"
            required
          />
        </BFormGroup>
        <BFormGroup label="cardano-node Path" label-for="cardano-node-input">
          <BFormInput
            id="cardano-node-input"
            placeholder="e.g. /home/<username>/.local/bin/cardano-node"
            v-model="formHost.cardanoNodePath"
            required
          />
        </BFormGroup>
        <BFormGroup label="Home Folder for Nodes" label-for="node-home-path-input">
          <BFormInput
            id="node-home-path-input"
            placeholder="e.g. /home/<username>/haskell"
            v-model="formHost.nodeHomePath"
            required
          />
        </BFormGroup>
        <BFormGroup label="ITN JCLI Path (Optional)" label-for="jcli-input">
          <BFormInput
            id="jcli-input"
            placeholder="e.g. /home/<username>/.cargo/bin/jcli"
            v-model="formHost.jcliPath"
          />
        </BFormGroup>
      </BForm>
    </BModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { cloneDeep } from 'lodash-es'
import {
  BModal,
  BForm,
  BFormGroup,
  BFormInput,
  BFormRadioGroup,
  BFormRadio
} from 'bootstrap-vue-next'
import { useJorManagerStore } from '@/stores/jormanager'
import { useEventBus } from '@/composables/useEventBus'
import type { Host } from '@/types'

const store = useJorManagerStore()
const { toastSuccess } = storeToRefs(store)
const emitter = useEventBus()

const editHostFormRef = ref<InstanceType<typeof BForm> | null>(null)
const isVisible = ref(false)

interface HostForm {
  type: 'local' | 'remote'
  hostname: string
  sshUser: string
  sshPort: number
  sshPemPath: string
  cardanoCliPath: string
  cardanoNodePath: string
  nodeHomePath: string
  jcliPath: string | null
}

const defaultFormHost: HostForm = {
  type: 'remote',
  hostname: '',
  sshUser: '',
  sshPort: 22,
  sshPemPath: '',
  cardanoCliPath: '',
  cardanoNodePath: '',
  nodeHomePath: '',
  jcliPath: null
}

const formHost = ref<HostForm>({ ...defaultFormHost })

const isFormRemote = computed(() => formHost.value.type === 'remote')
const userFormLabel = computed(() => isFormRemote.value ? 'SSH User' : 'User')

function clearFormHost() {
  formHost.value = { ...defaultFormHost }
}

function checkFormValidity(): boolean {
  if (!editHostFormRef.value) return false
  const formEl = (editHostFormRef.value as any).element || editHostFormRef.value
  if (formEl.checkValidity) {
    return formEl.checkValidity()
  }
  return true
}

function reportFormValidity() {
  if (!editHostFormRef.value) return
  const formEl = (editHostFormRef.value as any).element || editHostFormRef.value
  if (formEl.reportValidity) {
    formEl.reportValidity()
  }
}

function clickAddHost() {
  clearFormHost()
  isVisible.value = true
}

function clickEditHost(host: Host) {
  clearFormHost()
  formHost.value = cloneDeep(host) as HostForm
  isVisible.value = true
}

function handleValidateAndSave(event?: Event) {
  if (event) event.preventDefault()
  
  // validation logic
  if (!checkFormValidity()) {
    reportFormValidity()
    return
  }
  store.addHost(formHost.value as Host)
}

function handleSubmitAddHost(event: any) {
  // Prevent modal from closing automatically
  event.preventDefault()
  handleValidateAndSave()
}

// Watch for successful save to close modal
watch(toastSuccess, (toast) => {
  if (toast?.title === 'Host Saved') {
    isVisible.value = false
    clearFormHost()
    store.requestHosts()
  }
})

onMounted(() => {
  clearFormHost()
  emitter.on('edit-host', (host) => {
    clickEditHost(host as Host)
  })
  emitter.on('add-host', () => {
    clickAddHost()
  })
})

onUnmounted(() => {
  emitter.off('edit-host')
  emitter.off('add-host')
})
</script>