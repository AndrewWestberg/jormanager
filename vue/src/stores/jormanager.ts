import { defineStore } from 'pinia'
import SockJS from 'sockjs-client'
import Stomp, { type Client as StompClient, type Message } from 'webstomp-client'
import { orderBy, unionWith, findIndex, sortBy, find, filter, map, uniqBy } from 'lodash-es'
import { generateUUID, lovelaceToAda } from '@/utils/filters'
import JSONBIG from 'json-bigint'
import type {
  Host,
  Node,
  Block,
  FileOption,
  WalletItem,
  WalletResult,
  SeriesDataPoint,
  KESSeries,
  NodeStatEvent,
  Toast,
  AppVersionData,
  ChainReadState,
  NodeIndexStatus,
  LedgerReplayCoverage,
  TransactionResult,
  TxFeeData
} from '@/types'

const exactJson = JSONBIG({ storeAsString: true })
const pendingRequests = new Map<string, {
  type: string
  nodeId?: unknown
  action?: unknown
  keyId?: unknown
  networkKey?: string | null
  target?: Record<string, unknown>
  resolve: (data: unknown) => void
  reject: (error: Error) => void
  timer: number
}>()

type LeiosStatusValue = {
  requestId: string
  target: { nodeId: number; hostId: number; networkKey?: string | null }
  era?: string | null
  readState: { status: string; reasonCode: string; affectedData: string[]; [key: string]: unknown }
  [key: string]: unknown
}

function staleLeios(value: LeiosStatusValue): LeiosStatusValue {
  return {
    ...value,
    era: null,
    readState: {
      ...value.readState,
      status: 'stale',
      reasonCode: 'CHAIN_INDEX_CHANGED',
      affectedData: [...new Set([...(value.readState?.affectedData ?? []), 'registration', 'eligibility', 'selection'])]
    }
  }
}

interface SelectOption {
  value: string | number
  text: string
}

interface JorManagerState {
  // Connection state
  connected: boolean
  stompClient: StompClient | null

  // App info
  appVersion: string
  mp: boolean
  minUTxOValue: number

  // Toast notifications
  toastError: Toast | null
  toastWarn: Toast | null
  toastInfo: Toast | null
  toastSuccess: Toast | null

  // Data
  hosts: Host[]
  nodes: Node[]
  blocks: Block[]
  files: FileOption[]
  walletItems: WalletItem[]
  leiosStatus: unknown | null
  leiosStatusError: string | null
  leiosStatusRequest: { requestId: string; nodeId: number; hostId: number; networkKey: string | null } | null
  leiosResponses: Record<string, { data?: unknown; exception?: { message: string; requestId?: string } }>
  walletNetworkKey: string | null
  chainReadState: ChainReadState | null
  chainStatusRequestId: string | null
  nodeIndexStatus: Record<number, NodeIndexStatus>
  nodeIndexRequests: Record<number, string>
  networkInvalidations: Record<string, number>
  networkReadStates: Record<string, ChainReadState>
  txFee: string
  tokenFees: string[]
  tokenKeepFee: string
  tokenLocked: string
  requestFeesUUID: string
  responseFeesUUID: string
  feeTarget: { networkKey: string; generation: string } | null


  // Chart data
  nodeColors: string[]
  peersSeries: SeriesDataPoint[]
  blockHeightSeries: SeriesDataPoint[]
  incomingPeersSeries: SeriesDataPoint[]
  remainingKESSeriesCategories: string[]
  remainingKESSeriesCategoryLabels: string[]
  remainingKESSeries: KESSeries[]
  txsProcessedSeries: SeriesDataPoint[]

  // Epoch/slot tracking
  epoch: number
  slot: number
  epochLength: number

  // Editor state
  editorMetadata: unknown
}

