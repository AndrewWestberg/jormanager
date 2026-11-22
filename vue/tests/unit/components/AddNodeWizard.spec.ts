import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import AddNodeWizard from '@/components/AddNodeWizard.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

const files = [
  'musashi-byron-genesis.json', 'musashi-shelley-genesis.json', 'musashi-alonzo-genesis.json',
  'musashi-conway-genesis.json', 'musashi-dijkstra-genesis.json', 'musashi-config.json',
  'musashi-topology.json', 'musashi-peer-snapshot.json', 'other-config.json'
].map((text, index) => ({ value: index + 1, text }))
type WizardState = {
  formNode: Record<string, unknown>
  validateStep1: () => boolean
  onComplete: () => Promise<void>
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

  it('submits once after password and retains the request id after a lost reply', async () => {
    const store = useJorManagerStore()
    store.connected = true
    vi.spyOn(store, 'requestHosts').mockImplementation(() => {})
    vi.spyOn(store, 'requestFileOptions').mockImplementation(() => {})
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('connection lost'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    const wrapper = shallowMount(AddNodeWizard, { global: { stubs: ['font-awesome-icon'] } })
    const state = wrapper.vm as unknown as WizardState
    Object.assign(state.formNode, { host: 1, name: 'relay', type: 'relay', listen: '0.0.0.0', port: 3001, processorThreads: 2, genesisByron: 1, genesisShelley: 2, genesisAlonzo: 3, genesisConway: 4, config: 6, topology: 7 })
    await state.onComplete()
    expect(prompt).toHaveBeenCalledTimes(1)
    emitter.emit('confirm-spending-password-with-action', { action: 'create-node', password: 'secret', originalData: prompt.mock.lastCall![0].data })
    emitter.emit('confirm-spending-password-with-action', { action: 'create-node', password: 'secret', originalData: prompt.mock.lastCall![0].data })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    const first = socket.mock.calls[0][2]
    expect(first.spendingPassword).toBe('secret')
    expect(wrapper.emitted('hide-add-node-wizard')).toBeUndefined()
    await state.onComplete()
    emitter.emit('confirm-spending-password-with-action', { action: 'create-node', password: 'secret', originalData: prompt.mock.lastCall![0].data })
    await flushPromises()
    expect(socket.mock.calls[1][2].requestId).toBe(first.requestId)
    emitter.off('show-spending-password-modal', prompt)
    wrapper.unmount()
  })

  it('submits core creation without draft resume fields and closes wizard on success', async () => {
    const store = useJorManagerStore()
    store.connected = true
    vi.spyOn(store, 'requestHosts').mockImplementation(() => {})
    vi.spyOn(store, 'requestFileOptions').mockImplementation(() => {})
    const socket = vi.spyOn(store, 'socketRequest').mockImplementation(async (_dest, _type, payload) => ({
      requestId: payload.requestId as string,
      nodeId: 42,
      message: 'Pool created!'
    }))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    const wrapper = shallowMount(AddNodeWizard, { global: { stubs: ['font-awesome-icon'] } })
    const state = wrapper.vm as unknown as WizardState
    Object.assign(state.formNode, {
      host: 1, name: 'pool', type: 'core', listen: '0.0.0.0', port: 3001, processorThreads: 2,
      genesisByron: 1, genesisShelley: 2, genesisAlonzo: 3, genesisConway: 4, config: 6, topology: 7,
      registrationFeesAccount: 1, ownerStakingAccount: 2, rewardsStakingAccount: 3,
      poolPledge: 0, poolCost: 340, poolMargin: 0.05,
      metadata: {
        ticker: 'POOL', name: 'Pool', description: 'Pool', homepage: 'https://pool.test',
        extended: {
          itn: { publicKey: null, privateKey: null },
          info: {
            icon64: 'https://pool.test/icon.png', logo: 'https://pool.test/logo.png', location: null,
            social: { twitter: null, telegram: null, facebook: null, youtube: null, twitch: null, discord: null, github: null },
            company: { name: null, addr: null, city: null, country: null, company_id: null, vat_id: null },
            about: { me: null, server: null, company: null },
            rss: null
          },
          telegramAdminHandle: null
        }
      }
    })
    await nextTick()
    await state.onComplete()
    expect(prompt).toHaveBeenCalledTimes(1)
    emitter.emit('confirm-spending-password-with-action', { action: 'create-node', password: 'secret', originalData: prompt.mock.lastCall![0].data })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    const payload = socket.mock.calls[0][2]
    expect(payload).not.toHaveProperty('resumeNodeId')
    expect(payload).not.toHaveProperty('keyId')
    expect(wrapper.emitted('hide-add-node-wizard')).toHaveLength(1)
    emitter.off('show-spending-password-modal', prompt)
    wrapper.unmount()
  })
})
