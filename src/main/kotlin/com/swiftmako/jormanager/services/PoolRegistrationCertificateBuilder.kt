package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Reconstructs the selected future/current pool parameters, never silently replacing a scheduled key. */
@Service
class PoolRegistrationCertificateBuilder(
    private val json: ObjectMapper,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val keys: LeiosKeyRepository,
    private val walletUtils: WalletUtils,
    private val eras: CardanoRepository,
    private val snapshots: ProtocolSnapshotRepository,
    private val jdbc: JdbcTemplate,
) {
    data class Result(val certPath: String, val signingFileIds: List<Long>, val targetPublicKey: String?, val deposit: BigInteger, val cleanupPaths: List<String>,
        val ownerAccountIds: List<Long>, val rewardAccountId: Long)
    private val decimal = Regex("0|[1-9][0-9]*")
    private val hex28 = Regex("[0-9a-fA-F]{56}")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun build(host: Host, observer: Node, pool: Node, era: String, networkArgs: List<String>, poolState: JsonNode,
        action: String, keyId: Long?, password: String, prefix: String, changes: JsonNode? = null): Result {
        require(era == "dijkstra" || era == "conway")
        require(action in setOf("register", "rotate", "cancel", "update"))
        require(prefix.matches(Regex("/tmp/jormanager-pool-registration-[0-9a-f-]+")))
        require(walletUtils.isValidSpendingPassword(password))
        require(host.id == observer.hostId && eras.networkKey(pool) == eras.networkKey(observer))
        if (changes != null && !changes.isNull) {
            require(changes.isObject && changes.fieldNames().asSequence().all { it in setOf("pledge", "cost", "margin", "relays", "metadata", "ownerAccountIds", "rewardAccountId") })
        }
        val connection = HostConnection(host, observer)
        val cleanup = mutableListOf<String>()
        fun file(suffix: String, value: String, secret: Boolean = false): String {
            val path = "$prefix.$suffix"
            cleanup.add(path)
            if (secret) connection.commandWriteSecret(path, value) else connection.commandWriteFile(path, value)
            return path
        }
        fun command(vararg args: String) = connection.commandArgs(listOf(host.cardanoCliPath, era) + args).trim()
        fun number(value: JsonNode): String = value.asText().also { require(it.matches(decimal)) { "Invalid pool amount" } }
        fun value(name: String, fallback: JsonNode): JsonNode = changes?.get(name) ?: fallback
        try {
            require(poolState.isObject && poolState.size() <= 1)
            val registered = poolState.size() == 1
            val entry = if (registered) poolState.elements().next() else null
            val futureValue = entry?.path("futureStakePoolParams")
            require(futureValue == null || futureValue.isNull || futureValue.isObject) { "Invalid future pool parameters" }
            val future = futureValue?.takeIf { it.isObject }
            val state = future ?: entry
            if (registered) require(state != null && state.isObject && entry!!.path("deposit").isIntegralNumber)
            fun field(name: String): JsonNode = requireNotNull(state).path(name)
            val draft = if (!registered) {
                require(pool.isDraft && action == "register")
                val raw = jdbc.query("SELECT request_json FROM pool_draft_config WHERE node_id=?", { rs, _ -> rs.getString(1) }, pool.id).singleOrNull()
                json.readTree(requireNotNull(raw))
            } else null
            val ownerChanges = changes?.get("ownerAccountIds")
            val ownerIds = if (ownerChanges != null) {
                require(ownerChanges.isArray && ownerChanges.size() > 0 && ownerChanges.all { it.isIntegralNumber && it.canConvertToLong() && it.asLong() > 0 })
                ownerChanges.map { it.asLong() }.also { require(it.distinct().size == it.size) }
            } else listOf(pool.ownerStakingAccountId)
            val rewardChange = changes?.get("rewardAccountId")
            val rewardId = if (rewardChange != null) {
                require(rewardChange.isIntegralNumber && rewardChange.canConvertToLong() && rewardChange.asLong() > 0)
                rewardChange.asLong()
            } else pool.rewardsStakingAccountId
            val owner = requireNotNull(wallets.findByIdOrNull(ownerIds.first()))
            val reward = requireNotNull(wallets.findByIdOrNull(rewardId))
            val cold = file("cold.vkey", requireNotNull(files.findByIdOrNull(pool.coreVKeyId)).content)
            val vrf = file("vrf.vkey", requireNotNull(files.findByIdOrNull(pool.vrfVKeyId)).content)
            val ownerV = file("owner.vkey", requireNotNull(owner.stakingVkey).content)
            val rewardV = file("reward.vkey", requireNotNull(reward.stakingVkey).content)
            val expectedPool = requireNotNull(pool.poolId).let { if (it.startsWith("pool1")) Bech32.decode(it).bytes.toHexString() else it.lowercase() }
            val actualPool = command("stake-pool", "id", "--cold-verification-key-file", cold, "--output-hex")
            require(actualPool.equals(expectedPool, true)) { "Cold key does not match the target pool" }
            if (future != null) require(future.path("poolId").asText().equals(expectedPool, true)) {
                "Scheduled parameters belong to a different pool"
            }
            val ownerHash = command("stake-address", "key-hash", "--stake-verification-key-file", ownerV)
            val rewardHash = command("stake-address", "key-hash", "--stake-verification-key-file", rewardV)
            val vrfHash = command("node", "key-hash-VRF", "--verification-key-file", vrf)
            require(ownerHash.matches(hex28) && rewardHash.matches(hex28) && vrfHash.matches(Regex("[0-9a-fA-F]{64}")))
            val ownerFiles = mutableListOf(ownerV)
            val ownerSigningIds = mutableListOf(requireNotNull(owner.stakingSkey?.id))
            val selectedOwnerIds = ownerIds.toMutableList()
            ownerIds.drop(1).forEach { ownerId ->
                val account = requireNotNull(wallets.findByIdOrNull(ownerId))
                ownerFiles.add(file("owner-$ownerId.vkey", requireNotNull(account.stakingVkey).content))
                ownerSigningIds.add(requireNotNull(account.stakingSkey?.id))
            }
            if (registered) {
                require(field("vrf").asText().equals(vrfHash, true)) { "Scheduled VRF key differs from local key" }
                if (ownerChanges == null) {
                    val owners = field("owners")
                    require(owners.isArray && owners.size() > 0)
                    val needed = owners.map { it.asText().lowercase() }.toMutableSet()
                    require(needed.all { it.matches(hex28) } && needed.remove(ownerHash.lowercase())) { "Scheduled owners differ from local pool owner" }
                    wallets.findAllNotDeleted().filter { it.id != owner.id && it.stakingVkey != null && it.stakingSkey != null }.forEach { candidate ->
                        if (needed.isNotEmpty()) {
                            val candidateV = file("owner-${candidate.id}.vkey", requireNotNull(candidate.stakingVkey).content)
                            val hash = command("stake-address", "key-hash", "--stake-verification-key-file", candidateV).lowercase()
                            if (needed.remove(hash)) {
                                ownerFiles.add(candidateV)
                                ownerSigningIds.add(requireNotNull(candidate.stakingSkey?.id))
                                selectedOwnerIds.add(requireNotNull(candidate.id))
                            }
                        }
                    }
                    require(needed.isEmpty()) { "Signing keys for scheduled owners are unavailable" }
                }
                val rewardState = field(if (future != null) "accountAddress" else "accountId")
                val rewardCredential = if (rewardState.has("credential")) rewardState.path("credential") else rewardState
                val rewardKeyHash = rewardCredential.path("keyHash").takeUnless { it.isMissingNode } ?: rewardCredential.path("key hash")
                if (rewardChange == null) require(rewardKeyHash.asText().equals(rewardHash, true)) { "Scheduled reward credential differs" }
            }
            val pledge = number(value("pledge", if (registered) field("pledge") else draft!!.path("poolPledge")))
            val cost = number(value("cost", if (registered) field("cost") else draft!!.path("poolCost")))
            val params = json.readTree(snapshots.query(host, observer, connection, era, networkArgs.joinToString(" "), "--socket-path ${host.nodeHomePath}/${observer.name}/db/socket"))
            require(BigInteger(cost) >= BigInteger(number(params.path("minPoolCost")))) { "Pool cost is below the current minimum" }
            val marginNode = value("margin", if (registered) field("margin") else draft!!.path("poolMargin"))
            val margin = when {
                marginNode.isObject -> "${number(marginNode.path("numerator"))}/${number(marginNode.path("denominator"))}"
                marginNode.isNumber -> marginNode.decimalValue().toPlainString()
                else -> marginNode.asText()
            }
            require(margin.matches(Regex("(?:0|1|0\\.[0-9]+|1\\.0+|[0-9]+/[1-9][0-9]*)"))) { "Invalid pool margin" }
            val command = mutableListOf(host.cardanoCliPath, era, "stake-pool", "registration-certificate", "--cold-verification-key-file", cold,
                "--vrf-verification-key-file", vrf, "--pool-pledge", pledge, "--pool-cost", cost, "--pool-margin", margin,
                "--pool-reward-account-verification-key-file", rewardV)
            ownerFiles.forEach { command += listOf("--pool-owner-stake-verification-key-file", it) }
            val blsValue = if (registered) field("blsKey") else null
            val blsState = if (future != null) blsValue else blsValue?.path("bksKey")
            val desiredPublic = if (keyId != null) {
                require(era == "dijkstra" && action in setOf("register", "rotate"))
                val key = requireNotNull(keys.findByIdOrNull(keyId))
                require(key.networkKey == eras.networkKey(pool) && key.poolId == pool.poolId && key.signingFileId != null)
                key.publicKey.toHexString()
            } else blsState?.path("blsPubKey")?.takeIf { it.isTextual }?.asText()
            var expectedProof: String? = null
            if (era == "dijkstra") {
                require(!desiredPublic.isNullOrBlank()) { "Scheduled voting key unavailable" }
                val key = if (keyId != null) requireNotNull(keys.findByIdOrNull(keyId)) else
                    keys.findByNetworkKeyAndPoolId(eras.networkKey(pool), requireNotNull(pool.poolId)).firstOrNull { it.publicKey.toHexString().equals(desiredPublic, true) && it.signingFileId != null }
                        ?: throw IllegalStateException("Signing material for the scheduled voting key is unavailable")
                val secret = walletUtils.getSKeyContent(requireNotNull(files.findByIdOrNull(requireNotNull(key.signingFileId))), password)
                expectedProof = key.proof.toHexString()
                if (keyId == null) require(blsState?.path("blsPossessionProof")?.asText()?.equals(expectedProof, true) == true) { "Scheduled voting proof differs" }
                command += listOf("--bls-signing-key-file", file("bls.skey", secret, true))
            }
            val relays = value("relays", if (registered) field("relays") else draft!!.path("relays"))
            require(relays.isArray || relays.isNull)
            relays.forEach { relay ->
                val address = relay.path("single host address")
                val single = relay.path("single host name")
                val multi = relay.path("multi host name")
                when {
                    address.isObject -> {
                        if (address.path("IPv4").isTextual) command += listOf("--pool-relay-ipv4", address.path("IPv4").asText())
                        if (address.path("IPv6").isTextual) command += listOf("--pool-relay-ipv6", address.path("IPv6").asText())
                        command += listOf("--pool-relay-port", number(address.path("port")))
                    }
                    single.isObject -> command += listOf("--single-host-pool-relay", single.path("dnsName").asText(), "--pool-relay-port", number(single.path("port")))
                    multi.isObject -> command += listOf("--multi-host-pool-relay", multi.path("dnsName").asText())
                    !registered && relay.path("addr").isTextual -> {
                        val addr = relay.path("addr").asText()
                        command += listOf(if (addr.matches(Regex("[0-9.]+"))) "--pool-relay-ipv4" else "--single-host-pool-relay", addr, "--pool-relay-port", number(relay.path("port")))
                    }
                    else -> throw IllegalArgumentException("Unsupported pool relay")
                }
            }
            val metadata = value("metadata", if (registered) field("metadata") else json.nodeFactory.nullNode())
            if (!registered && draft!!.path("metadata").isObject) require(!metadata.isNull && !metadata.isMissingNode) { "Published draft metadata is required" }
            if (!metadata.isNull && !metadata.isMissingNode) {
                require(metadata.isObject && metadata.fieldNames().asSequence().toSet() == setOf("url", "hash"))
                val url = metadata.path("url").asText()
                val hash = metadata.path("hash").asText().lowercase()
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val uri = URI(url)
                if (!registered || changes?.has("metadata") == true) {
                    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
                    val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                    val bytes = response.body().use { it.readNBytes(513) }
                    require(response.statusCode() == 200 && bytes.size <= 512)
                    require(Blake2b.hash256(bytes).toHexString() == hash)
                    val published = json.readTree(bytes)
                    if (!registered) {
                        val requested = draft!!.path("metadata")
                        listOf("name", "description", "ticker", "homepage").forEach { field ->
                            if (!requested.path(field).isNull && !requested.path(field).isMissingNode) require(published.path(field) == requested.path(field))
                        }
                    }
                }
                command += listOf("--metadata-url", url, "--metadata-hash", hash)
            }
            val cert = "$prefix.pool.cert"
            cleanup.add(cert)
            connection.commandArgs(command + networkArgs + listOf("--out-file", cert))
            if (era == "dijkstra") {
                val certHex = json.readTree(connection.commandArgs(listOf("cat", cert))).path("cborHex").asText()
                require(certHex.matches(Regex("[0-9a-fA-F]+")) && certHex.length % 2 == 0)
                val parts = CborSpans.parse(certHex.hexToByteArray()).array()
                require(parts.size == 11 && parts[0].integer() == BigInteger.valueOf(3))
                val bls = parts[3].array()
                require(bls.size == 2 && bls[0].payload().toHexString().equals(desiredPublic, true) && bls[1].payload().toHexString().equals(expectedProof, true)) { "Generated BLS proof does not match selected key" }
            }
            val deposit = if (registered) BigInteger.ZERO else BigInteger(number(params.path("stakePoolDeposit")))
            return Result(cert, listOf(pool.coreSKeyId) + ownerSigningIds, desiredPublic, deposit, cleanup, selectedOwnerIds, rewardId)
        } catch (error: Exception) {
            if (cleanup.isNotEmpty()) try { connection.commandArgs(listOf("rm", "-f") + cleanup) } catch (_: Exception) { }
            throw error
        }
    }
}
