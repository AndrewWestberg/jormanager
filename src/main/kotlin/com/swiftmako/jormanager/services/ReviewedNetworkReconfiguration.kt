package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.swiftmako.jormanager.entities.NetworkReconfigurationReview
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NetworkReconfigurationReviewRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
data class ReconfigurationPreviewRequest(val sourceNodeId: Long, val request: JsonNode)
data class ReconfigurationAuthorizationRequest(val id: String, val spendingPassword: String, val acknowledgeCleanDatabase: Boolean)

@Service
class ReviewedNetworkReconfiguration(
    private val reviews: NetworkReconfigurationReviewRepository,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val bundles: NetworkBundleValidator,
    private val json: ObjectMapper,
    private val entityManager: EntityManager,
    private val passwordEncoder: Argon2PasswordEncoder,
    @param:Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
) {
    private fun fingerprint(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(json.writeValueAsBytes(value)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun redactSecrets(node: JsonNode) {
        if (node is ObjectNode) {
            node.remove(listOf("spendingPassword", "sudoPassword", "reconfigurationId", "coldSKey", "vrfSKey", "kesSKey", "privateKey"))
        }
        if (node.isContainerNode) node.elements().forEachRemaining { redactSecrets(it) }
    }

    private fun requestFingerprint(request: CreateNodeRequest): String {
        val value = json.valueToTree<ObjectNode>(request)
        redactSecrets(value)
        return fingerprint(value)
    }

    private fun validateTargetName(request: CreateNodeRequest) {
        require(request.name.isNotBlank()) { "Replacement node name is required" }
        require(nodes.findAll().none { !it.isDeleted && it.hostId == request.hostId && it.name == request.name }) {
            "Replacement requires a new node name and clean directory on the target host"
        }
    }

    @Transactional
    fun preview(input: ReconfigurationPreviewRequest): NetworkReconfigurationReview {
        val old = nodes.findById(input.sourceNodeId).orElseThrow { IllegalArgumentException("Original node not found") }
        require(!old.isDeleted) { "Original node is deleted" }
        val oldHost = hosts.findById(old.hostId).orElseThrow { IllegalArgumentException("Original host not found") }
        require(input.request.isObject) { "Replacement request must be an object" }
        val safeRequest = (input.request.deepCopy<JsonNode>() as ObjectNode).apply {
            redactSecrets(this)
            put("spendingPassword", "")
            put("sudoPassword", "")
        }
        val request = json.treeToValue(safeRequest, CreateNodeRequest::class.java)
        val newHost = hosts.findById(request.hostId).orElseThrow { IllegalArgumentException("Replacement host not found") }
        require(request.type == old.type) { "Replacement role must match the original node" }
        require(request.parentId == null) { "Replacement must use a standalone clean node, not an existing parent" }
        validateTargetName(request)
        val priorBundle = requireNotNull(old.configurationBundleId) { "Original node has no immutable bundle" }
        val priorReset = requireNotNull(old.configurationResetId) { "Original node has no reset identity" }
        bundles.resolve(priorBundle, priorReset)
        val newBundle = requireNotNull(request.bundleId) { "Replacement bundle required" }
        val newReset = requireNotNull(request.expectedResetId) { "Replacement reset required" }
        require(newBundle != priorBundle) { "Replacement must select a different immutable bundle" }
        bundles.resolve(newBundle, newReset)
        val consequence = if (newReset == priorReset) "Topology or configuration update: review the new bundle; existing node and database remain unchanged." else
            "New network reset: existing balances, registrations, pending authorizations and chain state must not be reused; the existing node and keys remain unchanged."
        val consequences = "$consequence A new clean directory and database with full sync or explicitly supported recovery are required. No upgrade or startup is implied by this authorization."
        val reviewed = json.valueToTree<ObjectNode>(request).apply {
            redactSecrets(this)
        }
        return reviews.save(NetworkReconfigurationReview(
            id = UUID.randomUUID().toString(), sourceNodeId = input.sourceNodeId, sourceHostId = old.hostId,
            targetHostId = request.hostId, priorBundleId = priorBundle, priorResetId = priorReset,
            targetBundleId = newBundle, targetResetId = newReset,
            sourceFingerprint = fingerprint(listOf(old, oldHost)), targetHostFingerprint = fingerprint(newHost),
            requestFingerprint = requestFingerprint(request), reviewedRequest = json.writeValueAsString(reviewed),
            consequences = consequences, state = "previewed", createdAt = Instant.now(),
        ))
    }

    private fun current(review: NetworkReconfigurationReview) {
        val old = nodes.findById(review.sourceNodeId).orElseThrow { IllegalStateException("Original node no longer exists") }
        val oldHost = hosts.findById(review.sourceHostId).orElseThrow { IllegalStateException("Original host no longer exists") }
        val targetHost = hosts.findById(review.targetHostId).orElseThrow { IllegalStateException("Replacement host no longer exists") }
        require(!old.isDeleted && old.hostId == review.sourceHostId && fingerprint(listOf(old, oldHost)) == review.sourceFingerprint &&
            fingerprint(targetHost) == review.targetHostFingerprint) { "Original node or host changed after review" }
        bundles.resolve(review.priorBundleId, review.priorResetId)
        bundles.resolve(review.targetBundleId, review.targetResetId)
    }

    private fun locked(id: String): NetworkReconfigurationReview =
        entityManager.find(NetworkReconfigurationReview::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            ?: throw IllegalArgumentException("Reconfiguration review not found")

    @Transactional
    fun authorize(input: ReconfigurationAuthorizationRequest): NetworkReconfigurationReview {
        require(passwordEncoder.matches(input.spendingPassword, spendingPasswordHash)) { "Invalid spending password" }
        require(input.acknowledgeCleanDatabase) { "Explicit clean database and replay acknowledgement required" }
        val review = locked(input.id)
        require(review.state == "previewed") { "Reconfiguration review is not awaiting authorization" }
        current(review)
        return reviews.save(review.copy(state = "authorized", authorizedAt = Instant.now()))
    }

    @Transactional
    fun consume(id: String, request: CreateNodeRequest): NetworkReconfigurationReview {
        val review = locked(id)
        require(review.state == "authorized") { "Reconfiguration is not authorized or was already used" }
        require(request.hostId == review.targetHostId && request.bundleId == review.targetBundleId &&
            request.expectedResetId == review.targetResetId && requestFingerprint(request) == review.requestFingerprint) {
            "Replacement request differs from the reviewed request"
        }
        current(review)
        validateTargetName(request)
        return reviews.save(review.copy(state = "consumed", consumedAt = Instant.now()))
    }
}
