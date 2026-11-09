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

class PoolCreationServiceTest {
    private val nodes = mockk<NodeRepository>()
    private val files = mockk<FileRepository>()
    private val eras = mockk<CardanoRepository>()
    private val keys = mockk<com.swiftmako.jormanager.repositories.LeiosKeyRepository>()
    private val walletUtils = mockk<com.swiftmako.jormanager.controllers.utils.WalletUtils>()
    private val jdbc = mockk<org.springframework.jdbc.core.JdbcTemplate>(relaxed = true)
    private val service = PoolCreationService(nodes, mockk(), files, mockk(), mockk(), mockk(), walletUtils, mockk(), eras, mockk(), tools.jackson.databind.json.JsonMapper.builder().build(), jdbc, keys, mockk(), mockk(), mockk(), mockk())
    private val observer = Node(id = 1, hostId = 2, color = "", type = "relay", processorThreads = 1, name = "observer",
        listen = "127.0.0.1", port = 3001, promPort = 12798, genesisByronFileId = 1, genesisShelleyFileId = 2,
        genesisAlonzoFileId = 3, genesisConwayFileId = 4, configFileId = 5, isDefault = true)
    private val draft = observer.copy(id = 3, type = "core", isDefault = false, isDraft = true, poolId = "pool")

    @Test
    fun `invalid password is rejected before preparation or registration`() {
        every { walletUtils.isValidSpendingPassword("wrong") } returns false
        val request = com.swiftmako.jormanager.model.CreateNodeRequest(
            requestId = java.util.UUID.randomUUID().toString(), spendingPassword = "wrong", color = "", hostId = 2,
            parentId = null, name = "pool", isDefault = false, type = "core", processorThreads = 1,
            listen = "127.0.0.1", port = 3001, generateColdKeys = true, coldSKey = null, coldVKey = null,
            coldCounter = null, generateVRFKeys = true, vrfSKey = null, vrfVKey = null,
            generateKESKeys = true, kesSKey = null, kesVKey = null, registrationFeesAccount = 1,
            ownerStakingAccount = 2, rewardsStakingAccount = 3, poolPledge = java.math.BigInteger.ZERO,
            poolCost = java.math.BigInteger.ZERO, poolMargin = "0", relays = emptyList(), metadata = null,
            sudoPassword = "")
        assertThrows<IllegalArgumentException> { service.create(request) }
        io.mockk.verify(exactly = 0) { nodes.findDefault() }
    }

    @Test
    fun `uncertain conway registration is not replayed and never installs`() = uncertainRegistration("conway")

    @Test
    fun `dijkstra reuses retained key and does not replay uncertain registration`() = uncertainRegistration("dijkstra")

    @Test
    fun `historical resume retains the node and key when submission is uncertain`() = uncertainRegistration("dijkstra", historical = true)

    @Test
    fun `historical resume rejects changed saved setup`() = uncertainRegistration("dijkstra", historical = true, changedSaved = true)

    @Test
    fun `conway creation installs once without a voting key`() = uncertainRegistration("conway", successful = true)

    @Test
    fun `dijkstra creation installs retained key once`() = uncertainRegistration("dijkstra", successful = true)

