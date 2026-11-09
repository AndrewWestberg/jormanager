import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createBootstrap } from 'bootstrap-vue-next'
import GovernanceVoteModal from '@/components/GovernanceVoteModal.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'

afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks() })

describe('governance submission', () => {
  it('uses one password and retains the request ID after an uncertain outcome', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useJorManagerStore()
    store.connected = true
    store.walletNetworkKey = 'network'
    store.chainReadState = { networkKey: 'network', status: 'complete', reason: '', observedTip: null, lastCompletePoint: null, walletReady: true, generation: '1' }
    store.nodes = [{ id: 1, name: 'Pool', type: 'core', poolId: 'pool1', color: '#000', status: 'Running', isDraft: false,
      host: { type: 'local', hostname: 'localhost', sshUser: '', sshPort: 22, sshPemPath: '', cardanoCliPath: '/bin/cardano-cli', cardanoNodePath: '/bin/cardano-node', nodeHomePath: '/node', jcliPath: null } }]
    store.walletItems = [{ id: 2, name: 'Fees', type: 'payment', hasPaymentKeys: true, paymentAddress: 'addr_fees', paymentAddrLovelace: '10000000', stakingAddrLovelace: '0', nativeAssetMap: {} }]
    const submit = vi.spyOn(store, 'submitGovernance').mockResolvedValue({ requestId: 'id', txId: 'tx', networkKey: 'network', era: 'conway', fee: '1', deposit: '0', refund: '0', state: 'submission_uncertain' })
    const prompt = vi.fn()
    emitter.on('show-spending-password-modal', prompt)
    const wrapper = mount(GovernanceVoteModal, { props: { modelValue: true }, attachTo: document.body, global: { plugins: [pinia, createBootstrap()], stubs: { FontAwesomeIcon: true } } })
    await flushPromises()
    const modal = document.querySelector('.modal') as HTMLElement
    const fee = modal.querySelector('select') as HTMLSelectElement
    fee.value = '2'
    fee.dispatchEvent(new Event('change', { bubbles: true }))
    const action = modal.querySelector('input[placeholder="gov_action1..."]') as HTMLInputElement
    action.value = `gov_action1${'a'.repeat(50)}`
    action.dispatchEvent(new Event('input', { bubbles: true }))
    const rationale = modal.querySelector('textarea') as HTMLTextAreaElement
    rationale.value = 'Because'
    rationale.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const button = Array.from(modal.querySelectorAll('button')).find(button => button.textContent?.includes('Submit Vote'))!
    button.click()
    const pending = prompt.mock.lastCall![0]
    expect(submit).not.toHaveBeenCalled()
    emitter.emit('confirm-spending-password-with-action', { action: pending.action, password: 'secret', originalData: pending.data })
    await flushPromises()
    expect(submit).toHaveBeenCalledWith(pending.data.requestId, expect.objectContaining({ rationale: 'Because', feesAccountId: 2 }), 'secret')
    expect(modal.textContent).toContain('submission_uncertain')
    button.click()
    expect(prompt.mock.lastCall![0].data.requestId).toBe(pending.data.requestId)
    emitter.off('show-spending-password-modal', prompt)
    wrapper.unmount()
  })
})
