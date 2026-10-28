package com.swiftmako.jormanager.model

import com.swiftmako.jormanager.utils.CborSpan
import java.math.BigInteger

/** Byte spans retain the source buffer; callers must not mutate the input bytes. */
data class DecodedLedgerBlock(
    val header: CborSpan,
    val body: CborSpan,
    val transactions: List<DecodedTransactionBatch>,
    val leiosCertificate: CborSpan?,
    val perasCertificate: CborSpan?,
    val headerDetails: DecodedDijkstraHeader? = null,
    val byronHeader: DecodedByronHeader? = null,
    val encoded: ByteArray,
)

data class DecodedTransactionBatch(
    val raw: CborSpan,
    val parent: DecodedTransactionMember,
    val children: List<DecodedTransactionMember>,
    /** Null denotes submission, not a producer-confirmed success. */
    val isValid: Boolean?
) {
    /** Pending transactions have no adopted effects. */
    val ordinaryEffectsApplied: Boolean get() = isValid == true
    val collateralEffectsApplied: Boolean get() = isValid == false
}

data class DecodedTransactionMember(
    val bodyId: ByteArray,
    val body: CborSpan,
    val witnesses: CborSpan,
    /** Null means absent; a CBOR null span records an explicitly present null. */
    val auxiliaryData: CborSpan?,
    val inputs: List<DecodedInput>,
    val outputs: List<DecodedOutput>,
    val collateralInputs: List<DecodedInput>,
    val referenceInputs: List<DecodedInput>,
    val collateralReturn: DecodedOutput?,
    val fee: BigInteger?,
    val totalCollateral: BigInteger?,
    val withdrawals: List<DecodedAccountAmount>,
    val directDeposits: List<DecodedAccountAmount>,
    val accountIntervals: List<DecodedAccountInterval>,
    val startingAccountIntervals: List<DecodedAccountInterval>,
    val mint: List<DecodedAsset>,
    val witnessData: DecodedWitnessData,
    val auxiliary: DecodedAuxiliaryData?,
    val fields: Map<Int, CborSpan>,
    val certificates: List<DecodedCertificate> = emptyList(),
    val votes: List<DecodedVote> = emptyList(),
    val proposals: List<DecodedProposal> = emptyList(),
    val guards: List<DecodedCredential> = emptyList(),
    val requiredGuards: List<DecodedRequiredGuard> = emptyList()
)

data class DecodedInput(val transactionId: ByteArray, val index: Int)
data class DecodedAsset(val policy: ByteArray, val name: ByteArray, val quantity: BigInteger)
data class DecodedOutput(
    val raw: CborSpan,
    val address: ByteArray,
    val coin: BigInteger,
    val assets: List<DecodedAsset>,
    val datum: CborSpan?,
    val referenceScript: CborSpan?,
    val datumKind: Int? = null,
    val referenceScriptDetails: DecodedScript? = null
)
data class DecodedAccountAmount(val address: ByteArray, val amount: BigInteger)
data class DecodedAccountInterval(
    val address: ByteArray,
    val lowerInclusive: BigInteger?,
    val upperExclusive: BigInteger?,
    val exact: BigInteger?
)

data class DecodedScript(val language: Int, val raw: CborSpan)
data class DecodedRedeemer(
    val purpose: Int,
    val index: BigInteger,
    val data: CborSpan,
    val memory: BigInteger,
    val steps: BigInteger
)
data class DecodedWitnessData(
    val scripts: List<DecodedScript>,
    val datums: List<CborSpan>,
    val redeemers: List<DecodedRedeemer>
)

data class DecodedAuxiliaryData(
    val metadata: CborSpan?,
    val scripts: List<DecodedScript>
)

data class DecodedDijkstraHeader(
    val body: CborSpan,
    val blockNumber: BigInteger,
    val slot: BigInteger,
    val previousHash: ByteArray?,
    val bodySize: BigInteger,
    val bodyHash: ByteArray,
    val highestSupportedMajor: BigInteger,
    val softwareTag: BigInteger,
    val containsLeiosCertificate: Boolean,
    val announcement: DecodedEbAnnouncement?,
    val issuerVkey: ByteArray,
    val vrfVkey: ByteArray,
    val vrfResult: ByteArray,
    val vrfProof: ByteArray
)
data class DecodedEbAnnouncement(val hash: ByteArray, val size: BigInteger)
data class DecodedCredential(val kind: Int, val hash: ByteArray)
data class DecodedPoolParameters(
    val operator: ByteArray, val vrf: ByteArray, val blsPublicKey: ByteArray?,
    val blsProof: ByteArray?, val pledge: BigInteger, val cost: BigInteger,
    val marginNumerator: BigInteger, val marginDenominator: BigInteger,
    val rewardAccount: ByteArray, val owners: List<ByteArray>, val relays: CborSpan,
    val metadata: CborSpan?
)
data class DecodedCertificate(
    val raw: CborSpan, val kind: Int, val credential: DecodedCredential? = null,
    val pool: ByteArray? = null, val amount: BigInteger? = null,
    val epoch: BigInteger? = null, val poolParameters: DecodedPoolParameters? = null,
    val delegateRepresentative: CborSpan? = null, val otherCredential: DecodedCredential? = null,
    val anchor: CborSpan? = null
)
data class DecodedVote(
    val voterKind: Int, val voterHash: ByteArray, val actionTransactionId: ByteArray,
    val actionIndex: Int, val vote: Int, val anchor: CborSpan?
)
data class DecodedProposal(
    val raw: CborSpan, val deposit: BigInteger, val rewardAccount: ByteArray,
    val actionKind: Int, val action: CborSpan, val anchor: CborSpan,
    val previousAction: DecodedGovernanceActionId? = null,
    val treasuryWithdrawals: List<DecodedAccountAmount> = emptyList(),
    val guardrailHash: ByteArray? = null,
    val committeeRemoved: List<DecodedCredential> = emptyList(),
    val committeeAdded: List<DecodedCommitteeMember> = emptyList(),
    val quorumNumerator: BigInteger? = null, val quorumDenominator: BigInteger? = null,
    val parameters: CborSpan? = null, val constitution: CborSpan? = null,
    val protocolMajor: BigInteger? = null, val protocolMinor: BigInteger? = null
)

data class DecodedByronHeader(
    val boundary: Boolean, val headerHash: ByteArray, val previousHash: ByteArray,
    val blockNumber: BigInteger, val slot: BigInteger
)

data class DecodedRequiredGuard(val credential: DecodedCredential, val datum: CborSpan?)

data class DecodedGovernanceActionId(val transactionId: ByteArray, val index: Int)
data class DecodedCommitteeMember(val credential: DecodedCredential, val epoch: BigInteger)
