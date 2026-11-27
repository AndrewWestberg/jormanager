import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useJorManagerStore } from '@/stores/jormanager'

vi.mock('sockjs-client', () => ({ default: vi.fn() }))

describe('store/jormanager - getters', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('hex2ascii', () => {
    it('converts valid hex to ASCII string', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('48656c6c6f')).toBe('Hello')
    })

    it('converts lowercase hex correctly', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('776f726c64')).toBe('world')
    })

    it('returns original hex for non-printable characters (< 32)', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('001f1e')).toBe('001f1e')
    })

    it('returns original hex for characters > 126', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('ff80')).toBe('ff80')
    })

    it('handles empty string', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('')).toBe('')
    })

    it('converts numeric string in hex', () => {
      const store = useJorManagerStore()
      expect(store.hex2ascii('313233')).toBe('123')
    })
  })

  describe('blocksCount', () => {
    it('returns the count of blocks', () => {
      const store = useJorManagerStore()
      store.blocks = [{ slot: 1, epoch: 1 }, { slot: 2, epoch: 1 }, { slot: 3, epoch: 1 }] as any
      expect(store.blocksCount).toBe(3)
    })

    it('returns 0 for empty blocks array', () => {
      const store = useJorManagerStore()
      store.blocks = []
      expect(store.blocksCount).toBe(0)
    })
  })

  describe('hostsCount', () => {
    it('returns the count of hosts', () => {
      const store = useJorManagerStore()
      store.hosts = [{ id: 1, hostname: 'a', type: 'local' }, { id: 2, hostname: 'b', type: 'local' }] as any
      expect(store.hostsCount).toBe(2)
    })

    it('returns 0 for empty hosts array', () => {
      const store = useJorManagerStore()
      store.hosts = []
      expect(store.hostsCount).toBe(0)
    })
  })

  describe('hostSelectOptions', () => {
    it('maps hosts to select options format', () => {
      const store = useJorManagerStore()
      store.hosts = [
        { id: 1, hostname: 'host-a', type: 'local' },
        { id: 2, hostname: 'host-b', type: 'local' }
      ] as any
      const result = store.hostSelectOptions
      expect(result).toEqual([
        { value: 1, text: 'host-a' },
        { value: 2, text: 'host-b' }
      ])
    })

    it('sorts hosts alphabetically by hostname', () => {
      const store = useJorManagerStore()
      store.hosts = [
        { id: 2, hostname: 'zebra', type: 'local' },
        { id: 1, hostname: 'alpha', type: 'local' },
        { id: 3, hostname: 'beta', type: 'local' }
      ] as any
      const result = store.hostSelectOptions
      expect(result.map(h => h.text)).toEqual(['alpha', 'beta', 'zebra'])
    })

    it('returns empty array when no hosts', () => {
      const store = useJorManagerStore()
      store.hosts = []
      expect(store.hostSelectOptions).toEqual([])
    })
  })

  describe('epochSelectOptions', () => {
    it('maps unique epochs from blocks to options', () => {
      const store = useJorManagerStore()
      store.blocks = [
        { epoch: 500, slot: 1 },
        { epoch: 500, slot: 2 },
        { epoch: 499, slot: 3 }
      ] as any
      const result = store.epochSelectOptions
      expect(result.map(e => e.value)).toEqual([500, 499])
    })

    it('orders epochs in descending order', () => {
      const store = useJorManagerStore()
      store.blocks = [
        { epoch: 100, slot: 1 },
        { epoch: 300, slot: 2 },
        { epoch: 200, slot: 3 }
      ] as any
      const result = store.epochSelectOptions
      expect(result.map(e => e.value)).toEqual([300, 200, 100])
    })
  })

  describe('coreNodeSelectOptions', () => {
    it('filters out relay nodes', () => {
      const store = useJorManagerStore()
      store.nodes = [
        { id: 1, name: 'core1', type: 'block-producer', color: '#000' },
        { id: 2, name: 'relay1', type: 'relay', color: '#000' },
        { id: 3, name: 'core2', type: 'bp', color: '#000' }
      ] as any
      const result = store.coreNodeSelectOptions
      expect(result.map(n => n.value)).toEqual(['core1', 'core2'])
    })

    it('sorts nodes alphabetically by name', () => {
      const store = useJorManagerStore()
      store.nodes = [
        { id: 1, name: 'z-core', type: 'bp', color: '#000' },
        { id: 2, name: 'a-core', type: 'bp', color: '#000' }
      ] as any
      const result = store.coreNodeSelectOptions
      expect(result.map(n => n.value)).toEqual(['a-core', 'z-core'])
    })
  })

  describe('displayNodes', () => {
    it('joins node with host data', () => {
      const store = useJorManagerStore()
      store.nodes = [
        { id: 1, name: 'node1', hostId: 10, type: 'relay', color: '#ff0000', poolId: 'pool123', isDefault: true, kesExpireTimeSec: 1000 }
      ] as any
      store.hosts = [
        { id: 10, hostname: 'server1.example.com', type: 'local' }
      ] as any
      const result = store.displayNodes
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
      const store = useJorManagerStore()
      store.nodes = [{ id: 1, name: 'orphan', hostId: 999, color: '#000' }] as any
      store.hosts = []
      const result = store.displayNodes
      expect((result[0] as any).host).toBe('')
    })
  })

  describe('walletItemById', () => {
    it('finds wallet item by id', () => {
      const store = useJorManagerStore()
      store.walletItems = [
        { id: 1, name: 'wallet1', paymentAddress: '', paymentAddrLovelace: '0', stakingAddrLovelace: '0', nativeAssetMap: {} },
        { id: 2, name: 'wallet2', paymentAddress: '', paymentAddrLovelace: '0', stakingAddrLovelace: '0', nativeAssetMap: {} }
      ]
      expect(store.walletItemById(2).name).toBe('wallet2')
    })

  })


  describe('saveNodeStats', () => {
    it('ignores null chart samples and preserves existing epoch values', () => {
      const store = useJorManagerStore()
      store.epoch = 38855
      store.slot = 1234

      store.saveNodeStats([
        {
          nodeName: 'GPOOL',
          timestamp: 1000,
          blockHeight: null,
          peers: null,
          incomingPeers: null,
          remainingKESPeriods: null,
          epoch: null,
          slotInEpoch: null,
          epochLength: 432000,
          txsProcessed: null,
          color: '#fff',
          isDefault: true
        }
      ])

      expect(store.epoch).toBe(38855)
      expect(store.slot).toBe(1234)
      expect(store.blockHeightSeries).toEqual([])
      expect(store.peersSeries).toEqual([])
      expect(store.incomingPeersSeries).toEqual([])
      expect(store.txsProcessedSeries).toEqual([])
    })

    it('uses the default node for epoch and slot tracking', () => {
      const store = useJorManagerStore()

      store.saveNodeStats([
        {
          nodeName: 'relay-a',
          timestamp: 1000,
          blockHeight: 1,
          peers: 2,
          incomingPeers: 3,
          remainingKESPeriods: 10,
          epoch: 400,
          slotInEpoch: 50,
          epochLength: 432000,
          txsProcessed: 5,
          color: '#111',
          isDefault: false
        },
        {
          nodeName: 'GPOOL',
          timestamp: 1001,
          blockHeight: 2,
          peers: 4,
          incomingPeers: 5,
          remainingKESPeriods: 11,
          epoch: 401,
          slotInEpoch: 60,
          epochLength: 432000,
          txsProcessed: 6,
          color: '#222',
          isDefault: true
        }
      ])

      expect(store.epoch).toBe(401)
      expect(store.slot).toBe(60)
    })

    it('recognizes the live websocket default field', () => {
      const store = useJorManagerStore()

      store.saveNodeStats([
        {
          nodeName: 'GPOOL',
          timestamp: 1001,
          blockHeight: 2,
          peers: 4,
          incomingPeers: 5,
          remainingKESPeriods: 11,
          epoch: 401,
          slotInEpoch: 60,
          epochLength: 432000,
          txsProcessed: 6,
          color: '#222',
          default: true
        }
      ])

      expect(store.epoch).toBe(401)
      expect(store.slot).toBe(60)
    })

    it('uses the first non-null epoch when no default node has reported yet', () => {
      const store = useJorManagerStore()

      store.saveNodeStats([
        {
          nodeName: 'core-a',
          timestamp: 1000,
          blockHeight: 3,
          peers: 1,
          incomingPeers: 2,
          remainingKESPeriods: 12,
          epoch: 402,
          slotInEpoch: 70,
          epochLength: 432000,
          txsProcessed: 7,
          color: '#333',
          isDefault: false
        }
      ])

      expect(store.epoch).toBe(402)
      expect(store.slot).toBe(70)
    })

    it('replaces series array entries when appending chart points', () => {
      const store = useJorManagerStore()

      store.saveNodeStats([
        {
          nodeName: 'GPOOL',
          timestamp: 1000,
          blockHeight: 10,
          peers: 2,
          incomingPeers: 1,
          remainingKESPeriods: 11,
          epoch: 401,
          slotInEpoch: 60,
          epochLength: 432000,
          txsProcessed: 6,
          color: '#222',
          default: true
        }
      ])

      const previousBlockSeriesEntry = store.blockHeightSeries[0]
      const previousPeerSeriesEntry = store.peersSeries[0]
      const previousIncomingSeriesEntry = store.incomingPeersSeries[0]
      const previousTxSeriesEntry = store.txsProcessedSeries[0]

      store.saveNodeStats([
        {
          nodeName: 'GPOOL',
          timestamp: 2000,
          blockHeight: 11,
          peers: 3,
          incomingPeers: 2,
          remainingKESPeriods: 11,
          epoch: 401,
          slotInEpoch: 61,
          epochLength: 432000,
          txsProcessed: 7,
          color: '#222',
          default: true
        }
      ])

      expect(store.blockHeightSeries[0]).not.toBe(previousBlockSeriesEntry)
      expect(store.peersSeries[0]).not.toBe(previousPeerSeriesEntry)
      expect(store.incomingPeersSeries[0]).not.toBe(previousIncomingSeriesEntry)
      expect(store.txsProcessedSeries[0]).not.toBe(previousTxSeriesEntry)
      expect(store.blockHeightSeries[0].data).toEqual([[1000, 10], [2000, 11]])
    })
  })

  describe('wallet fee planner metadata', () => {
    it('stores selectionMode, inputCount, outputCount, and estimatedTxSize from fee response', () => {
      const store = useJorManagerStore()

      store.connected = true
      store.requestFeesUUID = 'test-uuid-defrag'
      store.handleMessage({ type: 'calculatefee', data: {
        txFee: '342100',
        tokenFees: ['0'],
        tokenKeepFee: '1450000',
        tokenLocked: '0',
        selectionMode: 'DEFRAGMENT',
        inputCount: 84,
        outputCount: 4,
        estimatedTxSize: 4920,
        uuid: 'test-uuid-defrag'
      } })

      expect(store.txFee).toBe('342100')
      expect(store.tokenKeepFee).toBe('1450000')
      expect(store.planSelectionMode).toBe('DEFRAGMENT')
      expect(store.planInputCount).toBe(84)
      expect(store.planOutputCount).toBe(4)
      expect(store.planEstimatedTxSize).toBe(4920)
      expect(store.responseFeesUUID).toBe('test-uuid-defrag')

      store.invalidateSendAdaFees()
      expect(store.planSelectionMode).toBe('STANDARD')
      expect(store.planInputCount).toBe(0)
      expect(store.planOutputCount).toBe(0)
      expect(store.planEstimatedTxSize).toBe(0)

      expect(store.requestFeesUUID).toBe('')
      expect(store.responseFeesUUID).toBe('')
    })
  })
})

