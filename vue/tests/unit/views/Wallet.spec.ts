import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createBootstrap, vBTooltip } from 'bootstrap-vue-next'
import { library } from '@fortawesome/fontawesome-svg-core'
import { fas } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import Wallet from '@/views/Wallet.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

library.add(fas)
const cleanups: (() => void)[] = []
afterEach(() => {
  cleanups.splice(0).forEach(cleanup => cleanup())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

const identities = [
  { id: 101, name: 'Imported payment', type: 'payment', paymentAddr: 'addr_test_smoke_payment', hasPaymentKeys: true, stakingAddr: null },
  { id: 102, name: 'Imported stake', type: 'stake', paymentAddr: 'addr_test_smoke_stake', hasPaymentKeys: true, stakingAddr: 'stake_test_smoke' }
]
const unknown = { paymentAddrUtxoCount: null, paymentAddrLovelace: null, stakingAddrRegistered: null, stakingAddrLovelace: null, nativeAssetMap: null }
const completeItems = identities.map((item, index) => ({
  ...item, paymentAddrUtxoCount: 1, paymentAddrLovelace: index ? '10000000' : '9007199254740993',
  stakingAddrRegistered: !!index, stakingAddrLovelace: index ? '1000000' : '0', nativeAssetMap: {}
}))

function setup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useJorManagerStore()
  store.connected = true
  vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
  const wrapper = mount(Wallet, {
    attachTo: document.body,
    global: { plugins: [pinia, createBootstrap()], components: { FontAwesomeIcon }, directives: { 'b-tooltip': vBTooltip } }
  })
  cleanups.push(() => wrapper.unmount())
  let generation = 1
  let readState = { networkKey: 'wallet-import-smoke', status: 'syncing', reason: 'Following adopted chain', observedTip: null, lastCompletePoint: null, walletReady: false, generation: '1' }
  function status(status: string, reason = 'Following adopted chain', walletReady = status === 'complete') {
    if (!walletReady && readState.walletReady) generation++
    readState = { ...readState, status, reason, walletReady, generation: String(generation) }
    store.handleMessage({ type: 'chainstatus', data: readState })
  }
  function wallet(items: object[]) {
    store.handleMessage({ type: 'wallet', data: { networkKey: 'wallet-import-smoke', items, readState } })
  }
  return { store, wrapper, status, wallet }
}

const actions = '[data-icon="hand-holding-usd"], [data-icon="cash-register"], [data-icon="link"], [data-icon="unlink"]'

describe('Wallet indexed balance availability', () => {
  it('keeps identities visible through pending, recovered and revoked coverage', async () => {
    const { store, wrapper, status, wallet } = setup()
    store.handleMessage({ type: 'createwalletentry', data: 'Saved imported entry' })
    wallet(identities.map(item => ({ ...item, ...unknown })))
    status('syncing', '<b>Coverage unavailable</b>')
    await nextTick()
    const table = wrapper.get('table')
    expect(table.text()).toContain('Imported payment')
    expect(table.text()).toContain('Imported stake')
    expect(table.text()).toContain('addr_test_smok')
    expect(table.text()).toContain('stake_test_smok')
    expect(table.text()).toContain('Unavailable')
    expect(table.findAll(actions)).toHaveLength(0)
    expect(table.text()).not.toContain('Delegate')
    expect(wrapper.get('.alert-warning[role="status"]').text()).toContain('<b>Coverage unavailable</b>')
    expect(wrapper.get('.alert-warning').find('b').exists()).toBe(false)
    expect(store.toastError).toBeNull()

    status('complete')
    await nextTick()
    expect(table.findAll(actions)).toHaveLength(0)
    wallet(completeItems)
    await nextTick()
    expect(table.text()).toContain('₳9007199254.740993')
    expect(table.text()).toContain('₳10.000000')
    expect(table.text()).toContain('₳1.000000')
    expect(table.findAll('[data-icon="hand-holding-usd"]')).toHaveLength(2)
    expect(table.find('[data-icon="cash-register"]').exists()).toBe(true)
    expect(table.text()).toContain('Delegate')
    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    await table.get('input[type="checkbox"]').setValue(true)
    expect(wrapper.text()).toContain('Claim Selected (1)')
    status('syncing', 'Following adopted chain', true)
    await nextTick()
    expect(table.text()).toContain('₳9007199254.740993')
    expect(table.findAll('[data-icon="hand-holding-usd"]')).toHaveLength(2)
    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    expect(wrapper.text()).toContain('Claim Selected (1)')

    // A queued click must not use values invalidated before Vue updates the DOM.
    const send = vi.spyOn(emitter, 'emit')
    const staleControls = table.findAll(actions)
    status('incomplete')
    staleControls.forEach(control => (control.element.parentElement as HTMLElement).click())
    await nextTick()
    expect(send).not.toHaveBeenCalledWith('show-send-ada-modal', expect.anything())
    expect(table.findAll(actions)).toHaveLength(0)
    expect(table.text()).toContain('Imported payment')
    expect(table.text()).toContain('Unavailable')
    expect(wrapper.text()).not.toContain('Claim Selected')
    expect(document.querySelector('.modal.show')).toBeNull()
    status('complete')
    wallet(completeItems)
    await nextTick()
    expect((table.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)
  })

  it('does not mistake unknown registration or known zero rewards for eligible actions', async () => {
    const { wrapper, status, wallet } = setup()
    status('complete')
    wallet([{ ...completeItems[1], stakingAddrRegistered: null, stakingAddrLovelace: '0' }])
    await nextTick()
    const table = wrapper.get('table')
    expect(table.find('[data-icon="unlink"]').exists()).toBe(false)
    expect(table.find('[data-icon="link"]').exists()).toBe(false)
    expect(table.find('[data-icon="cash-register"]').exists()).toBe(false)
    expect(table.text()).not.toContain('Delegate')
    wallet([{ ...completeItems[1], paymentAddrLovelace: '0', stakingAddrRegistered: false, stakingAddrLovelace: '0' }])
    status('complete')
    await nextTick()
    expect(table.find('[data-icon="unlink"]').exists()).toBe(true)
    expect(table.find('[data-icon="hand-holding-usd"]').exists()).toBe(false)
  })

  it('requires a connected matching ready network', async () => {
    const { store, wrapper, status, wallet } = setup()
    status('complete')
    wallet(completeItems)
    await nextTick()
    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    store.chainReadState = { ...store.chainReadState!, networkKey: 'other' }
    await nextTick()
    expect(wrapper.find('.alert-warning').exists()).toBe(true)
    expect(wrapper.findAll(actions)).toHaveLength(0)
    store.chainReadState = { ...store.chainReadState!, networkKey: 'wallet-import-smoke' }
    store.connected = false
    await nextTick()
    expect(wrapper.find('.alert-warning').exists()).toBe(true)
    expect(wrapper.findAll(actions)).toHaveLength(0)
  })

  it('shows unknown destination balances in the actual Send modal', async () => {
    const { wrapper, status, wallet } = setup()
    status('complete')
    wallet([completeItems[0], { ...identities[1], ...unknown }])
    await nextTick()
    await wrapper.get('table [data-icon="hand-holding-usd"]').trigger('click')
    await flushPromises()
    await nextTick()
    const options = Array.from(document.querySelectorAll('#modal-send-ada option')).map(option => option.textContent)
    expect(options).toContain('Imported stake - Unavailable')
    expect(options).not.toContain('Imported stake - ₳0.000000')
  })
})
