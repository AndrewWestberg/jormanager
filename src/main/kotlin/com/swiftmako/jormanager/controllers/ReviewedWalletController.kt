package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

internal fun canonicalWalletInputs(snapshot: PendingWalletState.Snapshot, address: String): List<Any> =
    snapshot.utxosByAddress[address].orEmpty().sortedWith(compareBy({ it.hash }, { it.ix })).map { utxo ->
        listOf(utxo.hash, utxo.ix, utxo.lovelace.toString(),
            utxo.nativeAssets.sortedWith(compareBy({ it.policy }, { it.name }))
                .map { listOf(it.policy, it.name, it.amount.toString()) })
    }

/** Builds and submits the same persisted CLI body. */
@Controller
class ReviewedWalletController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val protocolSnapshots: ProtocolSnapshotRepository,
    private val walletUtils: WalletUtils,
    private val reviewed: ReviewedWalletOperations,
    private val builder: WalletTransactionBuilder,
    private val operations: AuthorizedOperationRepository,
) {
    data class SubmitRequest(val requestId: String, val kind: String, val request: JsonNode,
        @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, kind=$kind, spendingPassword='********')"
    }
    data class Result(val requestId: String, val txId: String, val networkKey: String, val era: String,
        val fee: String, val deposit: String, val refund: String, val state: String)
    private data class Target(val node: Node, val host: Host, val connection: HostConnection, val era: String,
        val magic: String, val socket: String, val networkArgs: List<String>)
    private data class Sources(val payer: WalletEntry, val stakes: List<WalletEntry>, val snapshot: PendingWalletState.Snapshot)
    private data class Observation(val parameters: String, val state: String, val evidence: ReviewedWalletOperations.Evidence,
        val sources: Sources)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun entry(id: Long): WalletEntry = wallets.findByIdOrNull(id)?.takeUnless { it.deleted }
        ?: throw IllegalArgumentException("Wallet account not found")
    private fun target(nodeId: Long? = null): Target {
        val node = if (nodeId == null) nodes.findDefault() else nodes.findByIdOrNull(nodeId)
        requireNotNull(node) { "Target node not found" }
        val host = requireNotNull(hosts.findByIdOrNull(node.hostId)) { "Target host not found" }
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
        val network = if (genesis.path("networkId").asString().equals("testnet", true))
            listOf("--testnet-magic", genesis.path("networkMagic").asString()) else listOf("--mainnet")
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
    private fun ids(request: JsonNode): List<Long> = request.path("fromIds").takeIf { it.isArray && it.size() > 0 }?.values()?.map { it.asLong() }
        ?: listOf(request.path("fromId").asLong())
    private fun operation(kind: String, request: JsonNode): String = when (kind) {
        "send" -> "wallet_send"
        "withdraw" -> "wallet_reward_withdrawal"
        "stake" -> if (request.hasNonNull("poolId")) "wallet_stake_delegation" else if (request.path("isRegistration").asBoolean()) "wallet_stake_registration" else "wallet_stake_deregistration"
        else -> throw IllegalArgumentException("Unknown wallet operation")
    }
    private fun sources(t: Target, kind: String, request: JsonNode, excludingOperationId: String? = null): Sources {
        val from = if (kind == "stake") listOf(entry(request.path("id").asLong())) else ids(request).map(::entry)
        require(from.isNotEmpty())
        val candidates = when (kind) {
            "stake" -> listOf(entry(request.path("stakingFeesAccount").asLong()))
            "send" -> listOf(from.first())
            else -> request.path("toAccounts").mapNotNull { it.path("account").asLong().takeIf { id -> id > 0 } }
                .distinct().map(::entry).filter { it.paymentSkey != null }
        }
        require(candidates.isNotEmpty()) { "No withdrawal fee payer signing key" }
        val snapshot = walletUtils.getUtxoSnapshot(t.host, t.node, t.connection, t.magic, t.socket, t.era,
            candidates.map { it.paymentAddr }.toSet(), excludingOperationId)
        val payer = if (kind == "withdraw") candidates.firstOrNull { candidate ->
            snapshot.utxosByAddress[candidate.paymentAddr].orEmpty().fold(BigInteger.ZERO) { sum, utxo -> sum + utxo.lovelace } >= BigInteger("1000000")
        } ?: throw IllegalArgumentException("No Account found capable of covering the claim fee!") else candidates.first()
        return Sources(payer, if (kind == "send") emptyList() else from, snapshot)
    }
    private fun observe(t: Target, kind: String, request: JsonNode, payload: String, excludingOperationId: String? = null): Observation {
        val sources = sources(t, kind, request, excludingOperationId)
        val (payer, stakes) = sources
        val params = protocolSnapshots.query(t.host, t.node, t.connection, t.era, t.magic, t.socket)
        val stake = stakes.map { requireNotNull(it.stakingAddr) }.map { query(t, "stake-address-info", "--address", it) }
        val identities = (listOf(payer) + stakes).map {
            listOf(it.id, it.paymentAddr, it.paymentSkey?.id, it.paymentSkey?.content?.let(::digest), it.paymentVkey?.id, it.paymentVkey?.content,
                it.stakingAddr, it.stakingSkey?.id, it.stakingSkey?.content?.let(::digest), it.stakingVkey?.id, it.stakingVkey?.content)
        }
        val destinations = request.path("toAccounts").mapNotNull {
            it.path("account").asLong().takeIf { id -> id > 0 }?.let { id -> entry(id).let { account -> listOf(account.id, account.paymentAddr) } }
        }
        val state = json.writeValueAsString(listOf(canonicalWalletInputs(sources.snapshot, payer.paymentAddr),
            sources.snapshot.readState.generation, stake.map { json.readTree(it) },
            stakes.map { sources.snapshot.reservedRewardWithdrawals[requireNotNull(it.stakingAddr)] ?: BigInteger.ZERO }, identities, destinations))
        val e = eras.evidence(t.node)
        check(e.freshness == "current" && e.era == t.era && e.source == "host:${t.host.id}/node:${t.node.id}") { "Current era unavailable" }
        return Observation(params, state, ReviewedWalletOperations.Evidence(digest(payload), digest(params), digest(state),
            digest(json.writeValueAsString(listOf(e.networkKey, e.era, e.generation, t.host.id, t.node.id))),
            sources.snapshot.readState.generation), sources)
    }
    private fun bodyField(body: String, key: Int): BigInteger? {
        val bytes = json.readTree(body).path("cborHex").asString().hexToByteArray()
        val tx = CborSpans.parse(bytes).array()
        return tx.first().entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    }
    private fun result(record: AuthorizedOperation, requestId: String): Result = Result(requestId, record.txId, record.networkKey, record.era,
        record.fee.toString(), record.deposit.toString(), record.refund.toString(), record.state)

    private class FeeChanged : IllegalArgumentException()

    private fun preview(input: SubmitRequest): Result {
        UUID.fromString(input.requestId)
        val allowed = if (input.kind == "stake") setOf("id", "stakingFeesAccount", "isRegistration", "poolId")
            else setOf("fromId", "fromIds", "toAccounts", "isClaim", "metadata", "txFee", "tokenKeepFee")
        require(input.request.propertyNames().all { it in allowed }) { "Unexpected review field" }
        input.request.path("toAccounts").forEach { destination ->
            require(destination.isObject && destination.propertyNames().all {
                it in setOf("currency", "account", "address", "amount", "percent", "tokenFee")
            }) { "Unexpected destination field" }
        }
        require(input.request.isObject) { "Invalid request" }
        operation(input.kind, input.request)
        if (input.kind != "stake") {
            val fee = input.request.path("txFee")
            require(fee.isString && fee.asString().matches(Regex("[0-9]+"))) { "Invalid displayed fee" }
        }
        val intent = json.valueToTree<JsonNode>(mapOf("kind" to input.kind, "request" to input.request))
        val existing = operations.findById(input.requestId).orElse(null)
        val payload = json.writeValueAsString(mapOf("intent" to intent, "kind" to input.kind, "request" to input.request))
        if (existing != null) {
            check(json.readTree(existing.requestPayload).path("intent") == intent) { "Request ID already used for a different action" }
            return result(existing, input.requestId)
        }
        val t = target()
        val observation = observe(t, input.kind, input.request, payload)
        val (payer, stakes, snapshot) = observation.sources
        val utxos = snapshot.utxosByAddress[payer.paymentAddr].orEmpty()
        require(utxos.isNotEmpty()) { "Fee payer has no available inputs" }
        val prefix = "/tmp/jormanager-review-${UUID.randomUUID()}"
        val bodyPath = "$prefix.body"
        val paramsPath = "$prefix.params"
        val cleanup = mutableListOf(bodyPath, paramsPath)
        try {
            t.connection.commandWriteFile(paramsPath, observation.parameters)
            val args = mutableListOf<String>()
            val outputs = mutableListOf<WalletTransactionBuilder.Output>()
            var withdrawals = BigInteger.ZERO
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
                    val poolId = input.request.path("poolId").asString()
                    require(poolId.matches(Regex("(pool1[0-9a-z]+|[0-9a-fA-F]{56})"))) { "Invalid pool ID" }
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "stake-address", "stake-delegation-certificate", "--stake-verification-key-file", key, "--stake-pool-id", poolId, "--out-file", cert))
                } else {
                    val amount = if (registration) json.readTree(observation.parameters).path("stakeAddressDeposit").asString().toBigInteger()
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
                    val available = (info.rewardAccountBalance -
                        (snapshot.reservedRewardWithdrawals[requireNotNull(stake.stakingAddr)] ?: BigInteger.ZERO)).max(BigInteger.ZERO)
                    withdrawals += available
                    args += listOf("--withdrawal", "${stake.stakingAddr}+$available")
                }
                val groups = input.request.path("toAccounts").groupBy { destination ->
                    val account = destination.path("account").asLong()
                    if (account < 0) destination.path("address").asString() else entry(account).paymentAddr
                }
                require(groups.isNotEmpty()) { "No destination" }
                groups.forEach { (address, items) ->
                    require(address.isNotBlank())
                    var ada = BigInteger.ZERO
                    val assets = mutableMapOf<String, BigInteger>()
                    items.forEach { item ->
                        val amount = item.path("amount").asString().toBigInteger()
                        require(amount.signum() > 0) { "Invalid output amount" }
                        val currency = item.path("currency").asString()
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
                    outputs += WalletTransactionBuilder.Output(address, ada, assets.map { (unit, amount) ->
                        val parts = unit.split(".", limit = 2)
                        NativeAsset(parts.getOrElse(1) { "" }, parts[0], amount)
                    })
                }
                val metadata = input.request.path("metadata")
                if (metadata.isString && metadata.asString().isNotBlank()) {
                    json.readTree(metadata.asString())
                    val file = "$prefix.metadata"; cleanup += file
                    t.connection.commandWriteFile(file, metadata.asString()); args += listOf("--metadata-json-file", file)
                }
            }
            val tip = json.readTree(query(t, "tip"))
            val slot = tip.path("slot").asString().toLong()
            require(slot >= 0 && tip.path("era").asString().equals(t.era, true) &&
                tip.path("hash").asString().matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid node tip" }
            val referenceScriptSize = walletUtils.queryReferenceScriptSize(t.host, t.connection, t.magic, t.socket, t.era, utxos, snapshot)
            val built = builder.build(t.host, t.connection, t.era, utxos, outputs, payer.paymentAddr,
                withdrawals = withdrawals, deposits = deposit, refunds = refund, extraArgs = args,
                validUntil = Math.addExact(slot, 21600L), protocolParameters = observation.parameters, protocolPath = paramsPath,
                witnessCount = 1 + stakes.size, bodyPath = bodyPath, referenceScriptSize = referenceScriptSize)
            val body = built.textEnvelope
            val fee = requireNotNull(bodyField(body, 2))
            val from = bodyField(body, 8)?.longValueExact(); val until = bodyField(body, 3)?.longValueExact()
            if (input.kind != "stake" && fee != input.request.path("txFee").asString().toBigInteger()) throw FeeChanged()
            val finalPayload = payload
            val after = observe(t, input.kind, input.request, finalPayload)
            check(after.evidence.parameterFingerprint == observation.evidence.parameterFingerprint &&
                after.evidence.stateFingerprint == observation.evidence.stateFingerprint &&
                after.evidence.targetFingerprint == observation.evidence.targetFingerprint) { "Wallet state changed while building" }
            return result(reviewed.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), t.host, t.node,
                operation(input.kind, input.request), after.evidence, finalPayload, built, fee, deposit, refund, from, until)), input.requestId)
        } finally { cmd(t, listOf("rm", "-f") + cleanup) }
    }

    @MessageMapping("/walletsubmit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("walletsubmit", execute(input))
    } catch (error: Exception) {
        val state = operations.findById(input.requestId).orElse(null)?.state
        val uncertain = state in setOf("signing", "submission_uncertain", "submitted", "rolled_back")
        val feeChanged = error is FeeChanged
        SocketResponse.Error("walletsubmit", SocketResponse.PublicFailure(if (feeChanged) "FEE_CHANGED" else "OPERATION_UNAVAILABLE",
            if (feeChanged) "Fee changed; refresh the form and submit again." else if (uncertain) "Submission outcome may be uncertain." else "Wallet action could not be completed.",
            "walletsubmit", if (uncertain) "wait" else "retry", input.requestId))
    }

    @Synchronized
    fun execute(input: SubmitRequest): Result {
        UUID.fromString(input.requestId)
        require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password" }
        preview(input)
        val record = operations.findById(input.requestId).orElseThrow()
        if (record.state != "previewed") return result(record, input.requestId)
        val payload = json.readTree(record.requestPayload); val kind = payload.path("kind").asString(); val request = payload.path("request")
        val t = target(record.nodeId)
        var signedEnvelope = ""
        val result = reviewed.submit(record.previewId, t.host, t.node,
            evidence = { operation -> observe(t, kind, request, record.requestPayload, operation.id).evidence },
            sign = { saved ->
                val (payer, stakes) = sources(t, kind, request, saved.id)
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
                    json.readTree(signedEnvelope).path("cborHex").asString().also { require(it.isNotBlank()) }
                } finally { cmd(t, listOf("rm", "-f", body, signed) + keys) }
            },
            submit = { signedHex ->
                val path = "/tmp/jormanager-submit-${UUID.randomUUID()}.signed"
                try {
                    check(json.readTree(signedEnvelope).path("cborHex").asString().equals(signedHex, ignoreCase = true))
                    t.connection.commandWriteFile(path, signedEnvelope)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", path) + t.networkArgs + listOf("--socket-path", "${t.host.nodeHomePath}/${t.node.name}/db/socket"))
                } finally { cmd(t, listOf("rm", "-f", path)) }
            })
        return result(result, input.requestId)
    }
}
