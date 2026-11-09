package com.swiftmako.jormanager.controllers

import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.Metadata
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.PoolRegistrationCertificateBuilder
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
class PoolRegistrationController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val parameters: ProtocolParametersRepository,
    private val walletUtils: WalletUtils,
    private val certificates: PoolRegistrationCertificateBuilder,
    private val builder: WalletTransactionBuilder,
    private val ledger: LedgerDao,
    private val nodeController: NodeController,
) {
    data class SubmitRequest(val requestId: String, val nodeId: Long, val feePayerAccountId: Long,
        val keyId: Long? = null, val action: String, val changes: JsonNode? = null,
        val metadata: Metadata? = null, val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, nodeId=$nodeId, action=$action, spendingPassword='********')"
    }
    data class Result(val requestId: String, val txId: String, val networkKey: String, val era: String,
        val nodeId: Long, val fee: String, val deposit: String, val refund: String, val state: String,
        val action: String, val keyId: Long?, val publication: NodeController.PublishedPoolMetadata? = null)
    private data class Target(val observer: Node, val pool: Node, val host: Host, val connection: HostConnection,
        val era: String, val network: List<String>, val magic: String, val socket: String)
    private fun target(poolId: Long, observerId: Long? = null): Target {
        val observer = requireNotNull(if (observerId == null) nodes.findDefault() else nodes.findByIdOrNull(observerId)) { "Observer not found" }
        val pool = requireNotNull(nodes.findByIdOrNull(poolId)) { "Pool not found" }
        require(!observer.isDeleted && !observer.isDraft && !pool.isDeleted && !pool.poolId.isNullOrBlank()) { "Pool or observer unavailable" }
        require(eras.networkKey(pool) == eras.networkKey(observer)) { "Pool and observer are on different networks" }
        val host = requireNotNull(hosts.findByIdOrNull(observer.hostId)) { "Observer host not found" }
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(observer.genesisShelleyFileId)).content)
        val network = when (genesis.path("networkId").asString().lowercase()) {
            "mainnet" -> listOf("--mainnet")
            "testnet" -> listOf("--testnet-magic", genesis.path("networkMagic").asString().also { require(it.matches(Regex("[0-9]+"))) })
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
    private fun expiry(t: Target): Long {
        val tip = json.readTree(query(t, "tip"))
        val slot = tip.path("slot")
        require(slot.isIntegralNumber && slot.canConvertToLong() && slot.asLong() >= 0 &&
            tip.path("era").asString().equals(t.era, true) && tip.path("hash").asString().matches(Regex("[0-9a-fA-F]{64}"))) { "Current slot unavailable" }
        return Math.addExact(slot.asLong(), 21600L)
    }
    private fun observe(t: Target, payerId: Long, action: String): Pair<String, JsonNode> {
        val payer = requireNotNull(wallets.findByIdOrNull(payerId)) { "Fee payer not found" }
        require(!payer.deleted && payer.paymentSkey != null) { "Fee payer cannot sign" }
        val parameters = parameters.query(t.host, t.observer, t.connection, t.era, t.magic, t.socket)
        val poolState = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(query(t, "pool-state", "--stake-pool-id", requireNotNull(t.pool.poolId)))
        require(poolState.isObject && poolState.size() <= 1) { "Pool state unavailable" }
        val poolId = requireNotNull(t.pool.poolId)
        val poolHash = if (poolId.startsWith("pool1")) Bech32.decode(poolId).let {
            require(it.prefix == "pool" && it.bytes.size == 28)
            it.bytes.toHexString()
        } else poolId.lowercase().also { require(it.matches(Regex("[0-9a-f]{56}"))) }
        if (poolState.size() == 1) require(poolState.propertyNames().first().equals(poolHash, true)) { "Pool query returned a different target" }
        if (t.pool.isDraft) require(poolState.isEmpty && action == "register") { "Draft pool is already registered or action is invalid" }
        if (action != "register") require(poolState.size() == 1 && poolState.values().first().path("deposit").isIntegralNumber) {
            "Registered pool state unavailable"
        }
        if (action == "register" && !t.pool.isDraft) require(poolState.size() == 1 && poolState.values().first().path("deposit").isIntegralNumber) {
            "Existing pool registration unavailable"
        }
        val currentEpoch = json.readTree(query(t, "tip")).path("epoch")
            .takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
            ?: throw IllegalStateException("Current epoch unavailable")
        if (action == "cancel") {
            val retirement = poolState.values().first().path("retiring")
            require(retirement.isIntegralNumber && retirement.canConvertToLong() && retirement.asLong() > currentEpoch) {
                "Pool has no future scheduled retirement"
            }
        }
        return parameters to poolState
    }
    private fun bodyField(body: String, key: Int): BigInteger? = CborSpans.parse(json.readTree(body).path("cborHex").asString().hexToByteArray())
        .array().first().entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()

    @Synchronized
    fun execute(input: SubmitRequest): Result {
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        UUID.fromString(input.requestId)
        require(input.action in setOf("register", "rotate", "cancel", "update")) { "Unsupported pool action" }
        require(input.changes == null || input.changes.isObject) { "Invalid pool changes" }
        require(input.changes?.has("metadata") != true) { "Client metadata anchors are not accepted" }
        require(input.changes == null || input.changes.propertyNames().all {
            it in setOf("pledge", "cost", "margin", "relays", "ownerAccountIds", "rewardAccountId")
        }) { "Invalid pool changes" }
        input.changes?.let { changes ->
            listOf("pledge", "cost").forEach { name ->
                changes.get(name)?.let { require(it.asString().matches(Regex("0|[1-9][0-9]*"))) { "Invalid pool amount" } }
            }
            changes.get("ownerAccountIds")?.let {
                require(it.isArray && it.size() > 0 && it.all { id -> id.isIntegralNumber && id.canConvertToLong() && id.asLong() > 0 } &&
                    it.values().map { id -> id.asLong() }.distinct().size == it.size()) { "Invalid pool owners" }
            }
            changes.get("rewardAccountId")?.let {
                require(it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0) { "Invalid reward account" }
            }
            changes.get("relays")?.let { require(it.isArray || it.isNull) { "Invalid pool relays" } }
            changes.get("margin")?.let { require(it.isObject || it.isNumber || it.isString) { "Invalid pool margin" } }
        }
        return ledger.withSubmissionLock { submit(input) }
    }

    private fun submit(input: SubmitRequest): Result {
        val t = target(input.nodeId)
        if (t.era.equals("dijkstra", true) && input.action in setOf("register", "rotate")) require(input.keyId != null) { "Voting key required" }
        if (!t.era.equals("dijkstra", true)) require(input.keyId == null) { "Voting key is not valid for this era" }
        val (protocol, poolState) = observe(t, input.feePayerAccountId, input.action)
        val payer = requireNotNull(wallets.findByIdOrNull(input.feePayerAccountId))
        val inputs = walletUtils.getUtxos(t.host, t.connection, t.magic, t.socket, t.era, payer.paymentAddr)
        require(inputs.isNotEmpty()) { "Available fee payer inputs unavailable" }
        val publication = input.metadata?.let { nodeController.publishPoolMetadata(t.pool, it, input.spendingPassword) }
        val changes = (input.changes as? tools.jackson.databind.node.ObjectNode)?.deepCopy() ?: json.createObjectNode()
        publication?.let { changes.set("metadata", json.readTree(json.writeValueAsString(mapOf("url" to it.url, "hash" to it.hash)))) }
        val prefix = "/tmp/jormanager-pool-registration-${UUID.randomUUID()}"
        val body = "$prefix.body"
        val protocolPath = "$prefix.protocol.json"
        val cert = certificates.build(t.host, t.observer, t.pool, t.era, t.network, poolState,
            input.action, input.keyId, input.spendingPassword, prefix, changes)
        val extraPaths = mutableListOf<String>()
        try {
            val stakeIds = (cert.ownerAccountIds + cert.rewardAccountId).distinct().sorted()
            val stakeStates = stakeIds.associateWith { id ->
                val account = requireNotNull(wallets.findByIdOrNull(id)) { "Stake account not found" }
                require(!account.deleted)
                val address = requireNotNull(account.stakingAddr) { "Stake address unavailable" }
                json.readTree(query(t, "stake-address-info", "--address", address)).also { require(it.isArray && it.size() <= 1) }
            }
            val expectedPoolDeposit = if (poolState.isEmpty)
                json.readTree(protocol).path("stakePoolDeposit").asString().toBigInteger() else BigInteger.ZERO
            check(cert.deposit == expectedPoolDeposit) { "Pool deposit does not match protocol parameters" }
            val signers = cert.signingFileIds.toMutableSet()
            val registrationCerts = mutableListOf<String>()
            val delegationCerts = mutableListOf<String>()
            var stakeDeposit = BigInteger.ZERO
            val stakeDepositPerAccount = json.readTree(protocol).path("stakeAddressDeposit").asString().toBigInteger()
            stakeIds.forEach { accountId ->
                val account = requireNotNull(wallets.findByIdOrNull(accountId))
                if (requireNotNull(stakeStates[accountId]).isEmpty) {
                    val vkey = "$prefix.stake-$accountId.vkey"
                    val registration = "$prefix.stake-$accountId.cert"
                    extraPaths += listOf(vkey, registration)
                    t.connection.commandWriteFile(vkey, requireNotNull(account.stakingVkey).content)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-address", "registration-certificate",
                        "--stake-verification-key-file", vkey, "--key-reg-deposit-amt", stakeDepositPerAccount.toString(),
                        "--out-file", registration))
                    registrationCerts += registration
                    signers += requireNotNull(requireNotNull(account.stakingSkey).id)
                    stakeDeposit += stakeDepositPerAccount
                }
            }
            if (poolState.isEmpty || input.changes?.has("ownerAccountIds") == true) {
                val cold = "$prefix.delegate-cold.vkey"
                extraPaths += cold
                t.connection.commandWriteFile(cold, requireNotNull(files.findByIdOrNull(t.pool.coreVKeyId)).content)
                cert.ownerAccountIds.distinct().forEach { accountId ->
                    val owner = requireNotNull(wallets.findByIdOrNull(accountId))
                    val vkey = "$prefix.delegate-$accountId.vkey"
                    val delegation = "$prefix.delegate-$accountId.cert"
                    extraPaths += listOf(vkey, delegation)
                    t.connection.commandWriteFile(vkey, requireNotNull(owner.stakingVkey).content)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-address", "stake-delegation-certificate",
                        "--stake-verification-key-file", vkey, "--cold-verification-key-file", cold, "--out-file", delegation))
                    delegationCerts += delegation
                    signers += requireNotNull(requireNotNull(owner.stakingSkey).id)
                }
            }
            val signingIds = signers.sorted()
            t.connection.commandWriteFile(protocolPath, protocol)
            val built = builder.build(
                host = t.host, connection = t.connection, era = t.era,
                inputs = inputs,
                outputs = emptyList(), changeAddress = payer.paymentAddr,
                deposits = cert.deposit + stakeDeposit,
                extraArgs = (registrationCerts + cert.certPath + delegationCerts).flatMap { listOf("--certificate-file", it) },
                protocolParameters = protocol, protocolPath = protocolPath,
                witnessCount = signingIds.size + 1, bodyPath = body, validUntil = expiry(t),
                referenceScriptSize = walletUtils.queryReferenceScriptSize(t.host, t.connection, t.magic, t.socket, t.era, inputs)
            )
            val signed = "$prefix.signed"
            val paths = mutableListOf<String>()
            try {
                val signingFiles = listOf(requireNotNull(payer.paymentSkey)) + signingIds.map { requireNotNull(files.findByIdOrNull(it)) }
                signingFiles.forEachIndexed { i, file ->
                    val path = "$prefix.$i.skey"; paths += path
                    t.connection.commandWriteSecret(path, walletUtils.getSKeyContent(file, input.spendingPassword))
                }
                val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "sign", "--tx-body-file", body)
                paths.forEach { args += listOf("--signing-key-file", it) }
                args += t.network; args += listOf("--out-file", signed)
                cmd(t, args)
                val envelope = t.connection.commandReadFile(signed)
                require(json.readTree(envelope).path("cborHex").asString().isNotBlank())
                cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", signed) + t.network +
                    listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket"))
                ledger.recordSubmitted(t.host, t.observer, t.era, built.txId, envelope)
                publication?.let {
                    nodes.save(t.pool.copy(metadataUrl = it.url, extendedMetadataUrl = it.extendedUrl,
                        itnPrivateKeyId = it.itnPrivateKeyId ?: t.pool.itnPrivateKeyId,
                        itnPublicKeyId = it.itnPublicKeyId ?: t.pool.itnPublicKeyId))
                }
                return Result(input.requestId, built.txId, eras.networkKey(t.observer), t.era, input.nodeId,
                    requireNotNull(bodyField(built.textEnvelope, 2)).toString(), (cert.deposit + stakeDeposit).toString(),
                    "0", "submitted", input.action, input.keyId, publication)
            } finally { cmd(t, listOf("rm", "-f", signed) + paths) }
        } finally { cmd(t, listOf("rm", "-f", body, protocolPath) + cert.cleanupPaths + extraPaths) }
    }
    @MessageMapping("/leios/registration/submit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("leios_registration_submitted", execute(input))
    } catch (e: Exception) {
        SocketResponse.Error("leios_registration_submitted", SocketResponse.PublicFailure(e.javaClass.simpleName,
            "Pool registration submission failed; inspect protected server diagnostics.", "leios_registration_submitted",
            "retry", input.requestId))
    }
}
