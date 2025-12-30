import { defineStore } from 'pinia'
import SockJS from 'sockjs-client'
import Stomp, { type Client as StompClient, type Message } from 'webstomp-client'
import { orderBy, unionWith, findIndex, sortBy, find, filter, map, uniqBy } from 'lodash-es'
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
  TxFeeData
} from '@/types'

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

  // Transaction fees
  txFee: number
  tokenFees: number[]
  tokenKeepFee: number
  tokenLocked: number
  requestFeesUUID: string
  responseFeesUUID: string

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

    // Transaction fees
    txFee: 0,
    tokenFees: [],
    tokenKeepFee: 0,
    tokenLocked: 0,
    requestFeesUUID: 'ec8301e5-98bb-4be2-82d4-0f97fa82bb32',
    responseFeesUUID: '200bbde5-d0ba-4810-ae31-aa5c66ff0bd1',

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

    paymentSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      return sortBy(
        map(this.walletItems, (walletItem) => ({
          value: walletItem.id,
          text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
        })),
        ['text']
      )
    },

    registrationFeesSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      const filtered = this.walletItems.filter((walletItem: WalletItem) =>
        (walletItem as any).type !== 'address' &&
        (walletItem as any).hasPaymentKeys &&
        walletItem.paymentAddrLovelace > 3000000
      )
      return sortBy(
        filtered.map((walletItem: WalletItem) => ({
          value: walletItem.id,
          text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
        })),
        ['text']
      )
    },

    reregistrationFeesSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      const filtered = this.walletItems.filter((walletItem: WalletItem) =>
        (walletItem as any).type !== 'address' &&
        (walletItem as any).hasPaymentKeys &&
        walletItem.paymentAddrLovelace > 1000000
      )
      return sortBy(
        filtered.map((walletItem: WalletItem) => ({
          value: walletItem.id,
          text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
        })),
        ['text']
      )
    },

    stakingFeesSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      const filtered = this.walletItems.filter((walletItem: WalletItem) =>
        (walletItem as any).type !== 'address' &&
        (walletItem as any).hasPaymentKeys &&
        walletItem.paymentAddrLovelace > 3000000
      )
      return sortBy(
        filtered.map((walletItem: WalletItem) => ({
          value: walletItem.id,
          text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
        })),
        ['text']
      )
    },

    stakingSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      return sortBy(
        map(
          filter(this.walletItems, (walletItem) =>
            (walletItem as any).type === 'stake' || (walletItem as any).type === 'pledge'
          ),
          (walletItem) => ({
            value: walletItem.id,
            text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
          })
        ),
        ['text']
      )
    },

    rewardsSelectOptions(currencyFormatter: (val: number, sym: string, dec: number) => string): SelectOption[] {
      return sortBy(
        map(
          filter(this.walletItems, (walletItem) => (walletItem as any).type === 'stake'),
          (walletItem) => ({
            value: walletItem.id,
            text: `${walletItem.name} - ${currencyFormatter(walletItem.paymentAddrLovelace / 1000000, '₳', 6)}`
          })
        ),
        ['text']
      )
    },

    walletItemById(walletId: number): WalletItem {
      return find(this.walletItems, (walletItem) => walletItem.id === walletId) || 
        { id: 0, name: '', paymentAddress: '', paymentAddrLovelace: 0, stakingAddrLovelace: 0, nativeAssetMap: {} }
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
          console.error('WebSocket connection error:', error)
        }
      )
    },

    subscribeToMessages() {
      if (!this.stompClient) return

      this.stompClient.subscribe('/topic/messages', (tick: Message) => {
        const message = JSONBIG.parse(tick.body) as { type: string; data?: unknown; exception?: { message: string } }
        this.handleMessage(message)
      })
      this.connected = true
    },

    handleMessage(message: { type: string; data?: unknown; exception?: { message: string } }) {
      switch (message.type) {
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
          this.nodes = message.data as Node[]
          break
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
        case 'wallet':
          this.walletItems = message.data as WalletItem[]
          break
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
        case 'calculatefee':
          if (message.data) {
            this.saveTxFee(message.data as TxFeeData)
          } else {
            this.toastError = { title: 'CalculateFee Error', message: message.exception!.message }
          }
          break
        case 'submittransaction':
          if (message.data) {
            this.toastSuccess = { title: 'Ada Sent', message: message.data as string }
          } else {
            this.toastError = { title: 'Submit Transaction Error', message: message.exception!.message }
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
        case 'updatestakingaddress':
          if (message.data) {
            this.toastSuccess = { title: 'Update Staking Address...', message: message.data as string }
          } else {
            this.toastError = { title: 'Update Staking Address Error', message: message.exception!.message }
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
        // Block height series
        let index = findIndex(this.blockHeightSeries, ['name', nodeStats.nodeName])
        if (index > -1) {
          this.blockHeightSeries[index].data.push([nodeStats.timestamp, nodeStats.blockHeight])
          this.blockHeightSeries[index].data = this.blockHeightSeries[index].data.slice(-60)
          this.nodeColors[index] = nodeStats.color
        } else {
          const heightData: SeriesDataPoint = {
            name: nodeStats.nodeName,
            data: [[nodeStats.timestamp, nodeStats.blockHeight]]
          }
          this.blockHeightSeries.push(heightData)
          this.blockHeightSeries = sortBy(this.blockHeightSeries, ['name'])
          const newIndex = this.blockHeightSeries.findIndex(s => s.name === nodeStats.nodeName)
          this.nodeColors[newIndex] = nodeStats.color
        }

        // Peers series
        const index1 = findIndex(this.peersSeries, ['name', nodeStats.nodeName])
        if (index1 > -1) {
          this.peersSeries[index1].data.push([nodeStats.timestamp, nodeStats.peers])
          this.peersSeries[index1].data = this.peersSeries[index1].data.slice(-60)
        } else {
          this.peersSeries.push({
            name: nodeStats.nodeName,
            data: [[nodeStats.timestamp, nodeStats.peers]]
          })
          this.peersSeries = sortBy(this.peersSeries, ['name'])
        }

        // KES remaining
        const daysRemaining = nodeStats.remainingKESPeriods * 1.5
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
        if (nodeStats.epoch !== this.epoch) {
          this.epoch = nodeStats.epoch
          this.slot = 0
        }
        if (nodeStats.slotInEpoch !== this.slot) {
          this.slot = nodeStats.slotInEpoch
        }
        if (nodeStats.epochLength !== this.epochLength) {
          this.epochLength = nodeStats.epochLength
        }

        // Incoming peers series
        const index3 = findIndex(this.incomingPeersSeries, ['name', nodeStats.nodeName])
        if (index3 > -1) {
          this.incomingPeersSeries[index3].data.push([nodeStats.timestamp, nodeStats.incomingPeers])
          this.incomingPeersSeries[index3].data = this.incomingPeersSeries[index3].data.slice(-60)
        } else {
          this.incomingPeersSeries.push({
            name: nodeStats.nodeName,
            data: [[nodeStats.timestamp, nodeStats.incomingPeers]]
          })
          this.incomingPeersSeries = sortBy(this.incomingPeersSeries, ['name'])
        }

        // Txs processed series
        const index4 = findIndex(this.txsProcessedSeries, ['name', nodeStats.nodeName])
        if (index4 > -1) {
          if (nodeStats.txsProcessed > 0) {
            this.txsProcessedSeries[index4].data.push([nodeStats.timestamp, nodeStats.txsProcessed])
            this.txsProcessedSeries[index4].data = this.txsProcessedSeries[index4].data.slice(-60)
          }
        } else {
          if (nodeStats.txsProcessed > 0) {
            this.txsProcessedSeries.push({
              name: nodeStats.nodeName,
              data: [[nodeStats.timestamp, nodeStats.txsProcessed]]
            })
            this.txsProcessedSeries = sortBy(this.txsProcessedSeries, ['name'])
          }
        }
      }
    },

    saveTxFee(data: TxFeeData) {
      this.txFee = data.txFee
      this.tokenFees = data.tokenFees
      this.tokenKeepFee = data.tokenKeepFee
      this.tokenLocked = data.tokenLocked
      this.responseFeesUUID = data.uuid
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

    calculateSendAdaFees(request: { uuid: string; [key: string]: unknown }) {
      this.requestFeesUUID = request.uuid
      this.sendMessage('/jormanager/calculatefee', request)
    },

    invalidateSendAdaFees() {
      this.requestFeesUUID = crypto.randomUUID()
      this.responseFeesUUID = crypto.randomUUID()
    },

    submitTransaction(formSendAda: unknown) {
      this.sendMessage('/jormanager/submittransaction', formSendAda)
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

    updateStakingAddress(stakingAddressForm: unknown) {
      this.sendMessage('/jormanager/updatestakingaddress', stakingAddressForm)
    },

    sendEditRelays(editRelaysForm: unknown) {
      this.sendMessage('/jormanager/editrelays', editRelaysForm)
    },

    sendRetirePool(retirePoolForm: unknown) {
      this.sendMessage('/jormanager/retirepool', retirePoolForm)
    }
  }
})
