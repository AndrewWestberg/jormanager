import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import Hosts from '@/views/Hosts.vue'
import AddEditHostModal from '@/components/AddEditHostModal.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import { emitter } from '@/composables/useEventBus'
import type { Host } from '@/types'

const wrappers: { unmount: () => void }[] = []
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
  vi.restoreAllMocks()
})

const pinnedHost: Host = {
  id: 1,
  type: 'remote',
  hostname: 'relay1.stakepool.io',
  sshUser: 'cardano',
  sshPort: 22,
  sshPemPath: '/home/cardano/.ssh/id_ed25519',
  cardanoCliPath: '/usr/local/bin/cardano-cli',
  cardanoNodePath: '/usr/local/bin/cardano-node',
  nodeHomePath: '/opt/cardano',
  jcliPath: null,
  sshFingerprint: 'SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU',
  sshMismatchFingerprint: null
}

const mismatchedHost: Host = {
  ...pinnedHost,
  id: 2,
  hostname: 'bp1.stakepool.io',
  sshFingerprint: 'SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU',
  sshMismatchFingerprint: 'SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s'
}

function mountHostsView(hosts: Host[]) {
  setActivePinia(createPinia())
  const store = useJorManagerStore()
  store.connected = true
  store.hosts = hosts
  vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
  const wrapper = mount(Hosts, {
    global: {
      stubs: {
        'font-awesome-icon': true,
        AddEditHostModal: true
      },
      directives: {
        'b-tooltip': {}
      }
    }
  })
  wrappers.push(wrapper)
  return { store, wrapper }
}

describe('Hosts.vue SSH fingerprint TOFU & mismatch alerts', () => {
  it('displays pinned SSH fingerprint status without showing mismatch banner when all hosts match', () => {
    const { wrapper } = mountHostsView([pinnedHost])
    expect(wrapper.find('[data-testid="host-key-mismatch-banner"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Pinned')
    expect(wrapper.text()).toContain('1 pinned (TOFU)')
    expect(wrapper.text()).toContain('SHA256:47DEQpj8HBSa')
  })

  it('displays prominent security warning with stored and received fingerprints and dispatches authorized accept & reset', async () => {
    const { store, wrapper } = mountHostsView([pinnedHost, mismatchedHost])
    const banner = wrapper.find('[data-testid="host-key-mismatch-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('SSH Host Key Mismatch Detected')
    expect(banner.text()).toContain('bp1.stakepool.io:22')
    expect(banner.text()).toContain(mismatchedHost.sshFingerprint!)
    expect(banner.text()).toContain(mismatchedHost.sshMismatchFingerprint!)

    const updateSpy = vi.spyOn(store, 'updateHostFingerprint').mockResolvedValue({
      requestId: '00000000-0000-4000-8000-000000000001',
      hostId: 2,
      action: 'accept',
      sshFingerprint: mismatchedHost.sshMismatchFingerprint!,
      message: 'Updated trusted SSH host key fingerprint'
    })

    const passwordPrompts: Array<{ action: string; data?: unknown }> = []
    const onPrompt = (payload: { action: string; data?: unknown }) => passwordPrompts.push(payload)
    emitter.on('show-spending-password-modal', onPrompt)
    try {
      await wrapper.find('[data-testid="accept-mismatch-btn"]').trigger('click')
      expect(passwordPrompts).toHaveLength(1)
      expect(passwordPrompts[0].data).toMatchObject({
        hostId: 2,
        action: 'accept',
        fingerprint: mismatchedHost.sshMismatchFingerprint
      })

      emitter.emit('confirm-spending-password-with-action', {
        action: passwordPrompts[0].action,
        originalData: passwordPrompts[0].data,
        password: 'operator-spending-password'
      })
      await flushPromises()

      expect(updateSpy).toHaveBeenCalledWith(
        (passwordPrompts[0].data as { requestId: string }).requestId,
        {
          hostId: 2,
          action: 'accept',
          fingerprint: mismatchedHost.sshMismatchFingerprint,
          spendingPassword: 'operator-spending-password'
        }
      )

      // Also verify Reset Fingerprint prompt & confirmation
      await wrapper.find('[data-testid="reset-mismatch-btn"]').trigger('click')
      expect(passwordPrompts).toHaveLength(2)
      expect(passwordPrompts[1].data).toMatchObject({
        hostId: 2,
        action: 'reset'
      })
    } finally {
      emitter.off('show-spending-password-modal', onPrompt)
    }
  })
})

describe('AddEditHostModal.vue host detail fingerprint display & resolution', () => {
  it('shows saved SSH fingerprint in host detail view and supports mismatch warning resolution', async () => {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.connected = true
    store.hosts = [pinnedHost, mismatchedHost]
    vi.spyOn(store, 'sendMessage').mockImplementation(() => {})

    const wrapper = mount(AddEditHostModal, {
      global: {
        stubs: {
          'font-awesome-icon': true,
          BModal: {
            props: ['modelValue', 'title'],
            template: '<div v-if="modelValue" data-testid="bmodal-stub"><h5>{{ title }}</h5><slot /></div>'
          }
        }
      }
    })
    wrappers.push(wrapper)

    // Open pinned host details
    emitter.emit('edit-host', pinnedHost)
    await flushPromises()

    const fpInput = wrapper.find('[data-testid="saved-ssh-fingerprint-input"]')
    expect(fpInput.exists()).toBe(true)
    expect((fpInput.element as HTMLInputElement).value).toBe(pinnedHost.sshFingerprint)
    expect(wrapper.find('[data-testid="modal-mismatch-warning"]').exists()).toBe(false)

    // Open mismatched host details
    emitter.emit('edit-host', mismatchedHost)
    await flushPromises()

    const modalWarning = wrapper.find('[data-testid="modal-mismatch-warning"]')
    expect(modalWarning.exists()).toBe(true)
    expect(modalWarning.text()).toContain(mismatchedHost.sshFingerprint!)
    expect(modalWarning.text()).toContain(mismatchedHost.sshMismatchFingerprint!)
  })
})