    private fun uncertainRegistration(era: String, historical: Boolean = false, successful: Boolean = false, changedSaved: Boolean = false) {
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val db = mockk<org.springframework.jdbc.core.JdbcTemplate>(relaxed = true)
        val manager = mockk<org.springframework.transaction.PlatformTransactionManager>(relaxed = true)
        val keyService = mockk<com.swiftmako.jormanager.services.LeiosKeyService>()
        val registration = mockk<ReviewedPoolRegistrationController>()
        val installer = mockk<com.swiftmako.jormanager.services.PoolDeploymentService>()
        val target = PoolCreationService(nodes, mockk(), files, mockk(), mockk(), mockk(), walletUtils, mockk(),
            eras, mockk(), mapper, db, keys, keyService, registration, installer, manager)
        val request = com.swiftmako.jormanager.model.CreateNodeRequest(
            requestId = java.util.UUID.randomUUID().toString(), resumeNodeId = if (historical) 3 else null,
            spendingPassword = "password", color = "", hostId = 2,
            parentId = null, name = "pool", isDefault = false, type = "core", processorThreads = 1,
            listen = "127.0.0.1", port = 3001, generateColdKeys = true, coldSKey = null, coldVKey = null,
            coldCounter = null, generateVRFKeys = true, vrfSKey = null, vrfVKey = null,
            generateKESKeys = true, kesSKey = null, kesVKey = null, registrationFeesAccount = 1,
            ownerStakingAccount = 2, rewardsStakingAccount = 3, poolPledge = java.math.BigInteger.ZERO,
            poolCost = java.math.BigInteger.ZERO, poolMargin = "0", relays = emptyList(), metadata = null, sudoPassword = "")
        val intentMethod = target.javaClass.getDeclaredMethod("intent", request.javaClass).apply { isAccessible = true }
        val intent = intentMethod.invoke(target, request) as tools.jackson.databind.node.ObjectNode
        var details = mapper.writeValueAsString(mapOf("intent" to intent))
        var fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(intent))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        var operationId = java.util.UUID.randomUUID().toString()
        var journalExists = !historical
        var recorded = false
        var journalState = "prepared"
        var installed = false
        every { db.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<Any>>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val rowMapper = secondArg<org.springframework.jdbc.core.RowMapper<Any>>()
            val rs = mockk<java.sql.ResultSet>()
            when {
                sql.contains("FROM pool_deployment_previews") && journalExists -> {
                    every { rs.getString("id") } returns operationId
                    every { rs.getString("request_id") } returns request.requestId
                    every { rs.getLong("node_id") } returns 3L
                    every { rs.getString("fingerprint") } returns fingerprint
                    every { rs.getString("details") } returns details
                    every { rs.getString("state") } returns journalState
                    listOf(requireNotNull(rowMapper.mapRow(rs, 0)))
                }
                sql.contains("FROM pool_draft_config") && historical -> {
                    every { rs.getString(1) } returns if (changedSaved) """{"host":2,"name":"other"}""" else """{"host":2,"name":"pool"}"""
                    listOf(requireNotNull(rowMapper.mapRow(rs, 0)))
                }
                sql.startsWith("SELECT state,tx_id") && recorded -> {
                    every { rs.getString(1) } returns "submission_uncertain"
                    every { rs.getString(2) } returns "a".repeat(64)
                    listOf(requireNotNull(rowMapper.mapRow(rs, 0)))
                }
                else -> emptyList()
            }
        }
        every { db.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val args = secondArg<Array<out Any?>>()
            if (sql.startsWith("UPDATE pool_deployment_previews SET details=")) details = args[0] as String
            if (sql.startsWith("UPDATE pool_deployment_previews SET state='registered'")) journalState = "registered"
            if (sql.startsWith("INSERT INTO pool_deployment_previews")) {
                operationId = args[0] as String
                fingerprint = args[4] as String
                details = args[5] as String
                journalExists = true
            }
            1
        }
        val savedNode = draft.copy(name = "pool", ownerStakingAccountId = 2, rewardsStakingAccountId = 3,
            poolPledge = java.math.BigInteger.ZERO, poolCost = java.math.BigInteger.ZERO, poolMargin = "0")
        every { nodes.findById(3) } answers { Optional.of(if (installed) savedNode.copy(isDraft = false) else savedNode) }
        every { nodes.findDefault() } returns observer
        every { eras.networkKey(any()) } returns "network"
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", era, "current", "host:2/node:1")
        if (era == "dijkstra") {
            every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns listOf(
                com.swiftmako.jormanager.entities.LeiosVotingKey(id = 9, networkKey = "network", poolId = "pool",
                    publicKey = ByteArray(96), proof = ByteArray(48), signingFileId = 7,
                    preparedAt = java.time.Instant.now(), validationProvenance = "retained validated key"))
        }
        every { registration.execute(any()) } answers {
            recorded = true
            ReviewedPoolRegistrationController.Result(firstArg<ReviewedPoolRegistrationController.SubmitRequest>().requestId,
                "a".repeat(64), "network", era, 3, "0", "0", "0", if (successful) "submitted" else "submission_uncertain", "register",
                if (era == "dijkstra") 9 else null)
        }
        if (successful) every { installer.install(any(), any(), any(), any(), any()) } answers {
            installed = true
            journalState = "installed"
            com.swiftmako.jormanager.services.PoolDeploymentService.Result(3, "installed", null, null, "Installed", operationId)
        }
        val create = target.javaClass.getDeclaredMethod("createLocked", request.javaClass).apply { isAccessible = true }
        if (changedSaved) {
            val failure = assertThrows<java.lang.reflect.InvocationTargetException> { create.invoke(target, request) }
            assertThat(failure.cause?.message).contains("Saved pool setup differs")
            io.mockk.verify(exactly = 0) { registration.execute(any()) }
            io.mockk.verify(exactly = 0) { db.update(match { it.startsWith("INSERT INTO pool_deployment_previews") }, *anyVararg()) }
            return
        }
        repeat(2) {
            if (successful) {
                assertThat((create.invoke(target, request) as Node).id).isEqualTo(3L)
            } else {
                val failure = assertThrows<java.lang.reflect.InvocationTargetException> { create.invoke(target, request) }
                assertThat(failure.cause).isInstanceOf(PoolCreationFailure::class.java)
                assertThat(failure.cause?.message).contains("submission_uncertain")
            }
        }
        val changed = assertThrows<java.lang.reflect.InvocationTargetException> { create.invoke(target, request.copy(color = "different")) }
        assertThat(changed.cause?.message).contains("Request ID already used")
        fingerprint = "different"
        val tampered = assertThrows<java.lang.reflect.InvocationTargetException> { create.invoke(target, request) }
        assertThat(tampered.cause?.message).contains("Request ID already used")
        io.mockk.verify(exactly = 1) { registration.execute(match { it.keyId == if (era == "dijkstra") 9L else null }) }
        val savedDetails = mapper.readTree(details)
        assertThat(savedDetails.path("registrationRequestId").asString()).isNotEmpty()
        if (era == "dijkstra") assertThat(savedDetails.path("keyId").asLong()).isEqualTo(9L)
        if (historical) io.mockk.verify(exactly = 1) { db.update(match { it.startsWith("INSERT INTO pool_deployment_previews") }, *anyVararg()) }
        io.mockk.verify(exactly = 0) { keyService.prepare(any(), any(), any()) }
        io.mockk.verify(exactly = if (successful) 1 else 0) { installer.install(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setup exposes retained validated key only for dijkstra`() {
        every { nodes.findById(3) } returns Optional.of(draft)
        every { nodes.findDefault() } returns observer
        every { eras.networkKey(any()) } returns "network"
        every { files.findById(5) } returns Optional.of(File(id = 5, name = "config.json", content = "{}"))
        val retained = com.swiftmako.jormanager.entities.LeiosVotingKey(
            id = 9, networkKey = "network", poolId = "pool", publicKey = ByteArray(96), proof = ByteArray(48),
            signingFileId = 7, preparedAt = java.time.Instant.now(), validationProvenance = "retained validated key")
        every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns listOf(retained)
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", "dijkstra", "current", "host:2/node:1")
        assertThat(service.context(PoolSetupContextRequest("request", 3)).keys.map { it.keyId }).containsExactly(9L)
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", "conway", "current", "host:2/node:1")
        assertThat(service.context(PoolSetupContextRequest("request", 3)).keys).isEmpty()
    }

    @Test
    fun `context requires current matching observer evidence for both supported eras`() {
        every { nodes.findById(3) } returns Optional.of(draft)
        every { nodes.findDefault() } returns observer
        every { eras.networkKey(any()) } returns "network"
        every { files.findById(5) } returns Optional.of(File(id = 5, name = "config.json", content = "{}"))
        every { keys.findByNetworkKeyAndPoolId("network", "pool") } returns emptyList()
        for (era in listOf("conway", "dijkstra")) {
            every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", era, "current", "host:2/node:1")
            assertThat(service.context(PoolSetupContextRequest("request", 3)).networkKey).isEqualTo("network")
        }
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("network", "dijkstra", "stale", "host:2/node:1")
        assertThrows<IllegalArgumentException> { service.context(PoolSetupContextRequest("request", 3)) }
        every { eras.evidence(observer) } returns CardanoRepository.EraEvidence("other", "conway", "current", "host:2/node:1")
        assertThrows<IllegalArgumentException> { service.context(PoolSetupContextRequest("request", 3)) }
    }
}
