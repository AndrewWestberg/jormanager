import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import LeiosKeyPanel from '@/components/LeiosKeyPanel.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

const wrappers: { unmount: () => void }[] = []
afterEach(() => { wrappers.splice(0).forEach(item => item.unmount()); vi.restoreAllMocks() })

type Panel = { registrationAction: 'register' | 'rotate' | 'cancel'; registrationPayer: number; registrationKeyId: number; requestRegistration: () => void }
function setup() {
  setActivePinia(createPinia())
  const store = useJorManagerStore()
  store.connected = true
  // The fixture only supplies fields the panel reads.
  store.nodes = [{ id: 1, name: 'pool', type: 'core', hostId: 2, poolId: 'pool1' }] as unknown as typeof store.nodes
  store.networkReadStates = { test: { networkKey: 'test', walletReady: true } } as unknown as typeof store.networkReadStates
  store.leiosStatus = { target: { nodeId: 1, hostId: 2, networkKey: 'test' }, era: 'dijkstra', readState: { status: 'complete' }, keys: [{ keyId: 9, publicKeyHex: 'key', proofValidated: true }] }
  vi.spyOn(store, 'requestLeiosStatus').mockImplementation(() => {})
  const wrapper = shallowMount(LeiosKeyPanel, { props: { nodeId: 1 } })
  wrappers.push(wrapper)
  return { store, state: wrapper.vm as unknown as Panel }
}