describe('wallet responses', () => {
  it('keeps exact balances without chain-readiness state and preserves them on errors', () => {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    const item = { id: 101, name: 'Payment', paymentAddress: 'addr', paymentAddrLovelace: '9007199254740993', stakingAddrLovelace: '0', nativeAssetMap: { asset: '18446744073709551615' } }
    store.handleMessage({ type: 'wallet', data: [item] })
    expect(store.walletItems).toEqual([item])
    store.handleMessage({ type: 'wallet', exception: { message: 'Database failed' } })
    expect(store.walletItems).toEqual([item])
    expect(store.toastError?.message).toBe('Database failed')
  })
})

describe('direct transaction requests', () => {
  const id = '12345678-1234-4234-8234-123456789abc'
  const result = {
    requestId: id, txId: 'tx', networkKey: 'network', era: 'Conway',
    fee: '170000', deposit: '0', refund: '0', state: 'submitted'
  }

  function setup() {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.stompClient = { connected: true } as typeof store.stompClient
    store.walletNetworkKey = 'network'
    const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
    return { store, send }
  }

  it('sends direct wallet transactions and reports errors without locking', () => {
    const { store, send } = setup()
    store.connected = true
    const request = { fromId: 1, txFee: '170000', spendingPassword: 'secret' }
    store.submitTransaction(request)
    store.submitTransaction(request)
    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith('/jormanager/submittransaction', request)
    store.handleMessage({ type: 'submittransaction', exception: { message: 'Rejected' } })
    expect(store.toastError?.message).toBe('Rejected')
    store.submitTransaction(request)
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('refreshes the wallet after direct defragmentation so pending balances can be shown', () => {
    const { store, send } = setup()
    store.connected = true
    store.submitTransaction({ fromId: 1, txFee: '170000', selectionMode: 'DEFRAGMENT' })
    store.handleMessage({ type: 'submittransaction', data: 'Submitted' })
    expect(store.walletSubmitting).toBe(false)
    expect(send).toHaveBeenLastCalledWith('/jormanager/wallet')
  })

  it('sends governance directly and preserves a public failure code', async () => {
    const { store, send } = setup()
    const request = { govActionId: 'action', votes: [], feesAccountId: 1 }
    const pending = store.submitGovernance(id, request, 'secret')
    expect(send).toHaveBeenCalledWith('/jormanager/governancesubmit', { requestId: id, request, spendingPassword: 'secret' })
    store.handleMessage({ type: 'governancesubmit', exception: { requestId: id, message: 'Fee changed', code: 'FEE_CHANGED' } })
    await expect(pending).rejects.toMatchObject({ message: 'Fee changed', code: 'FEE_CHANGED' })
  })

  it('rejects invalid identifiers and mismatched targets without dispatching twice', async () => {
    const { store, send } = setup()
    await expect(store.socketRequest('/test', 'test', { requestId: 'bad' })).rejects.toThrow('Invalid request ID')
    expect(send).not.toHaveBeenCalled()
    const pending = store.socketRequest('/jormanager/pool/retirement/submit', 'pool_retirement_submit', { requestId: id, nodeId: 5 })
    store.handleMessage({ type: 'pool_retirement_submit', data: { ...result, nodeId: 6 } })
    await expect(pending).rejects.toThrow('target')
  })


  it('does not dispatch while disconnected', () => {
    const { store, send } = setup()
    store.connected = false
    store.submitTransaction({})
    expect(send).not.toHaveBeenCalled()
  })

  it('does not expose retired pool STOMP actions that lack backend handlers', () => {
    const { store } = setup()
    expect('updatePoolConfig' in store).toBe(false)
    expect('updateMetadata' in store).toBe(false)
    expect('sendEditRelays' in store).toBe(false)
    expect('sendRetirePool' in store).toBe(false)
  })
})

describe('fee estimates', () => {
  it('accepts only matching exact fee responses', () => {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.connected = true
    const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
    store.calculateSendAdaFees({ uuid: 'first' })
    store.calculateSendAdaFees({ uuid: 'second' })
    const data = { uuid: 'first', txFee: '9007199254740999', tokenKeepFee: '0', tokenLocked: '0', tokenFees: ['123'] }
    store.handleMessage({ type: 'calculatefee', data })
    expect(store.responseFeesUUID).toBe('')
    store.handleMessage({ type: 'calculatefee', data: { ...data, uuid: 'second', txFee: 175000 } })
    expect(store.responseFeesUUID).toBe('')
    store.handleMessage({ type: 'calculatefee', data: { ...data, uuid: 'second' } })
    expect(store.txFee).toBe('9007199254740999')
    expect(store.responseFeesUUID).toBe('second')
    expect(store.planSelectionMode).toBe('STANDARD')
    expect(store.planInputCount).toBe(0)
    expect(send).toHaveBeenCalledTimes(2)
    store.invalidateSendAdaFees()
    store.handleMessage({ type: 'calculatefee', data: { ...data, uuid: 'second' } })
    expect(store.responseFeesUUID).toBe('')
  })
})

describe('SSH host key fingerprint TOFU & mismatch store actions', () => {
  it('tracks mismatched remote hosts, surfaces security alert toasts, and resolves updateHostFingerprint', async () => {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    store.stompClient = { connected: true } as typeof store.stompClient
    const send = vi.spyOn(store, 'sendMessage').mockImplementation(() => {})

    store.handleMessage({
      type: 'hosts',
      data: [
        { id: 1, type: 'local', hostname: 'localhost', sshFingerprint: null, sshMismatchFingerprint: null },
        { id: 2, type: 'remote', hostname: 'relay1', sshFingerprint: 'SHA256:AAA', sshMismatchFingerprint: null },
        { id: 3, type: 'remote', hostname: 'bp1', sshFingerprint: 'SHA256:AAA', sshMismatchFingerprint: 'SHA256:BBB' }
      ]
    })
    expect(store.mismatchedHosts.map(h => h.id)).toEqual([3])

    store.handleMessage({
      type: 'host_key_mismatch',
      data: {
        hostId: 3,
        hostname: 'bp1',
        sshPort: 22,
        expectedFingerprint: 'SHA256:AAA',
        receivedFingerprint: 'SHA256:BBB',
        message: 'SSH host key mismatch for bp1:22!'
      }
    })
    expect(store.lastHostKeyMismatch?.receivedFingerprint).toBe('SHA256:BBB')
    expect(store.toastError).toEqual({
      title: 'SSH Host Key Mismatch!',
      message: 'SSH host key mismatch for bp1:22!'
    })

    const reqId = '12345678-1234-4234-8234-123456789abc'
    const pending = store.updateHostFingerprint(reqId, {
      hostId: 3,
      action: 'accept',
      fingerprint: 'SHA256:BBB',
      spendingPassword: 'secret'
    })
    expect(send).toHaveBeenCalledWith('/jormanager/host/fingerprint', {
      requestId: reqId,
      hostId: 3,
      action: 'accept',
      fingerprint: 'SHA256:BBB',
      spendingPassword: 'secret'
    })

    store.handleMessage({
      type: 'host_fingerprint_updated',
      data: {
        requestId: reqId,
        hostId: 3,
        action: 'accept',
        sshFingerprint: 'SHA256:BBB',
        message: 'Updated trusted SSH host key fingerprint for bp1.'
      }
    })

    await expect(pending).resolves.toMatchObject({
      hostId: 3,
      action: 'accept',
      sshFingerprint: 'SHA256:BBB'
    })
    expect(store.toastSuccess?.title).toBe('SSH Host Key Updated')
  })
})
