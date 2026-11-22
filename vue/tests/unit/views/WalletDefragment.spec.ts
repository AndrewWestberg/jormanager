import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
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
  plugins: [pinia],
  directives: {
    'b-tooltip': () => {}
  },
  stubs: {
    'font-awesome-icon': { template: '<i />' },
    AddWalletEntryWizard: { template: '<div />' },
    CurrencyInput: {
      props: ['modelValue'],
      template: '<input :value="modelValue" />'
    },
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
      paymentAddrLovelace: 85_000_000,
      paymentAddrUtxoCount: 47,
      stakingAddr: '',
      stakingAddrLovelace: 0,
      nativeAssetMap: {}
    }
    const singleUtxoWallet = {
      id: 2,
      name: 'clean-wallet',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddress: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddrLovelace: 20_000_000,
      paymentAddrUtxoCount: 1,
      stakingAddr: '',
      stakingAddrLovelace: 0,
      nativeAssetMap: {}
    }
    const addressOnlyEntry = {
      id: 3,
      name: 'external-dest',
      type: 'address',
      hasPaymentKeys: false,
      paymentAddr: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddress: 'addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu',
      paymentAddrLovelace: 50_000_000,
      paymentAddrUtxoCount: 12,
      stakingAddr: '',
      stakingAddrLovelace: 0,
      nativeAssetMap: {}
    }

    store.walletItems = [fragmentedWallet, singleUtxoWallet, addressOnlyEntry] as any

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

    // Only fragmentedWallet (paymentAddrUtxoCount > 1 && type !== 'address') gets the Defragment button
    const defragButtons = wrapper.findAll('[data-testid="defragment-utxos-btn"]')
    expect(defragButtons).toHaveLength(1)

    await defragButtons[0].trigger('click')
    expect(emittedPayloads).toHaveLength(1)
    expect(emittedPayloads[0]).toEqual({
      walletItem: fragmentedWallet,
      isClaim: false,
      isDefragment: true
    })
  })

  it('auto-configures DEFRAGMENT mode in SendAdaModal, displays planned UTxO summary, and submits selectionMode', async () => {
    const store = useJorManagerStore()
    const walletItem = {
      id: 7,
      name: 'dusty-ops',
      type: 'payment',
      hasPaymentKeys: true,
      paymentAddr: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddress: 'addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9',
      paymentAddrLovelace: 120_000_000,
      paymentAddrUtxoCount: 64,
      stakingAddrLovelace: 0,
      nativeAssetMap: {}
    }
    store.walletItems = [walletItem] as any

    const calcSpy = vi.spyOn(store, 'calculateSendAdaFees').mockImplementation((req) => {
      store.requestFeesUUID = req.uuid
      store.saveTxFee({
        txFee: 385_000,
        tokenFees: [0],
        tokenKeepFee: 1_350_000,
        tokenLocked: 0,
        selectionMode: (req.selectionMode as any) || 'STANDARD',
        inputCount: 64,
        outputCount: 3,
        estimatedTxSize: 3_840,
        uuid: req.uuid
      })
    })
    const submitSpy = vi.spyOn(store, 'submitTransaction').mockImplementation(() => {})

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

    expect(wrapper.find('[data-testid="modal-title"]').text()).toContain('Defragmenting: ₳120.000000')
    expect(wrapper.find('[data-testid="selection-mode-group"]').exists()).toBe(true)

    // Verify calculateSendAdaFees was dispatched immediately with DEFRAGMENT and 100% self-consolidation
    expect(calcSpy).toHaveBeenCalled()
    const lastCalcArg = calcSpy.mock.calls[calcSpy.mock.calls.length - 1][0] as any
    expect(lastCalcArg.selectionMode).toBe('DEFRAGMENT')
    expect(lastCalcArg.fromId).toBe(7)
    expect(lastCalcArg.toAccounts[0].account).toBe(7)
    expect(lastCalcArg.toAccounts[0].percent).toBe(100)

    // Verify live SDK transaction plan summary renders input/output counts and 16KB budget percentage
    const summary = wrapper.find('[data-testid="tx-plan-summary"]')
    expect(summary.exists()).toBe(true)
    expect(summary.text()).toContain('64 inputs')
    expect(summary.text()).toContain('3 outputs')
    expect(summary.text()).toContain('3,840 / 16,384 bytes')

    // Confirm spending password and verify submitTransaction receives selectionMode: 'DEFRAGMENT'
    emitter.emit('confirm-spending-password-with-action', {
      action: 'send-ada',
      password: 'test-password',
      originalData: null
    })
    await nextTick()

    expect(submitSpy).toHaveBeenCalledTimes(1)
    const submitted = submitSpy.mock.calls[0][0] as any
    expect(submitted.selectionMode).toBe('DEFRAGMENT')
    expect(submitted.fromId).toBe(7)
    expect(submitted.txFee).toBe(385_000)
  })
})
