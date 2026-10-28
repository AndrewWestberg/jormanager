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
    data class Request(val requestId: String, val hostId: Long, val nodeId: Long? = null, val expectedNetworkKey: String? = null)
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
            val node = request.nodeId?.let { nodes.findByIdOrNull(it) }
            if (node == null) {
                Result(request.requestId, reason = "An existing verified observer node is required")
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
}
