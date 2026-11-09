import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createBootstrap, vBTooltip } from 'bootstrap-vue-next'
import { library } from '@fortawesome/fontawesome-svg-core'
import { fas } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import Wallet from '@/views/Wallet.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

library.add(fas)
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks() })

describe('wallet', () => {
  it('shows one exact balance and sends registration through the password prompt', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useJorManagerStore()
    store.connected = true
    const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    const wrapper = mount(Wallet, { attachTo: document.body, global: { plugins: [pinia, createBootstrap()], components: { FontAwesomeIcon }, directives: { 'b-tooltip': vBTooltip } } })
    store.handleMessage({ type: 'wallet', data: [{ id: 1, name: 'Stake', type: 'stake', paymentAddress: 'addr', paymentAddr: 'addr', stakingAddr: 'stake', hasPaymentKeys: true, paymentAddrLovelace: '9007199254740993', stakingAddrLovelace: '1000000', stakingAddrRegistered: false, nativeAssetMap: {} }] })
    await flushPromises()
    expect(wrapper.text()).toContain('₳9007199254.740993')
    expect(wrapper.text()).toContain('Balance')
    expect(wrapper.text()).not.toContain('On-chain balance')
    expect(wrapper.text()).not.toContain('Available to spend')
    expect(wrapper.text()).not.toContain('Delegate')
    wrapper.find('[data-icon="unlink"]').trigger('click')
    await flushPromises()
    const modal = document.querySelector('#modal-staking-address')!
    const select = modal.querySelector('select')!
    select.value = '1'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()
    Array.from(modal.querySelectorAll('button')).find(button => button.textContent?.trim() === 'Submit')!.click()
    await flushPromises()
    expect(prompt).toHaveBeenCalledWith({ action: 'submit-wallet-stake' })
    emitter.emit('confirm-spending-password-with-action', { action: 'submit-wallet-stake', password: 'secret', originalData: null })
    expect(send).toHaveBeenCalledWith('/jormanager/updatestakingaddress', { id: 1, stakingFeesAccount: 1, isRegistration: true, spendingPassword: 'secret' })
    store.handleMessage({ type: 'updatestakingaddress', exception: { message: 'Rejected' } })
    expect(store.toastError?.message).toBe('Rejected')
    expect(store.stakingSubmitting).toBe(false)
    emitter.off('show-spending-password-modal', prompt)
    wrapper.unmount()
  })
})
