// Type definitions for JorManager store

export interface Host {
  id?: number
  type: 'local' | 'remote'
  hostname: string
  sshUser: string
  sshPort: number
  sshPemPath: string
  cardanoCliPath: string
  cardanoNodePath: string
  nodeHomePath: string
  jcliPath: string | null
}

export interface Node {
  id: number
  name: string
  host: Host
  status: string
  color: string
  [key: string]: unknown
}

export interface Block {
  slot: number
  epoch: number
  blockHash: string
  [key: string]: unknown
}

export interface FileOption {
  id: number
  name: string
  path: string
  [key: string]: unknown
}

export interface WalletItem {
  id: number
  name: string
  paymentAddress: string
  paymentAddrLovelace: number
  stakingAddress?: string
  stakingAddrLovelace: number
  nativeAssetMap: Record<string, number>
  [key: string]: unknown
}

export interface SeriesDataPoint {
  name: string
  data: [number, number][]
}

export interface KESSeries {
  name: string
  data: number[]
}

export interface NodeStatEvent {
  nodeName: string
  timestamp: number
  blockHeight: number | null
  peers: number | null
  incomingPeers: number | null
  remainingKESPeriods: number | null
  epoch: number | null
  slotInEpoch: number | null
  epochLength: number
  txsProcessed: number | null
  color: string
  default?: boolean
  isDefault?: boolean
}

export interface Toast {
  title: string
  message: string
  variant?: 'success' | 'warning' | 'danger' | 'info'
}

export interface AppVersionData {
  version: string
  mp: boolean
  minUTxOValue: number
}

export interface TxFeeData {
  txFee: number
  tokenFees: number[]
  tokenKeepFee: number
  tokenLocked: number
  uuid: string
}

export interface ToAccount {
  account: number
  currency: string
  type: 'amount' | 'percent'
  amount: number | null
  percent: number | null
  tokenFee?: number
  isFeePayer?: boolean
}

export interface SendAdaForm {
  isClaim: boolean
  toAccounts: ToAccount[]
  [key: string]: unknown
}
