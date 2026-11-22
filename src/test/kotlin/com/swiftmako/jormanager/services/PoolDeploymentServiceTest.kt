package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.NodeController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.FileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

class PoolDeploymentServiceTest {
    private val json = JsonMapper.builder().build()
    private val files = mockk<FileRepository>()
    private val configs = mockk<NodeConfigurationFiles>()
    private val wallet = mockk<WalletUtils>()
    private val nodeController = mockk<NodeController>()
    private val service = PoolDeploymentService(files, configs, wallet, nodeController, json)

    private val host =
        Host(
            id = 3L,
            type = "remote",
            cardanoCliPath = "/bin/cardano-cli",
            cardanoNodePath = "/bin/cardano-node",
            hostname = "example",
            sshUser = "operator",
            nodeHomePath = "/nodes",
            jcliPath = null,
        )

    private val parentNode =
        Node(
            id = 2L,
            hostId = 3L,
            color = "#000000",
            type = "core",
            processorThreads = 2,
            name = "parent",
            listen = "0.0.0.0",
            port = 3001,
            promPort = 12798,
            genesisByronFileId = 1L,
            genesisShelleyFileId = 2L,
            genesisAlonzoFileId = 3L,
            genesisConwayFileId = 4L,
            configFileId = 5L,
            isDefault = false,
            kesSKeyId = 16L,
            vrfSKeyId = 14L,
            opcertId = 18L,
        )

    private val shelleyGenesisJson =
        """{"networkId":"testnet","networkMagic":42,"slotsPerKESPeriod":129600,"maxKESEvolutions":62,"slotLength":1}"""

    private val validBlsEnvelope =
        """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"BLS Signing Key","cborHex":"5820${"ab".repeat(32)}"}"""

    private val defaultAssets =
        mapOf(
            "byron-genesis.json" to "{}".toByteArray(),
            "shelley-genesis.json" to shelleyGenesisJson.toByteArray(),
            "alonzo-genesis.json" to "{}".toByteArray(),
            "conway-genesis.json" to "{}".toByteArray(),
            "config.json" to "{}".toByteArray(),
            "topology.json" to "{}".toByteArray(),
        )

    private fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private fun coreSpec(name: String = "core1") =
        PoolDeploymentService.TargetSpec(
            host = host,
            parent = null,
            name = name,
            type = "core",
            processorThreads = 2,
            listen = "0.0.0.0",
            port = 3001,
            promPort = 12798,
            tracingHost = "127.0.0.1",
            tracingPort = 12799,
            assets = defaultAssets,
            config = "{}",
        )

    private fun poolSpec(name: String = "pool1") =
        PoolDeploymentService.TargetSpec(
            host = host,
            parent = parentNode,
            name = name,
            type = "pool",
            processorThreads = 2,
            listen = "0.0.0.0",
            port = 3001,
            promPort = 12798,
            tracingHost = "127.0.0.1",
            tracingPort = 12799,
            assets = defaultAssets,
            config = "{}",
        )

