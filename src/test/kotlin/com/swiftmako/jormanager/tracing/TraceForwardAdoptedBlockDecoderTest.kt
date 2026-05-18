package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.tracing.fixtures.ScriptedTraceForwardServer
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardSessionScript
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TraceForwardAdoptedBlockDecoderTest {
    private val decoder = TraceForwardAdoptedBlockDecoder()

    @Test
    fun decodesValidForwardedAdoptedBlockTraceObject() {
        val event = decoder.decode(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson)

        assertThat(event).isEqualTo(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "completed",
            )
        )
    }

    @Test
    fun decodesValidForwardedForgedBlockTraceObject() {
        val event = decoder.decode(TraceForwardFixtures.forgedBlockTraceObject().traceObjectJson)

        assertThat(event).isEqualTo(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "created",
            )
        )
    }

    @Test
    fun decodesLiveLoopNamespacedForwardedBlockTraceObjects() {
        val adopted =
            decoder.decode(
                TraceForwardFixtures.adoptedBlockTraceObject(namespace = listOf("Forge", "Loop", "AdoptedBlock")).traceObjectJson
            )
        val forged =
            decoder.decode(
                TraceForwardFixtures.forgedBlockTraceObject(namespace = listOf("Forge", "Loop", "ForgedBlock")).traceObjectJson
            )

        assertThat(adopted?.status).isEqualTo("completed")
        assertThat(adopted?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
        assertThat(forged?.status).isEqualTo("created")
        assertThat(forged?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
    }

    @Test
    fun ignoresUnknownNamespace() {
        val event = decoder.decode(TraceForwardFixtures.unknownNamespaceTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresMalformedMachineJson() {
        val event = decoder.decode(TraceForwardFixtures.malformedMachineJsonTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresMissingRequiredFields() {
        val event = decoder.decode(TraceForwardFixtures.missingBlockHashTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresWrongMachineKind() {
        val event = decoder.decode(TraceForwardFixtures.wrongKindTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresNonNumericSlot() {
        val event = decoder.decode(TraceForwardFixtures.nonNumericSlotTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresWrapperOnlyBlockHash() {
        val event = decoder.decode(TraceForwardFixtures.wrapperOnlyBlockHashTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresForgedTraceObjectWithoutMachineBlockField() {
        val event = decoder.decode(TraceForwardFixtures.forgedBlockHashOnlyTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresOptionalMachineFieldsWhenDecoding() {
        val event = decoder.decode(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson)

        assertThat(event?.slot).isEqualTo(7_403_221L)
        assertThat(event?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
    }

    @Test
    fun decodesForwardedAdoptedBlockFromNonEmptyTraceObjectsReply() =
        runBlocking {
            val replies = mutableListOf<TraceForwardMessage.TraceObjectsReply>()

            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(
                        TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject()),
                        TraceForwardFixtures.msgDone(),
                    ),
                )
            ).use { server ->
                SocketTraceForwardSessionClient().runSession("127.0.0.1", server.port) { message ->
                    if (message is TraceForwardMessage.TraceObjectsReply) {
                        replies += message
                    }
                }

                server.awaitCompletion()
            }

            val events = decoder.decode(replies.single())

            assertThat(events).containsExactly(
                ForwardedBlockEvent(
                    slot = 7_403_221L,
                    blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                    timestamp = "2026-05-12T00:00:00Z",
                    hostname = "core-node-1",
                    status = "completed",
                )
            )
        }

    @Test
    fun toleratesSocketCloseAfterTraceObjectsReplyWithoutDone() =
        runBlocking {
            val replies = mutableListOf<TraceForwardMessage.TraceObjectsReply>()

            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(
                        TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject()),
                    ),
                )
            ).use { server ->
                SocketTraceForwardSessionClient().runSession("127.0.0.1", server.port) { message ->
                    if (message is TraceForwardMessage.TraceObjectsReply) {
                        replies += message
                    }
                }

                server.awaitCompletion()
            }

            val events = decoder.decode(replies.single())

            assertThat(events).containsExactly(
                ForwardedBlockEvent(
                    slot = 7_403_221L,
                    blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                    timestamp = "2026-05-12T00:00:00Z",
                    hostname = "core-node-1",
                    status = "completed",
                )
            )
        }
}
