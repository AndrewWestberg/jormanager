import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import Header from '@/components/Header.vue'
import { useJorManagerStore } from '@/stores/jormanager'

const routes = [
  { path: '/', name: 'dashboard', component: { template: '<div />' } },
  { path: '/hosts', name: 'hosts', component: { template: '<div />' } },
  { path: '/nodes', name: 'nodes', component: { template: '<div />' } },
  { path: '/blocks', name: 'blocks', component: { template: '<div />' } },
  { path: '/wallet', name: 'wallet', component: { template: '<div />' } }
]

const wrappers: { unmount: () => void }[] = []
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
  vi.restoreAllMocks()
})

async function mountHeader(initialPath: string, epochState = { epoch: 512, slot: 216000, epochLength: 432000 }) {
  setActivePinia(createPinia())
  const store = useJorManagerStore()
  store.connected = true
  store.appVersion = 'jormanager 11.4.0'
  store.epoch = epochState.epoch
  store.slot = epochState.slot
  store.epochLength = epochState.epochLength
  vi.spyOn(store, 'requestAppVersion').mockImplementation(() => {})

  const router = createRouter({
    history: createMemoryHistory(),
    routes
  })
  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(Header, {
    global: {
      plugins: [router],
      stubs: {
        'font-awesome-icon': true
      }
    }
  })
  wrappers.push(wrapper)
  return { wrapper, router, store }
}

describe('Header.vue', () => {
  it('never renders the Live/Offline connection pill', async () => {
    const { wrapper, router } = await mountHeader('/')
    expect(wrapper.find('.jm-conn-pill').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Live')
    expect(wrapper.text()).not.toContain('Offline')

    await router.push('/hosts')
    await flushPromises()
    expect(wrapper.find('.jm-conn-pill').exists()).toBe(false)
  })

  it('hides .jm-mini-epoch on the Dashboard route (/) and shows it on non-Dashboard routes when epoch > 0', async () => {
    const { wrapper, router } = await mountHeader('/')
    expect(wrapper.find('.jm-mini-epoch').exists()).toBe(false)
    expect(wrapper.find('.jm-status-cluster').exists()).toBe(false)

    for (const nonDashboardPath of ['/hosts', '/nodes', '/blocks', '/wallet']) {
      await router.push(nonDashboardPath)
      await flushPromises()

      const miniEpoch = wrapper.find('.jm-mini-epoch')
      expect(miniEpoch.exists()).toBe(true)
      expect(miniEpoch.attributes('href')).toBe('/')
      expect(miniEpoch.text()).toContain('Ep 512')
      expect(miniEpoch.text()).toContain('50%')
    }

    await router.push('/')
    await flushPromises()
    expect(wrapper.find('.jm-mini-epoch').exists()).toBe(false)
  })

  it('hides .jm-mini-epoch on non-Dashboard routes when epoch is 0', async () => {
    const { wrapper } = await mountHeader('/hosts', { epoch: 0, slot: 0, epochLength: 432000 })
    expect(wrapper.find('.jm-mini-epoch').exists()).toBe(false)
  })
})
