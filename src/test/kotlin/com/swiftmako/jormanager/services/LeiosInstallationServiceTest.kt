package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.LeiosController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.LeiosInstallRequest
import com.swiftmako.jormanager.repositories.*
import io.mockk.*
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.Optional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.ObjectMapper

class LeiosInstallationServiceTest {
    private val nodes = mockk<NodeRepository>()
    private val hosts = mockk<HostRepository>()
    private val keys = mockk<LeiosKeyRepository>()
    private val files = mockk<FileRepository>()
    private val installations = mockk<KeyInstallationRepository>()
    private val wallet = mockk<WalletUtils>()
    private val eras = mockk<CardanoRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = LeiosInstallationService(nodes, hosts, keys, files, installations, wallet, eras, jdbc, ObjectMapper())
    private val host = Host(id = 1, type = "remote", cardanoCliPath = "/bin/cardano-cli", cardanoNodePath = "/bin/cardano-node", hostname = "example", sshUser = "operator", nodeHomePath = "/nodes", jcliPath = null)
    private val node = Node(id = 2, hostId = 1, color = "", type = "core", processorThreads = 1, name = "core", listen = "127.0.0.1", port = 3001, promPort = 12798, genesisByronFileId = 1, genesisShelleyFileId = 2, genesisAlonzoFileId = 3, genesisConwayFileId = 4, configFileId = 5, poolId = "pool", isDefault = false)
    private val publicKey = ByteArray(96) { 1 }
    private val key = LeiosVotingKey(id = 7, networkKey = "network", poolId = "pool", publicKey = publicKey, proof = ByteArray(48), signingFileId = 8, preparedAt = Instant.now(), validationProvenance = "fixture")
    private val secret = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","cborHex":"5820${"01".repeat(32)}"}"""
    private val request = LeiosInstallRequest("request", LeiosController.Target(1, 2, "pool", "network"), 7, "password")
    private val remote = mutableMapOf("/nodes/core/startNode.sh" to "--shelley-operational-certificate cert", "/nodes/core/env" to "env", "/nodes/core/config.json" to "config")
    private val commands = mutableListOf<List<String>>()
    private var row: Array<out Any?>? = null
    private var state = ""
    private var matchingKey = true
    private var permissions = "operator:600"
    private var failMove = false

    private fun setup() {
        every { nodes.findById(2) } returns Optional.of(node)
        every { hosts.findById(1) } returns Optional.of(host)
        every { keys.findById(7) } returns Optional.of(key)
        every { files.findById(8) } returns Optional.of(File(8, "key", secret))
        every { wallet.isValidSpendingPassword("password") } returns true
        every { wallet.getSKeyContent(any(), "password") } returns secret
        every { eras.networkKey(node) } returns "network"
        every { eras.evidence(node) } returns CardanoRepository.EraEvidence("network", "dijkstra", "current", "host:1/node:2")
        every { installations.save(any<KeyInstallation>()) } answers { firstArg() }
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val data = row
            if (data == null) emptyList() else {
                val rs = mockk<ResultSet>()
                every { rs.getString(1) } returns data[0] as String
                every { rs.getLong(2) } returns data[2] as Long
                every { rs.getLong(3) } returns data[3] as Long
                every { rs.getLong(4) } returns data[4] as Long
                every { rs.getString(5) } returns data[6] as String
                every { rs.getString(6) } returns data[7] as String
                every { rs.getString(7) } returns state
                listOf(secondArg<RowMapper<Any>>().mapRow(rs, 0))
            }
        }
        every { jdbc.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val args = secondArg<Array<out Any?>>()
            when {
                sql.startsWith("INSERT") -> { row = args; state = "prepared" }
                sql.contains("state='installing'") -> state = "installing"
                sql.contains("state='installed'") -> state = "installed"
                sql.contains("state='failed'") -> state = "failed"
            }
            1
        }
        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().hasSystemd } returns false
        every { anyConstructed<HostConnection>().commandFileExists(any()) } answers { remote.containsKey(firstArg<String>()) }
        every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } answers { remote[firstArg()] = secondArg() }
        every { anyConstructed<HostConnection>().command(any<List<String>>()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            when {
                args.first() == "cat" && args[1].endsWith(".vkey") -> """{"cborHex":"5860${if (matchingKey) "01".repeat(96) else "02".repeat(96)}"}"""
                args.first() == "cat" -> remote.getValue(args[1])
                args.first() == "sha256sum" -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(remote.getValue(args[1]).toByteArray()))
                args.first() == "id" -> "operator"
                args.first() == "stat" -> permissions
                args.first() == "cp" -> { remote[args.last()] = remote.getValue(args[args.size - 2]); "" }
                args.first() == "mv" -> { if (failMove) throw IllegalStateException("write failed"); remote[args.last()] = remote.remove(args[args.size - 2])!!; "" }
                args.first() == "rm" -> { args.drop(2).forEach { remote.remove(it) }; "" }
                else -> ""
            }
        }
    }

    @Test fun `invalid derived key cannot install or replay`() {
        setup()
        try {
            matchingKey = false
            val error = assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }
            assertThat(error.message).contains("does not match")
            assertThat(error.disposition).isEqualTo("retry")
            assertThat(commands.none { it.first() == "mv" }).isTrue()
            assertThat(assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }.disposition).isEqualTo("wait")
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test fun `bad permissions after writing report wait and never replay or restart`() {
        setup()
        try {
            permissions = "operator:644"
            val error = assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }
            assertThat(error.message).contains("permissions")
            assertThat(error.disposition).isEqualTo("wait")
            val count = commands.size
            assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }
            assertThat(commands).hasSize(count)
            assertThat(commands.none { "restart" in it }).isTrue()
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test fun `remote write failure is not replayed`() {
        setup()
        try {
            failMove = true
            assertThat(assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }.disposition).isEqualTo("wait")
            val count = commands.size
            assertThrows<LeiosInstallationService.InstallationFailure> { service.install(request) }
            assertThat(commands).hasSize(count)
            assertThat(commands.none { "restart" in it }).isTrue()
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test fun `installed result is durable and cannot be reused for another key`() {
        setup()
        try {
            val result = service.install(request)
            assertThat(result.loadedFingerprint).isNull()
            val count = commands.size
            assertThat(service.install(request)).isEqualTo(result)
            assertThat(commands).hasSize(count)
            assertThrows<IllegalArgumentException> { service.install(LeiosInstallRequest("request", request.target, 8, "password")) }
        } finally { unmockkConstructor(HostConnection::class) }
    }
    @Test fun `historical previews cannot authorize an installation`() {
        setup()
        try {
            row = arrayOf("old", "request", 2L, 1L, 7L, "fingerprint", "public", "old review", Instant.now(), "previewed")
            state = "previewed"
            assertThrows<IllegalArgumentException> { service.install(request) }
            assertThat(commands).isEmpty()
        } finally { unmockkConstructor(HostConnection::class) }
    }
}
