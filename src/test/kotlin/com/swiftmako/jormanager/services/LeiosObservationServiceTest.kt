package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.PoolKeyObservation
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.PoolKeyObservationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.math.BigDecimal
import java.util.Optional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class LeiosObservationServiceTest {
    private val eras = mockk<CardanoRepository>()
    private val files = mockk<FileRepository>()
    private val observations = mockk<PoolKeyObservationRepository>()
    private val nodes = mockk<NodeRepository>()
    private val hosts = mockk<HostRepository>()
    private val service = LeiosObservationService(eras, files, observations, nodes, hosts, ObjectMapper())

    private val coreHost = Host(
        id = 1,
        type = "remote",
        cardanoCliPath = "/bin/cardano-cli",
        cardanoNodePath = "/bin/cardano-node",
        hostname = "core.example",
        sshUser = "operator",
        nodeHomePath = "/nodes",
        jcliPath = null,
    )
    private val coreNode = Node(
        id = 2,
        hostId = 1,
        color = "",
        type = "core",
        processorThreads = 2,
        name = "core",
        listen = "127.0.0.1",
        port = 3001,
        promPort = 12798,
        genesisByronFileId = 1,
        genesisShelleyFileId = 2,
        genesisAlonzoFileId = 3,
        genesisConwayFileId = 4,
        configFileId = 5,
        poolId = "aa".repeat(28),
        isDefault = false,
    )
    private val childPoolId = "bb".repeat(28)
    private val childPool = coreNode.copy(
        id = 3,
        parentId = 2,
        type = "pool",
        name = "child",
        poolId = childPoolId,
        isDefault = false,
    )

    private val commands = mutableListOf<List<String>>()
    private val currentBlsHex = "11".repeat(96)
    private val futureBlsHex = "22".repeat(96)
    private val tipHashHex = "33".repeat(32)

    @BeforeEach
    fun setUp() {
        commands.clear()
        every { hosts.findById(1) } returns Optional.of(coreHost)
        every { nodes.findById(2) } returns Optional.of(coreNode)
        every { files.findById(2) } returns Optional.of(
            File(2, "shelley-genesis.json", """{"networkId":"Testnet","networkMagic":42}"""),
        )
        every { eras.networkKey(coreNode) } returns "network"
        every { eras.networkKey(childPool) } returns "network"
        every { eras.evidence(coreNode) } returns CardanoRepository.EraEvidence(
            networkKey = "network",
            era = "dijkstra",
            freshness = "current",
            source = "host:1/node:2",
            generation = 7L,
        )
        every { eras.evidence(childPool) } throws AssertionError("Bulk child pool has no socket or era evidence")
        every { observations.save(any<PoolKeyObservation>()) } answers { firstArg() }

        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().command(any<List<String>>()) } answers {
            val args = firstArg<List<String>>()
            commands += args
            when {
                args.contains("tip") -> """{"slot":1234,"hash":"$tipHashHex"}"""
                args.contains("pool-state") ->
                    """
                    {
                      "$childPoolId": {
                        "blsKey": {"bksKey": {"blsPubKey": "$currentBlsHex"}, "bksRegisteredIn": 12},
                        "retiring": null,
                        "futureStakePoolParams": {"blsKey": {"blsPubKey": "$futureBlsHex"}}
                      }
                    }
                    """.trimIndent()
                args.contains("stake-snapshot") -> """{"pools":{},"total":{}}"""
                args.contains("leios-committee") -> """{"currentLeiosCommittee":[],"futureLeiosCommittee":[]}"""
                else -> error("Unexpected CLI command: $args")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(HostConnection::class)
    }

    @Test
    fun `refresh for installed bulk child pool queries parent core node socket and era evidence`() {
        val observed = service.refresh(coreHost, childPool)

        assertThat(observed).isNotNull()
        assertThat(observed!!.poolId).isEqualTo(childPoolId)
        assertThat(observed.networkKey).isEqualTo("network")
        assertThat(observed.chainSlot).isEqualTo(BigDecimal(1234))
        assertThat(observed.effectiveEpoch).isEqualTo(BigDecimal(12))
        assertThat(observed.selected).isFalse()
        assertThat(observed.currentPublicKey).isEqualTo(ByteArray(96) { 0x11 })
        assertThat(observed.futurePublicKey).isEqualTo(ByteArray(96) { 0x22 })

        assertThat(commands).isNotEmpty()
        assertThat(commands.all { "/nodes/core/db/socket" in it }).isTrue()
        assertThat(commands.none { "/nodes/child/db/socket" in it }).isTrue()
        assertThat(commands.any { it.contains("pool-state") && it.contains(childPoolId) }).isTrue()
    }

    @Test
    fun `refresh for bulk child pool falls back to default node on another host when parentId is null`() {
        val relayHost = coreHost.copy(id = 9, hostname = "relay.example", nodeHomePath = "/relays")
        val relayNode = coreNode.copy(id = 10, hostId = 9, type = "relay", name = "relay1", poolId = null, isDefault = true)
        val orphanChild = childPool.copy(id = 4, parentId = null)

        every { nodes.findDefault() } returns relayNode
        every { hosts.findById(9) } returns Optional.of(relayHost)
        every { eras.networkKey(orphanChild) } returns "network"
        every { eras.networkKey(relayNode) } returns "network"
        every { eras.evidence(relayNode) } returns CardanoRepository.EraEvidence(
            networkKey = "network",
            era = "dijkstra",
            freshness = "current",
            source = "host:9/node:10",
            generation = 3L,
        )

        val observed = service.refresh(coreHost, orphanChild)

        assertThat(observed).isNotNull()
        assertThat(observed!!.poolId).isEqualTo(childPoolId)
        assertThat(commands).isNotEmpty()
        assertThat(commands.all { "/relays/relay1/db/socket" in it }).isTrue()
    }

    @Test
    fun `refresh returns null when bulk child pool parent era evidence is unavailable`() {
        every { eras.evidence(coreNode) } returns CardanoRepository.EraEvidence(
            networkKey = "network",
            era = null,
            freshness = "unavailable",
            source = "host:1/node:2",
        )

        val observed = service.refresh(coreHost, childPool)

        assertThat(observed).isNull()
        assertThat(commands).isEmpty()
    }
}
