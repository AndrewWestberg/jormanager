package com.swiftmako.jormanager.controllers

import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.Metadata
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.services.PoolRegistrationCertificateBuilder
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import com.swiftmako.jormanager.services.WalletTransactionBuilder
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

@Controller
class ReviewedPoolRegistrationController(
    private val json: ObjectMapper,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val eras: CardanoRepository,
    private val snapshots: ProtocolSnapshotRepository,
    private val walletUtils: WalletUtils,
    private val certificates: PoolRegistrationCertificateBuilder,
    private val reviewed: ReviewedWalletOperations,
    private val builder: WalletTransactionBuilder,
    private val operations: AuthorizedOperationRepository,
    private val jdbc: JdbcTemplate,
    private val nodeController: NodeController,
) {
    data class SubmitRequest(val requestId: String, val nodeId: Long, val feePayerAccountId: Long,
        val keyId: Long? = null, val action: String, val changes: JsonNode? = null,
        val metadata: Metadata? = null, val spendingPassword: String) {
        override fun toString() = "SubmitRequest(requestId=$requestId, nodeId=$nodeId, action=$action, spendingPassword='********')"
    }
    data class Result(val requestId: String, val txId: String, val networkKey: String, val era: String,
        val nodeId: Long, val fee: String, val deposit: String, val refund: String, val state: String,
        val action: String, val keyId: Long?)
    private data class Target(val observer: Node, val pool: Node, val host: Host, val connection: HostConnection,
        val era: String, val network: List<String>, val magic: String, val socket: String)
    private data class Observation(val parameters: String, val snapshot: PendingWalletState.Snapshot, val poolState: JsonNode,
        val stakeStates: Map<Long, JsonNode>, val evidence: ReviewedWalletOperations.Evidence)

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun exact(value: JsonNode): JsonNode = when {
        value.isObject -> json.createObjectNode().also { out -> value.properties().forEach { (k, v) -> out.set(k, exact(v)) } }
        value.isArray -> json.createArrayNode().also { out -> value.forEach { out.add(exact(it)) } }
        value.isNumber -> json.nodeFactory.stringNode(value.asString())
        else -> value
    }
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
    private fun operation(action: String) = if (action == "register") "pool.register" else "pool.update"
    private fun observe(t: Target, payerId: Long, action: String, payload: String, stakeIds: List<Long> = emptyList(),
        excludingOperationId: String? = null): Observation {
        val payer = requireNotNull(wallets.findByIdOrNull(payerId)) { "Fee payer not found" }
        require(!payer.deleted && payer.paymentSkey != null) { "Fee payer cannot sign" }
        val parameters = snapshots.query(t.host, t.observer, t.connection, t.era, t.magic, t.socket)
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
        val snapshot = walletUtils.getUtxoSnapshot(t.host, t.observer, t.connection, t.magic, t.socket, t.era,
            setOf(payer.paymentAddr), excludingOperationId)
        val draft = if (t.pool.isDraft) jdbc.query("SELECT request_json FROM pool_draft_config WHERE node_id=?", { rs, _ -> rs.getString(1) }, t.pool.id).singleOrNull()
            ?: throw IllegalStateException("Draft configuration unavailable") else ""
        val keyFiles = listOf(t.pool.coreVKeyId, t.pool.vrfVKeyId).map { id -> listOf(id, requireNotNull(files.findByIdOrNull(id)).content) }
        val coldSigner = listOf(t.pool.coreSKeyId, digest(requireNotNull(files.findByIdOrNull(t.pool.coreSKeyId)).content))
        val owners = listOf(t.pool.ownerStakingAccountId, t.pool.rewardsStakingAccountId).map { id ->
            val account = requireNotNull(wallets.findByIdOrNull(id)); requireNotNull(account.stakingVkey).content
        }
        val stakeStates = stakeIds.distinct().associateWith { id ->
            val account = requireNotNull(wallets.findByIdOrNull(id)) { "Stake account not found" }
            require(!account.deleted)
            val address = requireNotNull(account.stakingAddr) { "Stake address unavailable" }
            json.readTree(query(t, "stake-address-info", "--address", address)).also { require(it.isArray && it.size() <= 1) }
        }
        val stakeKeys = stakeIds.distinct().sorted().map { id ->
            val account = requireNotNull(wallets.findByIdOrNull(id))
            listOf(id, account.stakingAddr, account.stakingVkey?.id, account.stakingVkey?.content,
                account.stakingSkey?.id, account.stakingSkey?.content?.let(::digest))
        }
        val state = json.writeValueAsString(listOf(poolState, canonicalWalletInputs(snapshot, payer.paymentAddr),
            snapshot.readState.generation, payer.paymentAddr, payer.paymentSkey?.id, payer.paymentSkey?.content?.let(::digest),
            payer.paymentVkey?.id, payer.paymentVkey?.content, draft, keyFiles, coldSigner, owners, stakeStates, stakeKeys, currentEpoch,
            t.pool.poolId, t.pool.poolPledge?.toString(), t.pool.poolCost?.toString(), t.pool.poolMargin))
        val e = eras.evidence(t.observer)
        check(e.freshness == "current" && e.era == t.era && e.source == "host:${t.host.id}/node:${t.observer.id}") { "Current era unavailable" }
        return Observation(parameters, snapshot, poolState, stakeStates, ReviewedWalletOperations.Evidence(digest(payload), digest(parameters), digest(state),
            digest(json.writeValueAsString(listOf(e.networkKey, e.era, e.generation, t.host.id, t.observer.id))), snapshot.readState.generation))
    }
    private fun bodyField(body: String, key: Int): BigInteger? = CborSpans.parse(json.readTree(body).path("cborHex").asString().hexToByteArray())
        .array().first().entries().firstOrNull { it.first.integer() == BigInteger.valueOf(key.toLong()) }?.second?.integer()
    private fun result(record: AuthorizedOperation, requestId: String): Result {
        val p = json.readTree(record.requestPayload)
        return Result(requestId, record.txId, record.networkKey, record.era, p.path("nodeId").asLong(),
            record.fee.toString(), record.deposit.toString(), record.refund.toString(), record.state,
            p.path("action").asString(), p.path("keyId").takeIf { it.isIntegralNumber }?.asLong())
    }
    private fun ensureNoPending(t: Target, requestId: String) {
        val pending = jdbc.query("SELECT id,request_payload FROM authorized_operations WHERE network_key=? AND operation IN ('pool.register','pool.update','pool.retire') AND state IN ('signing','submission_uncertain','submitted','rolled_back')",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, eras.networkKey(t.observer))
        require(pending.none { (id, payload) ->
            val p = json.readTree(payload)
            id != requestId && (p.path("nodeId").asLong(-1) == t.pool.id ||
                p.path("poolId").asString().isNotEmpty() && p.path("poolId").asString().equals(t.pool.poolId, true))
        }) {
            "Another pool operation is pending"
        }
    }
    private fun intent(input: SubmitRequest): JsonNode {
        val metadata = input.metadata
        val itn = metadata?.extended?.itn
        val sanitized = metadata?.copy(extended = metadata.extended?.copy(itn = itn?.copy(privateKey = null)))
        return json.readTree(json.writeValueAsString(mapOf("nodeId" to input.nodeId,
            "feePayerAccountId" to input.feePayerAccountId, "keyId" to input.keyId,
            "action" to input.action, "changes" to input.changes, "metadata" to sanitized,
            "itnPrivateKeyFingerprint" to itn?.privateKey?.let(::digest))))
    }

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
        val existing = operations.findById(input.requestId).orElse(null)
        if (existing != null) {
            check(existing.operation == operation(input.action) && json.readTree(existing.requestPayload).path("intent") == intent(input)) {
                "Request ID already used"
            }
            if (existing.state != "previewed") {
                persistPublication(existing)
                return result(existing, input.requestId)
            }
            val saved = submit(input, existing)
            persistPublication(saved)
            return result(saved, input.requestId)
        }
        val prepared = preview(input)
        val record = requireNotNull(operations.findById(prepared.requestId).orElse(null))
        check(record.operation == operation(input.action) && json.readTree(record.requestPayload).path("intent") == intent(input)) { "Request ID already used" }
        val saved = if (record.state == "previewed") submit(input, record) else record
        persistPublication(saved)
        return result(saved, input.requestId)
    }

    private fun persistPublication(record: AuthorizedOperation) {
        if (record.state !in setOf("submitted", "confirmed")) return
        val p = json.readTree(record.requestPayload)
        val publication = p.path("publication")
        if (!publication.isObject) return
        val node = requireNotNull(nodes.findByIdOrNull(p.path("nodeId").asLong()))
        nodes.save(node.copy(metadataUrl = publication.path("url").asString(),
            extendedMetadataUrl = publication.path("extendedUrl").asString(),
            itnPrivateKeyId = publication.path("itnPrivateKeyId").takeIf { it.isIntegralNumber }?.asLong() ?: node.itnPrivateKeyId,
            itnPublicKeyId = publication.path("itnPublicKeyId").takeIf { it.isIntegralNumber }?.asLong() ?: node.itnPublicKeyId))
    }

    private fun preview(input: SubmitRequest): Result {
        val t = target(input.nodeId)
        ensureNoPending(t, input.requestId)
        if (t.era.equals("dijkstra", true) && input.action in setOf("register", "rotate")) require(input.keyId != null) { "Voting key required" }
        if (!t.era.equals("dijkstra", true)) require(input.keyId == null) { "Voting key is not valid for this era" }
        val initial = observe(t, input.feePayerAccountId, input.action, "pending")
        val payer = requireNotNull(wallets.findByIdOrNull(input.feePayerAccountId))
        require(initial.snapshot.utxosByAddress[payer.paymentAddr].orEmpty().isNotEmpty()) { "Available fee payer inputs unavailable" }
        val publication = input.metadata?.let { nodeController.publishPoolMetadata(t.pool, it, input.spendingPassword) }
        val changes = (input.changes as? tools.jackson.databind.node.ObjectNode)?.deepCopy() ?: json.createObjectNode()
        publication?.let { changes.set("metadata", json.readTree(json.writeValueAsString(mapOf("url" to it.url, "hash" to it.hash)))) }
        val prefix = "/tmp/jormanager-pool-registration-${UUID.randomUUID()}"
        val body = "$prefix.body"
        val protocolPath = "$prefix.protocol.json"
        val cert = certificates.build(t.host, t.observer, t.pool, t.era, t.network, initial.poolState,
            input.action, input.keyId, input.spendingPassword, prefix, changes)
        val extraPaths = mutableListOf<String>()
        try {
            val unchanged = observe(t, input.feePayerAccountId, input.action, "pending")
            check(initial.evidence.parameterFingerprint == unchanged.evidence.parameterFingerprint &&
                initial.evidence.stateFingerprint == unchanged.evidence.stateFingerprint &&
                initial.evidence.targetFingerprint == unchanged.evidence.targetFingerprint) { "Pool state changed; review again" }
            val stakeIds = (cert.ownerAccountIds + cert.rewardAccountId).distinct().sorted()
            val beforeStake = observe(t, input.feePayerAccountId, input.action, "pending", stakeIds)
            check(initial.evidence.parameterFingerprint == beforeStake.evidence.parameterFingerprint &&
                initial.evidence.targetFingerprint == beforeStake.evidence.targetFingerprint &&
                initial.poolState == beforeStake.poolState &&
                initial.snapshot.readState.generation == beforeStake.snapshot.readState.generation &&
                canonicalWalletInputs(initial.snapshot, payer.paymentAddr) == canonicalWalletInputs(beforeStake.snapshot, payer.paymentAddr)) {
                "Fee payer inputs changed; review again"
            }
            val expectedPoolDeposit = if (beforeStake.poolState.isEmpty)
                json.readTree(beforeStake.parameters).path("stakePoolDeposit").asString().toBigInteger() else BigInteger.ZERO
            check(cert.deposit == expectedPoolDeposit) { "Pool deposit changed; review again" }
            val signers = cert.signingFileIds.toMutableSet()
            val registrationCerts = mutableListOf<String>()
            val delegationCerts = mutableListOf<String>()
            var stakeDeposit = BigInteger.ZERO
            val stakeDepositPerAccount = json.readTree(beforeStake.parameters).path("stakeAddressDeposit").asString().toBigInteger()
            stakeIds.forEach { accountId ->
                val account = requireNotNull(wallets.findByIdOrNull(accountId))
                if (requireNotNull(beforeStake.stakeStates[accountId]).isEmpty) {
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
            if (initial.poolState.isEmpty || input.changes?.has("ownerAccountIds") == true) {
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
            t.connection.commandWriteFile(protocolPath, beforeStake.parameters)
            val built = builder.build(
                host = t.host, connection = t.connection, era = t.era,
                inputs = beforeStake.snapshot.utxosByAddress[payer.paymentAddr].orEmpty(),
                outputs = emptyList(), changeAddress = payer.paymentAddr,
                deposits = cert.deposit + stakeDeposit,
                extraArgs = (registrationCerts + cert.certPath + delegationCerts).flatMap { listOf("--certificate-file", it) },
                protocolParameters = beforeStake.parameters, protocolPath = protocolPath,
                witnessCount = signingIds.size + 1, bodyPath = body, validUntil = expiry(t)
            )
            val envelope = built.textEnvelope
            val effects = exact(json.readTree(cmd(t, listOf(t.host.cardanoCliPath, "debug", "transaction", "view", "--tx-body-file", body, "--output-json"))))
            val payload = json.writeValueAsString(mapOf("intent" to intent(input), "kind" to operation(input.action), "nodeId" to input.nodeId, "poolId" to t.pool.poolId,
                "keyId" to input.keyId, "feePayerAccountId" to input.feePayerAccountId, "action" to input.action,
                "changes" to changes, "publication" to publication, "poolState" to exact(initial.poolState), "targetPublicKey" to cert.targetPublicKey,
                "signingFileIds" to signingIds, "stakeAccountIds" to stakeIds, "effects" to effects))
            val after = observe(t, input.feePayerAccountId, input.action, payload, stakeIds)
            check(beforeStake.evidence.parameterFingerprint == after.evidence.parameterFingerprint && beforeStake.evidence.stateFingerprint == after.evidence.stateFingerprint &&
                beforeStake.evidence.targetFingerprint == after.evidence.targetFingerprint) { "Pool or stake state changed; review again" }
            ensureNoPending(t, input.requestId)
            return result(reviewed.preview(ReviewedWalletOperations.Preview(input.requestId, UUID.randomUUID().toString(), t.host, t.observer,
                operation(input.action), after.evidence, payload, built, requireNotNull(bodyField(envelope, 2)),
                cert.deposit + stakeDeposit, BigInteger.ZERO, bodyField(envelope, 8)?.longValueExact(), bodyField(envelope, 3)?.longValueExact())), input.requestId)
        } finally { cmd(t, listOf("rm", "-f", body, protocolPath) + cert.cleanupPaths + extraPaths) }
    }
    @MessageMapping("/leios/registration/submit")
    @SendTo("/topic/messages")
    fun submitMessage(input: SubmitRequest): SocketResponse<Result> = try {
        SocketResponse.Success("leios_registration_submitted", execute(input))
    } catch (e: Exception) {
        SocketResponse.Error("leios_registration_submitted", SocketResponse.PublicFailure(e.javaClass.simpleName,
            "Pool registration submission failed; inspect protected server diagnostics.", "leios_registration_submitted",
            if (operations.findById(input.requestId).orElse(null)?.state in setOf("signing", "submission_uncertain", "submitted", "rolled_back")) "wait" else "retry", input.requestId))
    }
    private fun submit(input: SubmitRequest, record: AuthorizedOperation): AuthorizedOperation {
        val p = json.readTree(record.requestPayload)
        require(p.path("kind").asString() in setOf("pool.register", "pool.update")) { "Not a pool registration review" }
        val payerId = p.path("feePayerAccountId").asLong(); val action = p.path("action").asString()
        val t = target(p.path("nodeId").asLong(), record.nodeId)
        ensureNoPending(t, record.id)
        var envelope = ""
        val saved = reviewed.submit(record.previewId, t.host, t.observer,
            evidence = { operation -> observe(t, payerId, action, record.requestPayload, p.path("stakeAccountIds").values().map { it.asLong() }, operation.id).evidence },
            sign = { operation ->
                val payer = requireNotNull(wallets.findByIdOrNull(payerId))
                val signingFiles = listOf(requireNotNull(payer.paymentSkey)) + p.path("signingFileIds").values().map {
                    requireNotNull(files.findByIdOrNull(it.asLong()))
                }
                val prefix = "/tmp/jormanager-pool-sign-${UUID.randomUUID()}"
                val body = "$prefix.body"; val signed = "$prefix.signed"; val paths = mutableListOf<String>()
                try {
                    t.connection.commandWriteFile(body, operation.unsignedBody)
                    signingFiles.forEachIndexed { i, file ->
                        val path = "$prefix.$i.skey"; paths += path
                        t.connection.commandWriteSecret(path, walletUtils.getSKeyContent(file, input.spendingPassword))
                    }
                    val args = mutableListOf(t.host.cardanoCliPath, t.era, "transaction", "sign", "--tx-body-file", body)
                    paths.forEach { args += listOf("--signing-key-file", it) }; args += t.network; args += listOf("--out-file", signed)
                    cmd(t, args); envelope = t.connection.commandReadFile(signed)
                    json.readTree(envelope).path("cborHex").asString().also { require(it.isNotBlank()) }
                } finally { cmd(t, listOf("rm", "-f", body, signed) + paths) }
            },
            submit = { signedHex ->
                val path = "/tmp/jormanager-pool-submit-${UUID.randomUUID()}.signed"
                try {
                    check(json.readTree(envelope).path("cborHex").asString().equals(signedHex, ignoreCase = true))
                    t.connection.commandWriteFile(path, envelope)
                    cmd(t, listOf(t.host.cardanoCliPath, t.era, "transaction", "submit", "--tx-file", path) + t.network +
                        listOf("--socket-path", "${t.host.nodeHomePath}/${t.observer.name}/db/socket"))
                } finally { cmd(t, listOf("rm", "-f", path)) }
            })
        return saved
    }
}
