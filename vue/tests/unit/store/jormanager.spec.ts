import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useJorManagerStore } from '@/stores/jormanager'

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
})

describe('wallet coverage availability', () => {
  const identity = {
    id: 101, name: 'Imported payment', type: 'payment', paymentAddr: 'addr_test_payment',
    hasPaymentKeys: true, stakingAddr: null
  }
  const unknown = {
    paymentAddrUtxoCount: null, paymentAddrLovelace: null, stakingAddrRegistered: null,
    stakingAddrLovelace: null, nativeAssetMap: null
  }
  const available = {
    paymentAddrUtxoCount: 1, paymentAddrLovelace: '9007199254740993', stakingAddrRegistered: false,
    stakingAddrLovelace: '0', nativeAssetMap: { asset: '18446744073709551615' }
  }
  function setup() {
    setActivePinia(createPinia())
    const store = useJorManagerStore()
    vi.spyOn(store, 'sendMessage').mockImplementation(() => {})
    const state = (status = 'complete', walletReady = true, generation = '9007199254740993', networkKey = 'network') =>
      ({ networkKey, status, walletReady, generation, reason: null, observedTip: null, lastCompletePoint: null })
    const status = (readState = state()) => store.handleMessage({ type: 'chainstatus', data: readState })
    const wallet = (readState = state(), item: typeof available | typeof unknown = available) => store.handleMessage({
      type: 'wallet', data: { networkKey: readState.networkKey, readState, items: [{ ...identity, ...item }] }
    })
    return { store, state, status, wallet }
  }

  it('keeps metadata and nulls while initially unavailable', () => {
    const { store, state, wallet } = setup()
    store.handleMessage({ type: 'createwalletentry', data: 'Saved imported entry' })
    wallet(state('syncing', false), unknown)
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    expect(store.toastSuccess).not.toBeNull()
    expect(store.toastError).toBeNull()
    expect(store.paymentSelectOptions()).toEqual([{ value: 101, text: 'Imported payment - Unavailable' }])
    expect(store.feeAccountOptions(0n)).toEqual([])
  })

  it('recovers initial catch-up and a new session only after readiness', () => {
    const { store, state, status, wallet } = setup()
    wallet(state('syncing', false), unknown)
    wallet(state('complete', true))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    status(state('syncing', false, '9007199254740994'))
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet(state('complete', true, '9007199254740994'))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
  })

  it('retains exact balances and reviews through ordinary following and refresh', () => {
    const { store, state, status, wallet } = setup()
    wallet()
    const count = store.networkInvalidations.network ?? 0
    expect(store.walletItems[0].paymentAddrLovelace).toBe('9007199254740993')
    expect(store.walletItems[0].nativeAssetMap).toEqual({ asset: '18446744073709551615' })
    status(state('syncing'))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    expect(store.networkInvalidations.network ?? 0).toBe(count)
    store.requestChainStatus('network')
    expect(store.chainReadState?.walletReady).toBe(true)
    const send = vi.mocked(store.sendMessage)
    const calls = send.mock.calls.length
    wallet(state('syncing'))
    expect(send.mock.calls.length).toBe(calls)
    status(state('complete'))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    expect(store.networkInvalidations.network ?? 0).toBe(count)
  })

  it('fences older generations and invalidates when a missed boundary advances generation', () => {
    const { store, state, status, wallet } = setup()
    wallet()
    const old = state()
    status(state('syncing', true, '9007199254740994'))
    expect(store.networkInvalidations.network).toBe(1)
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet(old)
    status(old)
    expect(store.chainReadState?.generation).toBe('9007199254740994')
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet(state('syncing', true, '9007199254740994'))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    status(state('incomplete', false, '9007199254740995'))
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet(state('complete', true, '9007199254740994'))
    expect(store.chainReadState?.walletReady).toBe(false)
    wallet(state('complete', true, '9007199254740996'))
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
  })

  it('rejects obsolete correlated responses and recovers from status errors in the same generation', () => {
    const { store, state, wallet } = setup()
    wallet()
    store.requestChainStatus('network')
    const requestId = store.chainStatusRequestId!
    store.handleMessage({ type: 'chainstatus', data: { ...state('incomplete', false), requestId: 'old' } })
    store.handleMessage({ type: 'chainstatus', data: { ...state('incomplete', false, '9007199254740993', 'other'), requestId } })
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    store.handleMessage({ type: 'chainstatus', exception: { message: 'Status failed', requestId, target: 'network' } })
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    expect(store.chainReadState).toBeNull()
    store.handleMessage({ type: 'chainstatus', data: { ...state(), requestId } })
    expect(store.chainReadState).toBeNull()
    store.requestChainStatus('network')
    store.handleMessage({ type: 'chainstatus', data: { ...state(), requestId: store.chainStatusRequestId } })
    expect(store.chainReadState?.walletReady).toBe(true)
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet()
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
    store.invalidateAllNetworkObservations()
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet(state('complete', true, '9007199254740992'))
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet()
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
  })

  it('fails closed on missing readiness and rejects wrong-network wallet snapshots', () => {
    const { store, state, wallet } = setup()
    wallet()
    store.handleMessage({ type: 'wallet', data: {
      networkKey: 'other', readState: state(), items: [{ ...identity, ...available }]
    } })
    expect(store.walletNetworkKey).toBe('network')
    const missing = { ...state(), walletReady: undefined }
    store.handleMessage({ type: 'chainstatus', data: missing })
    expect(store.chainReadState?.walletReady).toBe(false)
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    wallet()
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
  })

  it('merges explicit non-default node status and keeps newer broadcasts', () => {
    const { store, state, status } = setup()
    store.connected = true
    store.nodes = [{
      id: 42, name: 'node', hostId: 1, status: '', color: '', isDraft: false,
      host: {
        type: 'local', hostname: '', sshUser: '', sshPort: 22, sshPemPath: '',
        cardanoCliPath: '', cardanoNodePath: '', nodeHomePath: '', jcliPath: null
      }
    }]
    store.requestNodeIndexStatus(42)
    const requestId = store.nodeIndexRequests[42]
    const other = state('syncing', true, '9007199254740994', 'other')
    store.handleMessage({ type: 'node_chainstatus', data: {
      requestId, nodeId: 42, readState: other, replay: { networkKey: 'other' }
    } })
    expect(store.networkReadStates.other).toEqual(other)
    expect(store.nodeIndexStatus[42].readState).toEqual(other)
    status(state('incomplete', false, '9007199254740995', 'other'))
    store.requestNodeIndexStatus(42)
    store.handleMessage({ type: 'node_chainstatus', data: {
      requestId: store.nodeIndexRequests[42], nodeId: 42, readState: other, replay: { networkKey: 'other' }
    } })
    expect(store.nodeIndexStatus[42].readState?.generation).toBe('9007199254740995')
    expect(store.networkReadStates.other.walletReady).toBe(false)
  })

  it('retains saved entries on refresh errors', () => {
    const { store, wallet } = setup()
    store.handleMessage({ type: 'createwalletentry', exception: { message: 'Save rejected' } })
    expect(store.toastError?.message).toBe('Save rejected')
    wallet()
    store.handleMessage({ type: 'wallet', exception: { message: 'Database failed' } })
    expect(store.walletItems).toEqual([{ ...identity, ...unknown }])
    expect(store.toastError?.message).toBe('Database failed')
    wallet()
    expect(store.walletItems).toEqual([{ ...identity, ...available }])
  })
})
