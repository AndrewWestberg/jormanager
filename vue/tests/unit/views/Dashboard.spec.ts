import { afterEach, describe, it, expect } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import Dashboard from '@/views/Dashboard.vue'
import { useJorManagerStore } from '@/stores/jormanager'
import type { Node } from '@/types'

const wrappers: { unmount: () => void }[] = []
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
})

// Test the Dashboard methods directly without full component mount
// since we want to avoid mocking the entire Pinia store
describe('Dashboard component methods', () => {
  describe('epochTimeRemaining logic', () => {
    // Test the calculation logic directly
    const calculateEpochTimeRemaining = (epochLength: number, slot: number): string => {
      const time = epochLength - slot
      const days = Math.floor(time / 60 / 60 / 24)
      const hours = Math.floor(time / 60 / 60) % 24
      const minutes = Math.floor(time / 60) % 60
      const seconds = Math.floor(time % 60)
      return days + 'd ' + hours + 'h ' + minutes + 'm ' + seconds + 's '
    }

    it('calculates 5 days correctly', () => {
      const epochLength = 432000 // 5 days in seconds
      const slot = 0
      expect(calculateEpochTimeRemaining(epochLength, slot)).toBe('5d 0h 0m 0s ')
    })

    it('calculates partial time correctly', () => {
      const epochLength = 432000
      const slot = 100000 // about 1.15 days elapsed
      const result = calculateEpochTimeRemaining(epochLength, slot)
      expect(result).toBe('3d 20h 13m 20s ')
    })

    it('handles zero seconds remaining', () => {
      expect(calculateEpochTimeRemaining(432000, 432000)).toBe('0d 0h 0m 0s ')
    })

    it('handles exactly 1 day remaining', () => {
      const oneDayInSeconds = 86400
      expect(calculateEpochTimeRemaining(oneDayInSeconds, 0)).toBe('1d 0h 0m 0s ')
    })

    it('handles complex time calculations', () => {
      // 2 days, 3 hours, 15 minutes, 30 seconds
      const time = 2 * 86400 + 3 * 3600 + 15 * 60 + 30
      expect(calculateEpochTimeRemaining(time, 0)).toBe('2d 3h 15m 30s ')
    })
  })

  describe('epochRemainingClass logic', () => {
    // Test the calculation logic directly
    const calculateEpochRemainingClass = (epochLength: number, slot: number): string => {
      const epochTimeRemainingSecs = epochLength - slot
      if ((epochTimeRemainingSecs * 1.0) / epochLength > 0.4) {
        return 'text-success'
      }
      if ((epochTimeRemainingSecs * 1.0) / epochLength > 0.2) {
        return 'text-warning'
      }
      return 'text-danger'
    }

    it('returns text-success when more than 40% remaining', () => {
      const epochLength = 100
      const slot = 50 // 50% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-success')
    })

    it('returns text-warning when between 20% and 40% remaining', () => {
      const epochLength = 100
      const slot = 70 // 30% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-warning')
    })

    it('returns text-danger when 20% or less remaining', () => {
      const epochLength = 100
      const slot = 85 // 15% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-danger')
    })

    it('returns text-danger at epoch boundary (exactly 20%)', () => {
      const epochLength = 100
      const slot = 80 // exactly 20% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-danger')
    })

    it('returns text-success at exactly 41% remaining', () => {
      const epochLength = 100
      const slot = 59 // 41% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-success')
    })

    it('returns text-warning at exactly 21% remaining', () => {
      const epochLength = 100
      const slot = 79 // 21% remaining
      expect(calculateEpochRemainingClass(epochLength, slot)).toBe('text-warning')
    })

    it('returns text-danger when epoch is complete', () => {
      expect(calculateEpochRemainingClass(100, 100)).toBe('text-danger')
    })
  })
})

describe('Dashboard.vue rendering', () => {
  function mountDashboard(nodes: Node[] = []) {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.connected = true
    store.epoch = 512
    store.slot = 108000
    store.epochLength = 432000
    store.nodes = nodes

    const wrapper = mount(Dashboard, {
      global: {
        stubs: {
          'font-awesome-icon': true,
          NodeChart: true,
          StackedBarChart: true
        }
      }
    })
    wrappers.push(wrapper)
    return { wrapper, store }
  }

  it('renders page header without redundant epoch/node action-group pills', () => {
    const { wrapper } = mountDashboard([
      { id: 1, name: 'bp-1', color: '#38bdf8', type: 'pool' },
      { id: 2, name: 'relay-1', color: '#10b981', type: 'relay' }
    ])

    const pageHeader = wrapper.find('.jm-page-header')
    expect(pageHeader.exists()).toBe(true)
    expect(pageHeader.find('.jm-page-title').text()).toBe('Mission Control')
    expect(pageHeader.find('.jm-action-group').exists()).toBe(false)
    expect(pageHeader.findAll('.jm-pill')).toHaveLength(0)

    const epochHero = wrapper.find('.jm-epoch-hero')
    expect(epochHero.find('.jm-epoch-number').text()).toBe('512')
    expect(epochHero.find('.jm-epoch-pct').text()).toBe('25.0%')
  })

  it('renders Active Fleet KPI card without unlabeled nodeDots', () => {
    const { wrapper } = mountDashboard([
      { id: 1, name: 'bp-1', color: '#38bdf8', type: 'pool' },
      { id: 2, name: 'relay-1', color: '#10b981', type: 'relay' }
    ])

    const fleetCard = wrapper.findAll('.jm-kpi-card')[0]
    expect(fleetCard.text()).toContain('Active Fleet')
    expect(fleetCard.text()).toContain('2')
    expect(fleetCard.text()).toContain('1 Core/Pool')
    expect(fleetCard.text()).toContain('1 Relay')
    expect(fleetCard.findAll('.jm-dot')).toHaveLength(0)
  })
})
