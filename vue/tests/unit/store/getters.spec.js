import { describe, it, expect } from 'vitest'
import getters from '@/store/getters'

describe('store/getters', () => {
  describe('hex2ascii', () => {
    const hex2ascii = getters.hex2ascii()

    it('converts valid hex to ASCII string', () => {
      expect(hex2ascii('48656c6c6f')).toBe('Hello')
    })

    it('converts lowercase hex correctly', () => {
      expect(hex2ascii('776f726c64')).toBe('world')
    })

    it('returns original hex for non-printable characters (< 32)', () => {
      expect(hex2ascii('001f1e')).toBe('001f1e')
    })

    it('returns original hex for characters > 126', () => {
      expect(hex2ascii('ff80')).toBe('ff80')
    })

    it('handles empty string', () => {
      expect(hex2ascii('')).toBe('')
    })

    it('converts numeric string in hex', () => {
      expect(hex2ascii('313233')).toBe('123')
    })
  })

  describe('blocksCount', () => {
    it('returns the count of blocks', () => {
      const state = { blocks: [{ slot: 1 }, { slot: 2 }, { slot: 3 }] }
      expect(getters.blocksCount(state)).toBe(3)
    })

    it('returns 0 for empty blocks array', () => {
      const state = { blocks: [] }
      expect(getters.blocksCount(state)).toBe(0)
    })
  })

  describe('hostsCount', () => {
    it('returns the count of hosts', () => {
      const state = { hosts: [{ id: 1 }, { id: 2 }] }
      expect(getters.hostsCount(state)).toBe(2)
    })

    it('returns 0 for empty hosts array', () => {
      const state = { hosts: [] }
      expect(getters.hostsCount(state)).toBe(0)
    })
  })

  describe('hostSelectOptions', () => {
    it('maps hosts to select options format', () => {
      const state = {
        hosts: [
          { id: 1, hostname: 'host-a' },
          { id: 2, hostname: 'host-b' }
        ]
      }
      const result = getters.hostSelectOptions(state)
      expect(result).toEqual([
        { value: 1, text: 'host-a' },
        { value: 2, text: 'host-b' }
      ])
    })

    it('sorts hosts alphabetically by hostname', () => {
      const state = {
        hosts: [
          { id: 2, hostname: 'zebra' },
          { id: 1, hostname: 'alpha' },
          { id: 3, hostname: 'beta' }
        ]
      }
      const result = getters.hostSelectOptions(state)
      expect(result.map(h => h.text)).toEqual(['alpha', 'beta', 'zebra'])
    })

    it('returns empty array when no hosts', () => {
      const state = { hosts: [] }
      expect(getters.hostSelectOptions(state)).toEqual([])
    })
  })

  describe('epochSelectOptions', () => {
    it('maps unique epochs from blocks to options', () => {
      const state = {
        blocks: [
          { epoch: 500, slot: 1 },
          { epoch: 500, slot: 2 },
          { epoch: 499, slot: 3 }
        ]
      }
      const result = getters.epochSelectOptions(state)
      expect(result.map(e => e.value)).toEqual([500, 499])
    })

    it('orders epochs in descending order', () => {
      const state = {
        blocks: [
          { epoch: 100 },
          { epoch: 300 },
          { epoch: 200 }
        ]
      }
      const result = getters.epochSelectOptions(state)
      expect(result.map(e => e.value)).toEqual([300, 200, 100])
    })
  })

  describe('coreNodeSelectOptions', () => {
    it('filters out relay nodes', () => {
      const state = {
        nodes: [
          { name: 'core1', type: 'block-producer' },
          { name: 'relay1', type: 'relay' },
          { name: 'core2', type: 'bp' }
        ]
      }
      const result = getters.coreNodeSelectOptions(state)
      expect(result.map(n => n.value)).toEqual(['core1', 'core2'])
    })

    it('sorts nodes alphabetically by name', () => {
      const state = {
        nodes: [
          { name: 'z-core', type: 'bp' },
          { name: 'a-core', type: 'bp' }
        ]
      }
      const result = getters.coreNodeSelectOptions(state)
      expect(result.map(n => n.value)).toEqual(['a-core', 'z-core'])
    })
  })

  describe('stakingSKeys', () => {
    it('filters files matching staking.skey pattern', () => {
      const state = {
        files: [
          { text: 'pool.staking.skey' },
          { text: 'payment.skey' },
          { text: 'owner.staking.skey' },
          { text: 'cold.vkey' }
        ]
      }
      const result = getters.stakingSKeys(state)
      expect(result.map(f => f.text)).toEqual(['owner.staking.skey', 'pool.staking.skey'])
    })

    it('is case insensitive', () => {
      const state = {
        files: [
          { text: 'Pool.STAKING.SKEY' }
        ]
      }
      const result = getters.stakingSKeys(state)
      expect(result).toHaveLength(1)
    })
  })

  describe('stakingVKeys', () => {
    it('filters files matching staking.vkey pattern', () => {
      const state = {
        files: [
          { text: 'pool.staking.vkey' },
          { text: 'payment.vkey' },
          { text: 'cold.vkey' }
        ]
      }
      const result = getters.stakingVKeys(state)
      expect(result.map(f => f.text)).toEqual(['pool.staking.vkey'])
    })
  })

  describe('paymentSKeys', () => {
    it('filters files matching payment.skey pattern', () => {
      const state = {
        files: [
          { text: 'addr.payment.skey' },
          { text: 'pool.staking.skey' }
        ]
      }
      const result = getters.paymentSKeys(state)
      expect(result.map(f => f.text)).toEqual(['addr.payment.skey'])
    })

    it('matches simple alphanumeric.skey pattern', () => {
      const state = {
        files: [
          { text: 'abc123.skey' },
          { text: 'staking.skey' }
        ]
      }
      const result = getters.paymentSKeys(state)
      expect(result.map(f => f.text)).toContain('abc123.skey')
    })
  })

  describe('genesisFiles', () => {
    it('filters files matching genesis.json pattern', () => {
      const state = {
        files: [
          { text: 'mainnet-shelley-genesis.json' },
          { text: 'byron-genesis.json' },
          { text: 'config.json' }
        ]
      }
      const result = getters.genesisFiles(state)
      expect(result).toHaveLength(2)
      expect(result.map(f => f.text)).toContain('mainnet-shelley-genesis.json')
      expect(result.map(f => f.text)).toContain('byron-genesis.json')
    })
  })

  describe('displayNodes', () => {
    it('joins node with host data', () => {
      const state = {
        nodes: [
          { id: 1, name: 'node1', hostId: 10, type: 'relay', color: '#ff0000', poolId: 'pool123', isDefault: true, kesExpireTimeSec: 1000 }
        ],
        hosts: [
          { id: 10, hostname: 'server1.example.com' }
        ]
      }
      const result = getters.displayNodes(state)
      expect(result).toEqual([{
        id: 1,
        name: 'node1',
        host: 'server1.example.com',
        type: 'relay',
        color: '#ff0000',
        poolId: 'pool123',
        isDefault: true,
        kesExpireTimeSec: 1000
      }])
    })

    it('returns empty host when host not found', () => {
      const state = {
        nodes: [{ id: 1, name: 'orphan', hostId: 999 }],
        hosts: []
      }
      const result = getters.displayNodes(state)
      expect(result[0].host).toBe('')
    })
  })

  describe('walletItemById', () => {
    it('finds wallet item by id', () => {
      const state = {
        walletItems: [
          { id: 1, name: 'wallet1' },
          { id: 2, name: 'wallet2' }
        ]
      }
      const findById = getters.walletItemById(state)
      expect(findById(2)).toEqual({ id: 2, name: 'wallet2' })
    })

    it('returns default object when not found', () => {
      const state = { walletItems: [] }
      const findById = getters.walletItemById(state)
      expect(findById(999)).toEqual({ name: '' })
    })
  })

  describe('currencySelectOptions', () => {
    it('returns ada option when no native assets', () => {
      const mockGetters = { hex2ascii: (hex) => hex }
      const getCurrencyOptions = getters.currencySelectOptions({}, mockGetters)
      const result = getCurrencyOptions({ nativeAssetMap: undefined })
      expect(result).toEqual([{ value: 'ada', text: '₳ - Ada' }])
    })

    it('returns ada option when native assets map is empty', () => {
      const mockGetters = { hex2ascii: (hex) => hex }
      const getCurrencyOptions = getters.currencySelectOptions({}, mockGetters)
      const result = getCurrencyOptions({ nativeAssetMap: {} })
      expect(result).toEqual([{ value: 'ada', text: '₳ - Ada' }])
    })

    it('includes native assets with converted names', () => {
      const mockGetters = { hex2ascii: (hex) => 'TOKEN' }
      const getCurrencyOptions = getters.currencySelectOptions({}, mockGetters)
      const result = getCurrencyOptions({
        nativeAssetMap: { 'policyId.544f4b454e': 100 }
      })
      expect(result).toHaveLength(2)
      expect(result[0].value).toBe('ada')
      expect(result[1].value).toBe('policyId.544f4b454e')
    })
  })

  describe('isDebug', () => {
    it('returns boolean based on webpackHotUpdate', () => {
      const result = getters.isDebug()
      expect(typeof result).toBe('boolean')
    })
  })
})
