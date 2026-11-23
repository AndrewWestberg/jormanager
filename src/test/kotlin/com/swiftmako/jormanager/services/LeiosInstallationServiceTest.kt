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
            if (data == null) {
                emptyList()
            } else {
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
                sql.startsWith("INSERT") -> {
                    row = args
                    state = "prepared"
                }
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
                args.first() == "cp" -> {
                    remote[args.last()] = remote.getValue(args[args.size - 2])
                    ""
                }
                args.first() == "mv" -> {
                    if (failMove) throw IllegalStateException("write failed")
                    remote[args.last()] = remote.remove(args[args.size - 2])!!
                    ""
                }
                args.first() == "rm" -> {
                    args.drop(2).forEach { remote.remove(it) }
                    ""
                }
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
        } finally {
            unmockkConstructor(HostConnection::class)
        }
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
        } finally {
            unmockkConstructor(HostConnection::class)
        }
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
        } finally {
            unmockkConstructor(HostConnection::class)
        }
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
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `historical previews cannot authorize an installation`() {
        setup()
        try {
            row = arrayOf<Any?>("old", "request", 2L, 1L, 7L, "fingerprint", "public", "old review", Instant.now(), "previewed")
            state = "previewed"
            assertThrows<IllegalArgumentException> { service.install(request) }
            assertThat(commands).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `fresh singleton install writes single BLS envelope object and configures shelley-bls-key`() {
        setup()
        try {
            service.install(request)
            val installedJson = ObjectMapper().readTree(remote.getValue("/nodes/core/leios-7.skey"))
            assertThat(installedJson.isObject).isTrue()
            assertThat(installedJson.path("cborHex").asString()).isEqualTo("5820${"01".repeat(32)}")
            assertThat(remote.getValue("/nodes/core/startNode.sh")).contains("--shelley-bls-key /nodes/core/leios-7.skey")
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `singleton rotation preserves active BLS key envelopes alongside rotated key in deduplicated array`() {
        setup()
        try {
            val mapper = ObjectMapper()
            val priorSecret = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","cborHex":"5820${"aa".repeat(32)}"}"""
            remote["/nodes/core/leios-5.skey"] = priorSecret
            remote["/nodes/core/startNode.sh"] = "--shelley-bls-key /nodes/core/leios-5.skey \\\n  --shelley-operational-certificate cert"

            service.install(request)

            // Prior key file remains untouched for rollback safety while new file contains [prior, rotated]
            assertThat(remote["/nodes/core/leios-5.skey"]).isEqualTo(priorSecret)
            val rotatedArray = mapper.readTree(remote.getValue("/nodes/core/leios-7.skey"))
            assertThat(rotatedArray.isArray).isTrue()
            assertThat(rotatedArray.size()).isEqualTo(2)
            assertThat(rotatedArray[0].path("cborHex").asString()).isEqualTo("5820${"aa".repeat(32)}")
            assertThat(rotatedArray[1].path("cborHex").asString()).isEqualTo("5820${"01".repeat(32)}")
            assertThat(remote.getValue("/nodes/core/startNode.sh")).contains("--shelley-bls-key /nodes/core/leios-7.skey")
            assertThat(remote.keys.any { it.startsWith("/nodes/core/startNode.sh.jormanager-backup-") }).isTrue()

            // Rotating a third key onto the existing 2-key array preserves all three in order
            row = null
            state = ""
            val thirdPublicKey = ByteArray(96) { 3 }
            val thirdKey = LeiosVotingKey(id = 9, networkKey = "network", poolId = "pool", publicKey = thirdPublicKey, proof = ByteArray(48), signingFileId = 10, preparedAt = Instant.now(), validationProvenance = "fixture")
            val thirdSecret = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","cborHex":"5820${"bb".repeat(32)}"}"""
            every { keys.findById(9) } returns Optional.of(thirdKey)
            every { files.findById(10) } returns Optional.of(File(10, "key-9", thirdSecret))
            every { wallet.getSKeyContent(match { it.id == 10L }, "password") } returns thirdSecret
            every { anyConstructed<HostConnection>().command(match<List<String>> { it.firstOrNull() == "cat" && it.getOrNull(1)?.endsWith(".vkey") == true }) } returns """{"cborHex":"5860${"03".repeat(96)}"}"""

            service.install(LeiosInstallRequest("request-3", request.target, 9, "password"))

            val chainedArray = mapper.readTree(remote.getValue("/nodes/core/leios-9.skey"))
            assertThat(chainedArray.isArray).isTrue()
            assertThat(chainedArray.size()).isEqualTo(3)
            assertThat((0 until chainedArray.size()).map { chainedArray[it].path("cborHex").asString() })
                .containsExactly(
                    "5820${"aa".repeat(32)}",
                    "5820${"01".repeat(32)}",
                    "5820${"bb".repeat(32)}",
                ).inOrder()
            assertThat(remote.getValue("/nodes/core/startNode.sh")).contains("--shelley-bls-key /nodes/core/leios-9.skey")
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `singleton idempotent re-install deduplicates by cborHex and preserves single object or active array`() {
        setup()
        try {
            val mapper = ObjectMapper()
            // Initial single-key install followed by re-install with a new requestId keeps single object
            service.install(request)
            row = null
            state = ""
            service.install(LeiosInstallRequest("request-reinstall-single", request.target, request.keyId, request.spendingPassword))
            val singleJson = mapper.readTree(remote.getValue("/nodes/core/leios-7.skey"))
            assertThat(singleJson.isObject).isTrue()
            assertThat(singleJson.path("cborHex").asString()).isEqualTo("5820${"01".repeat(32)}")
            assertThat(remote.keys.any { it.startsWith("/nodes/core/leios-7.skey.jormanager-backup-") }).isTrue()

            // Re-installing rotated key when [prior, current] array is active keeps 2-key array without duplicates even when description and hex case differ
            val priorEnvelope = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Prior CLI key","cborHex":"5820${"AA".repeat(32)}"}"""
            val currentUpperEnvelope = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Different description","cborHex":"5820${"01".repeat(32).uppercase()}"}"""
            remote["/nodes/core/leios-7.skey"] = "[$priorEnvelope,$currentUpperEnvelope]"
            row = null
            state = ""
            service.install(LeiosInstallRequest("request-reinstall-array", request.target, request.keyId, request.spendingPassword))
            val dedupedArray = mapper.readTree(remote.getValue("/nodes/core/leios-7.skey"))
            assertThat(dedupedArray.isArray).isTrue()
            assertThat(dedupedArray.size()).isEqualTo(2)
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `singleton install rejects malformed or empty existing shelley-bls-key file before writing`() {
        setup()
        try {
            remote["/nodes/core/startNode.sh"] = "--shelley-bls-key /nodes/core/leios-5.skey \\\n  --shelley-operational-certificate cert"
            for (badExisting in listOf("[]", """{"type":"WrongType","cborHex":"5820${"aa".repeat(32)}"}""")) {
                row = null
                state = ""
                commands.clear()
                remote["/nodes/core/leios-5.skey"] = badExisting
                val error = assertThrows<LeiosInstallationService.InstallationFailure> {
                    service.install(LeiosInstallRequest("bad-${badExisting.hashCode()}", request.target, request.keyId, request.spendingPassword))
                }
                assertThat(error.disposition).isEqualTo("retry")
                assertThat(remote.containsKey("/nodes/core/leios-7.skey")).isFalse()
            }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test fun `bulk child pool installs and rotates BLS keys across PR 6749 shapes using parent producer era evidence`() {
        setup()
        try {
            val mapper = ObjectMapper()
            val childPool = node.copy(
                id = 3,
                parentId = 2,
                type = "pool",
                name = "child",
                poolId = "child-pool",
                vrfSKeyId = 12,
            )
            val childKey1 = LeiosVotingKey(
                id = 17,
                networkKey = "network",
                poolId = "child-pool",
                publicKey = ByteArray(96) { 1 },
                proof = ByteArray(48),
                signingFileId = 8,
                preparedAt = Instant.now(),
                validationProvenance = "fixture",
            )
            val childKey2 = LeiosVotingKey(
                id = 18,
                networkKey = "network",
                poolId = "child-pool",
                publicKey = ByteArray(96) { 5 },
                proof = ByteArray(48),
                signingFileId = 180,
                preparedAt = Instant.now(),
                validationProvenance = "fixture",
            )
            val childKey3 = LeiosVotingKey(
                id = 19,
                networkKey = "network",
                poolId = "child-pool",
                publicKey = ByteArray(96) { 6 },
                proof = ByteArray(48),
                signingFileId = 190,
                preparedAt = Instant.now(),
                validationProvenance = "fixture",
            )
            val childVrf = """{"type":"VrfSigningKey_PraosVRF","cborHex":"5840${"cc".repeat(64)}"}"""
            val parentVrf = """{"type":"VrfSigningKey_PraosVRF","cborHex":"5840${"dd".repeat(64)}"}"""
            val opcert = """{"type":"NodeOperationalCertificate","cborHex":"8200"}"""
            val kes = """{"type":"KesSigningKey_ed25519_kes_2^6","cborHex":"5840${"ee".repeat(64)}"}"""
            val parentBls = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","cborHex":"5820${"ff".repeat(32)}"}"""
            val secret2 = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Key 2","cborHex":"5820${"05".repeat(32)}"}"""
            val secret3 = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Key 3","cborHex":"5820${"06".repeat(32)}"}"""
            val parentEntry = "[$opcert,$parentVrf,$kes,$parentBls]"

            every { nodes.findById(3) } returns Optional.of(childPool)
            every { eras.networkKey(childPool) } returns "network"
            every { eras.evidence(childPool) } throws AssertionError("Bulk child pool has no socket; must check producer era evidence")
            every { keys.findById(17) } returns Optional.of(childKey1)
            every { keys.findById(18) } returns Optional.of(childKey2)
            every { keys.findById(19) } returns Optional.of(childKey3)
            every { files.findById(12) } returns Optional.of(File(12, "child.vrf.skey", childVrf))
            every { files.findById(180) } returns Optional.of(File(180, "key-18", secret2))
            every { files.findById(190) } returns Optional.of(File(190, "key-19", secret3))
            every { wallet.getSKeyContent(match { it.id == 12L }, "password") } returns childVrf
            every { wallet.getSKeyContent(match { it.id == 180L }, "password") } returns secret2
            every { wallet.getSKeyContent(match { it.id == 190L }, "password") } returns secret3

            remote["/nodes/core/startNode.sh"] = "--config /nodes/core/config.json --bulk-credentials-file /nodes/core/credentials.json"
            val childTarget = LeiosController.Target(1, 3, "child-pool", "network")

            // 1. PR #6749 initial shapes: 3-tuple, null, and empty array [] all populate entry[3] cleanly as a single envelope
            for ((label, initialChildEntry) in listOf(
                "3-tuple" to "[$opcert,$childVrf,$kes]",
                "null" to "[$opcert,$childVrf,$kes,null]",
                "empty-array" to "[$opcert,$childVrf,$kes,[]]",
            )) {
                row = null
                state = ""
                remote["/nodes/core/credentials.json"] = "[$parentEntry,$initialChildEntry]"
                val result = service.install(LeiosInstallRequest("bulk-init-$label", childTarget, 17, "password"))
                assertThat(result.state).isEqualTo("installed")

                val updated = mapper.readTree(remote.getValue("/nodes/core/credentials.json"))
                assertThat(updated.size()).isEqualTo(2)
                // Parent entry in credentials.json is untouched
                assertThat(updated[0][3].path("cborHex").asString()).isEqualTo("5820${"ff".repeat(32)}")
                // Child entry[3] is a single BLS envelope object (no empty entries left behind)
                assertThat(updated[1].size()).isEqualTo(4)
                assertThat(updated[1][3].isObject).isTrue()
                assertThat(updated[1][3].path("cborHex").asString()).isEqualTo("5820${"01".repeat(32)}")
            }

            // 2. Single-envelope deduplication by cborHex (even when description and hex case differ)
            val key1DifferentDesc = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"CLI generated","cborHex":"5820${"01".repeat(32).uppercase()}"}"""
            remote["/nodes/core/credentials.json"] = "[$parentEntry,[$opcert,$childVrf,$kes,$key1DifferentDesc]]"
            row = null
            state = ""
            service.install(LeiosInstallRequest("bulk-dedup-single", childTarget, 17, "password"))
            val afterSingleDedup = mapper.readTree(remote.getValue("/nodes/core/credentials.json"))
            assertThat(afterSingleDedup[1][3].isObject).isTrue()
            assertThat(afterSingleDedup[1][3].path("cborHex").asString().lowercase()).isEqualTo("5820${"01".repeat(32)}")

            // 3. Rotate from single envelope -> 2-key array [key1, key2]
            var activePublicByte = 5
            every {
                anyConstructed<HostConnection>().command(match<List<String>> { it.firstOrNull() == "cat" && it.getOrNull(1)?.endsWith(".vkey") == true })
            } answers { """{"cborHex":"5860${"%02x".format(activePublicByte).repeat(96)}"}""" }
            row = null
            state = ""
            service.install(LeiosInstallRequest("bulk-rotate-2", childTarget, 18, "password"))
            val afterRotate2 = mapper.readTree(remote.getValue("/nodes/core/credentials.json"))
            assertThat(afterRotate2[1][3].isArray).isTrue()
            assertThat((0 until afterRotate2[1][3].size()).map { afterRotate2[1][3][it].path("cborHex").asString().lowercase() })
                .containsExactly(
                    "5820${"01".repeat(32)}",
                    "5820${"05".repeat(32)}",
                ).inOrder()

            // 4. Re-installing key2 with different description against envelope array does not duplicate
            val key2AltDesc = """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Alt description","cborHex":"5820${"05".repeat(32).uppercase()}"}"""
            remote["/nodes/core/credentials.json"] = "[$parentEntry,[$opcert,$childVrf,$kes,[$key1DifferentDesc,$key2AltDesc]]]"
            row = null
            state = ""
            service.install(LeiosInstallRequest("bulk-dedup-array", childTarget, 18, "password"))
            val afterArrayDedup = mapper.readTree(remote.getValue("/nodes/core/credentials.json"))
            assertThat(afterArrayDedup[1][3].isArray).isTrue()
            assertThat(afterArrayDedup[1][3].size()).isEqualTo(2)

            // 5. Rotate from 2-key array -> 3-key array [key1, key2, key3]
            activePublicByte = 6
            row = null
            state = ""
            service.install(LeiosInstallRequest("bulk-rotate-3", childTarget, 19, "password"))
            val afterRotate3 = mapper.readTree(remote.getValue("/nodes/core/credentials.json"))
            assertThat(afterRotate3[1][3].isArray).isTrue()
            assertThat((0 until afterRotate3[1][3].size()).map { afterRotate3[1][3][it].path("cborHex").asString().lowercase() })
                .containsExactly(
                    "5820${"01".repeat(32)}",
                    "5820${"05".repeat(32)}",
                    "5820${"06".repeat(32)}",
                ).inOrder()
            assertThat(remote.keys.any { it.startsWith("/nodes/core/credentials.json.jormanager-backup-") }).isTrue()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
