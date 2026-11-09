package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
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
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import com.swiftmako.jormanager.services.WalletTransactionBuilder
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
    private val reviews: ReviewedWalletOperations,
    private val builder: WalletTransactionBuilder,
    private val operations: AuthorizedOperationRepository,
    private val nodeController: NodeController,
    private val genesisAdapter: JsonAdapter<GenesisShelley>,
    private val tipAdapter: JsonAdapter<QueryTip>,
    private val json: ObjectMapper,
) {
    data class VoteRequest(val govActionId: String, val votes: List<NodeVoteSelection>, val feesAccountId: Long, val rationale: String? = null)
    data class SubmitRequest(val requestId: String, val request: VoteRequest,
        @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, spendingPassword=********)"
    }
    data class Result(val requestId: String, val txId: String, val networkKey: String, val era: String,
        val fee: String, val deposit: String, val refund: String, val state: String)
    private data class Target(val node: Node, val host: Host, val connection: HostConnection, val era: String, val magic: List<String>, val socket: List<String>)
    private data class Observation(val evidence: ReviewedWalletOperations.Evidence, val snapshot: PendingWalletState.Snapshot, val parameters: String)

    private fun target(): Target {
        val node = nodes.findDefault() ?: error("Default node not found")
        val host = hosts.findByIdOrNull(node.hostId) ?: error("Node host not found")
        val genesis = genesisAdapter.fromJson(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
            ?: error("Invalid Shelley genesis")
        val magic = if (genesis.networkId.equals("testnet", true)) listOf("--testnet-magic", genesis.networkMagic.toString()) else listOf("--mainnet")
        val socket = listOf("--socket-path", "${host.nodeHomePath}/${node.name}/db/socket")
        val era = eras.getEra(host, node, magic.joinToString(" "), socket.joinToString(" "))
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
    private fun evidence(target: Target, payload: String, excludingOperationId: String? = null): Observation {
        check(nodes.findDefault()?.id == target.node.id) { "Default node changed" }
        val request = json.treeToValue(json.readTree(payload).path("request"), VoteRequest::class.java)
        val pools = poolNodes(target, request)
        val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
        val paymentSigningKey = requireNotNull(payer.paymentSkey) { "Fee payer signing key unavailable" }
        val params = protocolSnapshots.query(target.host, target.node, target.connection, target.era, target.magic.joinToString(" "), target.socket.joinToString(" "))
        val gov = target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "query", "gov-state") + target.magic + target.socket + "--output-json")
        val snapshot = walletUtils.getUtxoSnapshot(target.host, target.node, target.connection, target.magic.joinToString(" "),
            target.socket.joinToString(" "), target.era, setOf(payer.paymentAddr), excludingOperationId)
        val state = listOf(payer.paymentAddr, paymentSigningKey.id, digest(paymentSigningKey.content), payer.paymentVkey?.id, payer.paymentVkey?.content,
            canonicalWalletInputs(snapshot, payer.paymentAddr), snapshot.readState.generation,
            pools.map { listOf(it.id, it.coreSKeyId, digest(requireNotNull(files.findByIdOrNull(it.coreSKeyId)).content),
                it.coreVKeyId, requireNotNull(files.findByIdOrNull(it.coreVKeyId)).content) }, json.readTree(gov))
        val e = eras.evidence(target.node)
        check(e.freshness == "current" && e.era == target.era && e.source == "host:${target.host.id}/node:${target.node.id}") { "Current era unavailable" }
        return Observation(ReviewedWalletOperations.Evidence(digest(payload), digest(json.readTree(params).toString()),
            digest(json.writeValueAsString(state)), digest(json.writeValueAsString(listOf(e.networkKey, e.era, e.generation, target.host.id, target.node.id))), snapshot.readState.generation), snapshot, params)
    }

    private fun bodyField(envelope: String, key: Int): BigInteger? {
        val hex = json.readTree(envelope).path("cborHex").asString()
        val body = CborSpans.parse(hex.hexToByteArray()).array().first()
        return body.entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    }

    private fun result(record: com.swiftmako.jormanager.entities.AuthorizedOperation, requestId: String): Result =
        Result(requestId, record.txId, record.networkKey, record.era, record.fee.toString(), record.deposit.toString(), record.refund.toString(), record.state)

    @MessageMapping("/governancesubmit")
    @SendTo("/topic/messages")
    fun receiveSubmit(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("governancesubmit", execute(input))
    } catch (_: Exception) {
        val state = operations.findById(input.requestId).orElse(null)?.state
        val uncertain = state in setOf("signing", "submission_uncertain", "submitted", "rolled_back")
        SocketResponse.Error("governancesubmit", SocketResponse.PublicFailure("OPERATION_UNAVAILABLE",
            if (uncertain) "Submission outcome may be uncertain." else "The governance action could not be completed.",
            "governancesubmit", if (uncertain) "wait" else "retry", input.requestId))
    }

    private fun review(input: SubmitRequest): Result {
        val request = input.request
        val intent = json.readTree(json.writeValueAsString(request))
        val existing = operations.findById(input.requestId).orElse(null)
        if (existing != null) {
            check(existing.operation == "governance.vote" && json.readTree(existing.requestPayload).path("intent") == intent) { "Request ID already used for a different action" }
            return result(existing, input.requestId)
        }
        val payload = json.writeValueAsString(mapOf("intent" to intent, "request" to request))
        val target = target()
        val decoded = Bech32.decode(request.govActionId)
        require(decoded.prefix == "gov_action" && decoded.bytes.size == 33) { "Invalid governance action ID" }
        val actionId = decoded.bytes.copyOfRange(0, 32).toHexString()
        val actionIndex = (decoded.bytes[32].toInt() and 255).toString()
        val pools = poolNodes(target, request)
        val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
        requireNotNull(payer.paymentSkey) { "Fee payer signing key unavailable" }
        val observed = evidence(target, payload)
        require(observed.snapshot.utxosByAddress[payer.paymentAddr].orEmpty().isNotEmpty()) { "No available fee payer inputs" }
        val anchor = nodeController.uploadGovernanceRationale(request.rationale)
        val finalPayload = json.writeValueAsString(mapOf("intent" to intent, "request" to request, "anchor" to anchor?.let { listOf(it.first, it.second) }))
        val base = "/tmp/jormanager-gov-${UUID.randomUUID()}"
        val paths = mutableListOf<String>()
        fun path(suffix: String): String = "$base-$suffix".also { paths.add(it) }
        val body = path("body")
        val paramsPath = path("params")
        try {
            val utxos = observed.snapshot.utxosByAddress[payer.paymentAddr].orEmpty()
            require(utxos.isNotEmpty()) { "No available fee payer inputs" }
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
            target.connection.commandWriteFile(paramsPath, observed.parameters)
            val args = votes.flatMap { listOf("--vote-file", it) }
            val built = builder.build(target.host, target.connection, target.era, utxos, emptyList(), payer.paymentAddr,
                extraArgs = args, validUntil = ttl, protocolParameters = observed.parameters, protocolPath = paramsPath,
                witnessCount = pools.size + 1, bodyPath = body)
            val envelope = built.textEnvelope
            val fee = requireNotNull(bodyField(envelope, 2)) { "Transaction fee unavailable" }
            val validFrom = bodyField(envelope, 8)?.longValueExact()
            val validUntil = bodyField(envelope, 3)?.longValueExact()
            val after = evidence(target, finalPayload).evidence
            check(after.parameterFingerprint == observed.evidence.parameterFingerprint && after.stateFingerprint == observed.evidence.stateFingerprint && after.targetFingerprint == observed.evidence.targetFingerprint) { "Governance state changed" }
            val record = reviews.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), target.host, target.node, "governance.vote", after, finalPayload, built, fee, BigInteger.ZERO, BigInteger.ZERO, validFrom, validUntil))
            return result(record, input.requestId)
        } finally {
            if (paths.isNotEmpty()) target.connection.commandArgs(listOf("rm", "-f") + paths)
        }
    }

    @Synchronized
    fun execute(input: SubmitRequest): Result {
        UUID.fromString(input.requestId)
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        review(input)
        val existing = operations.findById(input.requestId).orElseThrow()
        if (existing.state != "previewed") return result(existing, input.requestId)
        val target = target()
        val request = json.treeToValue(json.readTree(existing.requestPayload).path("request"), VoteRequest::class.java)
        val base = "/tmp/jormanager-gov-${UUID.randomUUID()}"
        val paths = mutableListOf<String>()
        fun path(suffix: String): String = "$base-$suffix".also { paths.add(it) }
        val body = path("body")
        val signed = path("signed")
        try {
            val record = reviews.submit(existing.previewId, target.host, target.node, { operation -> evidence(target, existing.requestPayload, operation.id).evidence }, { operation ->
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
                json.readTree(target.connection.commandReadFile(signed)).path("cborHex").asString().also { require(it.isNotBlank()) { "Invalid signed transaction" } }
            }, { signedHex ->
                val envelope = json.readTree(target.connection.commandReadFile(signed))
                check(envelope.path("cborHex").asString().equals(signedHex, true)) { "Signed transaction changed" }
                target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "transaction", "submit", "--tx-file", signed) + target.magic + target.socket)
            })
            return result(record, input.requestId)
        } finally {
            if (paths.isNotEmpty()) target.connection.commandArgs(listOf("rm", "-f") + paths)
        }
    }
}
