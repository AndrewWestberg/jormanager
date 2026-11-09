import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import WalletReviewPanel from '@/components/WalletReviewPanel.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import type { ChainReadState, WalletReview } from '@/types'

const state = (status = 'complete', walletReady = true, generation = '9007199254740993'): ChainReadState => ({
  networkKey: 'network', status, walletReady, generation, reason: null, observedTip: null, lastCompletePoint: null
})
const review: WalletReview = {
  previewId: 'preview', txId: 'transaction', networkKey: 'network', era: 'conway',
  fee: '200000', deposit: '0', refund: '0', validFrom: null, validUntil: null, state: 'previewed'
}
const stubs = {
  BButton: defineComponent({ props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' }),
  BFormGroup: defineComponent({ template: '<div><slot /></div>' }),
  BFormInput: defineComponent({
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">'
  })
}

function setup(currentReview = review) {
  const store = useJorManagerStore()
  store.connected = true
  store.walletNetworkKey = 'network'
  store.chainReadState = state()
  const wrapper = mount(WalletReviewPanel, { props: { review: currentReview }, global: { stubs } })
  const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)
  return { store, wrapper, button }
}

describe('wallet review readiness', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('keeps authorization available across ready following and catch-up', async () => {
    const { store, wrapper, button } = setup()
    await wrapper.find('input').setValue('secret')
    expect(button('Authorize and submit')?.attributes('disabled')).toBeUndefined()
    store.chainReadState = state('syncing')
    await nextTick()
    expect(wrapper.text()).not.toContain('historical')
    expect(button('Authorize and submit')?.attributes('disabled')).toBeUndefined()
    store.chainReadState = state()
    await nextTick()
    expect(button('Authorize and submit')?.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('requires exact coverage for reconciliation and never submits', async () => {
    const { store, wrapper, button } = setup({ ...review, state: 'submitted' })
    const reconcile = vi.spyOn(store, 'reconcileWallet').mockResolvedValue({
      requestId: 'request', previewId: review.previewId, txId: review.txId,
      networkKey: review.networkKey, state: 'confirmed', reason: null
    })
    const submit = vi.spyOn(store, 'submitReviewedWallet')
    await wrapper.find('input').setValue('secret')
    store.chainReadState = state('syncing')
    await nextTick()
    expect(button('Authorize reconciliation')?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('never resubmits')
    expect(wrapper.text()).toContain('requires complete chain coverage')
    await button('Authorize reconciliation')?.trigger('click')
    expect(reconcile).not.toHaveBeenCalled()
    store.chainReadState = state()
    await nextTick()
    expect(button('Authorize reconciliation')?.attributes('disabled')).toBeUndefined()
    await button('Authorize reconciliation')?.trigger('click')
    expect(reconcile).toHaveBeenCalledWith('preview', 'secret')
    expect(submit).not.toHaveBeenCalled()
    wrapper.unmount()
  })


  it('fails closed for missing readiness and a mismatched chain network', async () => {
    const { store, wrapper, button } = setup()
    store.chainReadState = { ...state(), walletReady: undefined } as unknown as ChainReadState
    await nextTick()
    expect(button('Authorize and submit')).toBeUndefined()
    expect(wrapper.text()).toContain('historical')
    wrapper.unmount()

    store.chainReadState = { ...state(), networkKey: 'other' }
    const other = mount(WalletReviewPanel, { props: { review: { ...review, state: 'submitted' } }, global: { stubs } })
    await other.find('input').setValue('secret')
    expect(other.findAll('button').find(item => item.text() === 'Authorize reconciliation')?.attributes('disabled')).toBeDefined()
    other.unmount()
  })
  it.each(['generation', 'readiness', 'disconnect', 'network', 'invalidation'])('makes a review stale on %s', async change => {
    const { store, wrapper, button } = setup()
    await wrapper.find('input').setValue('secret')
    if (change === 'generation') store.chainReadState = state('complete', true, '9007199254740994')
    if (change === 'readiness') store.chainReadState = state('syncing', false)
    if (change === 'disconnect') store.connected = false
    if (change === 'network') store.walletNetworkKey = 'other'
    if (change === 'invalidation') store.networkInvalidations.network = 1
    await nextTick()
    expect(wrapper.text()).toContain('historical')
    expect(button('Authorize and submit')).toBeUndefined()
    store.connected = true
    store.walletNetworkKey = 'network'
    store.chainReadState = state()
    await nextTick()
    expect(button('Authorize and submit')).toBeUndefined()
    wrapper.unmount()
  })
})
