package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.LeiosKeyRequest
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.utils.CborSpans
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class LeiosKeyService(
    private val walletUtils: WalletUtils,
    private val wallets: WalletRepository,
    private val files: FileRepository,
    private val keys: LeiosKeyRepository,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val json: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val safePath = Regex("[A-Za-z0-9_./-]+")

    // The secret is sent over stdin, never in a command line or the shared SCP temporary file.
    private fun writeSecret(host: Host, path: String, value: String) {
        if (!host.isRemote) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), value)
        } else {
            val pool = SSHClientPool.getInstance(host)
            val ssh = pool.borrow()
            try {
                ssh.startSession().use { session ->
                    session.exec("umask 077; cat > $path").use { command ->
                        command.outputStream.bufferedWriter().use { it.write(value) }
                        command.join(10, TimeUnit.SECONDS)
                        check(command.exitStatus == 0) { "Unable to transfer signing key" }
                    }
                }
            } finally { pool.recycle(ssh) }
        }
    }

    private fun bytesFromEnvelope(value: String): ByteArray {
        val hex = json.readTree(value).path("cborHex").asText()
        require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return CborSpans.parse(hex.hexToByteArray()).payload()
    }

    fun prepare(request: LeiosKeyRequest, host: Host, node: Node): LeiosVotingKey {
        require(request.intent == "prepare" || request.intent == "import")
        require(request.confirmed && walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        require(node.hostId == host.id && !node.isDeleted && node.type in setOf("core", "pool"))
        val poolId = requireNotNull(node.poolId)
        val network = eras.networkKey(node)
        require(request.target.poolId == null || request.target.poolId == poolId)
        require(request.target.networkKey == null || request.target.networkKey == network)
        val operation = "leios.key.${request.intent}"
        val observer = if (node.isDraft) {
            if (request.observerNodeId != null) requireNotNull(nodes.findByIdOrNull(request.observerNodeId))
            else nodes.findDefault() ?: throw IllegalStateException("A verified observer is required for a draft pool")
        } else node
        val observerHost = if (node.isDraft) requireNotNull(hosts.findByIdOrNull(observer.hostId)) else host
        val configDigest = if (node.isDraft) MessageDigest.getInstance("SHA-256")
            .digest(requireNotNull(files.findByIdOrNull(node.configFileId)).content.toByteArray(Charsets.UTF_8)).toHexString() else null
        fun qualified(): String {
            val currentNode = requireNotNull(nodes.findByIdOrNull(requireNotNull(node.id)))
            check(!currentNode.isDeleted && currentNode.hostId == node.hostId && currentNode.poolId == poolId &&
                currentNode.configFileId == node.configFileId && currentNode.coreVKeyId == node.coreVKeyId &&
                currentNode.vrfVKeyId == node.vrfVKeyId && currentNode.ownerStakingAccountId == node.ownerStakingAccountId &&
                currentNode.rewardsStakingAccountId == node.rewardsStakingAccountId && currentNode.isDraft == node.isDraft)
            if (node.isDraft) {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(requireNotNull(files.findByIdOrNull(node.configFileId)).content.toByteArray(Charsets.UTF_8)).toHexString()
                check(actual == configDigest) { "Draft configuration changed" }
            }
            val era = eras.evidence(observer)
            check(era.freshness == "current" && era.era == "dijkstra" &&
                era.source == "host:${observerHost.id}/node:${observer.id}" && era.networkKey == network)
            val capability = if (node.isDraft) capabilities.hostCapability(host, observerHost, observer, network, requireNotNull(configDigest), operation)
                else capabilities.capability(host, node, operation)
            check(capability.status == "verified") { "Key operation is not qualified" }
            return requireNotNull(capability.evidenceId)
        }
        val evidence = qualified()
        require(safePath.matches(host.cardanoCliPath) && !host.cardanoCliPath.startsWith("-"))
        val connection = HostConnection(host, node)
        val directory = connection.command(listOf("mktemp", "-d", "/tmp/jormanager-leios-XXXXXXXXXX")).trim()
        require(directory.matches(Regex("/tmp/jormanager-leios-[A-Za-z0-9]+")))
        val skey = "$directory/bls.skey"
        try {
            if (request.intent == "prepare") {
                require(request.signingKey == null)
                connection.command(listOf(host.cardanoCliPath, "dijkstra", "node", "key-gen-BLS", "--verification-key-file", "$directory/generated.vkey", "--signing-key-file", skey))
                connection.command(listOf("chmod", "600", skey))
            } else {
                val secret = requireNotNull(request.signingKey)
                require(secret.length <= 16384)
                writeSecret(host, skey, secret)
            }
            connection.command(listOf(host.cardanoCliPath, "dijkstra", "key", "verification-key", "--signing-key-file", skey, "--verification-key-file", "$directory/derived.vkey"))
            val derived = bytesFromEnvelope(connection.command(listOf("cat", "$directory/derived.vkey")))
            require(derived.size == 96)
            if (request.intent == "prepare") require(derived.contentEquals(bytesFromEnvelope(connection.command(listOf("cat", "$directory/generated.vkey")))) )
            val owner = requireNotNull(wallets.findByIdOrNull(node.ownerStakingAccountId)?.stakingVkey?.content)
            val reward = requireNotNull(wallets.findByIdOrNull(node.rewardsStakingAccountId)?.stakingVkey?.content)
            val cold = requireNotNull(files.findByIdOrNull(node.coreVKeyId)?.content)
            val vrf = requireNotNull(files.findByIdOrNull(node.vrfVKeyId)?.content)
            listOf("owner" to owner, "reward" to reward, "cold" to cold, "vrf" to vrf).forEach { (name, content) -> connection.commandWriteFile("$directory/$name.vkey", content) }
            val genesis = json.readTree(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
            val networkArgs = if (genesis.path("networkId").asText().equals("Mainnet", true)) listOf("--mainnet") else {
                val magic = genesis.path("networkMagic").asText()
                require(magic.matches(Regex("[0-9]+")))
                listOf("--testnet-magic", magic)
            }
            connection.command(listOf(host.cardanoCliPath, "dijkstra", "stake-pool", "registration-certificate",
                "--cold-verification-key-file", "$directory/cold.vkey", "--vrf-verification-key-file", "$directory/vrf.vkey",
                "--bls-signing-key-file", skey, "--pool-pledge", "0", "--pool-cost", "0", "--pool-margin", "0",
                "--pool-reward-account-verification-key-file", "$directory/reward.vkey", "--pool-owner-stake-verification-key-file", "$directory/owner.vkey") + networkArgs + listOf("--out-file", "$directory/pool.cert"))
            val certHex = json.readTree(connection.command(listOf("cat", "$directory/pool.cert"))).path("cborHex").asText()
            require(certHex.isNotEmpty() && certHex.length % 2 == 0 && certHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            val certificate = CborSpans.parse(certHex.hexToByteArray()).array()
            require(certificate.size == 11 && certificate[0].integer().toInt() == 3)
            val bls = certificate[3].array()
            require(bls.size == 2)
            val public = bls[0].payload()
            val proof = bls[1].payload()
            require(public.size == 96 && proof.size == 48 && public.contentEquals(derived))
            require(request.publicKeyHex == null || request.publicKeyHex.equals(public.toHexString(), true))
            require(request.proofOfPossessionHex == null || request.proofOfPossessionHex.equals(proof.toHexString(), true))
            val secret = connection.command(listOf("cat", skey))
            val encrypted = walletUtils.encryptSKeyContent(secret, request.spendingPassword)
            check(qualified() == evidence) { "Key qualification changed" }
            return requireNotNull(transaction.execute {
                val file = files.save(File(name = "leios-${node.id}-${UUID.randomUUID()}.skey", content = encrypted))
                keys.save(LeiosVotingKey(networkKey = network, poolId = poolId, publicKey = public, proof = proof,
                    signingFileId = requireNotNull(file.id), preparedAt = Instant.now(), validationProvenance = evidence))
            })
        } finally {
            connection.command(listOf("rm", "-rf", directory))
        }
    }
}
