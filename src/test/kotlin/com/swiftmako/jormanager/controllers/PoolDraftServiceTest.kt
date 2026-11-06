package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PoolDraftServiceTest {
    private val nodes = mockk<NodeRepository>()
    private val files = mockk<FileRepository>()
    private val eras = mockk<CardanoRepository>()
    private val service = PoolDraftService(nodes, mockk(), files, mockk(), mockk(), mockk(), mockk(), mockk(), eras, mockk(), mockk(), mockk(), mockk())
    private val observer = Node(id = 1, hostId = 2, color = "", type = "relay", processorThreads = 1, name = "observer",
        listen = "127.0.0.1", port = 3001, promPort = 12798, genesisByronFileId = 1, genesisShelleyFileId = 2,
        genesisAlonzoFileId = 3, genesisConwayFileId = 4, configFileId = 5, isDefault = true)
    private val draft = observer.copy(id = 3, type = "core", isDefault = false, isDraft = true)

    @Test
    fun `context requires current matching observer evidence for both supported eras`() {
        every { nodes.findById(3) } returns Optional.of(draft)
        every { nodes.findDefault() } returns observer
        every { eras.networkKey(any()) } returns "network"
        every { files.findById(5) } returns Optional.of(File(id = 5, name = "config.json", content = "{}"))
        for (era in listOf("conway", "dijkstra")) {
            every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", era, "current", "host:2/node:1")
            assertThat(service.context(PoolDraftContextRequest("request", 3)).networkKey).isEqualTo("network")
        }
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", "dijkstra", "stale", "host:2/node:1")
        assertThrows<IllegalArgumentException> { service.context(PoolDraftContextRequest("request", 3)) }
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("other", "conway", "current", "host:2/node:1")
        assertThrows<IllegalArgumentException> { service.context(PoolDraftContextRequest("request", 3)) }
    }
}
