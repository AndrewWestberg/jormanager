package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.monitors.LedgerPruneMonitor
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LedgerPruningServiceTest {
    private val moshi = Configuration().getMoshi()
    private val shelleyGenesisAdapter = moshi.adapter(GenesisShelley::class.java)

    @Test
    fun enforcesMinimumThirtyMinuteInactivityGuardrail() {
        assertThrows(IllegalArgumentException::class.java) {
            LedgerPruningService(
                dataSource = mockk(relaxed = true),
                nodeRepository = mockk(relaxed = true),
                fileRepository = mockk(relaxed = true),
                cardanoRepository = mockk(relaxed = true),
                shelleyGenesisAdapter = shelleyGenesisAdapter,
                inactivityMinutes = 29L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerPruningService.beforeSlotFor(
                completeSlot = BigDecimal.valueOf(5_000L),
                inactivityMinutes = 15L,
                slotLengthSeconds = 1L,
            )
        }
    }

    @Test
    fun computesExactSlotCutoffAcrossSlotLengthsAndProtectsRecentHistory() {
        // 1-second slots: 30 minutes = 1800 slots.
        // At completeSlot < 1800, nothing has been inactive for >= 30 minutes.
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(1_799L), 30L, 1L)).isNull()

        // At completeSlot = 1800, only slot 0 (1800s old) is >= 30m inactive -> beforeSlot = 1 (slot_spent < 1).
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(1_800L), 30L, 1L))
            .isEqualTo(BigDecimal.ONE)

        // At completeSlot = 3000, slots <= 1200 (>= 1800s old) are eligible -> beforeSlot = 1201.
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(3_000L), 30L, 1L))
            .isEqualTo(BigDecimal.valueOf(1_201L))

        // 20-second slots: 30 minutes = 90 slots. At completeSlot = 200, slots <= 110 -> beforeSlot = 111.
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(200L), 30L, 20L))
            .isEqualTo(BigDecimal.valueOf(111L))

        // Non-divisible 7-second slots: ceil(1800 / 7) = 258 slots (1806s >= 1800s).
        // At completeSlot = 257 -> null; at completeSlot = 258 -> beforeSlot = 1.
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(257L), 30L, 7L)).isNull()
        assertThat(LedgerPruningService.beforeSlotFor(BigDecimal.valueOf(258L), 30L, 7L))
            .isEqualTo(BigDecimal.ONE)
    }

    @Test
    fun vacuumRestoresConnectionAutoCommitStateForPooledConnections() {
        val executedSql = mutableListOf<String>()
        var currentAutoCommit = false
        var rolledBack = false

        val statement = mockk<Statement>(relaxed = true)
        every { statement.execute(any()) } answers {
            // VACUUM in PostgreSQL requires autoCommit == true
            check(currentAutoCommit) { "VACUUM executed while autoCommit was false" }
            executedSql += firstArg<String>()
            false
        }

        val connection = mockk<Connection>(relaxed = true)
        every { connection.autoCommit } answers { currentAutoCommit }
        every { connection.autoCommit = any() } answers { currentAutoCommit = firstArg() }
        every { connection.rollback() } answers { rolledBack = true }
        every { connection.createStatement() } returns statement

        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection

        val service = LedgerPruningService(
            dataSource = dataSource,
            nodeRepository = mockk<NodeRepository>(relaxed = true),
            fileRepository = mockk<FileRepository>(relaxed = true),
            cardanoRepository = mockk<CardanoRepository>(relaxed = true),
            shelleyGenesisAdapter = shelleyGenesisAdapter,
        )

        assertThat(service.vacuumLedgerTables()).isTrue()
        assertThat(rolledBack).isTrue()
        assertThat(currentAutoCommit).isFalse()
        assertThat(executedSql)
            .containsExactly(
                "VACUUM (ANALYZE) ledger_utxo_assets",
                "VACUUM (ANALYZE) ledger_utxos",
                "VACUUM (ANALYZE) ledger",
            ).inOrder()
    }

    @Test
    fun monitorRunsOnConfiguredScheduleAndStopsGracefully() {
        val invocations = AtomicInteger(0)
        val service = mockk<LedgerPruningService>()
        every { service.pruneOnce(any()) } answers {
            val callback = firstArg<() -> Unit>()
            callback()
            val count = invocations.incrementAndGet()
            if (count == 1) {
                throw IllegalStateException("Simulated transient DB error")
            }
            LedgerPruningService.SweepResult(
                skipped = false,
                networksEvaluated = 1,
                spentUtxosDeleted = 5L,
                orphanedAddressesDeleted = 2L,
                vacuumCompleted = true,
                durationMillis = 3L,
            )
        }

        val monitor = LedgerPruneMonitor(
            ledgerPruningService = service,
            enabled = true,
            initialDelayMs = 0L,
            intervalMs = 20L,
        )

        assertThat(monitor.isAutoStartup).isTrue()
        monitor.start()
        assertThat(monitor.isRunning).isTrue()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (invocations.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertThat(invocations.get()).isAtLeast(3)

        val stopped = CountDownLatch(1)
        monitor.stop { stopped.countDown() }
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(monitor.isRunning).isFalse()
    }

    @Test
    fun monitorHonorsDisabledFlagAndRepairMode() {
        val service = mockk<LedgerPruningService>(relaxed = true)
        val disabledMonitor = LedgerPruneMonitor(
            ledgerPruningService = service,
            enabled = false,
            initialDelayMs = 0L,
            intervalMs = 10L,
        )
        assertThat(disabledMonitor.isAutoStartup).isFalse()
        disabledMonitor.start()
        assertThat(disabledMonitor.isRunning).isFalse()

        val previousMode = System.getProperty("jormanager.mode")
        try {
            System.setProperty("jormanager.mode", "repair")
            val repairMonitor = LedgerPruneMonitor(
                ledgerPruningService = service,
                enabled = true,
                initialDelayMs = 0L,
                intervalMs = 10L,
            )
            assertThat(repairMonitor.isAutoStartup).isFalse()
        } finally {
            if (previousMode == null) {
                System.clearProperty("jormanager.mode")
            } else {
                System.setProperty("jormanager.mode", previousMode)
            }
        }
    }

    @Test
    fun singleFlightGuardSkipsOverlappingSweep() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nodeRepository = mockk<NodeRepository>()
        every { nodeRepository.findAll() } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw RuntimeException("abort after overlap check")
        }

        val service = LedgerPruningService(
            dataSource = mockk(relaxed = true),
            nodeRepository = nodeRepository,
            fileRepository = mockk(relaxed = true),
            cardanoRepository = mockk(relaxed = true),
            shelleyGenesisAdapter = shelleyGenesisAdapter,
        )

        val overlapSkipped = AtomicBoolean(false)
        val worker = Thread {
            try {
                service.pruneOnce()
            } catch (_: RuntimeException) {
                // Expected once latch releases without Exposed DB initialized in pure unit test
            }
        }
        worker.start()
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()

        val concurrentResult = service.pruneOnce()
        overlapSkipped.set(concurrentResult.skipped)
        release.countDown()
        worker.join(3_000L)

        assertThat(overlapSkipped.get()).isTrue()
    }
}
