package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeVoteSelection
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.CardanoCapabilities
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class GovernanceReviewController(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val walletUtils: WalletUtils,
    private val eras: CardanoRepository,
    private val protocolSnapshots: ProtocolSnapshotRepository,
    private val capabilities: CardanoCapabilities,
    private val reviews: ReviewedWalletOperations,
    private val operations: AuthorizedOperationRepository,
    private val nodeController: NodeController,
    private val genesisAdapter: JsonAdapter<GenesisShelley>,
    private val tipAdapter: JsonAdapter<QueryTip>,
    private val json: ObjectMapper,
) {
    data class VoteRequest(val govActionId: String, val votes: List<NodeVoteSelection>, val feesAccountId: Long, val anchorUrl: String? = null, val anchorHash: String? = null)
    data class ReviewRequest(val requestId: String, val request: VoteRequest)
    data class PublishRequest(val requestId: String, val rationale: String, val spendingPassword: String) {
        override fun toString() = "PublishRequest(requestId=$requestId, spendingPassword=********)"
    }
    data class PublishedAnchor(val requestId: String, val anchorUrl: String, val anchorHash: String)
    data class SubmitRequest(val requestId: String, val previewId: String, val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, previewId=$previewId, spendingPassword=********)"
    }
    data class Result(
        val requestId: String,
        val previewId: String, val txId: String, val networkKey: String, val era: String,
        val fee: String, val deposit: String, val refund: String,
        val validFrom: String?, val validUntil: String?, val state: String, val effects: JsonNode,
    )
    private data class Target(val node: Node, val host: Host, val connection: HostConnection, val era: String, val magic: List<String>, val socket: List<String>)

    private fun target(): Target {
        val node = nodes.findDefault() ?: error("Default node not found")
        val host = hosts.findByIdOrNull(node.hostId) ?: error("Node host not found")
        val genesis = genesisAdapter.fromJson(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
            ?: error("Invalid Shelley genesis")
        val magic = if (genesis.networkId.equals("testnet", true)) listOf("--testnet-magic", genesis.networkMagic.toString()) else listOf("--mainnet")
        val socket = listOf("--socket-path", "${host.nodeHomePath}/${node.name}/db/socket")
        val era = eras.getEra(host, node, magic.joinToString(" "), socket.joinToString(" "))
        capabilities.requireOperation(host, node, "governance.vote")
        return Target(node, host, HostConnection(host, node), era, magic, socket)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()
    private fun poolNodes(target: Target, request: VoteRequest): List<Node> {
        require(request.votes.isNotEmpty() && request.votes.map { it.nodeId }.distinct().size == request.votes.size) { "Select distinct pools" }
        return request.votes.map { vote ->
            require(vote.vote in setOf("YES", "NO", "ABSTAIN")) { "Invalid vote" }
            val node = nodes.findByIdOrNull(vote.nodeId) ?: error("Pool not found")
            require(!node.isDeleted && node.type != "relay" && eras.networkKey(node) == eras.networkKey(target.node)) { "Pool is not on the target network" }
            requireNotNull(files.findByIdOrNull(node.coreVKeyId)) { "Pool verification key unavailable" }
            requireNotNull(files.findByIdOrNull(node.coreSKeyId)) { "Pool signing key unavailable" }
            node
        }
    }
    private fun evidence(target: Target, payload: String): ReviewedWalletOperations.Evidence {
        check(nodes.findDefault()?.id == target.node.id) { "Default node changed" }
        val request = json.treeToValue(json.readTree(payload).path("request"), VoteRequest::class.java)
        val pools = poolNodes(target, request)
        val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
        val paymentSigningKey = requireNotNull(payer.paymentSkey) { "Fee payer signing key unavailable" }
        val params = protocolSnapshots.query(target.host, target.node, target.connection, target.era, target.magic.joinToString(" "), target.socket.joinToString(" "))
        val gov = target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "query", "gov-state") + target.magic + target.socket + "--output-json")
        val nodeUtxos = json.readTree(target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "query", "utxo", "--address", payer.paymentAddr) + target.magic + target.socket + "--output-json"))
        require(nodeUtxos.isObject) { "Invalid node UTxO response" }
        val utxos = walletUtils.getUtxos(target.host, target.connection, target.magic.joinToString(" "), target.socket.joinToString(" "), target.era, payer.paymentAddr)
            .sortedWith(compareBy({ it.hash }, { it.ix }))
        check(utxos.all { nodeUtxos.has("${it.hash}#${it.ix}") }) { "Confirmed wallet inputs differ from the target node; wait for synchronization" }
        val state = listOf(payer.paymentAddr, paymentSigningKey.id, payer.paymentVkey?.id, payer.paymentVkey?.content, utxos, nodeUtxos, pools.map { listOf(it.id, it.coreSKeyId, it.coreVKeyId, files.findByIdOrNull(it.coreVKeyId)!!.content) }, json.readTree(gov))
        val capability = capabilities.capability(target.host, target.node, "governance.vote")
        return ReviewedWalletOperations.Evidence(digest(payload), digest(json.readTree(params).toString()), digest(json.writeValueAsString(state)), digest(json.writeValueAsString(capability)))
    }

    private fun bodyField(envelope: String, key: Int): BigInteger? {
        val hex = json.readTree(envelope).path("cborHex").asText()
        val body = CborSpans.parse(hex.hexToByteArray()).array().first()
        return body.entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    }

    private fun exactJson(value: JsonNode): JsonNode = when {
        value.isObject -> json.createObjectNode().also { out -> value.fields().forEachRemaining { (key, child) -> out.set<JsonNode>(key, exactJson(child)) } }
        value.isArray -> json.createArrayNode().also { out -> value.forEach { out.add(exactJson(it)) } }
        value.isNumber -> json.nodeFactory.textNode(value.asText())
        else -> value
    }

    private fun result(record: com.swiftmako.jormanager.entities.AuthorizedOperation, requestId: String): Result =
        Result(requestId, record.previewId, record.txId, record.networkKey, record.era, record.fee.toString(), record.deposit.toString(), record.refund.toString(),
            record.validFrom?.toString(), record.validUntil?.toString(), record.state, json.readTree(record.requestPayload).path("effects"))

    @MessageMapping("/governancepublish")
    @SendTo("/topic/messages")
    fun receivePublication(input: PublishRequest): SocketResponse<PublishedAnchor> = try {
        require(input.requestId.isNotBlank() && input.requestId.length <= 128) { "Invalid request ID" }
        require(input.rationale.isNotBlank()) { "Rationale is empty" }
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        target()
        val anchor = requireNotNull(nodeController.uploadGovernanceRationale(input.rationale))
        SocketResponse.Success("governancepublish", PublishedAnchor(input.requestId, anchor.first, anchor.second))
    } catch (_: Exception) {
        SocketResponse.Error("governancepublish", SocketResponse.PublicFailure("PUBLICATION_UNAVAILABLE", "Rationale publication could not be completed; check its publication status before retrying.", "governancepublish", "reconcile", input.requestId))
    }

    @MessageMapping("/governancereview")
    @SendTo("/topic/messages")
    fun receiveReview(input: ReviewRequest): SocketResponse<Result> =
        respond("governancereview", input.requestId) { review(input) }

    @MessageMapping("/governancesubmit")
    @SendTo("/topic/messages")
    fun receiveSubmit(input: SubmitRequest): SocketResponse<Result> =
        respond("governancesubmit", input.requestId) { submit(input) }

    private fun respond(type: String, requestId: String, action: () -> Result): SocketResponse<Result> = try {
        require(requestId.isNotBlank() && requestId.length <= 128) { "Invalid request ID" }
        SocketResponse.Success(type, action())
    } catch (_: Exception) {
        SocketResponse.Error(type, SocketResponse.PublicFailure("OPERATION_UNAVAILABLE", "The governance operation could not be completed; review the target and try again.", type, "review", requestId))
    }

    private fun review(input: ReviewRequest): Result {
        require(input.requestId.isNotBlank()) { "Missing request ID" }
        val request = input.request
        require((request.anchorUrl == null) == (request.anchorHash == null)) { "Incomplete rationale anchor" }
        request.anchorUrl?.let { url ->
            require(url.toByteArray(Charsets.UTF_8).size <= 128 && Regex("""https://cardanostakehouse\.com/(?:[A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})+""").matches(url)) { "Invalid rationale anchor URL" }
            require(request.anchorHash!!.matches(Regex("[0-9a-f]{64}"))) { "Invalid rationale anchor hash" }
        }
        val payload = json.writeValueAsString(mapOf("request" to request))
        val existing = operations.findById(input.requestId).orElse(null)
        if (existing != null) {
            check(existing.operation == "governance.vote" && json.readTree(existing.requestPayload).path("request") == json.readTree(payload).path("request")) { "Request ID already used for a different review" }
            return result(existing, input.requestId)
        }
        val target = target()
        val decoded = Bech32.decode(request.govActionId)
        require(decoded.prefix == "gov_action" && decoded.bytes.size == 33) { "Invalid governance action ID" }
        val actionId = decoded.bytes.copyOfRange(0, 32).toHexString()
        val actionIndex = (decoded.bytes[32].toInt() and 255).toString()
        val pools = poolNodes(target, request)
        val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
        requireNotNull(payer.paymentSkey) { "Fee payer signing key unavailable" }
        val observed = evidence(target, payload)
        val base = "/tmp/jormanager-gov-${UUID.randomUUID()}"
        val paths = mutableListOf<String>()
        fun path(suffix: String): String = "$base-$suffix".also { paths.add(it) }
        val body = path("body")
        try {
            val utxos = walletUtils.getUtxos(target.host, target.connection, target.magic.joinToString(" "), target.socket.joinToString(" "), target.era, payer.paymentAddr)
            require(utxos.isNotEmpty()) { "No confirmed fee payer inputs" }
            val nodeUtxos = json.readTree(target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "query", "utxo", "--address", payer.paymentAddr) + target.magic + target.socket + "--output-json"))
            require(nodeUtxos.isObject && utxos.all { nodeUtxos.has("${it.hash}#${it.ix}") }) { "Confirmed wallet inputs differ from the target node; wait for synchronization" }
            val anchor = request.anchorUrl?.let { it to requireNotNull(request.anchorHash) }
            val votes = pools.mapIndexed { index, pool ->
                val vkey = path("$index.vkey")
                val vote = path("$index.vote")
                target.connection.commandWriteFile(vkey, requireNotNull(files.findByIdOrNull(pool.coreVKeyId)).content)
                val args = mutableListOf(target.host.cardanoCliPath, target.era, "governance", "vote", "create", "--${request.votes[index].vote.lowercase()}", "--governance-action-tx-id", actionId, "--governance-action-index", actionIndex, "--cold-verification-key-file", vkey)
                anchor?.let { args.addAll(listOf("--anchor-url", it.first, "--anchor-data-hash", it.second)) }
                args.addAll(listOf("--out-file", vote))
                target.connection.commandArgs(args)
                vote
            }
            val tip = target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "query", "tip") + target.magic + target.socket + "--output-json")
            val ttl = Math.addExact(requireNotNull(tipAdapter.fromJson(tip)).slot, 21600L)
            val args = mutableListOf(target.host.cardanoCliPath, target.era, "transaction", "build", "--witness-override", (pools.size + 1).toString())
            args.addAll(target.magic)
            args.addAll(target.socket)
            utxos.forEach { args.addAll(listOf("--tx-in", "${it.hash}#${it.ix}")) }
            args.addAll(listOf("--change-address", payer.paymentAddr, "--invalid-hereafter", ttl.toString()))
            votes.forEach { args.addAll(listOf("--vote-file", it)) }
            args.addAll(listOf("--out-file", body))
            target.connection.commandArgs(args)
            val envelope = target.connection.commandReadFile(body)
            val fee = requireNotNull(bodyField(envelope, 2)) { "Transaction fee unavailable" }
            val validFrom = bodyField(envelope, 8)?.longValueExact()
            val validUntil = bodyField(envelope, 3)?.longValueExact()
            val view = json.readTree(target.connection.commandArgs(listOf(target.host.cardanoCliPath, "debug", "transaction", "view", "--tx-body-file", body, "--output-json")))
            val finalPayload = json.writeValueAsString(mapOf("request" to request, "effects" to exactJson(view)))
            val after = evidence(target, finalPayload)
            check(after.parameterFingerprint == observed.parameterFingerprint && after.stateFingerprint == observed.stateFingerprint && after.capabilityFingerprint == observed.capabilityFingerprint) { "Governance state changed; review again" }
            val txid = target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "transaction", "txid", "--tx-body-file", body, "--output-text")).trim()
            val record = reviews.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), target.host, target.node, "governance.vote", after, finalPayload, ReviewedWalletOperations.CliBody(envelope, txid), fee, BigInteger.ZERO, BigInteger.ZERO, validFrom, validUntil))
            return result(record, input.requestId)
        } finally {
            if (paths.isNotEmpty()) target.connection.commandArgs(listOf("rm", "-f") + paths)
        }
    }

    private fun submit(input: SubmitRequest): Result {
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        val target = target()
        val existing = operations.findByPreviewId(input.previewId) ?: error("Unknown preview")
        require(existing.operation == "governance.vote") { "Not a governance preview" }
        val request = json.treeToValue(json.readTree(existing.requestPayload).path("request"), VoteRequest::class.java)
        val base = "/tmp/jormanager-gov-${UUID.randomUUID()}"
        val paths = mutableListOf<String>()
        fun path(suffix: String): String = "$base-$suffix".also { paths.add(it) }
        val body = path("body")
        val signed = path("signed")
        try {
            val record = reviews.submit(input.previewId, target.host, target.node, { evidence(target, existing.requestPayload) }, { operation ->
                val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
                val keys = listOf(requireNotNull(payer.paymentSkey)) + poolNodes(target, request).map { requireNotNull(files.findByIdOrNull(it.coreSKeyId)) }
                target.connection.commandWriteFile(body, operation.unsignedBody)
                val keyPaths = keys.mapIndexed { index, key ->
                    val file = path("$index.skey")
                    target.connection.commandWriteSecret(file, walletUtils.getSKeyContent(key, input.spendingPassword))
                    file
                }
                val args = mutableListOf(target.host.cardanoCliPath, target.era, "transaction", "sign", "--tx-body-file", body)
                keyPaths.forEach { args.addAll(listOf("--signing-key-file", it)) }
                args.addAll(target.magic)
                args.addAll(listOf("--out-file", signed))
                target.connection.commandArgs(args)
                json.readTree(target.connection.commandReadFile(signed)).path("cborHex").asText().also { require(it.isNotBlank()) { "Invalid signed transaction" } }
            }, { signedHex ->
                val envelope = json.readTree(target.connection.commandReadFile(signed))
                check(envelope.path("cborHex").asText().equals(signedHex, true)) { "Signed transaction changed" }
                target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "transaction", "submit", "--tx-file", signed) + target.magic + target.socket)
            })
            return result(record, input.requestId)
        } finally {
            if (paths.isNotEmpty()) target.connection.commandArgs(listOf("rm", "-f") + paths)
        }
    }
}
