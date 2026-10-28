package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
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
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

/** Review and submit use the same persisted CLI body; the password is never part of a review. */
@Controller
class ReviewedWalletController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val protocolSnapshots: ProtocolSnapshotRepository,
    private val capabilities: CardanoCapabilities,
    private val walletUtils: WalletUtils,
    private val reviewed: ReviewedWalletOperations,
    private val operations: AuthorizedOperationRepository,
) {
    data class ReviewRequest(val requestId: String, val kind: String, val request: JsonNode)
    data class SubmitRequest(val requestId: String, val previewId: String, val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, previewId=$previewId, spendingPassword='********')"
    }
    data class Result(val requestId: String, val previewId: String, val txId: String, val networkKey: String, val era: String,
        val fee: String, val deposit: String, val refund: String, val validFrom: String?, val validUntil: String?,
        val state: String, val effects: JsonNode)
    data class ReconcileResult(val requestId: String, val previewId: String, val txId: String,
        val networkKey: String, val state: String, val reason: String)
    private data class Target(val node: Node, val host: Host, val connection: HostConnection, val era: String,
        val magic: String, val socket: String, val networkArgs: List<String>)
    private data class Observation(val parameters: String, val state: String, val evidence: ReviewedWalletOperations.Evidence)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun entry(id: Long): WalletEntry = wallets.findByIdOrNull(id)?.takeUnless { it.deleted }
        ?: throw IllegalArgumentException("Wallet account not found")
    private fun target(nodeId: Long? = null): Target {
        val node = if (nodeId == null) nodes.findDefault() else nodes.findByIdOrNull(nodeId)
        requireNotNull(node) { "Target node not found" }
        val host = requireNotNull(hosts.findByIdOrNull(node.hostId)) { "Target host not found" }
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
        val network = if (genesis.path("networkId").asText().equals("testnet", true))
            listOf("--testnet-magic", genesis.path("networkMagic").asText()) else listOf("--mainnet")
        val magic = network.joinToString(" ")
        val socket = "--socket-path ${host.nodeHomePath}/${node.name}/db/socket"
        val era = eras.getEra(host, node, magic, socket)
        return Target(node, host, HostConnection(host, node), era, magic, socket, network)
    }
    private fun cmd(t: Target, args: List<String>): String {
        require(args.all { it.matches(Regex("[A-Za-z0-9_./+:#=-]+( [A-Za-z0-9_./+:#=-]+)*")) }) { "Unsafe CLI argument" }
        return t.connection.commandArgs(args).trim()
    }
    private fun query(t: Target, vararg args: String): String = cmd(t, listOf(t.host.cardanoCliPath, t.era, "query") + args + t.networkArgs + listOf("--socket-path", "${t.host.nodeHomePath}/${t.node.name}/db/socket", "--output-json"))
    private fun ids(request: JsonNode): List<Long> = request.path("fromIds").takeIf { it.isArray && it.size() > 0 }?.map { it.asLong() }
        ?: listOf(request.path("fromId").asLong())
    private fun operation(kind: String, request: JsonNode): String = when (kind) {
        "send" -> "wallet_send"
        "withdraw" -> "wallet_reward_withdrawal"
        "stake" -> if (request.hasNonNull("poolId")) "wallet_stake_delegation" else if (request.path("isRegistration").asBoolean()) "wallet_stake_registration" else "wallet_stake_deregistration"
        else -> throw IllegalArgumentException("Unknown wallet operation")
    }
    private fun sources(t: Target, kind: String, request: JsonNode): Pair<WalletEntry, List<WalletEntry>> {
        if (kind == "stake") return entry(request.path("stakingFeesAccount").asLong()) to listOf(entry(request.path("id").asLong()))
        val from = ids(request).map(::entry)
        require(from.isNotEmpty())
        if (kind == "send") return from.first() to emptyList()
        // Prefer a funded receiving account; the choice is included in the state fingerprint.
        val candidates = (request.path("toAccounts").mapNotNull { it.path("account").asLong().takeIf { id -> id > 0 } }.distinct().map(::entry) + from)
            .distinctBy { it.id }.filter { it.paymentSkey != null }
        val payer = candidates.maxByOrNull { candidate ->
            walletUtils.getUtxos(t.host, t.connection, t.magic, t.socket, t.era, candidate.paymentAddr)
                .fold(BigInteger.ZERO) { sum, utxo -> sum + utxo.lovelace }
        } ?: throw IllegalArgumentException("No withdrawal fee payer signing key")
        return payer to from
    }
    private fun observe(t: Target, kind: String, request: JsonNode, payload: String): Observation {
        val op = operation(kind, request)
        capabilities.requireOperation(t.host, t.node, op)
        val (payer, stakes) = sources(t, kind, request)
        val params = protocolSnapshots.query(t.host, t.node, t.connection, t.era, t.magic, t.socket)
        val utxo = query(t, "utxo", "--address", payer.paymentAddr)
        val stake = stakes.map { requireNotNull(it.stakingAddr) }.map { query(t, "stake-address-info", "--address", it) }
        val identities = (listOf(payer) + stakes).map {
            listOf(it.id, it.paymentAddr, it.paymentSkey?.id, it.paymentVkey?.content,
                it.stakingAddr, it.stakingSkey?.id, it.stakingVkey?.content)
        }
        val destinations = request.path("toAccounts").mapNotNull {
            it.path("account").asLong().takeIf { id -> id > 0 }?.let { id -> entry(id).let { account -> listOf(account.id, account.paymentAddr) } }
        }
        val state = json.writeValueAsString(listOf(json.readTree(utxo), stake.map { json.readTree(it) }, identities, destinations))
        val e = eras.evidence(t.node)
        check(e.freshness == "current" && e.era == t.era && e.source == "host:${t.host.id}/node:${t.node.id}") { "Current era unavailable" }
        val capability = capabilities.capability(t.host, t.node, op)
        return Observation(params, state, ReviewedWalletOperations.Evidence(digest(payload), digest(params), digest(state),
            digest(json.writeValueAsString(listOf(e.networkKey, e.era, e.generation, t.host.id, t.node.id, capability)))))
    }
    private fun bodyField(body: String, key: Int): BigInteger? {
        val bytes = json.readTree(body).path("cborHex").asText().hexToByteArray()
        val tx = CborSpans.parse(bytes).array()
        return tx.first().entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    }
    private fun exactJson(value: JsonNode): JsonNode = when {
        value.isObject -> json.createObjectNode().also { out -> value.fields().forEachRemaining { (key, child) -> out.set<JsonNode>(key, exactJson(child)) } }
        value.isArray -> json.createArrayNode().also { out -> value.forEach { out.add(exactJson(it)) } }
        value.isNumber -> json.nodeFactory.textNode(value.asText())
        else -> value
    }
    /** Newer CLI versions no longer render pre-Conway bodies. Preserve their original
     * byte identities and render their values directly rather than selecting a newer era. */
    private fun historicalView(envelope: String): JsonNode {
        val body = CborSpans.parse(json.readTree(envelope).path("cborHex").asText().hexToByteArray()).array().first()
        fun field(key: Int): CborSpan? = body.entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second
        val outputs = requireNotNull(field(1)).array().map { output ->
            val (address, value) = if (output.major == 4) {
                val fields = output.array()
                fields[0] to fields[1]
            } else {
                val fields = output.entries().associate { it.first.integer().toInt() to it.second }
                requireNotNull(fields[0]) to requireNotNull(fields[1])
            }
            val bytes = address.payload()
            require(bytes.isNotEmpty())
            val addressText = if ((bytes[0].toInt() and 0xf0) == 0x80) bytes.toHexString()
                else Bech32.encode(if ((bytes[0].toInt() and 15) == 1) "addr" else "addr_test", bytes)
            val coin: BigInteger
            val assets = linkedMapOf<String, String>()
            if (value.major == 0) coin = value.integer() else {
                val parts = value.array()
                coin = parts[0].integer()
                parts[1].entries().forEach { (policy, names) ->
                    names.entries().forEach { (name, quantity) ->
                        assets["${policy.payload().toHexString()}.${name.payload().toHexString()}".trimEnd('.')] = quantity.integer().toString()
                    }
                }
            }
            mapOf("address" to addressText, "addressBytes" to bytes.toHexString(), "lovelace" to coin.toString(), "assets" to assets)
        }
        val inputs = requireNotNull(field(0)).let { if (it.major == 6) it.tagged(258) else it }.array().map {
            val pair = it.array()
            "${pair[0].payload().toHexString()}#${pair[1].integer()}"
        }
        val withdrawals = field(5)?.entries()?.associate { (address, amount) ->
            val bytes = address.payload()
            Bech32.encode(if ((bytes[0].toInt() and 15) == 1) "stake" else "stake_test", bytes) to amount.integer().toString()
        } ?: emptyMap()
        val certificates = field(4)?.let { if (it.major == 6) it.tagged(258) else it }?.array()?.map { it.bytes().toHexString() } ?: emptyList()
        return json.valueToTree(mapOf("inputs" to inputs, "outputs" to outputs, "withdrawals" to withdrawals,
            "certificateCborHex" to certificates))
    }
    private fun result(record: AuthorizedOperation, requestId: String): Result = Result(requestId, record.previewId, record.txId, record.networkKey, record.era,
        record.fee.toString(), record.deposit.toString(), record.refund.toString(), record.validFrom?.toString(), record.validUntil?.toString(),
        record.state, json.readTree(record.requestPayload).path("effects"))

    @MessageMapping("/walletreview")
    @SendTo("/topic/messages")
    fun reviewMessage(input: ReviewRequest): SocketResponse<Result> = try {
        SocketResponse.Success("walletreview", preview(input))
    } catch (error: Exception) {
        SocketResponse.Error("walletreview", SocketResponse.PublicFailure(error.javaClass.simpleName,
            "Review failed; inspect protected server diagnostics.", "walletreview", "review", input.requestId))
    }

    private fun preview(input: ReviewRequest): Result {
        UUID.fromString(input.requestId)
        val allowed = if (input.kind == "stake") setOf("id", "stakingFeesAccount", "isRegistration", "poolId")
            else setOf("fromId", "fromIds", "toAccounts", "isClaim", "metadata", "txFee", "tokenKeepFee")
        require(input.request.fieldNames().asSequence().all { it in allowed }) { "Unexpected review field" }
        input.request.path("toAccounts").forEach { destination ->
            require(destination.isObject && destination.fieldNames().asSequence().all {
                it in setOf("currency", "account", "address", "amount", "percent", "tokenFee")
            }) { "Unexpected destination field" }
        }
        require(input.request.isObject && !input.request.has("spendingPassword")) { "Review must not contain credentials" }
        val existing = operations.findById(input.requestId).orElse(null)
        val payload = json.writeValueAsString(mapOf("kind" to input.kind, "request" to input.request))
        if (existing != null) {
            val previous = json.readTree(existing.requestPayload)
            check(previous.path("kind").asText() == input.kind && previous.path("request") == input.request) { "Request ID already used for a different review" }
            return result(existing, input.requestId)
        }
        val t = target()
        val observation = observe(t, input.kind, input.request, payload)
        val (payer, stakes) = sources(t, input.kind, input.request)
        val utxos = walletUtils.getUtxos(t.host, t.connection, t.magic, t.socket, t.era, payer.paymentAddr)
        val liveUtxos = json.readTree(observation.state).first()
        check(utxos.all { liveUtxos.has("${it.hash}#${it.ix}") }) { "Confirmed wallet inputs differ from the target node; wait for synchronization" }
        require(utxos.isNotEmpty()) { "Fee payer has no confirmed inputs" }
        val prefix = "/tmp/jormanager-review-${UUID.randomUUID()}"
        val bodyPath = "$prefix.body"
        val paramsPath = "$prefix.params"
        val cleanup = mutableListOf(bodyPath, paramsPath)
        try {
            t.connection.commandWriteFile(paramsPath, observation.parameters)
            val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "build", "--witness-override", (1 + stakes.size).toString())
            args += t.networkArgs
            args += listOf("--socket-path", "${t.host.nodeHomePath}/${t.node.name}/db/socket")
            utxos.forEach { args += listOf("--tx-in", "${it.hash}#${it.ix}") }
            var deposit = BigInteger.ZERO
            var refund = BigInteger.ZERO
            if (input.kind == "stake") {
                val stake = stakes.single()
                val stakeInfo = walletUtils.getStakeAddressInfo(t.host, t.connection, t.magic, t.socket, t.era, requireNotNull(stake.stakingAddr))
                val delegation = input.request.hasNonNull("poolId")
                val registration = input.request.path("isRegistration").asBoolean()
                require(if (delegation) stakeInfo.isNotEmpty() else registration == stakeInfo.isEmpty()) { "Stake registration state changed" }
                val key = "$prefix.vkey"; val cert = "$prefix.cert"
                cleanup += listOf(key, cert)
                t.connection.commandWriteFile(key, requireNotNull(stake.stakingVkey).content)
                if (delegation) {
                    val poolId = input.request.path("poolId").asText()
                    require(poolId.matches(Regex("(pool1[0-9a-z]+|[0-9a-fA-F]{56})"))) { "Invalid pool ID" }
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-address", "stake-delegation-certificate", "--stake-verification-key-file", key, "--stake-pool-id", poolId, "--out-file", cert))
                } else {
                    val amount = if (registration) json.readTree(observation.parameters).path("stakeAddressDeposit").asText().toBigInteger()
                        else requireNotNull(stakeInfo.single().stakeAddressDeposit) { "Recorded stake deposit unavailable" }
                    if (registration) deposit = amount else refund = amount
                    val certificate = mutableListOf(t.host.cardanoCliPath, t.era, "stake-address", if (registration) "registration-certificate" else "deregistration-certificate")
                    if (t.era == "conway" || t.era == "dijkstra") certificate += listOf("--key-reg-deposit-amt", amount.toString())
                    certificate += listOf("--stake-verification-key-file", key, "--out-file", cert)
                    cmd(t, certificate)
                }
                args += listOf("--certificate-file", cert)
            } else {
                if (input.kind == "withdraw") stakes.forEach { stake ->
                    val info = walletUtils.getStakeAddressInfo(t.host, t.connection, t.magic, t.socket, t.era, requireNotNull(stake.stakingAddr)).single()
                    args += listOf("--withdrawal", "${stake.stakingAddr}+${info.rewardAccountBalance}")
                }
                val groups = input.request.path("toAccounts").groupBy { destination ->
                    val account = destination.path("account").asLong()
                    if (account < 0) destination.path("address").asText() else entry(account).paymentAddr
                }
                require(groups.isNotEmpty()) { "No destination" }
                groups.forEach { (address, items) ->
                    require(address.isNotBlank())
                    var ada = BigInteger.ZERO
                    val assets = mutableMapOf<String, BigInteger>()
                    items.forEach { item ->
                        val amount = item.path("amount").asText().toBigInteger()
                        require(amount.signum() > 0) { "Invalid output amount" }
                        val currency = item.path("currency").asText()
                        if (currency == "ada") ada += amount else {
                            require(currency.matches(Regex("[0-9a-fA-F]{56}(\\.[0-9a-fA-F]{0,64})?"))) { "Invalid asset" }
                            assets[currency] = (assets[currency] ?: BigInteger.ZERO) + amount
                        }
                    }
                    fun output(coin: BigInteger) = address + "+" + coin + assets.entries.joinToString("") { "+${it.value} ${it.key}" }
                    if (assets.isNotEmpty()) {
                        val minimum = cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "calculate-min-required-utxo", "--protocol-params-file", paramsPath, "--tx-out", output(ada.max(BigInteger.valueOf(1_000_000)))))
                            .split(Regex("\\s+")).first { it.all(Char::isDigit) }.toBigInteger()
                        ada = ada.max(minimum)
                    }
                    args += listOf("--tx-out", output(ada))
                }
                val metadata = input.request.path("metadata")
                if (metadata.isTextual && metadata.asText().isNotBlank()) {
                    json.readTree(metadata.asText())
                    val file = "$prefix.metadata"; cleanup += file
                    t.connection.commandWriteFile(file, metadata.asText()); args += listOf("--metadata-json-file", file)
                }
            }
            args += listOf("--change-address", payer.paymentAddr, "--out-file", bodyPath)
            cmd(t, args)
            val body = t.connection.commandReadFile(bodyPath)
            val txId = cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "txid", "--tx-body-file", bodyPath, "--output-text"))
            val fee = requireNotNull(bodyField(body, 2))
            val from = bodyField(body, 8)?.longValueExact(); val until = bodyField(body, 3)?.longValueExact()
            val view = if (t.era in setOf("shelley", "allegra", "mary", "alonzo", "babbage")) historicalView(body)
                else json.readTree(cmd(t, listOf(t.host.cardanoCliPath, "debug", "transaction", "view", "--tx-body-file", bodyPath, "--output-json")))
            val finalPayload = json.writeValueAsString(mapOf("kind" to input.kind, "request" to input.request, "effects" to exactJson(view)))
            val after = observe(t, input.kind, input.request, finalPayload)
            check(after.evidence.parameterFingerprint == observation.evidence.parameterFingerprint &&
                after.evidence.stateFingerprint == observation.evidence.stateFingerprint &&
                after.evidence.capabilityFingerprint == observation.evidence.capabilityFingerprint) { "Wallet state changed while building; review again" }
            return result(reviewed.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), t.host, t.node,
                operation(input.kind, input.request), after.evidence, finalPayload, ReviewedWalletOperations.CliBody(body, txId), fee, deposit, refund, from, until)), input.requestId)
        } finally { cmd(t, listOf("rm", "-f") + cleanup) }
    }

    @MessageMapping("/walletsubmit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("walletsubmit", submit(input))
    } catch (error: Exception) {
        SocketResponse.Error("walletsubmit", SocketResponse.PublicFailure(error.javaClass.simpleName,
            "Submission outcome may be uncertain; inspect the operation before retrying.", "walletsubmit", "reconcile", input.requestId))
    }

    @MessageMapping("/walletreconcile")
    @SendTo("/topic/messages")
    fun reconcileMessage(input: SubmitRequest): SocketResponse<ReconcileResult> = try {
        UUID.fromString(input.requestId)
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        val record = operations.findByPreviewId(input.previewId) ?: throw IllegalArgumentException("Unknown preview")
        val t = target(record.nodeId)
        val outcome = reviewed.reconcile(input.previewId, t.host, t.node) { operation, inputs ->
            fun tip(): Pair<BigInteger, String> {
                val value = json.readTree(query(t, "tip"))
                val slot = value.path("slot").asText().toBigInteger()
                val hash = value.path("hash").asText().lowercase()
                require(slot.signum() >= 0 && hash.matches(Regex("[0-9a-f]{64}")) &&
                    value.path("era").asText().equals(t.era, ignoreCase = true)) { "Invalid node tip" }
                return slot to hash
            }
            val before = tip()
            val mempool = json.readTree(cmd(t, listOf(t.host.cardanoCliPath, t.era, "query", "tx-mempool") +
                t.networkArgs + listOf("--socket-path", "${t.host.nodeHomePath}/${t.node.name}/db/socket",
                    "--output-json", "tx-exists", operation.txId)))
            require(mempool.path("exists").isBoolean && mempool.path("txId").asText().equals(operation.txId, ignoreCase = true) &&
                mempool.path("slot").asText().toBigInteger() >= before.first) { "Invalid or stale mempool response" }
            val unspent = inputs.filter { (txId, index) ->
                val ref = "$txId#$index"
                json.readTree(query(t, "utxo", "--tx-in", ref)).has(ref)
            }.toSet()
            val after = tip()
            check(before == after) { "Node point changed during reconciliation" }
            ReviewedWalletOperations.ReconciliationObservation(before.first, before.second, mempool.path("exists").asBoolean(), unspent)
        }
        SocketResponse.Success("walletreconcile", ReconcileResult(input.requestId, outcome.operation.previewId,
            outcome.operation.txId, outcome.operation.networkKey, outcome.operation.state, outcome.reason))
    } catch (error: Exception) {
        SocketResponse.Error("walletreconcile", SocketResponse.PublicFailure(error.javaClass.simpleName,
            "Reconciliation could not be completed; the submission outcome remains unresolved.", "walletreconcile", "reconcile", input.requestId))
    }

    private fun submit(input: SubmitRequest): Result {
        UUID.fromString(input.requestId)
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        val record = operations.findByPreviewId(input.previewId) ?: throw IllegalArgumentException("Unknown preview")
        val payload = json.readTree(record.requestPayload); val kind = payload.path("kind").asText(); val request = payload.path("request")
        val t = target(record.nodeId)
        var signedEnvelope = ""
        val result = reviewed.submit(input.previewId, t.host, t.node,
            evidence = { observe(t, kind, request, record.requestPayload).evidence },
            sign = { saved ->
                val (payer, stakes) = sources(t, kind, request)
                val prefix = "/tmp/jormanager-sign-${UUID.randomUUID()}"
                val body = "$prefix.body"; val signed = "$prefix.signed"; val keys = mutableListOf<String>()
                try {
                    t.connection.commandWriteFile(body, saved.unsignedBody)
                    val keyFiles = listOf(requireNotNull(payer.paymentSkey)) + stakes.map { requireNotNull(it.stakingSkey) }
                    keyFiles.forEachIndexed { i, file ->
                        val key = "$prefix.$i.skey"; keys += key
                        t.connection.commandWriteSecret(key, walletUtils.getSKeyContent(file, input.spendingPassword))
                    }
                    val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "sign", "--tx-body-file", body)
                    keys.forEach { args += listOf("--signing-key-file", it) }; args += t.networkArgs; args += listOf("--out-file", signed)
                    cmd(t, args)
                    signedEnvelope = t.connection.commandReadFile(signed)
                    json.readTree(signedEnvelope).path("cborHex").asText().also { require(it.isNotBlank()) }
                } finally { cmd(t, listOf("rm", "-f", body, signed) + keys) }
            },
            submit = { signedHex ->
                val path = "/tmp/jormanager-submit-${UUID.randomUUID()}.signed"
                try {
                    check(json.readTree(signedEnvelope).path("cborHex").asText().equals(signedHex, ignoreCase = true))
                    t.connection.commandWriteFile(path, signedEnvelope)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", path) + t.networkArgs + listOf("--socket-path", "${t.host.nodeHomePath}/${t.node.name}/db/socket"))
                } finally { cmd(t, listOf("rm", "-f", path)) }
            })
        return result(result, input.requestId)
    }
}