export const useJorManagerStore = defineStore('jormanager', {
  state: (): JorManagerState => ({
    // Connection state
    connected: false,
    stompClient: null,

    // App info
    appVersion: '---',
    mp: false,
    minUTxOValue: 1000000,

    // Toast notifications
    toastError: null,
    toastWarn: null,
    toastInfo: null,
    toastSuccess: null,

    // Data
    hosts: [],
    nodes: [],
    blocks: [],
    files: [],
    walletItems: [],
    leiosStatus: null,
    leiosStatusError: null,
    leiosStatusRequest: null,
    leiosResponses: {},
    walletNetworkKey: null,
    chainReadState: null,
    chainStatusRequestId: null,
    nodeIndexStatus: {},
    nodeIndexRequests: {},
    networkInvalidations: {},
    networkReadStates: {},
    txFee: '0',
    tokenFees: [],
    tokenKeepFee: '0',
    tokenLocked: '0',
    requestFeesUUID: '',
    responseFeesUUID: '',
    feeTarget: null,


    // Chart data
    nodeColors: [],
    peersSeries: [],
    blockHeightSeries: [],
    incomingPeersSeries: [],
    remainingKESSeriesCategories: [],
    remainingKESSeriesCategoryLabels: [],
    remainingKESSeries: [
      { name: 'Low', data: [] },
      { name: 'Ok', data: [] },
      { name: 'All Good', data: [] }
    ],
    txsProcessedSeries: [],

    // Epoch/slot tracking
    epoch: 0,
    slot: 0,
    epochLength: 432000,

    // Editor state
    editorMetadata: null
  }),

  getters: {
    isDebug: (): boolean => {
      return import.meta.env.DEV
    },

    websocketUrl(): string {
      return this.isDebug
        ? 'http://localhost:9797/jormanager-websocket'
        : '/jormanager-websocket'
    },

    backupDownloadUrl(): string {
      return this.isDebug
        ? 'http://localhost:9797/jormanager_backup.zip'
        : '/jormanager_backup.zip'
    },

    blocksCount: (state): number => state.blocks.length,
    hostsCount: (state): number => state.hosts.length,

    hostSelectOptions: (state): SelectOption[] => {
      return sortBy(
        map(state.hosts, (host) => ({
          value: host.id!,
          text: host.hostname
        })),
        ['text']
      )
    },

    epochSelectOptions: (state): SelectOption[] => {
      return orderBy(
        map(
          uniqBy(state.blocks, (block) => block.epoch),
          (block) => ({
            value: block.epoch,
            text: String(block.epoch)
          })
        ),
        ['text'],
        ['desc']
      )
    },

    coreNodeSelectOptions: (state): SelectOption[] => {
      return sortBy(
        map(
          filter(state.nodes, (node) => (node as any).type !== 'relay'),
          (node) => ({
            value: node.name,
            text: node.name
          })
        ),
        ['text']
      )
    },

    stakingSKeys: (state): FileOption[] => {
      return sortBy(
        filter(state.files, (file) => /.*\.staking\.skey/i.test((file as any).text)),
        ['text']
      )
    },

    stakingVKeys: (state): FileOption[] => {
      return sortBy(
        filter(state.files, (file) => /.*\.staking\.vkey/i.test((file as any).text)),
        ['text']
      )
    },

    paymentSKeys: (state): FileOption[] => {
      return sortBy(
        filter(state.files, (file) => /^[a-z0-9]*\.skey|.*\.payment\.skey/i.test((file as any).text)),
        ['text']
      )
    },

    paymentVKeys: (state): FileOption[] => {
      return sortBy(
        filter(state.files, (file) => /^[a-z0-9]*\.vkey|.*\.payment\.vkey/i.test((file as any).text)),
        ['text']
      )
    },

    genesisFiles: (state): FileOption[] => {
      return sortBy(
        filter(state.files, (file) => /.*genesis\.json/i.test((file as any).text)),
        ['text']
      )
    },

    displayNodes: (state): unknown[] => {
      return map(state.nodes, (node) => {
        const host = find(state.hosts, (h) => (node as any).hostId === h.id)
        return {
          id: node.id,
          color: node.color,
          name: node.name,
          poolId: (node as any).poolId,
          type: (node as any).type,
          host: host ? host.hostname : '',
          isDefault: (node as any).isDefault,
          kesExpireTimeSec: (node as any).kesExpireTimeSec
        }
      })
    }
  },

  actions: {
    // Helper getters that need to be functions
    currencySelectOptions(walletItem: WalletItem): SelectOption[] {
      const options: SelectOption[] = [{ value: 'ada', text: '₳ - Ada' }]
      if (!walletItem.nativeAssetMap) return options

      const assetKeys = Object.keys(walletItem.nativeAssetMap)
      if (assetKeys.length === 0) return options

      return options.concat(
        sortBy(
          map(assetKeys, (assetKey) => ({
            value: assetKey,
            text: this.hex2ascii(assetKey.substring(assetKey.indexOf('.') + 1))
          })),
          ['text']
        )
      )
    },

    paymentSelectOptions(): SelectOption[] {
      return sortBy(this.walletItems.map(item => ({ value: item.id, text: `${item.name} - ${lovelaceToAda(item.paymentAddrLovelace)}` })), ['text'])
    },

    registrationFeesSelectOptions(): SelectOption[] {
      return this.feeAccountOptions(3000000n)
    },

    reregistrationFeesSelectOptions(): SelectOption[] {
      return this.feeAccountOptions(1000000n)
    },

    stakingFeesSelectOptions(): SelectOption[] {
      return this.feeAccountOptions(3000000n)
    },

    feeAccountOptions(minimum: bigint): SelectOption[] {
      return sortBy(this.walletItems.filter(item =>
        item.type !== 'address' && item.hasPaymentKeys &&
        typeof item.paymentAddrLovelace === 'string' &&
        /^\d+$/.test(item.paymentAddrLovelace) && BigInt(item.paymentAddrLovelace) > minimum
      ).map(item => ({ value: item.id, text: `${item.name} - ${lovelaceToAda(item.paymentAddrLovelace)}` })), ['text'])
    },

    stakingSelectOptions(): SelectOption[] {
      return sortBy(this.walletItems.filter(item => item.type === 'stake' || item.type === 'pledge')
        .map(item => ({ value: item.id, text: `${item.name} - ${lovelaceToAda(item.paymentAddrLovelace)}` })), ['text'])
    },

    rewardsSelectOptions(): SelectOption[] {
      return sortBy(this.walletItems.filter(item => item.type === 'stake')
        .map(item => ({ value: item.id, text: `${item.name} - ${lovelaceToAda(item.paymentAddrLovelace)}` })), ['text'])
    },

    walletItemById(walletId: number): WalletItem {
      return find(this.walletItems, (walletItem) => walletItem.id === walletId) ||
        { id: 0, name: '', paymentAddress: '', paymentAddrLovelace: '0', stakingAddrLovelace: '0', nativeAssetMap: {} }
    },

    hex2ascii(hexx: string): string {
      const hex = hexx.toString()
      let str = ''
      for (let i = 0; i < hex.length; i += 2) {
        const intVal = parseInt(hex.substr(i, 2), 16)
        if (intVal < 32 || intVal > 126) {
          return hexx
        }
        str += String.fromCharCode(intVal)
      }
      return str
    },

    // WebSocket connection actions
    connectToServer() {
      const socket = new SockJS(this.websocketUrl)
      const stompClient = Stomp.over(socket, { debug: false })
      this.stompClient = stompClient

      stompClient.connect(
        {},
        () => {
          this.subscribeToMessages()
        },
        (error: unknown) => {
          this.connected = false
          for (const pending of pendingRequests.values()) {
            clearTimeout(pending.timer)
            pending.reject(new Error('Disconnected; the operation outcome may be uncertain'))
          }
          pendingRequests.clear()
          this.invalidateAllNetworkObservations()
          this.nodeIndexStatus = {}
          this.nodeIndexRequests = {}
          console.error('WebSocket connection error:', error)
        }
      )
    },

    subscribeToMessages() {
      if (!this.stompClient) return

      this.stompClient.subscribe('/topic/messages', (tick: Message) => {
        const message = exactJson.parse(tick.body) as { type: string; data?: unknown; exception?: { message: string; requestId?: string; target?: string; code?: string } }
        this.handleMessage(message)
      })
      this.invalidateAllNetworkObservations()
      this.connected = true
      this.nodeIndexStatus = {}
      this.nodeIndexRequests = {}
      this.requestNodes()
    },

    handleMessage(message: { type: string; data?: unknown; exception?: { message: string; requestId?: string; target?: string; code?: string } }) {
      switch (message.type) {
        case 'walletsubmit':
        case 'governancesubmit':
        case 'pool_retirement_submit':
        case 'createnode':
        case 'node_setup_context':
        case 'leios_key_prepared':
        case 'leios_key_imported':
        case 'leios_key_installation':
        case 'leios_registration_submitted': {
          if (message.type.startsWith('leios_registration_') || message.type.startsWith('leios_key_')) {
            this.leiosResponses = { ...this.leiosResponses, [message.type]: { data: message.data, exception: message.exception } }
          }
          if (message.type === 'createnode') {
            const acknowledgement = message.data
            if (acknowledgement && typeof acknowledgement === 'object' && 'message' in acknowledgement && typeof acknowledgement.message === 'string') {
              this.toastSuccess = { title: 'Node Created', message: acknowledgement.message }
            } else if (message.exception) this.toastError = { title: 'Node Save Error', message: message.exception.message }
          }
          const data = message.data as { requestId: string; nodeId?: unknown; action?: unknown; keyId?: unknown; networkKey?: string; target?: Record<string, unknown> } | undefined
          const requestId = data?.requestId || message.exception?.requestId
          const pending = requestId ? pendingRequests.get(requestId) : undefined
          if (!pending || pending.type !== message.type) break
          clearTimeout(pending.timer)
          pendingRequests.delete(requestId!)
          if (data) {
            if ((pending.nodeId !== undefined && data.nodeId !== pending.nodeId) ||
                (pending.action !== undefined && data.action !== pending.action) ||
                (pending.keyId !== undefined && data.keyId !== pending.keyId) ||
                (pending.networkKey && data.networkKey !== pending.networkKey) ||
                (pending.target && Object.entries(pending.target).some(([key, value]) => value != null && data.target?.[key] !== value))) {
              pending.reject(new Error('Response target did not match the request'))
            } else pending.resolve(data)
          } else {
            const error = new Error(message.exception?.message || 'Operation failed') as Error & { code?: string }
            error.code = message.exception?.code
            pending.reject(error)
          }
          break
        }
        case 'calculatefee': {
          const data = message.data as TxFeeData | undefined
          const target = this.feeTarget
          if (data && target && this.connected && data.uuid === this.requestFeesUUID &&
              target.networkKey === this.walletNetworkKey && target.networkKey === this.chainReadState?.networkKey &&
              target.generation === this.chainReadState?.generation && this.chainReadState?.walletReady && Array.isArray(data.tokenFees) &&
              [data.txFee, data.tokenKeepFee, data.tokenLocked, ...data.tokenFees].every(value => typeof value === 'string' && /^\d+$/.test(value))) {
            this.txFee = data.txFee
            this.tokenFees = data.tokenFees
            this.tokenKeepFee = data.tokenKeepFee
            this.tokenLocked = data.tokenLocked
            this.responseFeesUUID = data.uuid
          } else if (!data && target) {
            this.toastError = { title: 'CalculateFee Error', message: message.exception?.message || 'Could not calculate fee' }
          }
          break
        }
        case 'version':
          this.setAppVersion(message.data as AppVersionData)
          break
        case 'blocks':
          this.setBlocks(message.data as Block[])
          break
        case 'block':
          this.addBlock(message.data as Block)
          break
        case 'hosts':
          this.hosts = message.data as Host[]
          break
        case 'nodes':
          if (Array.isArray(message.data)) {
            const nextNodes = message.data as Node[]
            const leios = this.leiosStatus as LeiosStatusValue | null
            const targetId = this.leiosStatusRequest?.nodeId ?? leios?.target?.nodeId
            if (targetId != null) {
              const before = this.nodes.find(node => node.id === targetId)
              const after = nextNodes.find(node => node.id === targetId)
              const [oldIdentity, newIdentity] = [before, after].map(node => node ? JSON.stringify([
                node.id, node.hostId, node.genesisByronFileId, node.genesisShelleyFileId,
                node.genesisAlonzoFileId, node.genesisConwayFileId, node.genesisDijkstraFileId,
                node.configFileId, node.topologyFileId, node.peerSnapshotFileId
              ]) : null)
              if (oldIdentity !== newIdentity) {
                const oldNetwork = this.leiosStatusRequest?.networkKey ?? leios?.target?.networkKey
                if (oldNetwork) this.invalidateNetworkObservations(oldNetwork)
                this.leiosStatus = null
                this.leiosStatusRequest = null
                this.leiosStatusError = 'Node configuration changed; refresh Leios status for the new target.'
              }
            }
            this.nodes = nextNodes
            // An ID can retain its identity while its host or configuration changes.
            // Discard every old association until this exact list has been resolved.
            this.nodeIndexStatus = {}
            this.nodeIndexRequests = {}
            this.nodes.forEach(node => this.requestNodeIndexStatus(node.id))
          }
          break
        case 'node_chainstatus': {
          const data = message.data as { requestId: string; nodeId: number; readState: ChainReadState; replay: LedgerReplayCoverage } | undefined
          const requestId = data?.requestId || message.exception?.requestId
          const nodeId = data?.nodeId ?? (message.exception?.target?.startsWith('node:') ? Number(message.exception.target.slice(5)) : NaN)
          if (!Number.isSafeInteger(nodeId) || !requestId || this.nodeIndexRequests[nodeId] !== requestId) break
          delete this.nodeIndexRequests[nodeId]
          if (data?.readState && data.replay && this.nodes.some(node => node.id === nodeId) &&
              data.readState.networkKey === data.replay.networkKey) {
            this.applyChainReadState(data.readState)
            const state = this.networkReadStates[data.readState.networkKey]
            if (!state) break
            this.nodeIndexStatus[nodeId] = { readState: state, replay: data.replay, error: null }
          } else {
            this.nodeIndexStatus[nodeId] = { readState: null, replay: null, error: message.exception?.message || 'Node network index status unavailable' }
          }
          break
        }
        case 'addhost':
          if (message.data) {
            this.toastSuccess = { title: 'Host Saved', message: message.data as string }
          } else {
            this.toastError = { title: 'Host Save Error', message: message.exception!.message }
          }
          break
        case 'file_options':
          this.files = message.data as FileOption[]
          break
        case 'leios_status': {
          const data = message.data as LeiosStatusValue | undefined
          const pending = this.leiosStatusRequest
          const requestId = data?.requestId ?? message.exception?.requestId
          if (!pending || !requestId || pending.requestId !== requestId) break
          this.leiosStatusRequest = null
          if (data && data.target?.nodeId === pending.nodeId && data.target.hostId === pending.hostId &&
              (!pending.networkKey || data.target.networkKey === pending.networkKey) && data.readState) {
            const current = data.target.networkKey ? this.networkReadStates[data.target.networkKey] : null
            this.leiosStatus = current && current.status !== 'complete' ? staleLeios(data) : data
            this.leiosStatusError = null
          } else {
            const previous = this.leiosStatus as LeiosStatusValue | null
            if (previous?.target?.nodeId === pending.nodeId && previous.readState) this.leiosStatus = staleLeios(previous)
            this.leiosStatusError = message.exception?.message || 'Leios status unavailable'
          }
          break
        }
        case 'nodestats':
          this.saveNodeStats(message.data as NodeStatEvent[])
          break
        case 'wallet': {
          if (!message.data) {
            if (this.walletNetworkKey) {
              const previous = this.networkReadStates[this.walletNetworkKey]
              if (previous) {
                this.networkReadStates[this.walletNetworkKey] = { ...previous, walletReady: false }
              }
              this.invalidateNetworkObservations(this.walletNetworkKey)
            }
            this.chainReadState = null
            this.toastError = { title: 'Wallet unavailable', message: message.exception?.message || 'Could not load wallet' }
            break
          }
          const data = message.data as WalletResult
          if (!data.networkKey || !Array.isArray(data.items)) break
          if (data.readState?.networkKey !== data.networkKey || typeof data.readState.generation !== 'string' || !/^\d+$/.test(data.readState.generation)) {
            if (data.networkKey === this.walletNetworkKey) {
              const previous = this.networkReadStates[data.networkKey]
              if (previous) {
                this.networkReadStates[data.networkKey] = { ...previous, walletReady: false }
              }
              this.invalidateNetworkObservations(data.networkKey)
              this.chainReadState = null
            }
            break
          }
          if (!this.canApplyChainReadState(data.readState)) break
          if (this.walletNetworkKey && this.walletNetworkKey !== data.networkKey) {
            this.invalidateNetworkObservations(this.walletNetworkKey)
            this.chainReadState = null
            this.chainStatusRequestId = null
          }
          this.walletNetworkKey = data.networkKey
          this.applyChainReadState(data.readState)
          this.walletItems = data.items.map(item => ({
            ...item,
            paymentAddrLovelace: item.paymentAddrLovelace == null ? null : String(item.paymentAddrLovelace),
            stakingAddrLovelace: item.stakingAddrLovelace == null ? null : String(item.stakingAddrLovelace),
            nativeAssetMap: item.nativeAssetMap == null ? null : Object.fromEntries(Object.entries(item.nativeAssetMap).map(([key, value]) => [key, String(value)]))
          }))
          if (data.readState.walletReady !== true) this.clearWalletValues()
          break
        }
        case 'chainstatus': {
          const data = message.data as (ChainReadState & { requestId?: string }) | undefined
          const accepted = !!data && (!data.requestId ||
            (data.requestId === this.chainStatusRequestId && data.networkKey === this.walletNetworkKey))
          if (data && accepted && data.networkKey) {
            this.applyChainReadState(data)
          } else if (!data && message.exception?.requestId && message.exception.requestId === this.chainStatusRequestId && message.exception.target === this.walletNetworkKey) {
            const network = this.walletNetworkKey
            if (network) {
              const previous = this.networkReadStates[network]
              if (previous) {
                this.networkReadStates[network] = { ...previous, walletReady: false }
              }
              this.invalidateNetworkObservations(network)
            }
            this.chainReadState = null
          }
          break
        }
        case 'createwalletentry':
          if (message.data) {
            this.toastSuccess = { title: 'Success', message: message.data as string }
            this.fetchWalletItems()
          } else {
            this.toastError = { title: 'WalletEntry Save Error', message: message.exception!.message }
          }
          break
        case 'deletewalletentry':
          if (message.data) {
            this.toastSuccess = { title: 'Success', message: message.data as string }
            this.fetchWalletItems()
          } else {
            this.toastError = { title: 'Delete WalletEntry Error', message: message.exception!.message }
          }
          break
        case 'restartnode':
          if (message.data) {
            this.toastSuccess = { title: 'Node Restart...', message: message.data as string }
          } else {
            this.toastError = { title: 'Restart Node Error', message: message.exception!.message }
          }
          break
        case 'rotatekes':
          if (message.data) {
            this.toastSuccess = { title: 'Rotate KES...', message: message.data as string }
          } else {
            this.toastError = { title: 'Rotate KES Error', message: message.exception!.message }
          }
          break
        case 'backup':
          if (message.data) {
            window.open(`${this.backupDownloadUrl}?token=${message.data}`, '_jm_download')
          } else {
            this.toastError = { title: 'Download Backup Error', message: message.exception!.message }
          }
          break
        case 'updatenodecolor':
          if (message.data) {
            this.toastSuccess = { title: 'Update Color...', message: message.data as string }
          } else {
            this.toastError = { title: 'Update Color Error', message: message.exception!.message }
          }
          break
        case 'updatepoolconfig':
          if (message.data) {
            this.toastSuccess = { title: 'Update Pool Config...', message: message.data as string }
          } else {
            this.toastError = { title: 'Update Pool Config Error', message: message.exception!.message }
          }
          break
        case 'leaderlogs':
          if (message.data) {
            this.toastSuccess = { title: 'Leader Logs', message: message.data as string }
          } else {
            this.toastError = { title: 'Leader Logs Error', message: message.exception!.message }
          }
          break
        case 'getmetadata':
          if (message.data) {
            this.editorMetadata = message.data
          } else {
            this.toastError = { title: 'Metadata Fetch Error', message: message.exception!.message }
          }
          break
        case 'updatemetadata':
          if (message.data) {
            this.toastSuccess = { title: 'Update Metadata...', message: message.data as string }
          } else {
            this.toastError = { title: 'Update Metadata Error', message: message.exception!.message }
          }
          break
        case 'editrelays':
          if (message.data) {
            this.toastSuccess = { title: 'Edit Relays...', message: message.data as string }
          } else {
            this.toastError = { title: 'Edit Relays Error', message: message.exception!.message }
          }
          break
        case 'retirepool':
          if (message.data) {
            this.toastSuccess = { title: 'Retire Pool...', message: message.data as string }
            this.toastWarn = { title: 'Retire Pool', message: 'Pool has been left running intentionally. You should stop it and disable systemd scripts manually after retirement.' }
          } else {
            this.toastError = { title: 'Retire Pool Error', message: message.exception!.message }
          }
          break
      }
    },

    // State mutation actions
    setAppVersion(data: AppVersionData) {
      this.appVersion = data.version
      this.mp = data.mp
      this.minUTxOValue = data.minUTxOValue
    },

    setBlocks(blocks: Block[]) {
      this.blocks = orderBy(blocks, ['slot'], ['desc'])
    },

    addBlock(block: Block) {
      this.blocks = orderBy(
        unionWith([block], this.blocks, (first, second) => first.slot === second.slot),
        ['slot'],
        ['desc']
      )
    },

    saveNodeStats(nodeStatEvents: NodeStatEvent[]) {
      for (const nodeStats of nodeStatEvents) {
        const isDefaultNode = nodeStats.isDefault === true || nodeStats.default === true
        const shouldUseForEpoch = isDefaultNode || this.epoch === 0

        // Block height series
        if (nodeStats.blockHeight != null) {
          let index = findIndex(this.blockHeightSeries, ['name', nodeStats.nodeName])
          if (index > -1) {
            const nextData = [...this.blockHeightSeries[index].data, [nodeStats.timestamp, nodeStats.blockHeight] as [number, number]].slice(-60)
            this.blockHeightSeries[index] = {
              ...this.blockHeightSeries[index],
              data: nextData
            }
            this.nodeColors[index] = nodeStats.color
            this.blockHeightSeries = [...this.blockHeightSeries]
            this.nodeColors = [...this.nodeColors]
          } else {
            const heightData: SeriesDataPoint = {
              name: nodeStats.nodeName,
              data: [[nodeStats.timestamp, nodeStats.blockHeight]]
            }
            this.blockHeightSeries.push(heightData)
            this.blockHeightSeries = sortBy(this.blockHeightSeries, ['name'])
            const newIndex = this.blockHeightSeries.findIndex(s => s.name === nodeStats.nodeName)
            this.nodeColors[newIndex] = nodeStats.color
            this.nodeColors = [...this.nodeColors]
          }
        }

        // Peers series
        if (nodeStats.peers != null) {
          const index1 = findIndex(this.peersSeries, ['name', nodeStats.nodeName])
          if (index1 > -1) {
            const nextData = [...this.peersSeries[index1].data, [nodeStats.timestamp, nodeStats.peers] as [number, number]].slice(-60)
            this.peersSeries[index1] = {
              ...this.peersSeries[index1],
              data: nextData
            }
            this.peersSeries = [...this.peersSeries]
          } else {
            this.peersSeries.push({
              name: nodeStats.nodeName,
              data: [[nodeStats.timestamp, nodeStats.peers]]
            })
            this.peersSeries = sortBy(this.peersSeries, ['name'])
          }
        }

        // KES remaining
        const daysRemaining = nodeStats.remainingKESPeriods != null ? nodeStats.remainingKESPeriods * 1.5 : 0
        if (daysRemaining > 0) {
          let low = 0, ok = 0, good = 0
          if (daysRemaining <= 5) {
            low = daysRemaining
          } else if (daysRemaining <= 20) {
            low = 5
            ok = daysRemaining - 5
          } else {
            low = 5
            ok = 15
            good = daysRemaining - ok - low
          }

          let index2 = this.remainingKESSeriesCategories.indexOf(nodeStats.nodeName)
          if (index2 < 0) {
            this.remainingKESSeriesCategories.push(nodeStats.nodeName)
            this.remainingKESSeriesCategories.sort()
            this.remainingKESSeriesCategoryLabels.push(`${nodeStats.nodeName} (${daysRemaining}d)`)
            this.remainingKESSeriesCategoryLabels.sort()
            index2 = this.remainingKESSeriesCategories.indexOf(nodeStats.nodeName)
          }
          this.remainingKESSeriesCategoryLabels[index2] = `${nodeStats.nodeName} (${daysRemaining}d)`
          this.remainingKESSeries[0].data[index2] = low
          this.remainingKESSeries[1].data[index2] = ok
          this.remainingKESSeries[2].data[index2] = good
        }

        // Epoch/slot tracking
        if (shouldUseForEpoch && nodeStats.epoch != null && nodeStats.epoch !== this.epoch) {
          this.epoch = nodeStats.epoch
          this.slot = 0
        }
        if (shouldUseForEpoch && nodeStats.slotInEpoch != null && nodeStats.slotInEpoch !== this.slot) {
          this.slot = nodeStats.slotInEpoch
        }
        if (shouldUseForEpoch && nodeStats.epochLength !== this.epochLength) {
          this.epochLength = nodeStats.epochLength
        }

        // Incoming peers series
        if (nodeStats.incomingPeers != null) {
          const index3 = findIndex(this.incomingPeersSeries, ['name', nodeStats.nodeName])
          if (index3 > -1) {
            const nextData = [...this.incomingPeersSeries[index3].data, [nodeStats.timestamp, nodeStats.incomingPeers] as [number, number]].slice(-60)
            this.incomingPeersSeries[index3] = {
              ...this.incomingPeersSeries[index3],
              data: nextData
            }
            this.incomingPeersSeries = [...this.incomingPeersSeries]
          } else {
            this.incomingPeersSeries.push({
              name: nodeStats.nodeName,
              data: [[nodeStats.timestamp, nodeStats.incomingPeers]]
            })
            this.incomingPeersSeries = sortBy(this.incomingPeersSeries, ['name'])
          }
        }

        // Txs processed series
        const index4 = findIndex(this.txsProcessedSeries, ['name', nodeStats.nodeName])
        if (index4 > -1) {
          if (nodeStats.txsProcessed != null && nodeStats.txsProcessed > 0) {
            const nextData = [...this.txsProcessedSeries[index4].data, [nodeStats.timestamp, nodeStats.txsProcessed] as [number, number]].slice(-60)
            this.txsProcessedSeries[index4] = {
              ...this.txsProcessedSeries[index4],
              data: nextData
            }
            this.txsProcessedSeries = [...this.txsProcessedSeries]
          }
        } else {
          if (nodeStats.txsProcessed != null && nodeStats.txsProcessed > 0) {
            this.txsProcessedSeries.push({
              name: nodeStats.nodeName,
              data: [[nodeStats.timestamp, nodeStats.txsProcessed]]
            })
            this.txsProcessedSeries = sortBy(this.txsProcessedSeries, ['name'])
          }
        }
      }
    },


    // WebSocket send actions
    sendMessage(endpoint: string, data?: unknown) {
      if (this.stompClient && this.stompClient.connected) {
        if (data !== undefined) {
          this.stompClient.send(endpoint, typeof data === 'string' ? data : JSON.stringify(data))
        } else {
          this.stompClient.send(endpoint)
        }
      } else {
        this.toastError = { title: 'Communication Error!', message: 'stompClient not connected!' }
      }
    },
    requestNodeIndexStatus(nodeId: number) {
      if (!this.connected || !Number.isSafeInteger(nodeId)) return
      const requestId = generateUUID()
      this.nodeIndexRequests[nodeId] = requestId
      this.nodeIndexStatus[nodeId] = { readState: null, replay: null, error: null }
      this.sendMessage('/jormanager/node/chainstatus', { requestId, nodeId })
    },

    canApplyChainReadState(state: ChainReadState): boolean {
      if (!state?.networkKey || typeof state.generation !== 'string' || !/^\d+$/.test(state.generation)) return false
      const previous = this.networkReadStates[state.networkKey]
      if (previous && BigInt(state.generation) < BigInt(previous.generation)) return false
      return true
    },
    applyChainReadState(incoming: ChainReadState): boolean {
      if (incoming?.networkKey && (typeof incoming.generation !== 'string' || !/^\d+$/.test(incoming.generation))) {
        const previous = this.networkReadStates[incoming.networkKey]
        if (previous) {
          this.networkReadStates[incoming.networkKey] = { ...previous, walletReady: false }
          this.invalidateNetworkObservations(incoming.networkKey)
          if (incoming.networkKey === this.walletNetworkKey) this.chainReadState = null
        }
        return false
      }
      if (!this.canApplyChainReadState(incoming)) return false
      const state = { ...incoming, walletReady: incoming.walletReady === true }
      const network = state.networkKey
      const previous = this.networkReadStates[network]
      const generationChanged = !!previous && BigInt(state.generation) > BigInt(previous.generation)
      if (generationChanged || (previous?.walletReady === true && !state.walletReady)) {
        this.invalidateNetworkObservations(network)
      }
      this.networkReadStates[network] = state
      if (network === this.walletNetworkKey) this.chainReadState = state
      Object.values(this.nodeIndexStatus).forEach(status => {
        if (status.readState?.networkKey === network) status.readState = state
      })
      if (state.status !== 'complete') {
        const leios = this.leiosStatus as LeiosStatusValue | null
        if (leios?.target?.networkKey === network && leios.readState) this.leiosStatus = staleLeios(leios)
      }
      return true
    },
    clearWalletValues() {
      this.walletItems = this.walletItems.map(item => ({
        ...item,
        paymentAddrUtxoCount: null,
        paymentAddrLovelace: null,
        stakingAddrRegistered: null,
        stakingAddrLovelace: null,
        nativeAssetMap: null
      }))
    },

    invalidateNetworkObservations(networkKey: string) {
      this.networkInvalidations[networkKey] = (this.networkInvalidations[networkKey] ?? 0) + 1
      if (this.walletNetworkKey === networkKey) {
        this.chainStatusRequestId = null
        this.clearWalletValues()
      }
      const leios = this.leiosStatus as LeiosStatusValue | null
      if (leios?.target?.networkKey === networkKey && leios.readState) this.leiosStatus = staleLeios(leios)
      const pending = this.leiosStatusRequest
      if (pending && (!pending.networkKey || pending.networkKey === networkKey)) this.leiosStatusRequest = null
    },
    invalidateAllNetworkObservations() {
      const networks = new Set(Object.keys(this.networkReadStates))
      if (this.walletNetworkKey) networks.add(this.walletNetworkKey)
      const leios = this.leiosStatus as LeiosStatusValue | null
      if (leios?.target?.networkKey) networks.add(leios.target.networkKey)
      networks.forEach(network => this.invalidateNetworkObservations(network))
      this.networkReadStates = Object.fromEntries(
        Object.entries(this.networkReadStates).map(([network, state]) => [network, { ...state, walletReady: false }])
      )
      this.chainReadState = null
      this.chainStatusRequestId = null
      this.leiosStatusRequest = null
    },


    requestLeiosStatus(target: { nodeId: number; hostId: number; poolId?: string; networkKey?: string }) {
      if (!this.connected) return
      const requestId = generateUUID()
      const old = this.leiosStatus as LeiosStatusValue | null
      const networkKey = target.networkKey ?? this.nodeIndexStatus[target.nodeId]?.readState?.networkKey ?? null
      this.leiosStatusRequest = { requestId, nodeId: target.nodeId, hostId: target.hostId, networkKey }
      if (old?.target?.nodeId === target.nodeId && old.readState) this.leiosStatus = staleLeios(old)
      this.leiosStatusError = null
      this.sendMessage('/jormanager/leios/status', { requestId, target })
    },


    requestChainStatus(networkKey: string) {
      this.chainStatusRequestId = generateUUID()
      this.sendMessage('/jormanager/chainstatus', { requestId: this.chainStatusRequestId, networkKey })
    },

    socketRequest<T>(destination: string, responseType: string, payload: Record<string, unknown>): Promise<T> {
      if (!this.stompClient?.connected) return Promise.reject(new Error('Not connected to the server'))
      const requestId = payload.requestId === undefined ? generateUUID() : payload.requestId
      if (typeof requestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
        return Promise.reject(new Error('Invalid request ID'))
      }
      if (pendingRequests.has(requestId)) return Promise.reject(new Error('Request is already in flight'))
      return new Promise((resolve, reject) => {
        const timer = window.setTimeout(() => {
          pendingRequests.delete(requestId)
          reject(new Error('No response received; the operation outcome may be uncertain'))
        }, 60000)
        const networkKey = responseType === 'walletsubmit' || responseType === 'governancesubmit'
          ? this.walletNetworkKey : null
        const target = payload.target && typeof payload.target === 'object'
          ? Object.fromEntries(Object.entries(payload.target).filter(([key]) => ['nodeId', 'hostId', 'networkKey', 'poolId'].includes(key)))
          : undefined
        pendingRequests.set(requestId, { type: responseType, nodeId: payload.nodeId, action: payload.action,
          keyId: responseType === 'leios_registration_submitted' || responseType === 'leios_key_installation' ? payload.keyId : undefined,
          networkKey, target, resolve: value => resolve(value as T), reject, timer })
        try {
          this.sendMessage(destination, { ...payload, requestId })
        } catch (error) {
          clearTimeout(timer)
          pendingRequests.delete(requestId)
          reject(error instanceof Error ? error : new Error(String(error)))
        }
      })
    },

    submitWallet(requestId: string, kind: string, request: unknown, spendingPassword: string): Promise<TransactionResult> {
      return this.socketRequest<TransactionResult>('/jormanager/walletsubmit', 'walletsubmit', { requestId, kind, request, spendingPassword })
    },

    submitGovernance(requestId: string, request: unknown, spendingPassword: string): Promise<TransactionResult> {
      return this.socketRequest<TransactionResult>('/jormanager/governancesubmit', 'governancesubmit', { requestId, request, spendingPassword })
    },

    calculateSendAdaFees(request: { uuid: string; [key: string]: unknown }) {
      const networkKey = this.walletNetworkKey
      const state = this.chainReadState
      this.invalidateSendAdaFees()
      if (!this.connected || !networkKey || state?.networkKey !== networkKey || !state.walletReady) return
      this.feeTarget = { networkKey, generation: state.generation }
      this.requestFeesUUID = request.uuid
      this.sendMessage('/jormanager/calculatefee', request)
    },

    invalidateSendAdaFees() {
      this.requestFeesUUID = ''
      this.responseFeesUUID = ''
      this.feeTarget = null
    },

    requestAppVersion() {
      this.sendMessage('/jormanager/version')
    },

    requestHosts() {
      this.sendMessage('/jormanager/hosts')
    },

    requestNodes() {
      this.sendMessage('/jormanager/nodes')
    },

    addHost(host: Host) {
      this.sendMessage('/jormanager/addhost', host)
    },

    requestFileOptions() {
      this.sendMessage('/jormanager/file_options')
    },


    createNode(formNode: unknown) {
      this.sendMessage('/jormanager/createnode', formNode)
    },

    createWalletEntry(formWallet: unknown) {
      this.sendMessage('/jormanager/createwalletentry', formWallet)
    },

    requestBlocks() {
      this.sendMessage('/jormanager/blocks')
    },

    requestLeaderLogs(formLeaderLogs: unknown) {
      this.sendMessage('/jormanager/leaderlogs', formLeaderLogs)
    },

    requestMetadata(nodeId: number) {
      this.sendMessage('/jormanager/getmetadata', String(nodeId))
    },

    deleteWalletItem(deleteRequest: unknown) {
      this.walletItems = []
      this.sendMessage('/jormanager/deletewalletentry', deleteRequest)
    },

    fetchWalletItems() {
      this.sendMessage('/jormanager/wallet')
    },


    restartNodeByName(nodeName: string) {
      const node = find(this.nodes, ['name', nodeName])
      if (node) {
        this.sendMessage('/jormanager/restartnode', String(node.id))
      }
    },

    rotateKesByName(rotateRequest: { name: string; spendingPassword: string }) {
      const node = find(this.nodes, ['name', rotateRequest.name])
      if (node) {
        this.sendMessage('/jormanager/rotatekes', { id: node.id, spendingPassword: rotateRequest.spendingPassword })
      }
    },

    downloadBackup(spendingPassword: string) {
      this.sendMessage('/jormanager/backup', spendingPassword)
    },

    updateNodeColor(updateColorForm: unknown) {
      this.sendMessage('/jormanager/updatenodecolor', updateColorForm)
    },

    updatePoolConfig(editPoolForm: unknown) {
      this.sendMessage('/jormanager/updatepoolconfig', editPoolForm)
    },

    updateMetadata(editMetadataForm: unknown) {
      this.sendMessage('/jormanager/updatemetadata', editMetadataForm)
    },


    sendEditRelays(editRelaysForm: unknown) {
      this.sendMessage('/jormanager/editrelays', editRelaysForm)
    },

    sendRetirePool(retirePoolForm: unknown) {
      this.sendMessage('/jormanager/retirepool', retirePoolForm)
    },
  }
})
