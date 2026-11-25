import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { createBootstrap } from 'bootstrap-vue-next'
import Wallet from '@/views/Wallet.vue'
import SendAdaModal from '@/components/SendAdaModal.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

const BModalStub = {
  props: ['modelValue', 'title'],
  template: `
    <div v-if="modelValue" class="modal-stub">
      <h5 data-testid="modal-title">{{ title }}</h5>
      <slot />
      <slot name="footer" :cancel="() => {}" />
    </div>
  `
}

const commonGlobalConfig = (pinia: ReturnType<typeof createPinia>) => ({
  plugins: [pinia, createBootstrap()],
  directives: {
    'b-tooltip': () => {}
  },
  stubs: {
    'font-awesome-icon': { template: '<i />' },
    AddWalletEntryWizard: { template: '<div />' },
    BModal: BModalStub
  }
})

describe('Wallet Defragment UTxOs UI & SendAdaModal Coin Selection', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    emitter.all.clear()
    pinia = createPinia()
    setActivePinia(pinia)
  })

  it('renders UTxO badge and Defragment UTxOs action only for spendable multi-UTxO wallets', async () => {
    const store = useJorManagerStore()
    vi.spyOn(store, 'fetchWalletItems').mockImplementation(() => {})
    vi.spyOn(store, 'requestFileOptions').mockImplementation(() => {})

    const fragmentedWallet = {
      id: 1,
      name: 'pool-ops',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddress: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddrLovelace: '85000000',
      paymentAddrUtxoCount: 47,
      stakingAddr: '',
      stakingAddrLovelace: '0',
      nativeAssetMap: {}
    }
    const singleUtxoWallet = {
      id: 2,
      name: 'clean-wallet',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddress: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddrLovelace: '20000000',
      paymentAddrUtxoCount: 1,
      stakingAddr: '',
      stakingAddrLovelace: '0',
      nativeAssetMap: {}
    }
    const addressOnlyEntry = {
      id: 3,
      name: 'external-dest',
      type: 'address',
      hasPaymentKeys: false,
      paymentAddr: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddress: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddrLovelace: '50000000',
      paymentAddrUtxoCount: 12,
      stakingAddr: '',
      stakingAddrLovelace: '0',
      nativeAssetMap: {}
    }

    store.walletItems = [fragmentedWallet, singleUtxoWallet, addressOnlyEntry]

    const emittedPayloads: unknown[] = []
    emitter.on('show-send-ada-modal', (payload) => emittedPayloads.push(payload))

    const wrapper = mount(Wallet, {
      global: {
        ...commonGlobalConfig(pinia),
        stubs: {
          ...commonGlobalConfig(pinia).stubs,
          SendAdaModal: { template: '<div />' }
        }
      }
    })
    await nextTick()

    expect(wrapper.text()).toContain('47 UTxOs')
    expect(wrapper.text()).toContain('1 UTxO')
    expect(wrapper.find('[data-testid="defragment-utxos-btn"]').exists()).toBe(false)

    store.connected = true
    await nextTick()

    // Only the funded, spendable multi-UTxO wallet can defragment.
    const defragButtons = wrapper.findAll('[data-testid="defragment-utxos-btn"]')
    expect(defragButtons).toHaveLength(1)

    await defragButtons[0].trigger('click')
    expect(emittedPayloads).toHaveLength(1)
    expect(emittedPayloads[0]).toEqual({
      walletItem: fragmentedWallet,
      isClaim: false,
      isDefragment: true
    })
    wrapper.unmount()
  })

  it('auto-configures DEFRAGMENT mode in SendAdaModal, displays planned UTxO summary, and submits selectionMode', async () => {
    const store = useJorManagerStore()
    store.connected = true
    const walletItem = {
      id: 7,
      name: 'dusty-ops',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddress: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddrLovelace: '120000000',
      paymentAddrUtxoCount: 64,
      stakingAddrLovelace: '0',
      nativeAssetMap: {}
    }
    store.walletItems = [walletItem]

    const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)

    const wrapper = mount(SendAdaModal, {
      global: commonGlobalConfig(pinia)
    })

    // Trigger Defragment UTxOs workflow via event bus
    emitter.emit('show-send-ada-modal', {
      walletItem,
      isClaim: false,
      isDefragment: true
    })
    await nextTick()
    await nextTick()

    expect(wrapper.find('[data-testid="modal-title"]').text()).toContain('Defragmenting: ₳120.000000')
    expect(wrapper.find('[data-testid="selection-mode-group"]').exists()).toBe(true)

    expect(send).toHaveBeenCalledWith('/jormanager/calculatefee', expect.objectContaining({
      selectionMode: 'DEFRAGMENT',
      fromId: 7,
      fromIds: [7],
      toAccounts: [expect.objectContaining({ currency: 'ada', account: 7, percent: 100 })]
    }))

    const submit = wrapper.findAll('button').find(button => button.text() === 'Defragment UTxOs')!
    expect(submit.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="tx-plan-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="tx-plan-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tx-plan-loading"] progress').attributes('value')).toBeUndefined()
    expect(wrapper.find('[data-testid="fee-calculating-label"]').text()).toBe('Calculating…')

    // Unrelated wallet updates must not cancel an in-flight fee calculation.
    const initialRequestUuid = store.requestFeesUUID
    store.walletItems = [
      walletItem,
      {
        ...walletItem,
        id: 8,
        name: 'other-wallet',
        paymentAddrLovelace: '9999999'
      }
    ]
    await nextTick()
    expect(store.requestFeesUUID).toBe(initialRequestUuid)
    expect(send).toHaveBeenCalledTimes(1)

    store.handleMessage({ type: 'calculatefee', data: {
      txFee: '385000',
      tokenFees: ['0'],
      tokenKeepFee: '1350000',
      tokenLocked: '118265000',
      selectionMode: 'DEFRAGMENT',
      inputCount: 64,
      outputCount: 3,
      estimatedTxSize: 3840,
      uuid: store.requestFeesUUID
    } })
    await nextTick()

    // Verify live SDK transaction plan summary renders input/output counts and 16KB budget percentage
    expect(wrapper.find('[data-testid="tx-plan-loading"]').exists()).toBe(false)
    const summary = wrapper.find('[data-testid="tx-plan-summary"]')
    expect(summary.exists()).toBe(true)
    expect(summary.text()).toContain('64 inputs')
    expect(summary.text()).toContain('3 outputs')
    expect(summary.text()).toContain('3,840 / 16,384 bytes')
    expect(summary.find('progress').attributes('value')).toBe('3840')

    await submit.trigger('click')
    expect(prompt).toHaveBeenCalledWith({ action: 'submit-wallet-send', data: store.responseFeesUUID })
    emitter.emit('confirm-spending-password-with-action', {
      action: 'submit-wallet-send',
      password: 'test-password',
      originalData: prompt.mock.lastCall![0].data
    })
    await nextTick()

    expect(send).toHaveBeenCalledWith('/jormanager/submittransaction', expect.objectContaining({
      selectionMode: 'DEFRAGMENT',
      spendingPassword: 'test-password',
      fromId: 7,
      txFee: '385000',
      tokenKeepFee: '1350000',
      toAccounts: [expect.objectContaining({ account: 7, percent: 100, amount: '0' })]
    }))
    emitter.off('show-spending-password-modal', prompt)
    wrapper.unmount()
  })

  it('clears the fee loading state on matching calculatefee error and disables defragment for single-UTxO ADA-only wallets', async () => {
    const store = useJorManagerStore()
    store.connected = true
    const multiUtxoWallet = {
      id: 9,
      name: 'ada-only-two',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1vq5l62qysq3j6u4jsw0u73e8teus5x36ghd04lv0vsvqvys9km6at',
      paymentAddress: 'addr_test1vq5l62qysq3j6u4jsw0u73e8teus5x36ghd04lv0vsvqvys9km6at',
      paymentAddrLovelace: '9997325500',
      paymentAddrUtxoCount: 2,
      stakingAddrLovelace: '0',
      nativeAssetMap: {}
    }
    const singleUtxoWallet = {
      ...multiUtxoWallet,
      id: 10,
      name: 'ada-only-one',
      paymentAddrUtxoCount: 1
    }
    store.walletItems = [multiUtxoWallet, singleUtxoWallet]
    vi.spyOn(store, 'sendMessage').mockImplementation(() => {})

    const wrapper = mount(SendAdaModal, {
      global: commonGlobalConfig(pinia)
    })

    emitter.emit('show-send-ada-modal', {
      walletItem: multiUtxoWallet,
      isClaim: false,
      isDefragment: true
    })
    await nextTick()
    await nextTick()

    expect(wrapper.find('[data-testid="tx-plan-loading"]').exists()).toBe(true)
    const activeUuid = store.requestFeesUUID

    // Stale error for another request must not clear the active indicator.
    store.handleMessage({ type: 'calculatefee', exception: { message: 'Stale error', requestId: 'other-uuid' } })
    await nextTick()
    expect(wrapper.find('[data-testid="tx-plan-loading"]').exists()).toBe(true)

    // Matching error stops the indeterminate indicator.
    store.handleMessage({ type: 'calculatefee', exception: { message: 'Node busy', requestId: activeUuid } })
    await nextTick()
    expect(wrapper.find('[data-testid="tx-plan-loading"]').exists()).toBe(false)

    // Single-UTxO ADA-only wallet disables Defragment radio option.
    emitter.emit('show-send-ada-modal', {
      walletItem: singleUtxoWallet,
      isClaim: false
    })
    await nextTick()
    const defragRadio = wrapper.find('input[value="DEFRAGMENT"]')
    expect(defragRadio.attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })
})
