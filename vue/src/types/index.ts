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
  isDraft: boolean
  [key: string]: unknown
}

export interface Block {
  slot: number
  epoch: number
  blockHash: string
  [key: string]: unknown
}

export interface FileOption {
  value: number
  text: string
  [key: string]: unknown
}

export interface WalletItem {
  id: number
  name: string
  paymentAddress: string
  paymentAddrLovelace: string
  stakingAddress?: string
  stakingAddrLovelace: string | null
  nativeAssetMap: Record<string, string>
  [key: string]: unknown
}

export interface ChainPoint { slot: string; hash: string }
export interface ChainReadState {
  networkKey: string
  status: string
  reason: string | null
  observedTip: ChainPoint | null
  lastCompletePoint: ChainPoint | null
}

export interface LedgerReplayCoverage {
  networkKey: string
  readState: ChainReadState
  genesisVerifiedAndSeeded: boolean
  retainedAdoptedInclusions: string
  unassignedLegacyOutputs: string
  replayAnchor: string
  recovery: string
}

export interface NodeIndexStatus {
  readState: ChainReadState | null
  replay: LedgerReplayCoverage | null
  error: string | null
}

export interface WalletReview {
  previewId: string
  txId: string
  networkKey: string
  era: string
  fee: string
  deposit: string
  refund: string
  validFrom: string | null
  validUntil: string | null
  state: string
  reason?: string | null
  effects?: unknown
}

export interface WalletReconciliation {
  requestId: string
  previewId: string
  txId: string
  networkKey: string
  state: string
  reason: string | null
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
