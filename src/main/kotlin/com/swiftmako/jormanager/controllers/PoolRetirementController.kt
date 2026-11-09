package com.swiftmako.jormanager.controllers

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
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
class PoolRetirementController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val walletUtils: WalletUtils,
    private val builder: WalletTransactionBuilder,
    private val parameters: ProtocolParametersRepository,
    private val ledger: LedgerDao,
) {
    data class SubmitRequest(val requestId: String, val nodeId: Long, val feePayerAccountId: Long,
        val retireEpoch: String, val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, nodeId=$nodeId, spendingPassword='********')"
    }
    data class Result(val requestId: String, val txId: String, val networkKey: String, val era: String,
        val nodeId: Long, val fee: String, val deposit: String, val refund: String, val state: String)
    private data class Target(val observer: Node, val pool: Node, val host: Host, val connection: HostConnection,
        val era: String, val network: List<String>, val magic: String, val socket: String)

    private fun target(poolId: Long): Target {
        val observer = requireNotNull(nodes.findDefault()) { "Observer not found" }
        val pool = requireNotNull(nodes.findByIdOrNull(poolId)) { "Pool not found" }
        require(!pool.isDeleted && !pool.poolId.isNullOrBlank()) { "Target is not a managed pool" }
        require(eras.networkKey(pool) == eras.networkKey(observer)) { "Pool and observer are on different networks" }
        val host = requireNotNull(hosts.findByIdOrNull(observer.hostId)) { "Observer host not found" }
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(observer.genesisShelleyFileId)).content)
        val network = when (genesis.path("networkId").asString().lowercase()) {
            "testnet" -> {
                val magic = genesis.path("networkMagic").asString()
                require(magic.matches(Regex("[0-9]+"))) { "Invalid testnet network magic" }
                listOf("--testnet-magic", magic)
            }
            "mainnet" -> listOf("--mainnet")
            else -> throw IllegalArgumentException("Network ID unavailable")
        }
        val magic = network.joinToString(" ")
        val socket = "--socket-path ${host.nodeHomePath}/${observer.name}/db/socket"
        val era = eras.getEra(host, observer, magic, socket)
        return Target(observer, pool, host, HostConnection(host, observer), era, network, magic, socket)
    }
    private fun cmd(t: Target, args: List<String>): String = t.connection.commandArgs(args).trim()
    private fun query(t: Target, vararg args: String): String = cmd(t,
        listOf(t.host.cardanoCliPath, t.era, "query") + args + t.network +
            listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket", "--output-json"))

    fun execute(input: SubmitRequest): Result {
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        UUID.fromString(input.requestId)
        require(input.retireEpoch.matches(Regex("0|[1-9][0-9]*"))) { "Invalid retirement epoch" }
        val epoch = input.retireEpoch.toLong()
        return ledger.withSubmissionLock {
            val t = target(input.nodeId)
            val payer = requireNotNull(wallets.findByIdOrNull(input.feePayerAccountId)) { "Fee payer not found" }
            require(!payer.deleted && payer.paymentSkey != null) { "Fee payer cannot sign" }
            val params = parameters.query(t.host, t.observer, t.connection, t.era, t.magic, t.socket)
            val tip = json.readTree(query(t, "tip"))
            val current = tip.path("epoch").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
                ?: throw IllegalStateException("Current epoch unavailable")
            val maximum = json.readTree(params).path("poolRetireMaxEpoch").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
                ?: throw IllegalStateException("Retirement limit unavailable")
            require(epoch > current && epoch - current <= maximum) { "Retirement epoch outside current permitted interval" }
            val slot = tip.path("slot")
            require(slot.isIntegralNumber && slot.canConvertToLong() && slot.asLong() >= 0 &&
                tip.path("era").asString().equals(t.era, true) && tip.path("hash").asString().matches(Regex("[0-9a-fA-F]{64}"))) { "Current slot unavailable" }
            val poolState = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(query(t, "pool-state", "--stake-pool-id", requireNotNull(t.pool.poolId)))
            require(poolState.isObject && poolState.size() == 1) { "Registered pool state unavailable" }
            val expectedPool = requireNotNull(t.pool.poolId)
            val poolHash = if (expectedPool.startsWith("pool1")) Bech32.decode(expectedPool).let {
                require(it.prefix == "pool" && it.bytes.size == 28)
                it.bytes.toHexString()
            } else expectedPool.lowercase().also { require(it.matches(Regex("[0-9a-f]{56}"))) }
            val entry = poolState.properties().first()
            require(entry.key.equals(poolHash, true) || entry.key.equals(expectedPool, true)) { "Pool query returned a different target" }
            require(entry.value.isObject && entry.value.path("deposit").isIntegralNumber) { "Pool registration or deposit unavailable" }
            val utxos = walletUtils.getUtxos(t.host, t.connection, t.magic, t.socket, t.era, payer.paymentAddr)
            require(utxos.isNotEmpty()) { "Available fee payer inputs unavailable" }
            val prefix = "/tmp/jormanager-pool-${UUID.randomUUID()}"
            val key = "$prefix.vkey"; val cert = "$prefix.cert"; val body = "$prefix.body"
            val protocolPath = "$prefix.protocol.json"; val signed = "$prefix.signed"
            val paths = mutableListOf(key, cert, body, protocolPath, signed)
            try {
                t.connection.commandWriteFile(key, requireNotNull(files.findByIdOrNull(t.pool.coreVKeyId)).content)
                cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-pool", "deregistration-certificate", "--cold-verification-key-file", key,
                    "--epoch", input.retireEpoch, "--out-file", cert))
                t.connection.commandWriteFile(protocolPath, params)
                val built = builder.build(t.host, t.connection, t.era, utxos, emptyList(), payer.paymentAddr,
                    extraArgs = listOf("--certificate-file", cert), protocolParameters = params, protocolPath = protocolPath,
                    witnessCount = 2, bodyPath = body, validUntil = Math.addExact(slot.asLong(), 21600L),
                    referenceScriptSize = walletUtils.queryReferenceScriptSize(t.host, t.connection, t.magic, t.socket, t.era, utxos))
                val fee = CborSpans.parse(json.readTree(built.textEnvelope).path("cborHex").asString().hexToByteArray())
                    .array().first().entries().firstOrNull { it.first.integer() == BigInteger.TWO }?.second?.integer()
                    ?: error("Transaction fee unavailable")
                val keys = listOf(requireNotNull(payer.paymentSkey), requireNotNull(files.findByIdOrNull(t.pool.coreSKeyId)))
                val keyPaths = keys.mapIndexed { index, file ->
                    val path = "$prefix.$index.skey"; paths += path
                    t.connection.commandWriteSecret(path, walletUtils.getSKeyContent(file, input.spendingPassword))
                    path
                }
                val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "sign", "--tx-body-file", body)
                keyPaths.forEach { args += listOf("--signing-key-file", it) }
                args += t.network; args += listOf("--out-file", signed)
                cmd(t, args)
                val envelope = t.connection.commandReadFile(signed)
                require(json.readTree(envelope).path("cborHex").asString().isNotBlank()) { "Invalid signed transaction" }
                cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", signed) + t.network +
                    listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket"))
                ledger.recordSubmitted(t.host, t.observer, t.era, built.txId, envelope)
                Result(input.requestId, built.txId, eras.networkKey(t.observer), t.era, input.nodeId, fee.toString(), "0", "0", "submitted")
            } finally { cmd(t, listOf("rm", "-f") + paths) }
        }
    }

    @MessageMapping("/pool/retirement/submit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("pool_retirement_submit", execute(input))
    } catch (e: Exception) {
        SocketResponse.Error("pool_retirement_submit", SocketResponse.PublicFailure(
            e.javaClass.simpleName, "Retirement submission failed; the outcome may be unknown if submission was interrupted.",
            "pool_retirement_submit", "retry", input.requestId))
    }
}
