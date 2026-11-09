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
    private val capabilities: CardanoCapabilities,
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
        val capabilityFingerprint: String,
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

    data class ReconciliationObservation(
        val slot: BigInteger,
        val hash: String,
        val inMempool: Boolean,
        val unspentInputs: Set<Pair<String, Int>>,
    )
    data class Reconciliation(val operation: AuthorizedOperation, val reason: String)

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

    private fun releaseUnusedInputs(id: String, batch: Any) {
        entityManager.createNativeQuery(
            "DELETE FROM authorized_operation_inputs r WHERE r.operation_id=:operation AND NOT EXISTS (" +
                "SELECT 1 FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id " +
                "WHERE m.batch_id=:batch AND i.effective AND encode(i.tx_id,'hex')=r.input_tx_id " +
                "AND i.output_index=r.input_index)"
        ).setParameter("operation", id).setParameter("batch", batch).executeUpdate()
    }

    /**
     * An operator explicitly reconciles the persisted whole batch; this never signs or submits.
     * Node observations happen outside the database transaction. The sync row is always locked
     * before the operation, matching the ingestion coordinator's lock order.
     */
    fun reconcile(
        previewId: String, host: Host, node: Node,
        observe: (AuthorizedOperation, Set<Pair<String, Int>>) -> ReconciliationObservation,
    ): Reconciliation {
        val original = operations.findByPreviewId(previewId) ?: throw IllegalArgumentException("Unknown preview")
        require(original.hostId == host.id && original.nodeId == node.id && original.networkKey == eras.networkKey(node)) { "Review target changed" }


        val initial = requireNotNull(independent.execute {
            val sync = lockSync(original.networkKey)
            val record = requireNotNull(entityManager.find(AuthorizedOperation::class.java, original.id, LockModeType.PESSIMISTIC_WRITE))
            if (sync == null) return@execute Reconciliation(record, "Network synchronization state is unavailable")
            if (record.state == "signing") {
                releaseInputs(record.id)
                val cancelled = operations.saveAndFlush(record.copy(state = "not_submitted"))
                return@execute Reconciliation(cancelled, "Interrupted signing was cancelled before submission; create a new review")
            }
            Reconciliation(record, "")
        })
        if (initial.reason.isNotEmpty()) {
            if (initial.operation.state == "not_submitted") coordinator.refreshWallet()
            return initial
        }
        val record = initial.operation
        if (record.state !in setOf("submission_uncertain", "submitted", "rolled_back", "confirmed", "phase2_failed")) {
            return Reconciliation(record, "This review has no outstanding submission to reconcile")
        }
        val signedHex = record.signedBatch ?: return Reconciliation(record, "The signed batch is unavailable; outcome remains unresolved")
        val signed = try { ReviewedTransactionIdentity.decodeSubmitted(signedHex, record.era) } catch (_: Exception) {
            return Reconciliation(record, "The persisted signed batch is invalid; outcome remains unresolved")
        }
        val unsignedHex = json.readTree(record.unsignedBody).path("cborHex").asString()
        val unsigned = try { ReviewedTransactionIdentity.decodeSubmitted(unsignedHex, record.era) } catch (_: Exception) {
            return Reconciliation(record, "The persisted reviewed body is invalid; outcome remains unresolved")
        }
        if (signed.id != record.txId || unsigned.id != record.txId || !signed.body.sameEncoding(unsigned.body)) {
            return Reconciliation(record, "Signed and reviewed body identities differ; outcome remains unresolved")
        }
        val observation = try { observe(record, signed.inputs) } catch (_: Exception) {
            return Reconciliation(record, "Node observations are unavailable; outcome remains unresolved")
        }

        val result = requireNotNull(independent.execute {
            val sync = lockSync(record.networkKey)
            val current = requireNotNull(entityManager.find(AuthorizedOperation::class.java, record.id, LockModeType.PESSIMISTIC_WRITE))
            if (sync == null || sync[0] != "complete" || sync[1] == null || sync[2] == null || sync[3] == null || sync[4] == null) {
                return@execute Reconciliation(current, "A complete point-matched chain index is unavailable")
            }
            val observedSlot = (sync[1] as java.math.BigDecimal).toBigIntegerExact()
            val completeSlot = (sync[3] as java.math.BigDecimal).toBigIntegerExact()
            val observedHash = (sync[2] as ByteArray).toHexString()
            val completeHash = (sync[4] as ByteArray).toHexString()
            if (observation.slot != observedSlot || observation.slot != completeSlot ||
                observation.hash != observedHash || observation.hash != completeHash) {
                return@execute Reconciliation(current, "The node and complete index are at different chain points")
            }
            if (current.signedBatch != signedHex || current.txId != record.txId || current.unsignedBody != record.unsignedBody) {
                return@execute Reconciliation(current, "The persisted review changed during reconciliation")
            }
            if (current.state !in setOf("submission_uncertain", "submitted", "rolled_back", "confirmed", "phase2_failed")) {
                return@execute Reconciliation(current, "The operation state changed during reconciliation")
            }
            val matches = entityManager.createNativeQuery(
                "SELECT b.id, i.header_hash, i.slot, i.height, b.valid, i.era FROM transaction_members m " +
                    "JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions i ON i.id=b.inclusion_id " +
                    "WHERE i.network_key=:network AND i.adopted AND i.complete AND m.parent_id IS NULL AND m.tx_id=decode(:txid,'hex')"
            ).setParameter("network", record.networkKey).setParameter("txid", record.txId).resultList
            if (matches.isNotEmpty()) {
                if (matches.size != 1) return@execute Reconciliation(current, "Multiple adopted parent occurrences require investigation")
                val found = matches.single() as Array<*>
                val rows = entityManager.createNativeQuery(
                    "SELECT raw_body, raw_witnesses, raw_auxiliary FROM transaction_members " +
                        "WHERE batch_id=:batch ORDER BY child_position NULLS FIRST"
                ).setParameter("batch", found[0]).resultList
                val members = rows.map { raw ->
                    val row = raw as Array<*>
                    ReviewedTransactionIdentity.StoredMember(row[0] as ByteArray, row[1] as ByteArray, row[2] as? ByteArray)
                }
                val identical = ReviewedTransactionIdentity.matchesStored((found[5] as Number).toInt(), signedHex, members)
                if (!identical) return@execute Reconciliation(current, "Adopted batch differs from the signed batch; investigate before releasing inputs")
                releaseUnusedInputs(current.id, requireNotNull(found[0]))
                val confirmed = operations.saveAndFlush(current.copy(state = if (found[4] == true) "confirmed" else "phase2_failed",
                    confirmationHash = (found[1] as ByteArray).toHexString(),
                    confirmationSlot = (found[2] as java.math.BigDecimal).toBigIntegerExact(),
                    confirmationHeight = (found[3] as java.math.BigDecimal).toBigIntegerExact()))
                return@execute Reconciliation(confirmed, if (found[4] == true) "The exact signed batch is confirmed" else "The exact signed batch was included but failed phase 2")
            }
            if (current.state in setOf("confirmed", "phase2_failed")) {
                return@execute Reconciliation(current, "Previously confirmed inclusion is not in the complete adopted index; investigate rollback")
            }
            if (observation.inMempool) return@execute Reconciliation(current, "The transaction is in the node mempool; outcome remains uncertain")
            val until = current.validUntil
            if (until != null && observation.slot >= BigInteger.valueOf(until)) {
                releaseInputs(current.id)
                val expired = operations.saveAndFlush(current.copy(state = "expired", confirmationHash = null, confirmationSlot = null, confirmationHeight = null))
                return@execute Reconciliation(expired, "The validity interval expired without an adopted occurrence or mempool entry; inputs were released")
            }
            val missing = signed.inputs.size - observation.unspentInputs.intersect(signed.inputs).size
            Reconciliation(current, if (missing == 0) "Inputs remain unspent, but absence from the mempool does not prove non-submission"
                else "$missing reviewed inputs are no longer unspent; the whole-batch outcome remains unresolved")
        })
        coordinator.refreshWallet()
        return result
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
        require(listOf(evidence.requestFingerprint, evidence.parameterFingerprint, evidence.stateFingerprint, evidence.capabilityFingerprint, evidence.walletGeneration).all { it.isNotBlank() }) { "Missing review evidence" }
        require(evidence.walletGeneration.toLongOrNull()?.let { it >= 0 } == true) { "Invalid wallet generation" }
    }

    /** A request ID is an idempotency key, not permission to replace an earlier review. */
    fun preview(preview: Preview): AuthorizedOperation {
        require(preview.requestId.isNotBlank() && preview.previewId.isNotBlank())
        validateEvidence(preview.evidence)
        require(preview.requestPayload.isNotBlank()) { "Missing secret-free request payload" }
        val era = current(preview.host, preview.node, preview.operation)
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
            era = targetEra, eraGeneration = era.generation, capabilityFingerprint = preview.evidence.capabilityFingerprint,
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
                    "Wallet coverage changed; review again"
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
            // is terminal for this review; a process crash leaves it for reconciliation.
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
