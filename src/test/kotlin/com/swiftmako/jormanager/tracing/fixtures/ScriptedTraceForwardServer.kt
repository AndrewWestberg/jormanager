package com.swiftmako.jormanager.tracing.fixtures

import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScriptedTraceForwardServer private constructor(
    private val serverSocket: ServerSocket,
    private val sessions: List<TraceForwardSessionScript>,
    private val readTimeoutMillis: Int,
) : AutoCloseable {
    private val acceptedSessionCount = AtomicInteger(0)
    private val failure = AtomicReference<Throwable?>(null)
    private val completedSessions = CountDownLatch(sessions.size)
    private val worker =
        Thread {
            try {
                sessions.forEach { script ->
                    serverSocket.accept().use { socket ->
                        acceptedSessionCount.incrementAndGet()
                        handleSession(socket, script)
                    }
                    completedSessions.countDown()
                }
            } catch (e: SocketException) {
                if (!serverSocket.isClosed) {
                    throw e
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                while (completedSessions.count > 0) {
                    completedSessions.countDown()
                }
            }
        }.apply {
            name = "scripted-trace-forward-server"
            isDaemon = true
            start()
        }

    val port: Int
        get() = serverSocket.localPort

    fun awaitCompletion(timeoutMillis: Long = 5_000L) {
        check(completedSessions.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for scripted trace-forward sessions to complete"
        }
        failure.get()?.let { throw AssertionError("Scripted trace-forward server failed", it) }
    }

    fun awaitAcceptedSessions(
        expectedSessions: Int,
        timeoutMillis: Long = 5_000L,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (acceptedSessionCount.get() >= expectedSessions) {
                return
            }
            Thread.sleep(10)
        }

        check(acceptedSessionCount.get() >= expectedSessions) {
            "Timed out waiting for $expectedSessions accepted trace-forward sessions"
        }
    }

    override fun close() {
        serverSocket.close()
        worker.join(1_000L)
    }

    private fun handleSession(
        socket: Socket,
        script: TraceForwardSessionScript,
    ) {
        socket.soTimeout = readTimeoutMillis
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        script.expectedClientMessages.forEachIndexed { index, expected ->
            val actual = input.readNBytes(expected.size)
            if (actual.size != expected.size) {
                throw EOFException(
                    "Expected ${expected.size} bytes for client message $index but received ${actual.size}"
                )
            }
            check(actual.contentEquals(expected)) {
                "Unexpected client message $index for scripted trace-forward session"
            }
        }
        script.serverResponses.forEach { response ->
            output.write(response)
            output.flush()
        }
        if (script.awaitClientDisconnectAfterResponses) {
            while (input.read() != -1) {
                // wait for the client to close the connection explicitly
            }
            return
        }
        if (script.closeAfterResponses) {
            socket.close()
        }
    }

    companion object {
        fun start(
            vararg sessions: TraceForwardSessionScript,
            readTimeoutMillis: Int = 2_000,
        ): ScriptedTraceForwardServer =
            ScriptedTraceForwardServer(
                serverSocket = ServerSocket(0).apply { reuseAddress = true },
                sessions = sessions.toList(),
                readTimeoutMillis = readTimeoutMillis,
            )
    }
}

data class TraceForwardSessionScript(
    val expectedClientMessages: List<ByteArray>,
    val serverResponses: List<ByteArray>,
    val closeAfterResponses: Boolean = true,
    val awaitClientDisconnectAfterResponses: Boolean = false,
)
