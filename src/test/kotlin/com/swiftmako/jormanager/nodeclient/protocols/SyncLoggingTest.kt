package com.swiftmako.jormanager.nodeclient.protocols

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainTip
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.MsgRollForward
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.PooltoolService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory

class SyncLoggingTest {
    private val coordinator = mock(ChainIngestionCoordinator::class.java)
    private val session = NetworkChainSession("network-a", coordinator, true, 1, 21600)
    private val leios = LeiosFetchProtocol({ true })
    private val fetch = BlockFetchProtocol(session, leios)
    private val sync = ChainSyncProtocol(mock(Host::class.java), ByteArray(32), emptyList(), mock(ChainRepository::class.java), false, mock(PooltoolService::class.java), "", "")
    private val loggers = listOf("ChainSyncProtocol", "LedgerDao").map { LoggerFactory.getLogger(it) as Logger }
    private val levels = loggers.map { it.level }
    private val events = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun captureLogs() {
        events.start()
        loggers.forEach { it.level = Level.INFO; it.addAppender(events) }
    }

    @AfterEach
    fun closeProtocols() {
        sync.shutdown(); fetch.shutdown(); leios.shutdown(); session.close()
        loggers.forEachIndexed { index, logger -> logger.detachAppender(events); logger.level = levels[index] }
        events.stop()
    }

    private fun saved(height: Long, tip: Long, now: Long) {
        val message = MsgRollForward(height, height * 20, "11".repeat(32), "00".repeat(32), "", "", "", "", "",
            ChainTip(tip * 20, tip, if (height >= tip) "11".repeat(32) else "22".repeat(32)))
        sync.logSavedBlock(message, "aa".repeat(28), now)
        session.tipBlockNumber = tip
        fetch.logSavedBlock(height, now)
    }

    @Test
    fun catchUpIsThrottledButEverySavedTipBlockIsReported() {
        saved(10, 100, 0)
        saved(20, 100, 9_999_999_999)
        assertThat(events.list).hasSize(2)
        saved(30, 100, 10_000_000_000)
        assertThat(events.list).hasSize(4)
        saved(100, 100, 10_000_000_001)
        saved(101, 101, 10_000_000_002)
        saved(102, 101, 10_000_000_003)
        assertThat(events.list.map { it.loggerName }).containsExactly(
            "ChainSyncProtocol", "LedgerDao", "ChainSyncProtocol", "LedgerDao",
            "ChainSyncProtocol", "LedgerDao", "ChainSyncProtocol", "LedgerDao", "ChainSyncProtocol", "LedgerDao"
        ).inOrder()
        assertThat(events.list.map { it.level }.toSet()).containsExactly(Level.INFO)
    }

    @Test
    fun fetchCadenceIsIndependentForEachConnection() {
        session.tipBlockNumber = 100
        fetch.logSavedBlock(10, 0)
        val otherSession = NetworkChainSession("network-b", coordinator, false, 1, 21600).apply { tipBlockNumber = 200 }
        val other = BlockFetchProtocol(otherSession, leios)
        try {
            other.logSavedBlock(20, 1)
            fetch.logSavedBlock(11, 2)
            other.logSavedBlock(21, 3)
            assertThat(events.list).hasSize(2)
        } finally {
            other.shutdown(); otherSession.close()
        }
    }
}
