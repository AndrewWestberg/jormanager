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
  editColorForm: { nodeId: number; color: string }
  editRelaysForm: { nodeId: number; relays: { addr: string; port: number }[] }
  retirePoolForm: { retireFeesAccount: number | null; retireEpoch: string | number }
  showColorModal: boolean
  hasCoreNodes: boolean
  openColorModal: (nodeId: number, currentColor: string) => void
  handleSaveColor: () => void
  openRelaysModal: (nodeId: number) => void
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
  it('saves node color directly without prompting for spending password', () => {
    const { store, state } = setup()
    const updateColor = vi.spyOn(store, 'updateNodeColor').mockImplementation(() => {})
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    try {
      state.openColorModal(1, '#112233')
      expect(state.showColorModal).toBe(true)
      state.editColorForm.color = '#abcdef'
      state.handleSaveColor()
      expect(updateColor).toHaveBeenCalledWith({ id: 1, color: '#abcdef' })
      expect(state.showColorModal).toBe(false)
      expect(prompt).not.toHaveBeenCalled()
    } finally {
      emitter.off('show-spending-password-modal', prompt)
    }
  })

  it('pre-populates relays modal from node relays and refreshes nodes after pool update succeeds', async () => {
    const { store, state } = setup()
    store.nodes = [{
      id: 1,
      name: 'pool',
      type: 'core',
      hostId: 2,
      relays: [{ id: 9, nodeId: 1, addr: 'relay1.pool.test', port: 3001 }, { id: 10, nodeId: 1, addr: '1.2.3.4', port: 3002 }]
    }] as unknown as typeof store.nodes
    state.openRelaysModal(1)
    expect(state.editRelaysForm.relays).toEqual([
      { addr: 'relay1.pool.test', port: 3001 },
      { addr: '1.2.3.4', port: 3002 }
    ])

    const requestNodes = vi.spyOn(store, 'requestNodes').mockImplementation(() => {})
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    try {
      state.openMetadataModal(1)
      store.editorMetadata = {
        nodeId: 1,
        metadata: { ticker: 'POOL', name: 'Pool', description: 'desc', homepage: 'https://pool.test' },
        extendedMetadata: { info: { location: 'here', social: { twitterHandle: 'pool' } } }
      }
      await flushPromises()
      state.poolChangeFeeAccount = 7
      state.handleSaveMetadata()
      const pending = prompt.mock.calls[0][0]
      vi.spyOn(store, 'socketRequest').mockResolvedValue({
        requestId: pending.data.requestId,
        nodeId: 1,
        action: 'update',
        state: 'submitted',
        txId: 'tx123'
      })
      emitter.emit('confirm-spending-password-with-action', {
        action: pending.action,
        originalData: pending.data,
        password: 'secret'
      })
      await flushPromises()
      expect(requestNodes).toHaveBeenCalled()
    } finally {
      emitter.off('show-spending-password-modal', prompt)
    }
  })

  it('excludes draft, deleted, and relay nodes from hasCoreNodes', () => {
    const { store, state } = setup()
    store.nodes = [
      { id: 1, name: 'relay-1', type: 'relay', hostId: 2 },
      { id: 2, name: 'draft-core', type: 'core', hostId: 2, isDraft: true },
      { id: 3, name: 'deleted-pool', type: 'pool', hostId: 2, isDeleted: true }
    ] as unknown as typeof store.nodes
    expect(state.hasCoreNodes).toBe(false)

    store.nodes = [
      ...store.nodes,
      { id: 4, name: 'active-pool', type: 'pool', hostId: 2, isDraft: false, isDeleted: false }
    ] as unknown as typeof store.nodes
    expect(state.hasCoreNodes).toBe(true)
  })
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
