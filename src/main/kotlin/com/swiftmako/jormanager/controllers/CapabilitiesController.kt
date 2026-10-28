package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.CardanoCapabilities
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class CapabilitiesController(
    private val hosts: HostRepository,
    private val nodes: NodeRepository,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
) {
    data class Request(
        val requestId: String, val hostId: Long, val nodeId: Long? = null, val expectedNetworkKey: String? = null,
        val observerNodeId: Long? = null, val configSha256: String? = null,
    )
    data class QualificationRequest(
        val requestId: String, val hostId: Long, val nodeId: Long, val expectedNetworkKey: String,
        val qualification: CardanoCapabilities.Qualification,
    )
    data class HostQualificationRequest(
        val requestId: String, val hostId: Long, val observerNodeId: Long, val expectedNetworkKey: String,
        val qualification: CardanoCapabilities.Qualification,
    )
    data class QualificationResult(val requestId: String, val recorded: Boolean, val reason: String? = null)
    data class Result(
        val requestId: String,
        val networkKey: String? = null,
        val eraEvidence: CardanoRepository.EraEvidence? = null,
        val operations: List<CardanoCapabilities.OperationCapability> = emptyList(),
        val reason: String? = null,
    )

    @MessageMapping("/capabilities")
    @SendTo("/topic/messages")
    fun capabilities(request: Request): SocketResponse<Result> {
        val result = try {
            val host = requireNotNull(hosts.findByIdOrNull(request.hostId))
            val node = request.nodeId?.let { requireNotNull(nodes.findByIdOrNull(it)) }
            if (node == null && request.observerNodeId != null && request.configSha256 != null && request.expectedNetworkKey != null) {
                val observer = requireNotNull(nodes.findByIdOrNull(request.observerNodeId))
                val observerHost = requireNotNull(hosts.findByIdOrNull(observer.hostId))
                val evidence = eras.evidence(observer)
                val operations = listOf("node.create", "leios.key.prepare", "leios.key.import").map {
                    capabilities.hostCapability(host, observerHost, observer, request.expectedNetworkKey, request.configSha256, it)
                }
                Result(request.requestId, request.expectedNetworkKey, evidence, operations)
            } else if (node == null) {
                Result(request.requestId, reason = "An observer, expected network and effective configuration fingerprint are required")
            } else {
                require(host.id == node.hostId)
                val network = eras.networkKey(node)
                if (request.expectedNetworkKey != null && request.expectedNetworkKey != network) {
                    Result(request.requestId, networkKey = network, reason = "Target network does not match the request")
                } else {
                    Result(request.requestId, network, eras.evidence(node), capabilities.capabilities(host, node))
                }
            }
        } catch (_: Exception) {
            Result(request.requestId, reason = "Target or network configuration is unavailable")
        }
        return SocketResponse.Success(type = "capabilities", data = result)
    }

    @MessageMapping("/capabilities/qualification")
    @SendTo("/topic/messages")
    fun qualification(request: QualificationRequest): SocketResponse<QualificationResult> {
        val result = try {
            val host = requireNotNull(hosts.findByIdOrNull(request.hostId))
            val node = requireNotNull(nodes.findByIdOrNull(request.nodeId))
            capabilities.recordQualification(host, node, request.expectedNetworkKey, request.qualification)
            QualificationResult(request.requestId, true)
        } catch (_: Exception) {
            QualificationResult(request.requestId, false, "Qualification was not recorded; check target, current era, evidence and artifact fingerprints")
        }
        return SocketResponse.Success(type = "capability_qualification", data = result)
    }

    @MessageMapping("/capabilities/host-qualification")
    @SendTo("/topic/messages")
    fun hostQualification(request: HostQualificationRequest): SocketResponse<QualificationResult> {
        val result = try {
            val host = requireNotNull(hosts.findByIdOrNull(request.hostId))
            val observer = requireNotNull(nodes.findByIdOrNull(request.observerNodeId))
            val observerHost = requireNotNull(hosts.findByIdOrNull(observer.hostId))
            capabilities.recordHostQualification(host, observerHost, observer, request.expectedNetworkKey, request.qualification)
            QualificationResult(request.requestId, true)
        } catch (_: Exception) {
            QualificationResult(request.requestId, false, "Host qualification was not recorded; check observer, network, evidence and host artifact fingerprints")
        }
        return SocketResponse.Success(type = "host_capability_qualification", data = result)
    }
}
