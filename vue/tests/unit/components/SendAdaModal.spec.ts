import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createBootstrap } from 'bootstrap-vue-next'
import { library } from '@fortawesome/fontawesome-svg-core'
import { fas } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import SendAdaModal from '@/components/SendAdaModal.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

library.add(fas)
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks() })

async function setup(claim = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useJorManagerStore()
  store.connected = true
  const source = { id: 1, name: 'Source', type: 'payment', paymentAddress: 'addr_source', paymentAddr: 'addr_source', hasPaymentKeys: true, stakingAddr: null, paymentAddrUtxoCount: 1, paymentAddrLovelace: '9007199254740999', stakingAddrRegistered: false, stakingAddrLovelace: '5000000', nativeAssetMap: { 'abcd.61': '9007199254740999' } }
  const destination = { ...source, id: 2, name: 'Destination', paymentAddress: 'addr_destination', paymentAddr: 'addr_destination', paymentAddrLovelace: '5000000', stakingAddrLovelace: '7000000', nativeAssetMap: {} }
  store.walletItems = [source, destination] as typeof store.walletItems
  const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
  const prompt = vi.fn()
  emitter.on('show-spending-password-modal', prompt)
  const wrapper = mount(SendAdaModal, { attachTo: document.body, global: { plugins: [pinia, createBootstrap()], components: { FontAwesomeIcon } } })
  emitter.emit('show-send-ada-modal', claim ? { walletItems: [source, destination], isClaim: true, isMultiClaim: true } : { walletItem: source, isClaim: false })
  await flushPromises()
  const modal = document.querySelector('#modal-send-ada') as HTMLElement
  const select = (index: number, value: string) => {
    const element = modal.querySelectorAll('select')[index] as HTMLSelectElement
    element.value = value
    element.dispatchEvent(new Event('change', { bubbles: true }))
  }
  const input = (element: HTMLInputElement, value: string) => {
    element.value = value
    element.dispatchEvent(new Event('input', { bubbles: true }))
  }
  const fee = (overrides: Record<string, unknown> = {}) => store.handleMessage({ type: 'calculatefee', data: { uuid: store.requestFeesUUID, txFee: '170000', tokenKeepFee: '0', tokenLocked: '0', tokenFees: ['0'], ...overrides } })
  const button = () => Array.from(modal.querySelectorAll('button')).find(item => item.textContent?.trim() === 'Send')!
  const cleanup = () => { emitter.off('show-spending-password-modal', prompt); wrapper.unmount() }
  return { store, source, destination, send, prompt, modal, select, input, fee, button, cleanup }
}

async function entry(ctx: { modal: HTMLElement; select: (index: number, value: string) => void; input: (element: HTMLInputElement, value: string) => void }, type: 'amount' | 'percent', value: string) {
  ctx.select(1, '2')
  ;(ctx.modal.querySelector(`input[value="${type}"]`) as HTMLInputElement).click()
  await nextTick()
  const input = ctx.modal.querySelector(type === 'amount' ? 'input[placeholder="e.g. 10.000000"]' : 'input[type="range"]') as HTMLInputElement
  ctx.input(input, value)
  await flushPromises()
}

describe('send form fees', () => {
  it('gates a fixed exact amount on a matching fee and dispatches once', async () => {
    const c = await setup()
    const submit = vi.spyOn(c.store, 'submitTransaction')
    await entry(c, 'amount', '10')
    expect(c.button().disabled).toBe(true)
    c.fee({ uuid: 'old' })
    await nextTick()
    expect(c.button().disabled).toBe(true)
    c.fee()
    await nextTick()
    expect(c.modal.textContent).toContain('TxFee: ₳0.170000')
    c.button().click()
    await nextTick()
    const p = c.prompt.mock.lastCall![0]
    emitter.emit('confirm-spending-password-with-action', { action: p.action, password: 'secret', originalData: p.data })
    emitter.emit('confirm-spending-password-with-action', { action: p.action, password: 'secret', originalData: p.data })
    await flushPromises()
    expect(submit).toHaveBeenCalledTimes(1)
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ spendingPassword: 'secret', txFee: '170000', toAccounts: [expect.objectContaining({ amount: '10000000' })] }))
    c.store.handleMessage({ type: 'submittransaction', exception: { message: 'submission failed' } })
    expect(c.store.toastError?.message).toBe('submission failed')
    c.cleanup()
  })

  it('calculates 100 percent exactly after fee and reserved asset change', async () => {
    const c = await setup()
    await entry(c, 'percent', '100')
    c.fee({ tokenKeepFee: '1500000', tokenLocked: '2000000', tokenFees: ['0'] })
    await nextTick()
    expect(c.modal.textContent).toContain('Available:')
    expect(c.modal.textContent).toContain('Remaining:')
    c.button().click()
    await nextTick()
    emitter.emit('confirm-spending-password-with-action', { action: 'submit-wallet-send', password: 'secret', originalData: null })
    expect(c.send).toHaveBeenCalledWith('/jormanager/submittransaction', expect.objectContaining({ toAccounts: [expect.objectContaining({ amount: '9007199251070999' })] }))
    c.cleanup()
  })

  it('sends exact asset units and reserves the destination minimum ADA', async () => {
    const c = await setup()
    c.select(0, 'abcd.61')
    await entry(c, 'percent', '100')
    c.fee({ tokenFees: ['1500000'], tokenKeepFee: '0' })
    await nextTick()
    c.button().click()
    await nextTick()
    emitter.emit('confirm-spending-password-with-action', { action: 'submit-wallet-send', password: 'secret', originalData: null })
    expect(c.send).toHaveBeenCalledWith('/jormanager/submittransaction', expect.objectContaining({ toAccounts: [expect.objectContaining({ amount: '9007199254740999' })] }))
    c.cleanup()
  })

  it('combines multi-claim rewards and identifies the selected fee payer', async () => {
    const c = await setup(true)
    await entry(c, 'percent', '100')
    c.fee()
    await nextTick()
    expect(c.modal.textContent).toContain('Fee payer')
    c.button().click()
    await nextTick()
    emitter.emit('confirm-spending-password-with-action', { action: 'submit-wallet-send', password: 'secret', originalData: null })
    expect(c.send).toHaveBeenCalledWith('/jormanager/submittransaction', expect.objectContaining({
      isClaim: true, fromIds: [1, 2], toAccounts: [expect.objectContaining({ amount: '12000000' })]
    }))
    c.cleanup()
  })

})
