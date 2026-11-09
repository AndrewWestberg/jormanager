package com.swiftmako.jormanager.services

import tools.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.AuthorizedOperationInput
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.utils.CborSpan
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
    private val json: ObjectMapper,
    private val coordinator: ChainIngestionCoordinator,
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
        val targetFingerprint: String,
        val walletGeneration: String,
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

    private fun releaseInputs(id: String) {
        entityManager.createQuery("delete from AuthorizedOperationInput i where i.operationId = :operationId")
            .setParameter("operationId", id).executeUpdate()
    }

    private fun lockSync(network: String): Array<Any?>? {
        val rows = entityManager.createNativeQuery(
            "SELECT s.status, s.observed_slot, s.observed_hash, i.slot, i.header_hash, s.generation, " +
                "(i.id IS NOT NULL AND i.complete AND i.adopted) " +
                "FROM sync_states s LEFT JOIN chain_inclusions i ON i.id=s.complete_inclusion_id " +
                "WHERE s.network_key=:network FOR UPDATE OF s"
        ).setParameter("network", network).resultList
        @Suppress("UNCHECKED_CAST")
        return rows.singleOrNull() as? Array<Any?>
    }

    fun expirePending(networkKey: String, generation: Long, slot: BigInteger, hash: String, absentOperationIds: Set<String>): Boolean {
        if (absentOperationIds.isEmpty()) return false
        val changed = independent.execute {
            val sync = lockSync(networkKey) ?: return@execute false
            if (sync[0] != "complete" || sync[6] != true || (sync[5] as Number).toLong() != generation ||
                sync[1] == null || sync[2] == null || sync[3] == null || sync[4] == null ||
                (sync[1] as java.math.BigDecimal).toBigIntegerExact() != slot ||
                (sync[3] as java.math.BigDecimal).toBigIntegerExact() != slot ||
                (sync[2] as ByteArray).toHexString() != hash || (sync[4] as ByteArray).toHexString() != hash) return@execute false

            var updated = false
            for (id in absentOperationIds.sorted()) {
                val record = entityManager.find(AuthorizedOperation::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: continue
                val validUntil = record.validUntil
                if (record.networkKey != networkKey || record.state !in setOf("signing", "submission_uncertain", "submitted", "rolled_back") ||
                    validUntil == null || BigInteger.valueOf(validUntil) > slot) continue
                val valid = try {
                    val unsigned = ReviewedTransactionIdentity.decodeSubmitted(json.readTree(record.unsignedBody).path("cborHex").asString(), record.era)
                    validateConsequences(unsigned.body, record.fee, record.deposit, record.refund, record.validFrom, record.validUntil)
                    val signed = record.signedBatch?.let { ReviewedTransactionIdentity.decodeSubmitted(it, record.era) }
                    unsigned.id == record.txId && (if (signed == null) record.state == "signing" && record.signedBatch == null
                    else signed.id == record.txId && signed.body.sameEncoding(unsigned.body))
                } catch (_: Exception) {
                    false
                }
                if (!valid) continue
                val adopted = entityManager.createNativeQuery(
                    "SELECT 1 FROM transaction_members m JOIN transaction_batches b ON b.id=m.batch_id " +
                        "JOIN chain_inclusions i ON i.id=b.inclusion_id " +
                        "WHERE i.network_key=:network AND i.adopted AND i.complete AND m.tx_id=decode(:txid,'hex')"
                ).setParameter("network", networkKey).setParameter("txid", record.txId).setMaxResults(1).resultList
                if (adopted.isNotEmpty()) continue
                releaseInputs(record.id)
                operations.saveAndFlush(record.copy(state = if (record.state == "signing") "not_submitted" else "expired",
                    confirmationHash = null, confirmationSlot = null, confirmationHeight = null))
                updated = true
            }
            updated
        } == true
        if (changed) coordinator.refreshWallet()
        return changed
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

    private fun current(host: Host, node: Node): CardanoRepository.EraEvidence {
        val evidence = eras.evidence(node)
        check(evidence.freshness == "current" && evidence.source == "host:${host.id}/node:${node.id}" && evidence.networkKey == eras.networkKey(node)) { "Target era unavailable" }
        return evidence
    }

    private fun validateEvidence(evidence: Evidence) {
        require(listOf(evidence.requestFingerprint, evidence.parameterFingerprint, evidence.stateFingerprint, evidence.targetFingerprint, evidence.walletGeneration).all { it.isNotBlank() }) { "Missing review evidence" }
        require(evidence.walletGeneration.toLongOrNull()?.let { it >= 0 } == true) { "Invalid wallet generation" }
    }

    /** A request ID is an idempotency key, not permission to replace an earlier review. */
    fun preview(preview: Preview): AuthorizedOperation {
        require(preview.requestId.isNotBlank() && preview.previewId.isNotBlank())
        validateEvidence(preview.evidence)
        require(preview.requestPayload.isNotBlank()) { "Missing secret-free request payload" }
        val era = current(preview.host, preview.node)
        val bodyJson = json.readTree(preview.body.textEnvelope)
        require(bodyJson.isObject && bodyJson.path("type").isString && bodyJson.path("type").asString().isNotBlank()) { "Invalid CLI transaction body envelope" }
        val bodyHex = bodyJson.path("cborHex").takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("Missing CLI body CBOR")
        require(bodyHex.isNotEmpty() && bodyHex.length % 2 == 0 && bodyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid CLI body CBOR" }
        val targetEra = requireNotNull(era.era) { "Target era unavailable" }
        val unsigned = ReviewedTransactionIdentity.decodeSubmitted(bodyHex, targetEra)
        val txId = preview.body.txId.lowercase()
        require(txId.length == 64 && txId.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid CLI transaction ID" }
        require(unsigned.id == txId) { "CLI transaction ID differs from unsigned body" }
        validateConsequences(unsigned.body, preview.fee, preview.deposit, preview.refund, preview.validFrom, preview.validUntil)
        val candidate = AuthorizedOperation(
            id = preview.requestId, previewId = preview.previewId, networkKey = era.networkKey,
            nodeId = requireNotNull(preview.node.id), hostId = requireNotNull(preview.host.id), operation = preview.operation,
            era = targetEra, eraGeneration = era.generation, targetFingerprint = preview.evidence.targetFingerprint,
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
        val era = current(host, node)
        check(host.id == record.hostId && node.id == record.nodeId && era.networkKey == record.networkKey && era.era == record.era && era.generation == record.eraGeneration) { "Target or era changed; refresh and retry" }
        check(evidence.requestFingerprint == record.requestFingerprint && evidence.parameterFingerprint == record.parameterFingerprint && evidence.stateFingerprint == record.stateFingerprint && evidence.targetFingerprint == record.targetFingerprint) { "Transaction inputs changed; refresh and retry" }
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
        evidence: (AuthorizedOperation) -> Evidence,
        sign: (AuthorizedOperation) -> String,
        submit: (String) -> Unit,
    ): AuthorizedOperation {
        // Claim the review under a short lock. A concurrent request observes the durable claim
        // rather than signing or submitting a second time.
        val found = operations.findByPreviewId(previewId) ?: throw IllegalArgumentException("Unknown preview")
        val claimed = requireNotNull(independent.execute {
            lockSync(found.networkKey)
            val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, found.id, LockModeType.PESSIMISTIC_WRITE))
            if (record.state != "previewed") record to false
            else operations.saveAndFlush(record.copy(state = "signing")) to true
        })
        if (!claimed.second) return claimed.first

        val prepared = try {
            // These callbacks may query the node and invoke the CLI/SSH; no database
            // transaction is held while they run.
            revalidate(claimed.first, host, node, evidence(claimed.first))
            val signedHex = sign(claimed.first).lowercase()
            val signed = ReviewedTransactionIdentity.decodeSubmitted(signedHex, claimed.first.era)
            val unsignedHex = json.readTree(claimed.first.unsignedBody).path("cborHex").asString()
            val unsigned = ReviewedTransactionIdentity.decodeSubmitted(unsignedHex, claimed.first.era)
            check(signed.body.sameEncoding(unsigned.body)) { "Signed body differs from review" }
            check(signed.id == claimed.first.txId) { "Signed body differs from review" }
            validateConsequences(signed.body, claimed.first.fee, claimed.first.deposit, claimed.first.refund, claimed.first.validFrom, claimed.first.validUntil)
            val reservationEvidence = evidence(claimed.first)
            revalidate(claimed.first, host, node, reservationEvidence)
            requireNotNull(independent.execute {
                val sync = lockSync(claimed.first.networkKey)
                check(sync != null && coordinator.isWalletReady(claimed.first.networkKey, (sync[5] as Number).toLong(),
                    sync[0] as String, sync[6] == true) && reservationEvidence.walletGeneration == (sync[5] as Number).toLong().toString()) {
                    "Wallet coverage changed; refresh and retry"
                }
                val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, claimed.first.id, LockModeType.PESSIMISTIC_WRITE))
                check(record.state == "signing") { "Review state changed while signing" }
                signed.inputs.forEach { (txId, index) ->
                    entityManager.persist(AuthorizedOperationInput(UUID.randomUUID().toString(), record.id, record.networkKey, txId, index))
                }
                operations.saveAndFlush(record.copy(state = "submission_uncertain", signedBatch = signedHex))
            }).also { coordinator.refreshWallet() }
        } catch (error: Exception) {
            // Before submission no network write has occurred. A failed signing attempt
            // is terminal; a finite interval lets a later wallet read fence an interrupted signer.
            independent.execute {
                lockSync(claimed.first.networkKey)
                val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, claimed.first.id, LockModeType.PESSIMISTIC_WRITE))
                if (record.state == "signing") {
                    releaseInputs(record.id)
                    operations.saveAndFlush(record.copy(state = "not_submitted"))
                }
            }
            coordinator.refreshWallet()
            throw error
        }
        // A failed final check is provably before the submission callback. Release only
        // those reservations and retain a durable terminal result for this review.
        try {
            revalidate(prepared, host, node, evidence(prepared))
        } catch (error: Exception) {
            independent.execute {
                lockSync(prepared.networkKey)
                val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, prepared.id, LockModeType.PESSIMISTIC_WRITE))
                if (record.state == "submission_uncertain") {
                    entityManager.createQuery("delete from AuthorizedOperationInput i where i.operationId = :operationId")
                        .setParameter("operationId", record.id).executeUpdate()
                    operations.saveAndFlush(record.copy(state = "not_submitted"))
                }
            }
            coordinator.refreshWallet()
            throw error
        }
        // From this point a failed callback may have broadcast. Retain uncertainty
        // and all input reservations, and never retry implicitly.
        try {
            submit(requireNotNull(prepared.signedBatch))
        } catch (error: Exception) {
            coordinator.refreshWallet()
            throw error
        }
        val result = requireNotNull(independent.execute {
            lockSync(prepared.networkKey)
            val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, prepared.id, LockModeType.PESSIMISTIC_WRITE))
            if (record.state == "submission_uncertain") operations.saveAndFlush(record.copy(state = "submitted")) else record
        })
        coordinator.refreshWallet()
        return result
    }
}
