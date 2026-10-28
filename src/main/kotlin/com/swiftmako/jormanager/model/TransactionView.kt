package com.swiftmako.jormanager.model

data class TransactionView(val requestId: String, val networkKey: String, val txId: String, val readState: ChainReadState, val occurrences: List<TransactionOccurrenceView>, val operationState: String?)
data class TransactionOccurrenceView(
    val inclusion: InclusionView,
    val parentId: String?,
    val childIds: List<String>,
    val valid: Boolean,
    val effective: Boolean,
    val fee: String?,
    val inputs: List<InputView>,
    val outputs: List<OutputView>,
    val directDeposits: DirectDepositsView,
    val accountEffects: List<AccountEffectView>,
    val memberData: List<MemberDataView>,
    val state: String,
    val leios: LeiosProvenanceView?,
)
data class InclusionView(val hash: String, val slot: String, val height: String, val era: Int, val adopted: Boolean, val source: String)
data class LeiosProvenanceView(
    val announcingPoint: ChainPoint,
    val ebHash: String,
    val fullTransactionHash: String,
    val referencePosition: Int,
    val transactionSize: String,
    val certificateHex: String,
)
data class InputView(val txId: String, val index: Int, val role: String, val effective: Boolean)
data class OutputView(val index: Int, val addressHex: String, val coin: String, val assets: List<AssetView>, val collateralReturn: Boolean, val effective: Boolean)
data class AssetView(val policyHex: String, val nameHex: String, val quantity: String)
data class DirectDepositsView(val presence: String, val entries: List<DirectDepositView>)
data class DirectDepositView(val accountAddressHex: String, val amount: String, val effective: Boolean)
data class AccountEffectView(val kind: String, val accountAddressHex: String, val amount: String?, val effective: Boolean, val lowerInclusive: String? = null, val upperExclusive: String? = null)
data class MemberDataView(val family: String, val position: Int, val rawHex: String, val typed: Any?)
