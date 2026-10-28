package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.LeiosKeyRequest
import com.swiftmako.jormanager.services.LeiosKeyService
import com.swiftmako.jormanager.services.LeiosObservationService
import com.swiftmako.jormanager.model.LeiosInstallPreviewRequest
import com.swiftmako.jormanager.model.LeiosInstallRequest
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.LeiosInstallationService
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.PoolKeyObservationRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class LeiosController(
    private val hosts: HostRepository,
    private val nodes: NodeRepository,
    private val eras: CardanoRepository,
    private val keys: LeiosKeyRepository,
    private val observations: PoolKeyObservationRepository,
    private val keyService: LeiosKeyService,
    private val observationService: LeiosObservationService,
    private val installationService: LeiosInstallationService,
    private val installations: KeyInstallationRepository,
    private val chainCoordinator: ChainIngestionCoordinator,
) {
    data class Target(val hostId: Long, val nodeId: Long, val poolId: String? = null, val networkKey: String? = null)
    data class StatusRequest(val requestId: String, val target: Target)
    data class Point(val slot: String, val hash: String)
    data class ReadState(val status: String, val asOf: String?, val observedPoint: Point?, val lastCompletePoint: Point?, val reasonCode: String, val affectedData: List<String>)
    data class Registration(
        val current: Boolean, val future: Boolean, val registrationEpoch: String?,
        val effectiveEpoch: String?, val registrationEpochReason: String?, val scheduledRetirement: String?
    )
    data class Eligibility(val markEpoch: String?, val setEpoch: String?, val eligible: Boolean?, val selected: Boolean?, val expired: Boolean?)
    data class Installation(val state: String, val installedFingerprint: String?, val loadedFingerprint: String?, val permissionResult: String?, val observedAt: String, val stale: Boolean, val error: String?)
    data class KeyView(val keyId: Long?, val publicKeyHex: String, val proofOfPossessionHex: String?, val proofValidated: Boolean, val registration: Registration?, val eligibility: Eligibility?, val installation: Installation?)
    data class Status(val requestId: String, val target: Target, val keys: List<KeyView>, val readState: ReadState)
    data class KeyResult(val requestId: String, val target: Target, val key: KeyView)
    private fun failure(type: String, requestId: String, message: String) = SocketResponse.Error(
        type, SocketResponse.PublicFailure(
            code = "LeiosOperationFailed", message = message, operation = type,
            retryDisposition = "review", requestId = requestId,
        ),
    )


    private fun keyOperation(request: LeiosKeyRequest, intent: String, responseType: String): SocketResponse<KeyResult> = try {
        require(request.requestId.isNotBlank() && request.intent == intent)
        val node = requireNotNull(nodes.findByIdOrNull(request.target.nodeId))
        val host = requireNotNull(hosts.findByIdOrNull(request.target.hostId))
        val key = keyService.prepare(request, host, node)
        val target = Target(request.target.hostId, request.target.nodeId, key.poolId, key.networkKey)
        SocketResponse.Success(responseType, KeyResult(request.requestId, target,
            KeyView(key.id, key.publicKey.toHexString(), key.proof.toHexString(), true, null, null, null)))
    } catch (_: Exception) {
        failure(responseType, request.requestId, "Key operation failed or is not qualified for this target")
    }

    @MessageMapping("/leios/key/prepare")
    @SendTo("/topic/messages")
    fun prepare(request: LeiosKeyRequest): SocketResponse<KeyResult> = keyOperation(request, "prepare", "leios_key_prepared")

    @MessageMapping("/leios/key/import")
    @SendTo("/topic/messages")
    fun importKey(request: LeiosKeyRequest): SocketResponse<KeyResult> = keyOperation(request, "import", "leios_key_imported")

    @MessageMapping("/leios/key/install-preview")
    @SendTo("/topic/messages")
    fun installPreview(request: LeiosInstallPreviewRequest): SocketResponse<LeiosInstallationService.Preview> = try {
        SocketResponse.Success("leios_key_install_preview", installationService.preview(request))
    } catch (_: Exception) {
        failure("leios_key_install_preview", request.requestId, "Installation target or qualified preview unavailable")
    }

    @MessageMapping("/leios/key/install")
    @SendTo("/topic/messages")
    fun install(request: LeiosInstallRequest): SocketResponse<LeiosInstallationService.Result> = try {
        SocketResponse.Success("leios_key_installation", installationService.install(request))
    } catch (_: Exception) {
        failure("leios_key_installation", request.requestId, "Installation failed or the review is stale; inspect the producer before retrying")
    }

    @MessageMapping("/leios/status")
    @SendTo("/topic/messages")
    fun status(request: StatusRequest): SocketResponse<Status> = try {
        require(request.requestId.isNotBlank())
        val node = requireNotNull(nodes.findByIdOrNull(request.target.nodeId))
        val host = requireNotNull(hosts.findByIdOrNull(request.target.hostId))
        require(!node.isDeleted && node.hostId == host.id && node.type in setOf("core", "pool"))
        val pool = requireNotNull(node.poolId)
        val network = eras.networkKey(node)
        require(request.target.poolId == null || request.target.poolId == pool)
        require(request.target.networkKey == null || request.target.networkKey == network)
        val target = Target(request.target.hostId, request.target.nodeId, pool, network)
        val refreshed = try { observationService.refresh(host, node) } catch (_: Exception) { null }
        val observation = refreshed ?: observations.findFirstByNetworkKeyAndPoolIdOrderByObservedAtDesc(network, pool)
        val installed = installations.findByNodeIdAndHostIdOrderByObservedAtDesc(request.target.nodeId, request.target.hostId).groupBy { it.keyId }
        val current = observation?.currentPublicKey?.toHexString()
        val future = observation?.futurePublicKey?.toHexString()
        fun registration(hex: String) = observation?.takeIf { hex == current || hex == future }?.let {
            Registration(
                hex == current, hex == future, it.registrationEpoch?.toPlainString(),
                if (hex == current) it.effectiveEpoch?.toPlainString() else null,
                if (it.registrationEpoch == null) "NODE_QUERY_EXPOSES_EFFECTIVE_EPOCH_ONLY" else null,
                it.scheduledRetirement?.toPlainString()
            )
        }
        fun eligibility(hex: String) = observation?.takeIf { hex == current }?.let {
            Eligibility(it.markEpoch?.toPlainString(), it.setEpoch?.toPlainString(), it.eligible, it.selected, it.expired)
        }
        val views = keys.findByNetworkKeyAndPoolId(network, pool).map { key ->
            val hex = key.publicKey.toHexString()
            val installation = installed[key.id]?.firstOrNull()?.let {
                Installation(it.state, it.installedFingerprint, it.loadedFingerprint, it.permissionResult, it.observedAt.toString(), true, it.sanitizedError)
            }
            KeyView(key.id, hex, key.proof.toHexString(), true, registration(hex), eligibility(hex), installation)
        }.toMutableList()
        listOfNotNull(current, future).distinct().filter { hex -> views.none { it.publicKeyHex == hex } }.forEach { hex ->
            views.add(KeyView(null, hex, null, false, registration(hex), eligibility(hex), null))
        }
        val point = observation?.let { o ->
            val slot = o.chainSlot
            val hash = o.chainHash
            if (slot != null && hash != null) Point(slot.toPlainString(), hash.toHexString()) else null
        }
        val lastComplete = try {
            chainCoordinator.status(network).lastCompletePoint?.let { Point(it.slot, it.hash) }
        } catch (_: Exception) {
            null
        }
        val affected = mutableListOf<String>()
        if (observation?.registrationEpoch == null) affected.add("registrationEpoch")
        if (observation?.markEpoch == null || observation.setEpoch == null) affected.add("snapshotEpochs")
        if (observation?.eligible == null || observation.expired == null) affected.add("eligibility")
        if (observation?.selected == null) affected.add("selection")
        affected.add("loadedKey")
        val state = ReadState(
            when {
                observation == null -> "unavailable"
                refreshed == null -> "stale"
                affected.isNotEmpty() -> "incomplete"
                else -> "complete"
            },
            observation?.observedAt?.toString(), point, lastComplete,
            when {
                observation == null -> "POOL_OBSERVATION_UNAVAILABLE"
                refreshed == null -> "POOL_OBSERVATION_NOT_REFRESHED"
                affected.isNotEmpty() -> "POOL_OBSERVATION_PARTIAL"
                else -> "POOL_OBSERVATION_CURRENT"
            }, affected
        )
        SocketResponse.Success("leios_status", Status(request.requestId, target, views, state))
    } catch (_: Exception) {
        failure("leios_status", request.requestId, "Leios target or observations unavailable")
    }
}
