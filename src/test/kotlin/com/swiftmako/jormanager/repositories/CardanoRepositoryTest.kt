package com.swiftmako.jormanager.repositories

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Optional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardanoRepositoryTest {
    private val moshi = Configuration().getMoshi()
    private val files = mockk<FileRepository>()
    private val repository = CardanoRepository(
        moshi.adapter(QueryTip::class.java),
        moshi.adapter(GenesisShelley::class.java),
        moshi.adapter(Config::class.java),
        files,
    )

    private val host = Host(
        id = 1,
        type = "remote",
        cardanoCliPath = "/bin/cardano-cli",
        cardanoNodePath = "/bin/cardano-node",
        hostname = "example",
        sshUser = "operator",
        nodeHomePath = "/nodes",
        jcliPath = null,
    )
    private val node = Node(
        id = 2,
        hostId = 1,
        color = "",
        type = "core",
        processorThreads = 1,
        name = "core",
        listen = "127.0.0.1",
        port = 3001,
        promPort = 12798,
        genesisByronFileId = 1,
        genesisShelleyFileId = 2,
        genesisAlonzoFileId = 3,
        genesisConwayFileId = 4,
        configFileId = 5,
        poolId = "pool",
        isDefault = true,
    )

    private fun stubNodeFiles() {
        val hash = "a".repeat(64)
        every { files.findById(2) } returns Optional.of(
            File(
                id = 2,
                name = "shelley-genesis.json",
                content = """{
                    "activeSlotsCoeff":0.05,
                    "networkId":"Testnet",
                    "networkMagic":164,
                    "slotLength":1,
                    "epochLength":432000,
                    "slotsPerKESPeriod":129600,
                    "systemStart":"2026-10-18T00:00:00Z",
                    "maxKESEvolutions":62
                }""",
            ),
        )
        every { files.findById(5) } returns Optional.of(
            File(
                id = 5,
                name = "config.json",
                content = """{
                    "RequiresNetworkMagic":"RequiresMagic",
                    "ByronGenesisHash":"$hash",
                    "ShelleyGenesisHash":"$hash",
                    "AlonzoGenesisHash":"$hash",
                    "ConwayGenesisHash":"$hash",
                    "DijkstraGenesisHash":"$hash"
                }""",
            ),
        )
    }

    @Test
    fun `getEra invokes cardano-cli latest query tip first without blank arguments`() {
        stubNodeFiles()
        val invocations = mutableListOf<List<String>>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                invocations += args
                """{"era":"Dijkstra","slot":1234,"hash":"${"b".repeat(64)}","epoch":5,"block":42}"""
            }

            val era = repository.getEra(host, node, "--testnet-magic 164", "--socket-path /nodes/core/db/socket")
            assertThat(era).isEqualTo("dijkstra")
            assertThat(invocations).containsExactly(
                listOf(
                    "/bin/cardano-cli",
                    "latest",
                    "query",
                    "tip",
                    "--testnet-magic",
                    "164",
                    "--socket-path",
                    "/nodes/core/db/socket",
                    "--output-json",
                ),
            )
            val evidence = repository.evidence(node)
            assertThat(evidence.freshness).isEqualTo("current")
            assertThat(evidence.era).isEqualTo("dijkstra")
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `getEra falls back to legacy query tip when latest query tip fails and marks unavailable if both fail`() {
        stubNodeFiles()
        val invocations = mutableListOf<List<String>>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                invocations += args
                if (args.getOrNull(1) == "latest") {
                    throw RuntimeException("Invalid argument `latest'")
                }
                """{"era":"Conway","slot":999,"hash":"${"c".repeat(64)}","epoch":2,"block":10}"""
            }

            val era = repository.getEra(host, node, "--testnet-magic 164", "--socket-path /nodes/core/db/socket")
            assertThat(era).isEqualTo("conway")
            assertThat(invocations).hasSize(2)
            assertThat(invocations[0].subList(0, 4)).isEqualTo(listOf("/bin/cardano-cli", "latest", "query", "tip"))
            assertThat(invocations[1].subList(0, 3)).isEqualTo(listOf("/bin/cardano-cli", "query", "tip"))
            assertThat(repository.evidence(node).freshness).isEqualTo("current")

            every { anyConstructed<HostConnection>().commandArgs(any()) } throws RuntimeException("socket unavailable")
            assertThrows<RuntimeException> {
                repository.getEra(host, node, "--testnet-magic 164", "--socket-path /nodes/core/db/socket")
            }
            assertThat(repository.evidence(node).freshness).isEqualTo("unavailable")
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
