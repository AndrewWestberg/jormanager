import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import Nodes from '@/views/Nodes.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

const wrappers: { unmount: () => void }[] = []
afterEach(() => { wrappers.splice(0).forEach(item => item.unmount()); vi.restoreAllMocks() })
type NodeState = {
  poolChangeFeeAccount: number | null
  editMetadataForm: { name: string }
  retirePoolForm: { retireFeesAccount: number | null; retireEpoch: string | number }
  openMetadataModal: (nodeId: number) => void
  handleSaveMetadata: () => void
  openRetireModal: (nodeId: number) => void
  requestRetirement: () => void
  openSetup: (nodeId: number) => Promise<void>
  showAddNodeWizard: boolean
  setupContext: { nodeId: number } | null
}

function setup() {
  setActivePinia(createPinia())
  const store = useJorManagerStore()
  store.connected = true
  // The fixture only supplies fields the view reads.
  store.nodes = [{ id: 1, name: 'pool', type: 'core', hostId: 2 }] as unknown as typeof store.nodes
  store.nodeIndexStatus = { 1: { readState: { networkKey: 'test' } } } as unknown as typeof store.nodeIndexStatus
  store.networkReadStates = { test: { networkKey: 'test', walletReady: true } } as unknown as typeof store.networkReadStates
  vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
  const wrapper = shallowMount(Nodes, { global: { stubs: ['font-awesome-icon'], directives: { 'b-tooltip': {} } } })
  wrappers.push(wrapper)
  return { store, state: wrapper.vm as unknown as NodeState }
}

describe('direct pool actions', () => {
  it('submits editable metadata after one password and retains its id after failure', async () => {
    const { store, state } = setup()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('connection lost'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    state.openMetadataModal(1)
    store.editorMetadata = { nodeId: 1, metadata: { ticker: 'POOL', name: 'Pool', description: 'desc', homepage: 'https://pool.test' }, extendedMetadata: { info: { location: 'here', social: { twitterHandle: 'pool' } } } }
    await flushPromises()
    state.poolChangeFeeAccount = 7
    state.handleSaveMetadata()
    const first = prompt.mock.calls[0][0]
    expect(first.data.metadata.extended.info.social.twitter).toBe('pool')
    expect(first.data.changes).toEqual({})
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    expect(socket.mock.calls[0][0]).toBe('/jormanager/leios/registration/submit')
    expect(socket.mock.calls[0][2]).toMatchObject({ spendingPassword: 'secret' })
    state.handleSaveMetadata()
    expect(prompt.mock.calls[1][0].data.requestId).toBe(first.data.requestId)
    state.editMetadataForm.name = 'Changed'
    state.handleSaveMetadata()
    expect(prompt.mock.calls[2][0].data.requestId).not.toBe(first.data.requestId)
    emitter.off('show-spending-password-modal', prompt)
  })

  it('dispatches retirement without wallet coverage readiness', async () => {
    const { store, state } = setup()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('Rejected'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    state.openRetireModal(1)
    state.retirePoolForm.retireFeesAccount = 7
    state.retirePoolForm.retireEpoch = '9007199254740993'
    state.requestRetirement()
    const request = prompt.mock.calls[0][0]
    expect(request.data.retireEpoch).toBe('9007199254740993')
    store.networkInvalidations.test = 1
    emitter.emit('confirm-spending-password-with-action', { action: request.action, originalData: request.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledWith('/jormanager/pool/retirement/submit', 'pool_retirement_submit', expect.objectContaining({ nodeId: 1, spendingPassword: 'secret' }))
    emitter.off('show-spending-password-modal', prompt)
  })

  it('opens the same wizard with the matching incomplete setup context', async () => {
    const { store, state } = setup()
    store.nodes = [{ id: 1, name: 'pool', type: 'core', hostId: 2, isDraft: true }] as unknown as typeof store.nodes
    const context = { requestId: 'query', setupRequestId: 'setup', nodeId: 1, hostId: 2, request: {}, keys: [] }
    const socket = vi.spyOn(store, 'socketRequest').mockResolvedValue(context)
    await state.openSetup(1)
    expect(socket).toHaveBeenCalledWith('/jormanager/node/setup-context', 'node_setup_context', { nodeId: 1 })
    expect(state.showAddNodeWizard).toBe(true)
    expect(state.setupContext).toEqual(context)
  })
})
