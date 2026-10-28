package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.TransactionMemberRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.utils.Bech32
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import com.swiftmako.jormanager.services.LedgerReplayService
import org.springframework.data.repository.findByIdOrNull

@Controller
class TransactionController(
    private val members: TransactionMemberRepository,
    private val coordinator: ChainIngestionCoordinator,
    private val nodes: NodeRepository,
    private val cardano: CardanoRepository,
    private val walletUtils: WalletUtils,
    private val replay: LedgerReplayService,
) {
    data class Query(val requestId: String, val networkKey: String, val txId: String? = null, val accountAddressHex: String? = null)
    data class NodeQuery(val requestId: String, val nodeId: Long)

    private fun checkTarget(request: Query) {
        require(request.requestId.isNotBlank() && request.requestId.length <= 128) { "Invalid request ID" }
        require(nodes.findAll().any { node -> runCatching { cardano.networkKey(node) }.getOrNull() == request.networkKey }) { "Unknown configured network" }
    }

    @MessageMapping("/chainstatus")
    @SendTo("/topic/messages")
    fun chainStatus(request: Query): SocketResponse<Any> = respond("chainstatus", request) {
        val coverage = replay.inspect(request.networkKey)
        val status = coverage.readState
        mapOf("requestId" to request.requestId, "networkKey" to status.networkKey, "status" to status.status, "reason" to status.reason, "observedTip" to status.observedTip, "lastCompletePoint" to status.lastCompletePoint, "replay" to coverage)
    }

    @MessageMapping("/node/chainstatus")
    @SendTo("/topic/messages")
    fun nodeChainStatus(request: NodeQuery): SocketResponse<Any> = try {
        require(request.requestId.isNotBlank() && request.requestId.length <= 128) { "Invalid request ID" }
        val node = requireNotNull(nodes.findByIdOrNull(request.nodeId)) { "Unknown node" }
        require(!node.isDeleted) { "Node is deleted" }
        val coverage = replay.inspect(cardano.networkKey(node))
        SocketResponse.Success("node_chainstatus", mapOf(
            "requestId" to request.requestId, "nodeId" to request.nodeId,
            "readState" to coverage.readState, "replay" to coverage,
        ))
    } catch (_: Exception) {
        SocketResponse.Error("node_chainstatus", SocketResponse.PublicFailure(
            "NODE_INDEX_UNAVAILABLE", "Network identity or index unavailable; check configured genesis files and synchronization.",
            "node_chainstatus", "refresh", request.requestId, "node:${request.nodeId}",
        ))
    }

    @MessageMapping("/transaction")
    @SendTo("/topic/messages")
    fun transaction(request: Query): SocketResponse<Any> = respond("transaction", request) {
        members.find(request.requestId, request.networkKey, requireNotNull(request.txId))
    }

    @MessageMapping("/account/history")
    @SendTo("/topic/messages")
    fun accountHistory(request: Query): SocketResponse<Any> = respond("account_history", request) {
        val account = requireNotNull(request.accountAddressHex)
        val history = members.accountHistory(request.requestId, request.networkKey, account)
        val point = (history["readState"] as com.swiftmako.jormanager.model.ChainReadState).lastCompletePoint
        val node = nodes.findAll().firstOrNull { !it.isDeleted && !it.isDraft && runCatching { cardano.networkKey(it) }.getOrNull() == request.networkKey }
        if (point == null || node == null) history else {
            val bytes = account.hexToByteArray()
            val address = Bech32.encode(if ((bytes[0].toInt() and 15) == 1) "stake" else "stake_test", bytes)
            val snapshot = runCatching { walletUtils.getAccountSnapshot(node, address, point) }.getOrNull()
            if (snapshot == null) history else history + mapOf(
                "balance" to snapshot.rewardAccountBalance,
                "registered" to snapshot.registered,
                "registrationDeposit" to snapshot.stakeRegistrationDeposit,
                "balanceReason" to null,
                "balanceSnapshot" to snapshot,
            )
        }
    }

    private fun respond(type: String, request: Query, action: () -> Any): SocketResponse<Any> = try {
        checkTarget(request)
        SocketResponse.Success(type, action())
    } catch (_: Exception) {
        SocketResponse.Error(type, SocketResponse.PublicFailure("QUERY_UNAVAILABLE", "The target or query is invalid, or indexed history is unavailable.", type, "refresh", request.requestId, request.networkKey))
    }
}
