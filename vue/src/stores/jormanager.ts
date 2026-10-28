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
  SeriesDataPoint,
  KESSeries,
  NodeStatEvent,
  Toast,
  AppVersionData,
  ChainReadState,
  NodeIndexStatus,
  LedgerReplayCoverage,
  WalletReview,
  TransactionDetail,
  WalletReconciliation
} from '@/types'

const exactJson = JSONBIG({ storeAsString: true })
const pendingReviews = new Map<string, {
  type: string
  resolve: (data: unknown) => void
  reject: (error: Error) => void
  timer: number
}>()

type LeiosStatusValue = {
  requestId: string
  target: { nodeId: number; hostId: number; networkKey?: string | null }
  readState: { status: string; reasonCode: string; affectedData: string[]; [key: string]: unknown }
  [key: string]: unknown
}

function staleLeios(value: LeiosStatusValue): LeiosStatusValue {
  return {
    ...value,
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
  networkBundleOptions: { bundles: Array<{ bundleId: string; resetId: string; catalogPrefix: string; networkMagic: number; systemStart: string }>; status: string }
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
  transactionDetail: TransactionDetail | { error: string } | null
  transactionRequestId: string | null


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
    networkBundleOptions: { bundles: [], status: 'Not requested' },
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
    transactionDetail: null,
    transactionRequestId: null,


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
        /^\d+$/.test(String(item.paymentAddrLovelace)) && BigInt(item.paymentAddrLovelace) > minimum
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
        const message = exactJson.parse(tick.body) as { type: string; data?: unknown; exception?: { message: string; requestId?: string; target?: string } }
        this.handleMessage(message)
      })
      this.invalidateAllNetworkObservations()
      this.connected = true
      this.nodeIndexStatus = {}
      this.nodeIndexRequests = {}
      this.requestNodes()
    },

    handleMessage(message: { type: string; data?: unknown; exception?: { message: string; requestId?: string; target?: string } }) {
      switch (message.type) {
        case 'walletreview':
        case 'walletsubmit':
        case 'walletreconcile':
        case 'governancereview':
        case 'governancepublish':
        case 'governancesubmit':
        case 'pool_retirement_review':
        case 'pool_retirement_submit':
        case 'node_pool_deployment_preview':
        case 'node_pool_deployment_install':
        case 'leios_registration_preview':
        case 'leios_registration_submitted': {
          if (message.type.startsWith('node_pool_deployment_') || message.type.startsWith('leios_registration_')) {
            this.leiosResponses = { ...this.leiosResponses, [message.type]: { data: message.data, exception: message.exception } }
          }
          const data = message.data as { requestId: string } | undefined
          const requestId = data?.requestId || message.exception?.requestId
          const pending = requestId ? pendingReviews.get(requestId) : undefined
          if (!pending || pending.type !== message.type) break
          clearTimeout(pending.timer)
          pendingReviews.delete(requestId!)
          if (data) pending.resolve(data)
          else pending.reject(new Error(message.exception?.message || 'Operation failed'))
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
                node.configFileId, node.configurationBundleId, node.configurationResetId
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
            // A network broadcast may have arrived while this request was in flight.
            // Once the association is known, prefer the latest broadcast for its state.
            const state = this.networkReadStates[data.readState.networkKey] ?? data.readState
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
        case 'network_bundle_options':
          if (message.data) this.networkBundleOptions = message.data as JorManagerState['networkBundleOptions']
          else this.networkBundleOptions = { bundles: [], status: message.exception?.message || 'Unavailable' }
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
        case 'capabilities':
          this.leiosResponses = { ...this.leiosResponses, capabilities: { data: message.data, exception: message.exception } }
          break
        case 'leios_key_prepared':
        case 'leios_key_imported':
        case 'leios_key_install_preview':
        case 'leios_key_installation':
        case 'network_reconfiguration_preview':
        case 'network_reconfiguration_authorize':
        case 'network_configuration_restore':
        case 'node_provisioning_preview':
        case 'node_pool_draft':
        case 'node_pool_draft_context':
        case 'capability_qualification':
        case 'host_capability_qualification':
          this.leiosResponses = { ...this.leiosResponses, [message.type]: { data: message.data, exception: message.exception } }
          break
        case 'createnode':
          if (message.data) {
            this.toastSuccess = { title: 'Node Created', message: message.data as string }
          } else {
            this.toastError = { title: 'Node Save Error', message: message.exception!.message }
          }
          break
        case 'nodestats':
          this.saveNodeStats(message.data as NodeStatEvent[])
          break
        case 'wallet': {
          if (!message.data) {
            this.walletItems = []
            this.walletNetworkKey = null
            this.chainReadState = null
            this.toastError = { title: 'Wallet unavailable', message: message.exception?.message || 'Could not load wallet' }
            break
          }
          const data = message.data as { items: WalletItem[]; networkKey: string }
          this.walletNetworkKey = data.networkKey
          this.walletItems = data.items.map(item => ({
            ...item,
            paymentAddrLovelace: String(item.paymentAddrLovelace),
            stakingAddrLovelace: item.stakingAddrLovelace == null ? null : String(item.stakingAddrLovelace),
            nativeAssetMap: Object.fromEntries(Object.entries(item.nativeAssetMap || {}).map(([key, value]) => [key, String(value)]))
          }))
          this.requestChainStatus(data.networkKey)
          break
        }
        case 'chainstatus': {
          const data = message.data as (ChainReadState & { requestId?: string }) | undefined
          const accepted = !!data && (!data.requestId ||
            (data.requestId === this.chainStatusRequestId && data.networkKey === this.walletNetworkKey))
          if (data && accepted && data.networkKey) {
            if (data.networkKey === this.walletNetworkKey) this.chainReadState = data
            this.networkReadStates[data.networkKey] = data
            if (data.status !== 'complete') this.invalidateNetworkObservations(data.networkKey)
            Object.values(this.nodeIndexStatus).forEach(status => {
              if (status.readState?.networkKey === data.networkKey) status.readState = data
            })
          } else if (!data && message.exception?.requestId === this.chainStatusRequestId && message.exception.target === this.walletNetworkKey) {
            this.chainReadState = null
            if (this.walletNetworkKey) {
              delete this.networkReadStates[this.walletNetworkKey]
              this.invalidateNetworkObservations(this.walletNetworkKey)
            }
          }
          break
        }
        case 'transaction': {
          const data = message.data as TransactionDetail | undefined
          if (data && data.requestId === this.transactionRequestId && data.networkKey === this.walletNetworkKey) {
            const networkState = this.networkReadStates[data.networkKey]
            this.transactionDetail = data.readState.status !== 'complete' || (networkState && networkState.status !== 'complete')
              ? { ...data, readState: { ...data.readState, status: 'stale', reason: 'Chain index is incomplete; inclusion labels are historical until a complete refresh.' } }
              : data
          }
          else if (!data && message.exception?.requestId === this.transactionRequestId && message.exception.target === this.walletNetworkKey) this.transactionDetail = { error: message.exception.message || 'Transaction unavailable' }
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

    invalidateNetworkObservations(networkKey: string) {
      this.networkInvalidations[networkKey] = (this.networkInvalidations[networkKey] ?? 0) + 1
      const detail = this.transactionDetail
      if (detail && !('error' in detail) && detail.networkKey === networkKey) {
        this.transactionDetail = {
          ...detail,
          readState: { ...detail.readState, status: 'stale', reason: 'Chain index changed; refresh the transaction before relying on its inclusion.' }
        }
      }
      if (this.walletNetworkKey === networkKey) {
        this.transactionRequestId = null
        this.chainStatusRequestId = null
      }
      const leios = this.leiosStatus as LeiosStatusValue | null
      if (leios?.target?.networkKey === networkKey && leios.readState) this.leiosStatus = staleLeios(leios)
      const pending = this.leiosStatusRequest
      if (pending && (!pending.networkKey || pending.networkKey === networkKey)) this.leiosStatusRequest = null
    },
    invalidateAllNetworkObservations() {
      const networks = new Set(Object.keys(this.networkReadStates))
      if (this.walletNetworkKey) networks.add(this.walletNetworkKey)
      const detail = this.transactionDetail
      if (detail && !('error' in detail)) networks.add(detail.networkKey)
      const leios = this.leiosStatus as LeiosStatusValue | null
      if (leios?.target?.networkKey) networks.add(leios.target.networkKey)
      networks.forEach(network => this.invalidateNetworkObservations(network))
      this.networkReadStates = {}
      this.chainReadState = null
      this.chainStatusRequestId = null
      this.transactionRequestId = null
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
      this.chainReadState = null
      this.chainStatusRequestId = generateUUID()
      this.sendMessage('/jormanager/chainstatus', { requestId: this.chainStatusRequestId, networkKey })
    },

    requestTransaction(txId: string) {
      if (!this.walletNetworkKey) return
      this.transactionDetail = null
      this.transactionRequestId = generateUUID()
      this.sendMessage('/jormanager/transaction', { requestId: this.transactionRequestId, networkKey: this.walletNetworkKey, txId })
    },

    socketRequest<T>(destination: string, responseType: string, payload: Record<string, unknown>): Promise<T> {
      if (!this.stompClient?.connected) return Promise.reject(new Error('Not connected to the server'))
      const requestId = generateUUID()
      return new Promise((resolve, reject) => {
        const timer = window.setTimeout(() => {
          pendingReviews.delete(requestId)
          reject(new Error('No response received; the operation outcome may be uncertain'))
        }, 60000)
        pendingReviews.set(requestId, { type: responseType, resolve: value => resolve(value as T), reject, timer })
        try {
          this.sendMessage(destination, { ...payload, requestId })
        } catch (error) {
          clearTimeout(timer)
          pendingReviews.delete(requestId)
          reject(error instanceof Error ? error : new Error(String(error)))
        }
      })
    },

    reviewWallet(kind: string, request: unknown): Promise<WalletReview> {
      return this.socketRequest<WalletReview>('/jormanager/walletreview', 'walletreview', { kind, request })
    },

    reviewGovernance(request: unknown): Promise<WalletReview> {
      return this.socketRequest<WalletReview>('/jormanager/governancereview', 'governancereview', { request })
    },

    async submitReviewedWallet(previewId: string, spendingPassword: string, governance = false): Promise<WalletReview> {
      const type = governance ? 'governancesubmit' : 'walletsubmit'
      const result = await this.socketRequest<WalletReview>(`/jormanager/${type}`, type, { previewId, spendingPassword })
      if (result.previewId !== previewId) throw new Error('Submission response did not match the reviewed preview')
      return result
    },

    async reconcileWallet(previewId: string, spendingPassword: string): Promise<WalletReconciliation> {
      const result = await this.socketRequest<WalletReconciliation>('/jormanager/walletreconcile', 'walletreconcile', { previewId, spendingPassword })
      if (result.previewId !== previewId) throw new Error('Reconciliation response did not match the reviewed preview')
      return result
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

    requestNetworkBundleOptions() {
      this.sendMessage('/jormanager/network_bundle_options')
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
