package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.firehose.controllers.nodeclient.protocol.Agency
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.utils.CborSpans
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Node-to-node LeiosFetch (mini-protocol 19). One request at a time per connection. */
class LeiosFetchProtocol(private val isCurrent: (Long) -> Boolean, private val maxClosureBytes: Long = 256L * 1024 * 1024) : MiniProtocol(19) {
    override val rxBufferSize = 1_048_576
    private enum class State { Idle, Busy, Done }
    @Volatile private var state = State.Idle
        set(value) { field = value; updates.tryEmit(agency) }
    private val updates = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(Agency.Client) }
    override val agencyFlow: Flow<Agency> = updates
    override val agency: Agency get() = when (state) { State.Idle -> Agency.Client; State.Busy -> Agency.Server; State.Done -> Agency.None }
    private data class Request(val wire: ByteArray, val reply: CompletableDeferred<ByteArray>)
    private val requests = Channel<Request>(1)
    private val mutex = Mutex()
    @Volatile private var pending: Request? = null

    override suspend fun sendData(): ByteBuffer {
        val request = requests.receive()
        check(state == State.Idle)
        pending = request
        state = State.Busy
        return muxByteBufferPool.borrow().also { it.put(request.wire); it.flip() }
    }

    override fun receiveData(payload: ByteBuffer) {
        val request = pending ?: throw IllegalStateException("Unexpected LeiosFetch response")
        try {
            val bytes = ByteArray(payload.remaining()); payload.get(bytes)
            request.reply.complete(bytes)
        } catch (e: Throwable) {
            request.reply.completeExceptionally(e)
            throw e
        } finally {
            pending = null
            if (state != State.Done) state = State.Idle
        }
    }

    override fun shutdown() {
        state = State.Done
        requests.close()
        pending?.reply?.completeExceptionally(IllegalStateException("LeiosFetch connection closed"))
    }

    suspend fun acquire(slot: Long, hash: ByteArray, size: Long, generation: Long): LeiosContentResolver.Closure = mutex.withLock {
        require(slot >= 0 && hash.size == 32 && size in 1L..600_000L)
        suspend fun exchange(wire: ByteArray): ByteArray {
            check(isCurrent(generation)) { "Stale LeiosFetch generation" }
            val reply = CompletableDeferred<ByteArray>()
            requests.send(Request(wire, reply))
            val response = withTimeout(120_000) { reply.await() }
            check(isCurrent(generation)) { "Stale LeiosFetch generation" }
            return response
        }
        try {
            val point = point(slot, hash)
            val response = CborSpans.parse(exchange(byteArrayOf(0x82.toByte(), 1) + point)).array()
            require(response.size == 2 && response[0].integer() == BigInteger.TWO) { "Invalid Leios block response" }
            val body = response[1].bytes()
            require(body.size <= 600_000)
            val refs = LeiosContentResolver.references(hash, size, body)
            require(refs.isNotEmpty() && refs.size <= 17142)
            require(maxClosureBytes > 0 && refs.sumOf { it.second } <= maxClosureBytes) { "Leios closure exceeds configured memory limit" }
            val transactions = HashMap<String, ByteArray>()
            var first = 0
            while (first < refs.size) {
                val offsets = ArrayList<Int>()
                var total = 0L
                var next = first
                while (next < refs.size) {
                    val n = refs[next].second
                    require(n in 1L..500_000L) { "Leios transaction too large or empty" }
                    if (offsets.isNotEmpty() && total + n > 500_000) break
                    offsets.add(next++); total += n
                }
                val message = ByteArrayOutputStream()
                message.write(byteArrayOf(0x83.toByte(), 3)); message.write(point)
                val groups = offsets.groupBy { it / 64 }
                number(message, 5, groups.size.toULong())
                for ((group, members) in groups) {
                    number(message, 0, group.toULong())
                    var bits = 0UL
                    for (offset in members) bits = bits or (1UL shl (offset % 64))
                    number(message, 0, bits)
                }
                val txResponse = CborSpans.parse(exchange(message.toByteArray())).array()
                require(txResponse.size == 2 && txResponse[0].integer() == BigInteger.valueOf(4)) { "Invalid Leios transaction response" }
                val txs = txResponse[1].array(); require(txs.size == offsets.size) { "Incomplete Leios closure" }
                for ((position, offset) in offsets.withIndex()) {
                    val key = refs[offset].first
                    val tx = txs[position].tagged(24); require(tx.major == 2)
                    require(transactions.put(key.toHexString(), tx.payload()) == null) { "Duplicate Leios transaction" }
                }
                first = next
            }
            LeiosContentResolver.resolve(hash, size, body, transactions)
        } catch (e: Throwable) {
            // A timed-out or cancelled in-flight reply cannot be assigned to a later request.
            shutdown()
            throw e
        }
    }

    private fun point(slot: Long, hash: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(0x82); number(out, 0, slot.toULong())
        number(out, 2, hash.size.toULong()); out.write(hash); return out.toByteArray()
    }
    private fun number(out: ByteArrayOutputStream, major: Int, n: ULong) {
        val prefix = major shl 5
        val bytes = when { n < 24UL -> 0; n <= 255UL -> 1; n <= 65535UL -> 2; n <= 0xffffffffUL -> 4; else -> 8 }
        if (bytes == 0) out.write(prefix or n.toInt()) else {
            out.write(prefix or when (bytes) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 })
            for (i in bytes - 1 downTo 0) out.write((n shr (i * 8)).toInt())
        }
    }
}
