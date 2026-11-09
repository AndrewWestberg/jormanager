package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.NodeController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.NodeConfigurationSnapshot
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeConfigurationSnapshotRepository
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.repositories.NodeRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.ObjectMapper

class PoolDeploymentServiceTest {
    private val nodes = mockk<NodeRepository>()
    private val wallet = mockk<WalletUtils>()
    private val jdbc = mockk<JdbcTemplate>()
    private val id = UUID.randomUUID().toString()
    private val service = PoolDeploymentService(nodes, mockk(), mockk(), mockk(), mockk(), mockk(),
        mockk(), wallet, mockk(), mockk(), jdbc, ObjectMapper(), MutableSharedFlow())

    private fun journal(state: String, details: String = """{"keyId":7,"installedFingerprint":"fingerprint","reason":"saved outcome"}""") {
        every { wallet.isValidSpendingPassword("password") } returns true
        every { jdbc.query(any<String>(), any<RowMapper<List<String>>>(), *anyVararg()) } returns
            listOf(listOf("42", "3", details, state))
    }

    @Test
    fun `interrupted or failed remote writes cannot be replayed`() {
        for (state in listOf("installing", "failed")) {
            journal(state)
            val error = assertThrows<IllegalStateException> { service.install(42, 7, id, "password", "sudo") }
            assertThat(error.message).contains("inspection")
        }
        verify(exactly = 0) { nodes.findById(any()) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `installed and startup failure outcomes are returned without touching host or regenerating certificate`() {
        for (state in listOf("installed", "installed_startup_failed")) {
            journal(state)
            val result = service.install(42, 7, id, "password", "sudo")
            assertThat(result.state).isEqualTo(state)
            assertThat(result.installedFingerprint).isEqualTo("fingerprint")
            assertThat(result.reason).isEqualTo("saved outcome")
        }
        verify(exactly = 0) { nodes.findById(any()) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `saved outcome cannot be read with different node or key`() {
        journal("installed")
        assertThrows<IllegalArgumentException> { service.install(43, 7, id, "password", "sudo") }
        assertThrows<IllegalArgumentException> { service.install(42, 8, id, "password", "sudo") }
        verify(exactly = 0) { nodes.findById(any()) }
    }
}

class RemotePoolDeploymentServiceTest {
    private val json = ObjectMapper()
    private val nodes = mockk<NodeRepository>()
    private val hosts = mockk<HostRepository>()
    private val files = mockk<FileRepository>()
    private val eras = mockk<CardanoRepository>()
    private val configs = mockk<NodeConfigurationFiles>()
    private val snapshots = mockk<NodeConfigurationSnapshotRepository>()
    private val wallet = mockk<WalletUtils>()
    private val jdbc = mockk<JdbcTemplate>()
    private val id = UUID.randomUUID().toString()
    private val host = Host(id = 3, type = "remote", cardanoCliPath = "/bin/cardano-cli", cardanoNodePath = "/bin/cardano-node",
        hostname = "example", sshUser = "operator", nodeHomePath = "/nodes", jcliPath = null)
    private val observer = Node(id = 1, hostId = 3, color = "", type = "relay", processorThreads = 1,
        name = "observer", listen = "127.0.0.1", port = 3001, promPort = 12798, genesisByronFileId = 1,
        genesisShelleyFileId = 2, genesisAlonzoFileId = 3, genesisConwayFileId = 4, configFileId = 5, isDefault = true)
    private val parent = observer.copy(id = 2, name = "parent", type = "core", isDefault = false,
        kesSKeyId = 16, vrfSKeyId = 14, opcertId = 18)
    private val draft = observer.copy(id = 42, name = "pool", type = "pool", parentId = 2, isDefault = false,
        isDraft = true, poolId = "pool-id", coreSKeyId = 11, coreVKeyId = 12, coreCounterId = 13,
        vrfSKeyId = 14, vrfVKeyId = 15, kesSKeyId = 16, kesVKeyId = 17)
    private val service = PoolDeploymentService(nodes, hosts, files, mockk(), mockk(), eras, configs,
        wallet, snapshots, mockk<NodeController>(), jdbc, json, MutableSharedFlow())
    private var state = "registered"
    private var details = """{"intent":{"name":"pool"},"registrationRequestId":"registration","keyId":null}"""
    private val records = mutableMapOf<Long, File>()
    private val remote = mutableMapOf<String, String>()
    private val commands = mutableListOf<List<String>>()
    private var failCredentialInstall = false
    private var failStartup = false
    private var failCertificateSave = false

    private fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun setup() {
        val genesis = listOf("byron-genesis.json" to "{}", "shelley-genesis.json" to """{"networkId":"testnet","networkMagic":42,"slotsPerKESPeriod":10}""",
            "alonzo-genesis.json" to "{}", "conway-genesis.json" to "{}")
        val assets = (genesis + listOf("config.json" to "{}", "topology.json" to "{}")).toMap()
        genesis.forEachIndexed { index, (_, content) -> records[index.toLong() + 1] = File(index.toLong() + 1, "genesis", content) }
        records[5] = File(5, "config", "{}")
        for (key in 11L..18L) records[key] = File(key, "key", if (key == 13L) "old-counter" else """{"cborHex":"key$key"}""")
        val snapshot = NodeConfigurationSnapshot("snapshot", 42, "pool",
            json.writeValueAsString(assets.mapValues { Base64.getEncoder().encodeToString(it.value.toByteArray()) }),
            hash("{}"), hash("{}"), "{}", json.writeValueAsString(assets.mapValues { hash(it.value) }), null)
        every { snapshots.findByNodeId(42) } returns listOf(snapshot)
        every { configs.validate(any()) } returns Unit
        every { configs.renderTopology(any()) } returns "{}"
        every { nodes.findById(42) } returns Optional.of(draft)
        every { nodes.findById(2) } returns Optional.of(parent)
        every { nodes.findDefault() } returns observer
        every { nodes.save(any<Node>()) } answers { firstArg() }
        every { hosts.findById(3) } returns Optional.of(host)
        every { files.findById(any()) } answers { Optional.ofNullable(records[firstArg<Long>()]) }
        every { files.save(any<File>()) } answers {
            if (failCertificateSave && firstArg<File>().id == null) throw IllegalStateException("certificate persistence failed")
            val saved = firstArg<File>().let { if (it.id == null) it.copy(id = 99) else it }
            records[requireNotNull(saved.id)] = saved
            saved
        }
        every { wallet.isValidSpendingPassword("password") } returns true
        every { wallet.getSKeyContent(any(), "password") } answers { firstArg<File>().content }
        every { eras.networkKey(any()) } returns "network"
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", "conway", "current", "host:3/node:1")
        every { jdbc.query(match { it.contains("FROM pool_deployment_previews") }, any<RowMapper<List<String>>>(), *anyVararg()) } answers {
            listOf(listOf("42", "3", details, state))
        }
        every { jdbc.query(match { it.contains("FROM authorized_operations") }, any<RowMapper<String>>(), *anyVararg()) } returns
            listOf("""{"nodeId":42,"keyId":null,"poolId":"pool-id"}""")
        every { jdbc.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val args = secondArg<Array<out Any?>>()
            when {
                sql.contains("SET state='installing'") -> { state = "installing"; details = args[0] as String }
                sql.contains("SET state='failed'") -> { state = "failed"; details = args[0] as String }
                sql.contains("SET state=?,details") -> { state = args[0] as String; details = args[1] as String }
                sql.contains("SET state='installed_startup_failed'") -> { state = "installed_startup_failed"; details = args[0] as String }
                sql.contains("SET state='installed'") -> { state = "installed"; details = args[0] as String }
            }
            1
        }
        remote["/nodes/parent/config.json"] = "{}"
        assets.filterKeys { it != "config.json" }.forEach { (name, value) -> remote["/nodes/parent/$name"] = value }
        remote["/etc/systemd/system/parent-node.service"] = "User=operator\n--bulk-credentials-file /nodes/parent/credentials.json"
        remote["/nodes/parent/credentials.json"] = """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"}]]"""
        remote["/nodes/parent/env"] = "env"
        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().hasSystemd } returns true
        every { anyConstructed<HostConnection>().commandFileExists(any()) } answers { remote.containsKey(firstArg<String>()) }
        every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { remote.getValue(firstArg<String>()) }
        every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
            remote[firstArg()] = secondArg(); ""
        }
        every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } answers {
            remote[firstArg()] = secondArg()
        }
        every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            when {
                "tip" in args -> """{"slot":100}"""
                args.first() == "id" -> "operator"
                args.first() == "ss" -> ""
                args.first() == "sha256sum" -> hash(remote.getValue(args[1]))
                args.first() == "stat" -> "operator:600"
                "issue-op-cert" in args -> {
                    remote[args[args.indexOf("--operational-certificate-issue-counter") + 1]] = "new-counter"
                    remote[args[args.indexOf("--out-file") + 1]] = """{"cert":"new"}"""
                    ""
                }
                args.first() == "cp" -> { remote[args.last()] = remote.getValue(args[args.size - 2]); "" }
                args.first() == "install" -> {
                    if (failCredentialInstall) throw IllegalStateException("remote write failed")
                    remote[args.last()] = remote.getValue(args[args.size - 2]); ""
                }
                args.first() == "rm" -> { args.drop(2).forEach { remote.remove(it) }; "" }
                else -> ""
            }
        }
        every { anyConstructed<HostConnection>().sudoCommand(any<List<String>>(), any()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            if (failStartup && args.contains("restart")) throw IllegalStateException("startup failed")
            ""
        }
    }

    @Test
    fun `failure after counter issuance retains counter and backup and forbids replay`() {
        setup()
        try {
            failCredentialInstall = true
            assertThrows<IllegalStateException> { service.install(42, null, id, "password", "sudo") }
            assertThat(state).isEqualTo("failed")
            assertThat(records.getValue(13).content).isEqualTo("new-counter")
            assertThat(remote.getValue("/nodes/parent/credentials.json.jormanager-backup-$id"))
                .isEqualTo(remote.getValue("/nodes/parent/credentials.json"))
            assertThrows<IllegalStateException> { service.install(42, null, id, "password", "sudo") }
            assertThat(commands.count { "issue-op-cert" in it }).isEqualTo(1)
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test
    fun `certificate persistence failure retains issued certificate and saved counter`() {
        setup()
        try {
            failCertificateSave = true
            assertThrows<IllegalStateException> { service.install(42, null, id, "password", "sudo") }
            assertThat(state).isEqualTo("failed")
            assertThat(records.getValue(13).content).isEqualTo("new-counter")
            assertThat(remote.filterKeys { it.startsWith("/tmp/jormanager-deploy-") && it.endsWith(".opcert") }.values)
                .containsExactly("""{"cert":"new"}""")
            assertThrows<IllegalStateException> { service.install(42, null, id, "password", "sudo") }
            assertThat(commands.count { "issue-op-cert" in it }).isEqualTo(1)
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test
    fun `startup failure preserves installation and repeated call never reissues certificate`() {
        setup()
        try {
            failStartup = true
            val result = service.install(42, null, id, "password", "sudo")
            assertThat(result.state).isEqualTo("installed_startup_failed")
            assertThat(state).isEqualTo("installed_startup_failed")
            assertThat(records.getValue(13).content).isEqualTo("new-counter")
            assertThat(remote.getValue("/nodes/parent/credentials.json.jormanager-backup-$id")).contains("old-vrf")
            assertThat(remote.getValue("/nodes/parent/credentials.json")).contains("key14")
            assertThat(commands).contains(listOf("systemctl", "restart", "parent-node.service"))
            assertThat(service.install(42, null, id, "password", "sudo").state).isEqualTo("installed_startup_failed")
            assertThat(commands.count { "issue-op-cert" in it }).isEqualTo(1)
        } finally { unmockkConstructor(HostConnection::class) }
    }
}
