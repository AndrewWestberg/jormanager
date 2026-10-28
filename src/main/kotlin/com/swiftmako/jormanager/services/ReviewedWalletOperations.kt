package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.AuthorizedOperationInput
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DijkstraBlockDecoder
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.math.BigInteger
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Durable authorization boundary. Callbacks are supplied by the wallet workflow, never by a client. */
@Service
class ReviewedWalletOperations(
    private val operations: AuthorizedOperationRepository,
    private val entityManager: EntityManager,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val json: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val independent = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Fingerprints must be calculated from fresh, authoritative observations by the wallet workflow. */
    data class Evidence(
        val requestFingerprint: String,
        val parameterFingerprint: String,
        val stateFingerprint: String,
        val capabilityFingerprint: String,
    )

    /** Exact CLI text envelope and the ID obtained from that same file with transaction txid. */
    data class CliBody(val textEnvelope: String, val txId: String)

    data class Preview(
        val requestId: String,
        val previewId: String,
        val host: Host,
        val node: Node,
        val operation: String,
        val evidence: Evidence,
        val requestPayload: String,
        val body: CliBody,
        val fee: BigInteger,
        val deposit: BigInteger,
        val refund: BigInteger,
        val validFrom: Long?,
        val validUntil: Long?,
    )

    private data class Envelope(val body: CborSpan, val id: String, val inputs: Set<Pair<String, Int>>)

    private fun decode(hex: String, era: String): Envelope {
        require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid transaction hex" }
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val parts = CborSpans.parse(bytes).array()
        require(parts.size == 3 || parts.size == 4) { "Invalid submitted transaction envelope" }
        if (era != "dijkstra") require(parts.size == 4) { "Invalid transaction representation for target era" }
        if (parts.size == 4) require(parts[2].boolean()) { "Invalid submission validity flag" }
        val body = parts[0]
        val fields = body.entries()
        require(fields.isNotEmpty() && parts[1].major == 5) { "Invalid transaction body or witnesses" }
        val keys = HashSet<BigInteger>()
        fields.forEach { (key, _) -> require(key.major == 0 && keys.add(key.integer())) { "Invalid or duplicate transaction field" } }
        require(keys.containsAll(setOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO))) { "A child is not a complete submitted transaction" }
        val inputs = if (era == "dijkstra") {
            val batch = DijkstraBlockDecoder.decodeSubmittedTransaction(bytes)
            (listOf(batch.parent) + batch.children).flatMap { it.inputs + it.collateralInputs }
                .map { it.transactionId.toHexString() to it.index }.toSet()
        } else {
            fields.filter { it.first.integer() in setOf(BigInteger.ZERO, BigInteger.valueOf(13)) }.flatMap { (_, value) ->
                val items = if (value.major == 6) value.tagged(258).array() else value.array()
                items.map { item ->
                    val pair = item.array()
                    require(pair.size == 2 && pair[0].major == 2 && pair[0].payload().size == 32) { "Invalid transaction input" }
                    val index = pair[1].integer()
                    require(index.signum() >= 0 && index <= BigInteger.valueOf(65535)) { "Invalid transaction input index" }
                    pair[0].payload().toHexString() to index.toInt()
                }
            }.toSet()
        }
        return Envelope(body, Blake2b.hash256(body.bytes()).toHexString(), inputs)
    }


    private fun field(body: CborSpan, key: Int): BigInteger? =
        body.entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()

    private fun validateConsequences(body: CborSpan, fee: BigInteger, deposit: BigInteger, refund: BigInteger, from: Long?, until: Long?) {
        require(fee.signum() >= 0 && deposit.signum() >= 0 && refund.signum() >= 0) { "Negative reviewed consequence" }
        require(field(body, 2) == fee) { "Reviewed fee differs from transaction body" }
        require(field(body, 8) == from?.let(BigInteger::valueOf) && field(body, 3) == until?.let(BigInteger::valueOf)) { "Reviewed validity differs from transaction body" }
        require(from == null || from >= 0) { "Invalid validity start" }
        require(until == null || until >= 0) { "Invalid validity end" }
        require(from == null || until == null || from <= until) { "Invalid validity interval" }
    }

    private fun current(host: Host, node: Node, operation: String): CardanoRepository.EraEvidence {
        capabilities.requireOperation(host, node, operation)
        val evidence = eras.evidence(node)
        check(evidence.freshness == "current" && evidence.source == "host:${host.id}/node:${node.id}" && evidence.networkKey == eras.networkKey(node)) { "Target era unavailable" }
        return evidence
    }

    private fun validateEvidence(evidence: Evidence) {
        require(listOf(evidence.requestFingerprint, evidence.parameterFingerprint, evidence.stateFingerprint, evidence.capabilityFingerprint).all { it.isNotBlank() }) { "Missing review evidence" }
    }

    /** A request ID is an idempotency key, not permission to replace an earlier review. */
    fun preview(preview: Preview): AuthorizedOperation {
        require(preview.requestId.isNotBlank() && preview.previewId.isNotBlank())
        validateEvidence(preview.evidence)
        require(preview.requestPayload.isNotBlank()) { "Missing secret-free request payload" }
        val era = current(preview.host, preview.node, preview.operation)
        val bodyJson = json.readTree(preview.body.textEnvelope)
        require(bodyJson.isObject && bodyJson.path("type").isTextual && bodyJson.path("type").asText().isNotBlank()) { "Invalid CLI transaction body envelope" }
        val bodyHex = bodyJson.path("cborHex").takeIf { it.isTextual }?.asText() ?: throw IllegalArgumentException("Missing CLI body CBOR")
        require(bodyHex.isNotEmpty() && bodyHex.length % 2 == 0 && bodyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid CLI body CBOR" }
        CborSpans.parse(ByteArray(bodyHex.length / 2) { bodyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        val txId = preview.body.txId.lowercase()
        require(txId.length == 64 && txId.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid CLI transaction ID" }
        require(preview.fee.signum() >= 0 && preview.deposit.signum() >= 0 && preview.refund.signum() >= 0)
        require(preview.validFrom == null || preview.validFrom >= 0)
        require(preview.validUntil == null || preview.validUntil >= 0)
        require(preview.validFrom == null || preview.validUntil == null || preview.validFrom <= preview.validUntil)
        val candidate = AuthorizedOperation(
            id = preview.requestId, previewId = preview.previewId, networkKey = era.networkKey,
            nodeId = requireNotNull(preview.node.id), hostId = requireNotNull(preview.host.id), operation = preview.operation,
            era = era.era, eraGeneration = era.generation, capabilityFingerprint = preview.evidence.capabilityFingerprint,
            state = "previewed", requestFingerprint = preview.evidence.requestFingerprint,
            requestPayload = preview.requestPayload,
            parameterFingerprint = preview.evidence.parameterFingerprint, stateFingerprint = preview.evidence.stateFingerprint,
            txId = txId, unsignedBody = preview.body.textEnvelope,
            fee = preview.fee, deposit = preview.deposit, refund = preview.refund,
            validFrom = preview.validFrom, validUntil = preview.validUntil,
        )
        fun reconcile(existing: AuthorizedOperation): AuthorizedOperation {
            val comparable = existing.copy(
                id = candidate.id, previewId = candidate.previewId, state = candidate.state,
                signedBatch = null, confirmationHash = null, confirmationSlot = null, confirmationHeight = null,
            )
            check(comparable == candidate) { "Request or transaction already has a different review" }
            return existing
        }
        try {
            return requireNotNull(independent.execute {
                val existing = operations.findById(preview.requestId).orElse(null)
                    ?: operations.findByNetworkKeyAndTxId(candidate.networkKey, candidate.txId)
                if (existing != null) reconcile(existing) else operations.saveAndFlush(candidate)
            })
        } catch (error: DataIntegrityViolationException) {
            // A concurrent preview may have won either unique key; never turn it into a second authorization.
            return requireNotNull(independent.execute {
                val existing = operations.findById(preview.requestId).orElse(null)
                    ?: operations.findByNetworkKeyAndTxId(candidate.networkKey, candidate.txId)
                    ?: throw error
                reconcile(existing)
            })
        }
    }

    private fun revalidate(record: AuthorizedOperation, host: Host, node: Node, evidence: Evidence) {
        validateEvidence(evidence)
        val era = current(host, node, record.operation)
        check(host.id == record.hostId && node.id == record.nodeId && era.networkKey == record.networkKey && era.era == record.era && era.generation == record.eraGeneration) { "Target or era changed; review again" }
        check(evidence.requestFingerprint == record.requestFingerprint && evidence.parameterFingerprint == record.parameterFingerprint && evidence.stateFingerprint == record.stateFingerprint && evidence.capabilityFingerprint == record.capabilityFingerprint) { "Review evidence changed; review again" }
    }

    /**
     * Signer must validate credentials and return a complete CBOR submission envelope. The callback
     * must submit exactly those bytes to the specified target. Neither callback may persist credentials.
     * An uncertain outcome is never automatically resubmitted, even after restart or rollback.
     */
    fun submit(
        previewId: String,
        host: Host,
        node: Node,
        evidence: () -> Evidence,
        sign: (AuthorizedOperation) -> String,
        submit: (String) -> Unit,
    ): AuthorizedOperation {
        val prepared = requireNotNull(independent.execute {
            val found = operations.findByPreviewId(previewId) ?: throw IllegalArgumentException("Unknown preview")
            val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, found.id, LockModeType.PESSIMISTIC_WRITE))
            if (record.state != "previewed") return@execute record to false
            revalidate(record, host, node, evidence())
            val signedHex = sign(record).lowercase()
            val signed = decode(signedHex, record.era)
            check(signed.id == record.txId) { "Signed body differs from review" }
            validateConsequences(signed.body, record.fee, record.deposit, record.refund, record.validFrom, record.validUntil)
            revalidate(record, host, node, evidence())
            signed.inputs.forEach { (txId, index) ->
                entityManager.persist(AuthorizedOperationInput(UUID.randomUUID().toString(), record.id, record.networkKey, txId, index))
            }
            operations.saveAndFlush(record.copy(state = "submission_uncertain", signedBatch = signedHex)) to true
        })
        if (!prepared.second) return prepared.first
        // Any error from this point retains uncertainty. Never retry implicitly.
        revalidate(prepared.first, host, node, evidence())
        submit(requireNotNull(prepared.first.signedBatch))
        return requireNotNull(independent.execute {
            val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, prepared.first.id, LockModeType.PESSIMISTIC_WRITE))
            if (record.state == "submission_uncertain") operations.saveAndFlush(record.copy(state = "submitted")) else record
        })
    }
}