describe('direct key registration', () => {
  it('authorizes rotation once and retains the request id after a lost response', async () => {
    const { store, state } = setup()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('connection lost'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    state.registrationAction = 'rotate'
    state.registrationPayer = 3
    state.registrationKeyId = 9
    state.requestRegistration()
    const first = prompt.mock.calls[0][0]
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    expect(socket.mock.calls[0][0]).toBe('/jormanager/leios/registration/submit')
    expect(socket.mock.calls[0][2]).toMatchObject({ requestId: first.data.requestId, nodeId: 1, action: 'rotate', keyId: 9, spendingPassword: 'secret' })
    state.requestRegistration()
    expect(prompt.mock.calls[1][0].data.requestId).toBe(first.data.requestId)
    state.registrationAction = 'cancel'
    state.requestRegistration()
    expect(prompt.mock.calls[2][0].data).toMatchObject({ action: 'cancel', keyId: null })
    expect(prompt.mock.calls[2][0].data.requestId).not.toBe(first.data.requestId)
    emitter.off('show-spending-password-modal', prompt)
  })

  it('dispatches registration without wallet coverage readiness', async () => {
    const { store, state } = setup()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('Rejected'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    state.registrationAction = 'cancel'
    state.registrationPayer = 3
    state.requestRegistration()
    const request = prompt.mock.calls[0][0]
    store.networkInvalidations.test = 1
    emitter.emit('confirm-spending-password-with-action', { action: request.action, originalData: request.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledWith('/jormanager/leios/registration/submit', 'leios_registration_submitted', expect.objectContaining({ nodeId: 1, action: 'cancel', spendingPassword: 'secret' }))
    emitter.off('show-spending-password-modal', prompt)
  })
})

describe('direct key actions', () => {
  function mounted() {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.connected = true
    store.nodes = [{ id: 1, name: 'pool', type: 'core', hostId: 2, poolId: 'pool1' }] as unknown as typeof store.nodes
    store.leiosStatus = { target: { nodeId: 1, hostId: 2, networkKey: 'test' }, era: 'dijkstra',
      readState: { status: 'complete' }, keys: [{ keyId: 9, publicKeyHex: 'key', proofValidated: true }] }
    vi.spyOn(store, 'requestLeiosStatus').mockImplementation(() => {})
    const wrapper = mount(LeiosKeyPanel, { props: { nodeId: 1 }, global: {
      stubs: { BButton: { template: '<button :disabled="$attrs.disabled"><slot /></button>' } }
    } })
    wrappers.push(wrapper)
    return { store, wrapper }
  }

  it('shows factual empty history without workflow guidance', async () => {
    const { store, wrapper } = mounted()
    store.leiosStatus = { target: { nodeId: 1, hostId: 2, networkKey: 'test' }, era: 'dijkstra',
      readState: { status: 'unavailable' }, keys: [] }
    await flushPromises()
    expect(wrapper.text()).toContain('No public keys returned.')
    expect(wrapper.text()).not.toContain('Review key installation')
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
  })

  it('prepares with one prompt and displays the acknowledged key', async () => {
    const { store, wrapper } = mounted()
    const socket = vi.spyOn(store, 'socketRequest').mockImplementation(async (_destination, _type, payload) => ({
      requestId: payload.requestId, target: payload.target, key: { keyId: 10 }
    }) as never)
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    await wrapper.findAll('button').find(button => button.text() === 'Prepare key')!.trigger('click')
    await flushPromises()
    const request = prompt.mock.calls[0][0]
    emitter.emit('confirm-spending-password-with-action', { action: request.action, originalData: request.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    expect(socket.mock.calls[0][0]).toBe('/jormanager/leios/key/prepare')
    expect(socket.mock.calls[0][2]).toMatchObject({ requestId: request.data, intent: 'prepare', spendingPassword: 'secret' })
    expect(wrapper.text()).toContain('Key 10 prepared.')
    emitter.off('show-spending-password-modal', prompt)
  })

  it('imports once with one password and changes request id after a form edit', async () => {
    const { store, wrapper } = mounted()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('connection lost'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    await wrapper.get('#leios-signing-key').setValue('private envelope')
    await wrapper.findAll('button').find(button => button.text() === 'Import key')!.trigger('click')
    await flushPromises()
    const first = prompt.mock.calls[0][0]
    expect(first.data).not.toContain('private envelope')
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    expect(socket.mock.calls[0][2]).toMatchObject({ requestId: first.data, intent: 'import', signingKey: 'private envelope', spendingPassword: 'secret' })
    expect((wrapper.get('#leios-signing-key').element as HTMLTextAreaElement).value).toBe('')
    await wrapper.get('#leios-signing-key').setValue('private envelope')
    await wrapper.findAll('button').find(button => button.text() === 'Import key')!.trigger('click')
    await flushPromises()
    expect(prompt.mock.calls[1][0].data).not.toBe(first.data)
    emitter.emit('spending-password-modal-hidden', { action: first.action })
    await flushPromises()
    expect((wrapper.get('#leios-signing-key').element as HTMLTextAreaElement).value).toBe('')
    emitter.off('show-spending-password-modal', prompt)
  })

  it('installs directly and rejects an outdated target before dispatch', async () => {
    const { store, wrapper } = mounted()
    const socket = vi.spyOn(store, 'socketRequest').mockRejectedValue(new Error('lost'))
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    const sudo = wrapper.get('input[type="password"]')
    await sudo.setValue('sudo')
    await wrapper.findAll('button').find(button => button.text() === 'Install key')!.trigger('click')
    await flushPromises()
    const first = prompt.mock.calls[0][0]
    store.networkInvalidations.test = 1
    emitter.emit('confirm-spending-password-with-action', { action: first.action, originalData: first.data, password: 'secret' })
    await flushPromises()
    expect(socket).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(button => button.text() === 'Install key')!.trigger('click')
    await flushPromises()
    const second = prompt.mock.calls[1][0]
    emitter.emit('confirm-spending-password-with-action', { action: second.action, originalData: second.data, password: 'secret' })
    await flushPromises()
    expect(socket).toHaveBeenCalledTimes(1)
    expect(socket.mock.calls[0][0]).toBe('/jormanager/leios/key/install')
    expect(socket.mock.calls[0][2]).toEqual({ requestId: second.data, target: { nodeId: 1, hostId: 2, poolId: 'pool1', networkKey: 'test' }, keyId: 9, sudoPassword: 'sudo', spendingPassword: 'secret' })
    expect((sudo.element as HTMLInputElement).value).toBe('')
    await wrapper.findAll('button').find(button => button.text() === 'Install key')!.trigger('click')
    expect(prompt.mock.calls[2][0].data).toBe(second.data)
    expect(wrapper.text()).not.toContain('Review key installation')
    emitter.off('show-spending-password-modal', prompt)
  })
})
