package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

/** Pool retirement is reviewed separately from the later epoch-boundary refund. */
@Controller
class ReviewedPoolRetirementController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val walletUtils: WalletUtils,
    private val reviewed: ReviewedWalletOperations,
    private val operations: AuthorizedOperationRepository,
    private val protocolSnapshots: ProtocolSnapshotRepository,
    private val jdbc: JdbcTemplate,
) {
    data class ReviewRequest(val requestId: String, val nodeId: Long, val feePayerAccountId: Long, val retireEpoch: String)
    data class SubmitRequest(val requestId: String, val previewId: String, val spendingPassword: String, val approved: Boolean) {
        override fun toString() = "SubmitRequest(previewId=$previewId, spendingPassword='********', approved=$approved)"
    }
    data class Result(val requestId: String, val previewId: String, val txId: String, val networkKey: String, val era: String,
        val nodeId: Long, val observerNodeId: Long, val hostId: Long, val feePayerAccountId: Long,
        val fee: String, val deposit: String, val refund: String, val validFrom: String?, val validUntil: String?,
        val state: String, val poolState: JsonNode, val retireEpoch: String,
        val parameterFingerprint: String, val stateFingerprint: String)
    private data class Target(val observer: Node, val pool: Node, val host: Host, val connection: HostConnection,
        val era: String, val network: List<String>, val magic: String, val socket: String)
    private data class Observation(val parameters: String, val utxo: JsonNode, val poolState: JsonNode,
        val evidence: ReviewedWalletOperations.Evidence)

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun exact(value: JsonNode): JsonNode = when {
        value.isObject -> json.createObjectNode().also { out -> value.fields().forEachRemaining { (k, v) -> out.set<JsonNode>(k, exact(v)) } }
        value.isArray -> json.createArrayNode().also { out -> value.forEach { out.add(exact(it)) } }
        value.isNumber -> json.nodeFactory.textNode(value.asText())
        else -> value
    }
    private fun target(poolId: Long, observerId: Long? = null): Target {
        val observer = requireNotNull(if (observerId == null) nodes.findDefault() else nodes.findByIdOrNull(observerId)) { "Observer not found" }
        val pool = requireNotNull(nodes.findByIdOrNull(poolId)) { "Pool not found" }
        require(!pool.isDeleted && !pool.poolId.isNullOrBlank()) { "Target is not a managed pool" }
        require(eras.networkKey(pool) == eras.networkKey(observer)) { "Pool and observer are on different networks" }
        val host = requireNotNull(hosts.findByIdOrNull(observer.hostId)) { "Observer host not found" }
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(observer.genesisShelleyFileId)).content)
        val network = when (genesis.path("networkId").asText().lowercase()) {
            "testnet" -> {
                val magic = genesis.path("networkMagic").asText()
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
    private fun cmd(t: Target, args: List<String>): String {
        require(args.all { it.matches(Regex("[A-Za-z0-9_./+:#=-]+( [A-Za-z0-9_./+:#=-]+)*")) }) { "Unsafe CLI argument" }
        return t.connection.command(if (t.host.isRemote) args.map { if (' ' in it) "'$it'" else it } else args).trim()
    }
    private fun query(t: Target, vararg args: String): String = cmd(t,
        listOf(t.host.cardanoCliPath, t.era, "query") + args + t.network +
            listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket", "--output-json"))
    private fun observe(t: Target, payerId: Long, epoch: Long, payload: String): Observation {
        capabilities.requireOperation(t.host, t.observer, "pool.retire")
        val payer = requireNotNull(wallets.findByIdOrNull(payerId)) { "Fee payer not found" }
        require(!payer.deleted && payer.paymentSkey != null) { "Fee payer cannot sign" }
        val parameters = protocolSnapshots.query(t.host, t.observer, t.connection, t.era, t.magic, t.socket)
        val tip = json.readTree(query(t, "tip"))
        val current = tip.path("epoch").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
            ?: throw IllegalStateException("Current epoch unavailable")
        val maximum = json.readTree(parameters).path("poolRetireMaxEpoch").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
            ?: throw IllegalStateException("Retirement limit unavailable")
        require(epoch > current && epoch - current <= maximum) { "Retirement epoch outside current permitted interval" }
        val poolState = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(query(t, "pool-state", "--stake-pool-id", requireNotNull(t.pool.poolId)))
        require(poolState.isObject && poolState.size() == 1) { "Registered pool state unavailable" }
        val expectedPool = requireNotNull(t.pool.poolId)
        val poolHash = if (expectedPool.startsWith("pool1")) Bech32.decode(expectedPool).let {
            require(it.prefix == "pool" && it.bytes.size == 28)
            it.bytes.toHexString()
        } else expectedPool.lowercase().also { require(it.matches(Regex("[0-9a-f]{56}"))) }
        val entry = poolState.fields().next()
        require(entry.key.equals(poolHash, true) || entry.key.equals(expectedPool, true)) { "Pool query returned a different target" }
        require(entry.value.isObject && entry.value.path("deposit").isIntegralNumber) {
            "Pool registration or deposit unavailable"
        }
        val utxo = json.readTree(query(t, "utxo", "--address", payer.paymentAddr))
        val state = json.writeValueAsString(listOf(poolState, utxo, current))
        val e = eras.evidence(t.observer)
        check(e.freshness == "current" && e.era == t.era && e.source == "host:${t.host.id}/node:${t.observer.id}") { "Current era unavailable" }
        return Observation(parameters, utxo, poolState, ReviewedWalletOperations.Evidence(digest(payload), digest(parameters), digest(state),
            digest("${e.networkKey}|${e.era}|${e.generation}|pool.retire|${t.host.id}|${t.observer.id}")))
    }
    private fun bodyField(body: String, key: Int): BigInteger? = CborSpans.parse(json.readTree(body).path("cborHex").asText().hexToByteArray())
        .array().first().entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    private fun result(record: AuthorizedOperation, requestId: String): Result {
        val payload = json.readTree(record.requestPayload)
        return Result(requestId, record.previewId, record.txId, record.networkKey, record.era, payload.path("nodeId").asLong(),
            record.nodeId, record.hostId, payload.path("feePayerAccountId").asLong(), record.fee.toString(), record.deposit.toString(),
            record.refund.toString(), record.validFrom?.toString(), record.validUntil?.toString(), record.state,
            payload.path("poolState"), payload.path("retireEpoch").asText(), record.parameterFingerprint, record.stateFingerprint)
    }
    private fun ensureNoPending(t: Target, requestId: String) {
        val pending = jdbc.query("SELECT id,request_payload FROM authorized_operations WHERE network_key=? AND operation IN ('pool.register','pool.update','pool.retire') AND state IN ('signing','submission_uncertain','submitted','rolled_back')",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, eras.networkKey(t.observer))
        require(pending.none { (id, raw) ->
            val p = json.readTree(raw)
            id != requestId && (p.path("nodeId").asLong(-1) == t.pool.id ||
                p.path("poolId").asText().isNotEmpty() && p.path("poolId").asText().equals(t.pool.poolId, true))
        }) { "A pool operation needs reconciliation before another authorization" }
    }


    @MessageMapping("/pool/retirement/review")
    @SendTo("/topic/messages")
    fun reviewMessage(input: ReviewRequest): SocketResponse<Result> = try {
        SocketResponse.Success("pool_retirement_review", preview(input))
    } catch (e: Exception) {
        SocketResponse.Error("pool_retirement_review", SocketResponse.PublicFailure(
            e.javaClass.simpleName, "Retirement review failed; inspect the protected server diagnostics.",
            "pool_retirement_review", "review", input.requestId))
    }

    private fun preview(input: ReviewRequest): Result {
        UUID.fromString(input.requestId)
        require(input.retireEpoch.matches(Regex("0|[1-9][0-9]*"))) { "Invalid retirement epoch" }
        val epoch = input.retireEpoch.toLong()
        val existing = operations.findById(input.requestId).orElse(null)
        if (existing != null) {
            val old = json.readTree(existing.requestPayload)
            check(old.path("kind").asText() == "pool.retire" && old.path("nodeId").asLong() == input.nodeId &&
                old.path("feePayerAccountId").asLong() == input.feePayerAccountId && old.path("retireEpoch").asText() == input.retireEpoch) { "Request ID already used" }
            return result(existing, input.requestId)
        }
        val t = target(input.nodeId)
        ensureNoPending(t, input.requestId)
        val initial = observe(t, input.feePayerAccountId, epoch, "pending")
        val payer = requireNotNull(wallets.findByIdOrNull(input.feePayerAccountId))
        val utxos = walletUtils.getUtxos(t.host, t.connection, t.magic, t.socket, t.era, payer.paymentAddr)
        require(utxos.isNotEmpty() && utxos.all { initial.utxo.has("${it.hash}#${it.ix}") }) { "Confirmed fee payer inputs unavailable" }
        val prefix = "/tmp/jormanager-pool-review-${UUID.randomUUID()}"
        val key = "$prefix.vkey"; val cert = "$prefix.cert"; val body = "$prefix.body"
        try {
            t.connection.commandWriteFile(key, requireNotNull(files.findByIdOrNull(t.pool.coreVKeyId)).content)
            cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-pool", "deregistration-certificate", "--cold-verification-key-file", key,
                "--epoch", input.retireEpoch, "--out-file", cert))
            val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "build", "--witness-override", "2")
            args += t.network; args += listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket")
            utxos.forEach { args += listOf("--tx-in", "${it.hash}#${it.ix}") }
            args += listOf("--certificate-file", cert, "--change-address", payer.paymentAddr, "--out-file", body)
            cmd(t, args)
            val envelope = t.connection.commandReadFile(body)
            val txid = cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "txid", "--tx-body-file", body, "--output-text"))
            val payload = json.writeValueAsString(mapOf("kind" to "pool.retire", "nodeId" to input.nodeId, "poolId" to t.pool.poolId,
                "feePayerAccountId" to input.feePayerAccountId, "retireEpoch" to input.retireEpoch, "poolState" to exact(initial.poolState)))
            val after = observe(t, input.feePayerAccountId, epoch, payload)
            check(initial.evidence.parameterFingerprint == after.evidence.parameterFingerprint && initial.evidence.stateFingerprint == after.evidence.stateFingerprint &&
                initial.evidence.capabilityFingerprint == after.evidence.capabilityFingerprint) { "Pool state changed; review again" }
            ensureNoPending(t, input.requestId)
            return result(reviewed.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), t.host, t.observer,
                "pool.retire", after.evidence, payload, ReviewedWalletOperations.CliBody(envelope, txid), requireNotNull(bodyField(envelope, 2)),
                BigInteger.ZERO, BigInteger.ZERO, bodyField(envelope, 8)?.longValueExact(), bodyField(envelope, 3)?.longValueExact())), input.requestId)
        } finally { cmd(t, listOf("rm", "-f", key, cert, body)) }
    }

    @MessageMapping("/pool/retirement/submit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("pool_retirement_submit", submit(input))
    } catch (e: Exception) {
        SocketResponse.Error("pool_retirement_submit", SocketResponse.PublicFailure(
            e.javaClass.simpleName, "Retirement submission failed; inspect the protected server diagnostics.",
            "pool_retirement_submit", "reconcile", input.requestId))
    }

    private fun submit(input: SubmitRequest): Result {
        require(input.approved) { "Explicit approval required" }
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        val record = operations.findByPreviewId(input.previewId) ?: throw IllegalArgumentException("Unknown preview")
        val payload = json.readTree(record.requestPayload)
        require(payload.path("kind").asText() == "pool.retire") { "Not a pool retirement review" }
        val payerId = payload.path("feePayerAccountId").asLong()
        val epoch = payload.path("retireEpoch").asText().toLong()
        val t = target(payload.path("nodeId").asLong(), record.nodeId)
        ensureNoPending(t, record.id)
        var envelope = ""
        val saved = reviewed.submit(input.previewId, t.host, t.observer,
            evidence = { observe(t, payerId, epoch, record.requestPayload).evidence },
            sign = { operation ->
                val payer = requireNotNull(wallets.findByIdOrNull(payerId))
                val keys = listOf(requireNotNull(payer.paymentSkey), requireNotNull(files.findByIdOrNull(t.pool.coreSKeyId)))
                val prefix = "/tmp/jormanager-pool-sign-${UUID.randomUUID()}"
                val body = "$prefix.body"; val signed = "$prefix.signed"; val paths = mutableListOf<String>()
                try {
                    t.connection.commandWriteFile(body, operation.unsignedBody)
                    keys.forEachIndexed { index, file ->
                        val path = "$prefix.$index.skey"; paths += path
                        t.connection.commandWriteSecret(path, walletUtils.getSKeyContent(file, input.spendingPassword))
                    }
                    val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "sign", "--tx-body-file", body)
                    paths.forEach { args += listOf("--signing-key-file", it) }; args += t.network; args += listOf("--out-file", signed)
                    cmd(t, args)
                    envelope = t.connection.commandReadFile(signed)
                    json.readTree(envelope).path("cborHex").asText().also { require(it.isNotBlank()) }
                } finally { cmd(t, listOf("rm", "-f", body, signed) + paths) }
            },
            submit = { signedHex ->
                val path = "/tmp/jormanager-pool-submit-${UUID.randomUUID()}.signed"
                try {
                    check(json.readTree(envelope).path("cborHex").asText().equals(signedHex, ignoreCase = true))
                    t.connection.commandWriteFile(path, envelope)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", path) + t.network +
                        listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket"))
                } finally { cmd(t, listOf("rm", "-f", path)) }
            })
        return result(saved, input.requestId)
    }
}