    private fun setupHostMocks(
        remote: MutableMap<String, String>,
        commands: MutableList<List<String>>,
        systemd: Boolean = true,
        failRestart: Boolean = false,
    ) {
        every { configs.validate(any()) } returns Unit
        every { configs.renderTopology(any()) } returns "{}"
        every { wallet.isValidSpendingPassword("password") } returns true
        every { wallet.getSKeyContent(any(), "password") } answers { firstArg<File>().content }
        every { nodeController.renderEnvContent(any(), any(), any(), any(), any(), any(), any()) } returns "ENV_CONTENT"
        every { nodeController.renderSystemdContent(any(), any(), any(), any(), any(), any()) } answers {
            val nodeName = thirdArg<String>()
            "User=operator\nExecStart=/bin/cardano-node run \\\n  --config \${CONFIG} \\\n  --shelley-kes-key /nodes/$nodeName/$nodeName.kes.skey \\\n  --shelley-vrf-key /nodes/$nodeName/$nodeName.vrf.skey \\\n  --shelley-operational-certificate /nodes/$nodeName/$nodeName.node.opcert"
        }

        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().hasSystemd } returns systemd
        every { anyConstructed<HostConnection>().commandFileExists(any()) } answers {
            val target = firstArg<String>()
            remote.containsKey(target) || remote.keys.any { it.startsWith("$target/") }
        }
        every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { remote.getValue(firstArg()) }
        every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
            remote[firstArg()] = secondArg()
            ""
        }
        every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } answers {
            remote[firstArg()] = secondArg()
        }
        every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            when (args.first()) {
                "id" -> "operator"
                "ss" -> ""
                "mkdir" -> {
                    remote["${args[1]}/.dir"] = ""
                    ""
                }
                "sha256sum" -> sha256(remote.getValue(args[1]))
                "stat" -> "operator:600"
                "cp" -> {
                    remote[args.last()] = remote.getValue(args[args.size - 2])
                    ""
                }
                "install" -> {
                    remote[args.last()] = remote.getValue(args[args.size - 2])
                    ""
                }
                "rm" -> {
                    val recursive = "-rf" in args
                    args.filter { !it.startsWith("-") && it != "rm" }.forEach { path ->
                        remote.remove(path)
                        if (recursive) {
                            remote.keys
                                .filter { it.startsWith("$path/") }
                                .toList()
                                .forEach(remote::remove)
                        }
                    }
                    ""
                }
                else -> ""
            }
        }
        every { anyConstructed<HostConnection>().sudoCommand(any<List<String>>(), any()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            when {
                args.first() == "cp" -> remote[args.last()] = remote.getValue(args[args.size - 2])
                args.first() == "install" -> remote[args.last()] = remote.getValue(args[args.size - 2])
                args.first() == "rm" -> args.filter { !it.startsWith("-") && it != "rm" }.forEach(remote::remove)
                failRestart && args == listOf("systemctl", "restart", "${args.last()}") && !commands.any { it.contains("stop") } -> {
                    throw IllegalStateException("systemctl restart failed")
                }
            }
            ""
        }
    }

    @Test
    fun `core deployment installs credentials systemd service and kes expiry in conway and dijkstra eras`() {
        for (era in listOf("conway", "dijkstra")) {
            val remote = mutableMapOf<String, String>()
            val commands = mutableListOf<List<String>>()
            setupHostMocks(remote, commands)
            try {
                val blsPublic = if (era == "dijkstra") ByteArray(96) { 7 } else null
                val beforeSec = java.time.Instant
                    .now()
                    .epochSecond
                val result =
                    service.deploy(
                        spec = coreSpec(),
                        credentials =
                            PoolDeploymentService.CredentialMaterial(
                                kesSKey = """{"cborHex":"kes"}""",
                                vrfSKey = """{"cborHex":"vrf"}""",
                                opcert = """{"cborHex":"opcert"}""",
                                blsSKey = if (era == "dijkstra") validBlsEnvelope else null,
                                blsPublicKey = blsPublic,
                            ),
                        spendingPassword = "password",
                        sudoPassword = "sudo",
                    )
                val afterSec = java.time.Instant
                    .now()
                    .epochSecond

                assertThat(remote["/nodes/core1/core1.kes.skey"]).isEqualTo("""{"cborHex":"kes"}""")
                assertThat(remote["/nodes/core1/core1.vrf.skey"]).isEqualTo("""{"cborHex":"vrf"}""")
                assertThat(remote["/nodes/core1/core1.node.opcert"]).isEqualTo("""{"cborHex":"opcert"}""")
                val unit = remote.getValue("/etc/systemd/system/core1-node.service")
                if (era == "dijkstra") {
                    assertThat(remote["/nodes/core1/core1.bls.skey"]).isEqualTo(validBlsEnvelope)
                    assertThat(unit).contains("--shelley-bls-key /nodes/core1/core1.bls.skey")
                    assertThat(result.installedFingerprint).isNotNull()
                } else {
                    assertThat(remote.containsKey("/nodes/core1/core1.bls.skey")).isFalse()
                    assertThat(unit).doesNotContain("--shelley-bls-key")
                    assertThat(result.installedFingerprint).isNull()
                }
                assertThat(commands).contains(listOf("systemctl", "restart", "core1-node.service"))
                assertThat(commands).contains(listOf("systemctl", "enable", "core1-node.service"))
                val expectedDuration = 62L * 129_600L
                assertThat(result.kesExpireTimeSec).isAtLeast(beforeSec + expectedDuration)
                assertThat(result.kesExpireTimeSec).isAtMost(afterSec + expectedDuration)
                assertThat(result.kesExpireDate).contains("UTC")
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }
    }

    @Test
    fun `core deployment rolls back systemd unit and directory when service startup fails`() {
        val remote = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        setupHostMocks(remote, commands, failRestart = true)
        try {
            assertThrows<IllegalStateException> {
                service.deploy(
                    spec = coreSpec(),
                    credentials =
                        PoolDeploymentService.CredentialMaterial(
                            kesSKey = """{"cborHex":"kes"}""",
                            vrfSKey = """{"cborHex":"vrf"}""",
                            opcert = """{"cborHex":"opcert"}""",
                        ),
                    spendingPassword = "password",
                    sudoPassword = "sudo",
                )
            }
            assertThat(remote.keys.filter { it.startsWith("/nodes/core1") }).isEmpty()
            assertThat(remote.containsKey("/etc/systemd/system/core1-node.service")).isFalse()
            assertThat(commands).contains(listOf("systemctl", "stop", "core1-node.service"))
            assertThat(commands).contains(listOf("systemctl", "disable", "core1-node.service"))
            assertThat(commands).contains(listOf("rm", "-rf", "/nodes/core1"))
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `pool deployment converts singleton parent to bulk credentials and supports rollback on db failure`() {
        val remote = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        setupHostMocks(remote, commands)
        try {
            val parentKes = """{"cborHex":"parent-kes"}"""
            val parentVrf = """{"cborHex":"parent-vrf"}"""
            val parentOpcert = """{"cborHex":"parent-opcert"}"""
            val originalEnv = "CONFIG=/nodes/parent/config.json"
            val originalUnit =
                """
                User=operator
                ExecStart=/bin/cardano-node run \
                  --config ${'$'}{CONFIG} \
                  --shelley-kes-key /nodes/parent/parent.kes.skey \
                  --shelley-vrf-key /nodes/parent/parent.vrf.skey \
                  --shelley-bls-key /nodes/parent/parent.bls.skey \
                  --shelley-operational-certificate /nodes/parent/parent.node.opcert
                """.trimIndent()
            every { files.findById(16L) } returns Optional.of(File(16L, "kes", parentKes))
            every { files.findById(14L) } returns Optional.of(File(14L, "vrf", parentVrf))
            every { files.findById(18L) } returns Optional.of(File(18L, "opcert", parentOpcert))

            defaultAssets.forEach { (name, bytes) -> remote["/nodes/parent/$name"] = bytes.toString(Charsets.UTF_8) }
            remote["/nodes/parent/parent.kes.skey"] = parentKes
            remote["/nodes/parent/parent.vrf.skey"] = parentVrf
            remote["/nodes/parent/parent.node.opcert"] = parentOpcert
            remote["/nodes/parent/parent.bls.skey"] = validBlsEnvelope
            remote["/nodes/parent/env"] = originalEnv
            remote["/etc/systemd/system/parent-node.service"] = originalUnit

            val opId = UUID.randomUUID().toString()
            val childBls =
                """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"Child BLS","cborHex":"5820${"cd".repeat(32)}"}"""
            val result =
                service.deploy(
                    spec = poolSpec(),
                    credentials =
                        PoolDeploymentService.CredentialMaterial(
                            kesSKey = """{"cborHex":"child-kes"}""",
                            vrfSKey = """{"cborHex":"child-vrf"}""",
                            opcert = """{"cborHex":"child-opcert"}""",
                            blsSKey = childBls,
                            blsPublicKey = ByteArray(96) { 9 },
                        ),
                    spendingPassword = "password",
                    sudoPassword = "sudo",
                    operationId = opId,
                    deferBackupCleanup = true,
                )

            val bulk = json.readTree(remote.getValue("/nodes/parent/credentials.json"))
            assertThat(bulk.size()).isEqualTo(2)
            assertThat(bulk[0].size()).isEqualTo(4)
            assertThat(bulk[1].size()).isEqualTo(4)
            assertThat(remote.getValue("/nodes/parent/env")).contains("BULK_CREDENTIALS=/nodes/parent/credentials.json")
            assertThat(remote.getValue("/etc/systemd/system/parent-node.service")).contains("--bulk-credentials-file")

            // Simulate subsequent DB transaction failure triggering rollback + cleanup
            result.rollback()
            result.cleanup()

            assertThat(remote.containsKey("/nodes/parent/credentials.json")).isFalse()
            assertThat(remote.getValue("/nodes/parent/env")).isEqualTo(originalEnv)
            assertThat(remote.getValue("/etc/systemd/system/parent-node.service")).isEqualTo(originalUnit)
            assertThat(remote.keys.filter { it.contains(".jormanager-backup-") }).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `existing bulk credentials accept PR 6749 fourth slot forms`() {
        val validBls2 =
            """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"BLS Signing Key","cborHex":"5820${"CD".repeat(32)}"}"""
        val allowedForms =
            listOf(
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},null]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},[]]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},$validBlsEnvelope]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},[$validBlsEnvelope,$validBls2]]]""",
            )

        for (existingCredentials in allowedForms) {
            val remote = mutableMapOf<String, String>()
            val commands = mutableListOf<List<String>>()
            setupHostMocks(remote, commands)
            try {
                defaultAssets.forEach { (name, bytes) -> remote["/nodes/parent/$name"] = bytes.toString(Charsets.UTF_8) }
                remote["/etc/systemd/system/parent-node.service"] = "User=operator\n--bulk-credentials-file /nodes/parent/credentials.json"
                remote["/nodes/parent/credentials.json"] = existingCredentials
                remote["/nodes/parent/env"] = "env"

                service.deploy(
                    spec = poolSpec(),
                    credentials =
                        PoolDeploymentService.CredentialMaterial(
                            kesSKey = """{"cborHex":"new-kes"}""",
                            vrfSKey = """{"cborHex":"new-vrf"}""",
                            opcert = """{"cert":"new"}""",
                        ),
                    spendingPassword = "password",
                    sudoPassword = "sudo",
                )
                val updated = json.readTree(remote.getValue("/nodes/parent/credentials.json"))
                assertThat(updated.size()).isEqualTo(2)
                assertThat(updated[0]).isEqualTo(json.readTree(existingCredentials)[0])
                assertThat(updated[1][1].path("cborHex").asString()).isEqualTo("new-vrf")
                assertThat(remote.keys.filter { it.contains(".jormanager-backup-") }).isEmpty()
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }
    }

    @Test
    fun `existing bulk credentials reject malformed fourth slot cleanly before modifying host`() {
        val malformedForms =
            listOf(
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},"/nodes/parent/bls.skey"]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},{"type":"WrongKeyType","cborHex":"5820${"ab".repeat(32)}"}]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","cborHex":"deadbeef"}]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},[null]]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},[$validBlsEnvelope,{"type":"Bad","cborHex":"5820${"ab".repeat(32)}"}]]]""",
                """[[{"cert":"old"},{"cborHex":"old-vrf"},{"cborHex":"old-kes"},null,null]]""",
            )

        for (malformedCredentials in malformedForms) {
            val remote = mutableMapOf<String, String>()
            val commands = mutableListOf<List<String>>()
            setupHostMocks(remote, commands)
            try {
                defaultAssets.forEach { (name, bytes) -> remote["/nodes/parent/$name"] = bytes.toString(Charsets.UTF_8) }
                remote["/etc/systemd/system/parent-node.service"] = "User=operator\n--bulk-credentials-file /nodes/parent/credentials.json"
                remote["/nodes/parent/credentials.json"] = malformedCredentials
                remote["/nodes/parent/env"] = "env"

                assertThrows<IllegalArgumentException> {
                    service.deploy(
                        spec = poolSpec(),
                        credentials =
                            PoolDeploymentService.CredentialMaterial(
                                kesSKey = """{"cborHex":"new-kes"}""",
                                vrfSKey = """{"cborHex":"new-vrf"}""",
                                opcert = """{"cert":"new"}""",
                            ),
                        spendingPassword = "password",
                        sudoPassword = "sudo",
                    )
                }
                assertThat(remote.getValue("/nodes/parent/credentials.json")).isEqualTo(malformedCredentials)
                assertThat(commands.none { it.firstOrNull() == "install" || it.firstOrNull() == "cp" }).isTrue()
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }
    }
}
