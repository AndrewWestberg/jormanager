import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import AddNodeWizard from '@/components/AddNodeWizard.vue'
import { useJorManagerStore } from '@/stores/jormanager'

const files = [
  'musashi-byron-genesis.json', 'musashi-shelley-genesis.json', 'musashi-alonzo-genesis.json',
  'musashi-conway-genesis.json', 'musashi-dijkstra-genesis.json', 'musashi-config.json',
  'musashi-topology.json', 'musashi-peer-snapshot.json', 'other-config.json'
].map((text, index) => ({ value: index + 1, text }))
type WizardState = {
  formNode: Record<string, unknown>
  validateStep1: () => boolean
}


describe('node file selection', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('permits a first relay with independently selected files and clearable optional files', async () => {
    const store = useJorManagerStore()
    store.files = files
    vi.spyOn(store, 'requestHosts').mockImplementation(() => {})
    vi.spyOn(store, 'requestFileOptions').mockImplementation(() => {})
    const wrapper = shallowMount(AddNodeWizard, { global: { stubs: ['font-awesome-icon'] } })
    const state = wrapper.vm as unknown as WizardState
    Object.assign(state.formNode, { host: 1, name: 'relay', type: 'relay', listen: '0.0.0.0', port: 3001, processorThreads: 2, genesisByron: 1 })
    await nextTick()
    expect(state.formNode.genesisShelley).toBe(2)
    expect(state.formNode.config).toBe(6)
    state.formNode.config = 9
    state.formNode.genesisDijkstra = null
    state.formNode.peerSnapshot = null
    expect(state.validateStep1()).toBe(true)
    wrapper.unmount()
  })

  it('requires config and topology for a standalone node', async () => {
    const store = useJorManagerStore()
    vi.spyOn(store, 'requestHosts').mockImplementation(() => {})
    vi.spyOn(store, 'requestFileOptions').mockImplementation(() => {})
    const wrapper = shallowMount(AddNodeWizard, { global: { stubs: ['font-awesome-icon'] } })
    const state = wrapper.vm as unknown as WizardState
    Object.assign(state.formNode, { host: 1, name: 'relay', type: 'relay', listen: '0.0.0.0', port: 3001, processorThreads: 2, genesisByron: 1, genesisShelley: 2, genesisAlonzo: 3, genesisConway: 4 })
    await nextTick()
    expect(state.validateStep1()).toBe(false)
    state.formNode.config = 6
    expect(state.validateStep1()).toBe(false)
    state.formNode.topology = 7
    expect(state.validateStep1()).toBe(true)
    wrapper.unmount()
  })

})
