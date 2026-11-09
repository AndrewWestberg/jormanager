package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class GovernanceController(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val walletUtils: WalletUtils,
    private val eras: CardanoRepository,
    private val parameters: ProtocolParametersRepository,
    private val builder: WalletTransactionBuilder,
    private val ledger: LedgerDao,
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

    @MessageMapping("/governancesubmit")
    @SendTo("/topic/messages")
    fun receiveSubmit(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("governancesubmit", execute(input))
    } catch (_: Exception) {
        SocketResponse.Error("governancesubmit", SocketResponse.PublicFailure("OPERATION_UNAVAILABLE",
            "The governance action could not be completed; the outcome may be unknown if submission was interrupted.",
            "governancesubmit", "retry", input.requestId))
    }

    fun execute(input: SubmitRequest): Result {
        UUID.fromString(input.requestId)
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        return ledger.withSubmissionLock {
            val request = input.request
            val target = target()
            val decoded = Bech32.decode(request.govActionId)
            require(decoded.prefix == "gov_action" && decoded.bytes.size == 33) { "Invalid governance action ID" }
            val actionId = decoded.bytes.copyOfRange(0, 32).toHexString()
            val actionIndex = (decoded.bytes[32].toInt() and 255).toString()
            val pools = poolNodes(target, request)
            val payer = wallets.findByIdOrNull(request.feesAccountId) ?: error("Fee payer not found")
            requireNotNull(payer.paymentSkey) { "Fee payer signing key unavailable" }
            val params = parameters.query(target.host, target.node, target.connection, target.era,
                target.magic.joinToString(" "), target.socket.joinToString(" "))
            val utxos = walletUtils.getUtxos(target.host, target.connection, target.magic.joinToString(" "),
                target.socket.joinToString(" "), target.era, payer.paymentAddr)
            require(utxos.isNotEmpty()) { "No available fee payer inputs" }
            val anchor = nodeController.uploadGovernanceRationale(request.rationale)
            val base = "/tmp/jormanager-gov-${UUID.randomUUID()}"
            val paths = mutableListOf<String>()
            fun path(suffix: String): String = "$base-$suffix".also { paths.add(it) }
            val body = path("body")
            val paramsPath = path("params")
            val signed = path("signed")
            try {
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
                target.connection.commandWriteFile(paramsPath, params)
                val built = builder.build(target.host, target.connection, target.era, utxos, emptyList(), payer.paymentAddr,
                    extraArgs = votes.flatMap { listOf("--vote-file", it) }, validUntil = ttl, protocolParameters = params,
                    protocolPath = paramsPath, witnessCount = pools.size + 1, bodyPath = body,
                    referenceScriptSize = walletUtils.queryReferenceScriptSize(target.host, target.connection,
                        target.magic.joinToString(" "), target.socket.joinToString(" "), target.era, utxos))
                val fee = CborSpans.parse(json.readTree(built.textEnvelope).path("cborHex").asString().hexToByteArray())
                    .array().first().entries().firstOrNull { it.first.integer() == BigInteger.TWO }?.second?.integer()
                    ?: error("Transaction fee unavailable")
                val keys = listOf(requireNotNull(payer.paymentSkey)) + pools.map { requireNotNull(files.findByIdOrNull(it.coreSKeyId)) }
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
                val envelope = target.connection.commandReadFile(signed)
                require(json.readTree(envelope).path("cborHex").asString().isNotBlank()) { "Invalid signed transaction" }
                target.connection.commandArgs(listOf(target.host.cardanoCliPath, target.era, "transaction", "submit", "--tx-file", signed) + target.magic + target.socket)
                ledger.recordSubmitted(target.host, target.node, target.era, built.txId, envelope)
                Result(input.requestId, built.txId, eras.networkKey(target.node), target.era, fee.toString(), "0", "0", "submitted")
            } finally {
                if (paths.isNotEmpty()) target.connection.commandArgs(listOf("rm", "-f") + paths)
            }
        }
    }
}
