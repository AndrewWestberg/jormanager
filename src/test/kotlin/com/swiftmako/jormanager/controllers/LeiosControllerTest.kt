package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.PoolKeyObservation
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.PoolKeyObservationRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.LeiosInstallationService
import com.swiftmako.jormanager.services.LeiosKeyService
import com.swiftmako.jormanager.services.LeiosObservationService
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeiosControllerTest {
    private val hosts = mockk<HostRepository>()
    private val nodes = mockk<NodeRepository>()
    private val eras = mockk<CardanoRepository>()
    private val keys = mockk<LeiosKeyRepository>()
    private val observations = mockk<PoolKeyObservationRepository>()
    private val keyService = mockk<LeiosKeyService>()
    private val observationService = mockk<LeiosObservationService>()
    private val installationService = mockk<LeiosInstallationService>()
    private val installations = mockk<KeyInstallationRepository>()
    private val chainCoordinator = mockk<ChainIngestionCoordinator>()

    private val controller = LeiosController(
        hosts,
        nodes,
        eras,
        keys,
        observations,
        keyService,
        observationService,
        installationService,
        installations,
        chainCoordinator,
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
        isDefault = false,
    )
    private val keyOne = LeiosVotingKey(
        id = 10,
        networkKey = "network",
        poolId = "pool",
        publicKey = ByteArray(96) { 1 },
        proof = ByteArray(48) { 2 },
        signingFileId = 100,
        preparedAt = Instant.parse("2026-11-01T00:00:00Z"),
        validationProvenance = "fixture",
    )
    private val keyTwo = LeiosVotingKey(
        id = 20,
        networkKey = "network",
        poolId = "pool",
        publicKey = ByteArray(96) { 3 },
        proof = ByteArray(48) { 4 },
        signingFileId = 200,
        preparedAt = Instant.parse("2026-11-02T00:00:00Z"),
        validationProvenance = "fixture",
    )
    private val liveObservation = PoolKeyObservation(
        id = 1,
        networkKey = "network",
        poolId = "pool",
        observedAt = Instant.parse("2026-11-20T00:00:00Z"),
        chainSlot = BigDecimal.TEN,
        chainHash = ByteArray(32) { 9 },
        currentPublicKey = keyOne.publicKey,
        effectiveEpoch = BigDecimal.ONE,
    )
    private val statusRequest = LeiosController.StatusRequest("req-1", LeiosController.Target(1, 2, "pool", "network"))

    @BeforeEach
    fun setUp() {
        every { hosts.findById(1) } returns Optional.of(host)
        every { nodes.findById(2) } returns Optional.of(node)
        every { eras.networkKey(node) } returns "network"
        every { eras.evidence(node) } returns CardanoRepository.EraEvidence("network", "dijkstra", "current", "host:1/node:2")
        every { chainCoordinator.status("network") } returns mockk<ChainReadState>(relaxed = true) {
            every { lastCompletePoint } returns null
        }
        every { observations.findFirstByNetworkKeyAndPoolIdOrderByObservedAtDesc("network", "pool") } returns liveObservation
    }

    @Test
    fun `latest healthy installed key reports stale false when observation refresh succeeds`() {
        every { observationService.refresh(host, node) } returns liveObservation
        every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns listOf(keyOne)
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(2, 1) } returns listOf(
            KeyInstallation(
                id = 50,
                keyId = 10,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-1",
                installedFingerprint = "fp-10",
                loadedFingerprint = null,
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T00:00:00Z"),
            ),
        )

        val response = controller.status(statusRequest) as SocketResponse.Success<LeiosController.Status>
        val installation = requireNotNull(
            response.data.keys
                .single { it.keyId == 10L }
                .installation
        )
        assertThat(installation.state).isEqualTo("installed")
        assertThat(installation.stale).isFalse()
    }

    @Test
    fun `superseded installation reports stale true while latest active installation reports stale false`() {
        every { observationService.refresh(host, node) } returns liveObservation
        every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns listOf(keyOne, keyTwo)
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(2, 1) } returns listOf(
            KeyInstallation(
                id = 52,
                keyId = 20,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-2",
                installedFingerprint = "fp-20",
                loadedFingerprint = "fp-20",
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T01:00:00Z"),
            ),
            KeyInstallation(
                id = 51,
                keyId = 10,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-1",
                installedFingerprint = "fp-10",
                loadedFingerprint = null,
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T00:00:00Z"),
            ),
        )

        val response = controller.status(statusRequest) as SocketResponse.Success<LeiosController.Status>
        val older = requireNotNull(
            response.data.keys
                .single { it.keyId == 10L }
                .installation
        )
        val latest = requireNotNull(
            response.data.keys
                .single { it.keyId == 20L }
                .installation
        )
        assertThat(older.stale).isTrue()
        assertThat(latest.stale).isFalse()
    }

    @Test
    fun `failed install fingerprint mismatch or failed refresh reports stale true`() {
        every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns listOf(keyOne)

        // 1. Failed installation state
        every { observationService.refresh(host, node) } returns liveObservation
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(2, 1) } returns listOf(
            KeyInstallation(
                id = 60,
                keyId = 10,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-1",
                state = "failed",
                observedAt = Instant.parse("2026-11-20T02:00:00Z"),
                sanitizedError = "Installation failed; inspect the producer configuration before retrying",
            ),
        )
        val failedInstall = (controller.status(statusRequest) as SocketResponse.Success<LeiosController.Status>)
            .data.keys
            .single()
            .installation!!
        assertThat(failedInstall.stale).isTrue()

        // 2. Loaded fingerprint differs from installed fingerprint
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(2, 1) } returns listOf(
            KeyInstallation(
                id = 61,
                keyId = 10,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-1",
                installedFingerprint = "fp-10",
                loadedFingerprint = "fp-old",
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T02:05:00Z"),
            ),
        )
        val mismatched = (controller.status(statusRequest) as SocketResponse.Success<LeiosController.Status>)
            .data.keys
            .single()
            .installation!!
        assertThat(mismatched.stale).isTrue()

        // 3. Observation refresh throws / returns null for healthy installed key
        every { observationService.refresh(host, node) } throws IllegalStateException("node unreachable")
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(2, 1) } returns listOf(
            KeyInstallation(
                id = 62,
                keyId = 10,
                nodeId = 2,
                hostId = 1,
                configurationFingerprint = "cfg-1",
                installedFingerprint = "fp-10",
                loadedFingerprint = null,
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T02:10:00Z"),
            ),
        )
        val offline = (controller.status(statusRequest) as SocketResponse.Success<LeiosController.Status>)
            .data.keys
            .single()
            .installation!!
        assertThat(offline.stale).isTrue()
    }

    @Test
    fun `bulk child pool status resolves era observer to parent core producer and falls back to default node`() {
        val childPool = node.copy(
            id = 3,
            parentId = 2,
            type = "pool",
            name = "child",
            poolId = "child-pool",
            isDefault = false,
        )
        val childKey = LeiosVotingKey(
            id = 30,
            networkKey = "network",
            poolId = "child-pool",
            publicKey = ByteArray(96) { 7 },
            proof = ByteArray(48) { 8 },
            signingFileId = 300,
            preparedAt = Instant.parse("2026-11-03T00:00:00Z"),
            validationProvenance = "fixture",
        )
        val childObservation = PoolKeyObservation(
            id = 3,
            networkKey = "network",
            poolId = "child-pool",
            observedAt = Instant.parse("2026-11-20T00:00:00Z"),
            chainSlot = BigDecimal.TEN,
            chainHash = ByteArray(32) { 9 },
            currentPublicKey = childKey.publicKey,
            effectiveEpoch = BigDecimal.ONE,
        )

        every { nodes.findById(3) } returns Optional.of(childPool)
        every { eras.networkKey(childPool) } returns "network"
        every { eras.evidence(childPool) } throws AssertionError("Bulk child pool has no era evidence of its own")
        every { observationService.refresh(host, childPool) } returns childObservation
        every { observations.findFirstByNetworkKeyAndPoolIdOrderByObservedAtDesc("network", "child-pool") } returns childObservation
        every { keys.findByNetworkKeyAndPoolId("network", "child-pool") } returns listOf(childKey)
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(3, 1) } returns listOf(
            KeyInstallation(
                id = 70,
                keyId = 30,
                nodeId = 3,
                hostId = 1,
                configurationFingerprint = "cfg-child",
                installedFingerprint = "fp-30",
                loadedFingerprint = null,
                permissionResult = "operator:600",
                state = "installed",
                observedAt = Instant.parse("2026-11-20T03:00:00Z"),
            ),
        )

        val childRequest = LeiosController.StatusRequest("req-child", LeiosController.Target(1, 3, "child-pool", "network"))
        val response = controller.status(childRequest) as SocketResponse.Success<LeiosController.Status>
        assertThat(response.data.era).isEqualTo("dijkstra")
        assertThat(
            response.data.keys
                .single()
                .installation
                ?.stale
        ).isFalse()

        // Fallback to default node when parentId is null
        val orphanChildPool = childPool.copy(id = 4, parentId = null)
        every { nodes.findById(4) } returns Optional.of(orphanChildPool)
        every { nodes.findDefault() } returns node
        every { eras.networkKey(orphanChildPool) } returns "network"
        every { eras.evidence(orphanChildPool) } throws AssertionError("Bulk child pool has no era evidence of its own")
        every { observationService.refresh(host, orphanChildPool) } returns childObservation
        every { installations.findByNodeIdAndHostIdOrderByObservedAtDesc(4, 1) } returns emptyList()

        val fallbackResponse = controller.status(
            LeiosController.StatusRequest("req-child-fallback", LeiosController.Target(1, 4, "child-pool", "network")),
        ) as SocketResponse.Success<LeiosController.Status>
        assertThat(fallbackResponse.data.era).isEqualTo("dijkstra")
    }
}
