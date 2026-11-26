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
  sshFingerprint?: string | null
  sshMismatchFingerprint?: string | null
}

export interface HostKeyMismatchAlert {
  hostId?: number | null
  hostname: string
  sshPort: number
  expectedFingerprint: string
  receivedFingerprint: string
  message: string
}

export interface HostFingerprintResult {
  requestId: string
  hostId: number
  action: 'reset' | 'accept' | 'update'
  sshFingerprint: string | null
  message: string
}

export interface NodeRelay {
  addr: string
  port: number
}

export interface Node {
  id: number
  name: string
  host?: Host
  hostId?: number | null
  parentId?: number | null
  status?: string
  color: string
  type?: string
  poolId?: string | null
  isDefault?: boolean
  isDeleted?: boolean
  kesExpireTimeSec?: number | null
  processorThreads?: number
  listen?: string
  port?: number
  genesisByronFileId?: number | null
  genesisShelleyFileId?: number | null
  genesisAlonzoFileId?: number | null
  genesisConwayFileId?: number | null
  genesisDijkstraFileId?: number | null
  configFileId?: number | null
  topologyFileId?: number | null
  peerSnapshotFileId?: number | null
  registrationFeesAccountId?: number | null
  ownerStakingAccountId?: number | null
  rewardsStakingAccountId?: number | null
  poolPledge?: number | null
  poolCost?: number | null
  poolMargin?: number | null
  relays?: NodeRelay[]
  [key: string]: unknown
}

export interface DisplayNode {
  id: number
  color: string
  name: string
  poolId?: string | null
  type?: string
  host: string
  isDefault?: boolean
  kesExpireTimeSec?: number | null
}

export interface Block {
  id?: number | null
  at?: string
  pool?: string
  host?: string
  slot: number
  epoch: number
  slotInEpoch?: number
  hash?: string
  blockHash?: string
  status?: string
  [key: string]: unknown
}

export interface FileOption {
  value: number
  text: string
  [key: string]: unknown
}

export type SelectionMode = 'STANDARD' | 'DEFRAGMENT'

export interface WalletItem {
  id: number
  name: string
  type?: string
  paymentAddr?: string
  paymentAddress?: string
  hasPaymentKeys?: boolean
  paymentAddrUtxoCount?: number | null
  paymentAddrLovelace: string | null
  stakingAddr?: string | null
  stakingAddress?: string
  stakingAddrRegistered?: boolean | null
  stakingAddrLovelace: string | null
  nativeAssetMap: Record<string, string> | null
  [key: string]: unknown
}


export interface ChainPoint { slot: string; hash: string }
export interface ChainReadState {
  networkKey: string
  status: string
  walletReady: boolean
  generation: string
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

export interface TransactionResult {
  requestId: string
  txId: string
  networkKey: string
  era: string
  fee: string
  deposit: string
  refund: string
  state: string
}

export interface PoolRetirementResult extends TransactionResult {
  nodeId: number
}

export interface PoolRegistrationResult extends PoolRetirementResult {
  action: string
  keyId: number | null
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
  txFee: string
  tokenFees: string[]
  tokenKeepFee: string
  tokenLocked: string
  uuid: string
  selectionMode?: SelectionMode
  inputCount?: number
  outputCount?: number
  estimatedTxSize?: number
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
