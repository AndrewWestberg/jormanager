import { describe, it, expect } from 'vitest'
import mutations from '@/store/mutations'

describe('store/mutations', () => {
  describe('setConnected', () => {
    it('sets connected state to true', () => {
      const state = { connected: false }
      mutations.setConnected(state, true)
      expect(state.connected).toBe(true)
    })

    it('sets connected state to false', () => {
      const state = { connected: true }
      mutations.setConnected(state, false)
      expect(state.connected).toBe(false)
    })
  })

  describe('setAppVersion', () => {
    it('sets version, mp and minUTxOValue from data', () => {
      const state = { appVersion: null, mp: null, minUTxOValue: null }
      mutations.setAppVersion(state, {
        version: '10.3.0',
        mp: 'testnet',
        minUTxOValue: 1000000
      })
      expect(state.appVersion).toBe('10.3.0')
      expect(state.mp).toBe('testnet')
      expect(state.minUTxOValue).toBe(1000000)
    })
  })

  describe('setBlocks', () => {
    it('orders blocks by slot in descending order', () => {
      const state = { blocks: [] }
      mutations.setBlocks(state, [
        { slot: 100 },
        { slot: 300 },
        { slot: 200 }
      ])
      expect(state.blocks.map(b => b.slot)).toEqual([300, 200, 100])
    })

    it('replaces existing blocks', () => {
      const state = { blocks: [{ slot: 1 }] }
      mutations.setBlocks(state, [{ slot: 999 }])
      expect(state.blocks).toEqual([{ slot: 999 }])
    })
  })

  describe('addBlock', () => {
    it('adds new block and maintains descending order', () => {
      const state = { blocks: [{ slot: 300 }, { slot: 100 }] }
      mutations.addBlock(state, { slot: 200 })
      expect(state.blocks.map(b => b.slot)).toEqual([300, 200, 100])
    })

    it('does not add duplicate block by slot', () => {
      const state = { blocks: [{ slot: 100 }] }
      mutations.addBlock(state, { slot: 100 })
      expect(state.blocks).toHaveLength(1)
    })

    it('adds block to empty array', () => {
      const state = { blocks: [] }
      mutations.addBlock(state, { slot: 50 })
      expect(state.blocks).toEqual([{ slot: 50 }])
    })

    it('adds block with highest slot at the beginning', () => {
      const state = { blocks: [{ slot: 100 }] }
      mutations.addBlock(state, { slot: 500 })
      expect(state.blocks[0].slot).toBe(500)
    })
  })

  describe('setHosts', () => {
    it('sets hosts array', () => {
      const state = { hosts: [] }
      const hosts = [{ id: 1, hostname: 'host1' }]
      mutations.setHosts(state, hosts)
      expect(state.hosts).toEqual(hosts)
    })
  })

  describe('setNodes', () => {
    it('sets nodes array', () => {
      const state = { nodes: [] }
      const nodes = [{ id: 1, name: 'node1' }]
      mutations.setNodes(state, nodes)
      expect(state.nodes).toEqual(nodes)
    })
  })

  describe('setFileOptions', () => {
    it('sets files array', () => {
      const state = { files: [] }
      const fileOptions = [{ text: 'file1.skey' }]
      mutations.setFileOptions(state, fileOptions)
      expect(state.files).toEqual(fileOptions)
    })
  })

  describe('saveWallet', () => {
    it('sets walletItems', () => {
      const state = { walletItems: [] }
      const items = [{ id: 1, name: 'wallet1' }]
      mutations.saveWallet(state, items)
      expect(state.walletItems).toEqual(items)
    })
  })

  describe('saveTxFee', () => {
    it('sets transaction fee data', () => {
      const state = {
        txFee: null,
        tokenFees: null,
        tokenKeepFee: null,
        tokenLocked: null,
        responseFeesUUID: null
      }
      mutations.saveTxFee(state, {
        txFee: 200000,
        tokenFees: 1500000,
        tokenKeepFee: 100000,
        tokenLocked: true,
        uuid: 'abc-123'
      })
      expect(state.txFee).toBe(200000)
      expect(state.tokenFees).toBe(1500000)
      expect(state.tokenKeepFee).toBe(100000)
      expect(state.tokenLocked).toBe(true)
      expect(state.responseFeesUUID).toBe('abc-123')
    })
  })

  describe('saveMetadata', () => {
    it('sets editorMetadata', () => {
      const state = { editorMetadata: null }
      const metadata = { name: 'Pool Name', ticker: 'POOL' }
      mutations.saveMetadata(state, metadata)
      expect(state.editorMetadata).toEqual(metadata)
    })
  })

  describe('setRequestFeesUUID', () => {
    it('sets requestFeesUUID', () => {
      const state = { requestFeesUUID: null }
      mutations.setRequestFeesUUID(state, 'uuid-456')
      expect(state.requestFeesUUID).toBe('uuid-456')
    })
  })

  describe('toast mutations', () => {
    it('toastError sets toastError', () => {
      const state = { toastError: null }
      const toast = { title: 'Error', message: 'Something failed' }
      mutations.toastError(state, toast)
      expect(state.toastError).toEqual(toast)
    })

    it('toastWarn sets toastWarn', () => {
      const state = { toastWarn: null }
      const toast = { title: 'Warning', message: 'Be careful' }
      mutations.toastWarn(state, toast)
      expect(state.toastWarn).toEqual(toast)
    })

    it('toastInfo sets toastInfo', () => {
      const state = { toastInfo: null }
      const toast = { title: 'Info', message: 'FYI' }
      mutations.toastInfo(state, toast)
      expect(state.toastInfo).toEqual(toast)
    })

    it('toastSuccess sets toastSuccess', () => {
      const state = { toastSuccess: null }
      const toast = { title: 'Success', message: 'It worked' }
      mutations.toastSuccess(state, toast)
      expect(state.toastSuccess).toEqual(toast)
    })
  })

  // Note: saveNodeStats tests are excluded because they rely on Vue's reactivity
  // system (__ob__.dep.notify()) which doesn't exist in plain JS objects during
  // unit testing. These would need to be tested as integration tests with a
  // real Vuex store or by refactoring the mutation to avoid direct __ob__ access.
})
