package com.swiftmako.jormanager.nodeclient.protocols.fixtures

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DijkstraBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LedgerBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosContentResolver
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Synthetic indexer inputs, not cryptographically valid consensus blocks. */
object CatchupFixtures {
    data class Replay(
        val network: String,
        val era: Int,
        val blocks: List<ByteArray>,
        val seeds: List<DecodedGenesisOutput>,
        val endorsement: ByteArray? = null,
        val transactions: List<ByteArray> = emptyList(),
    )

    val genesisHash: String = "00".repeat(32)
    val address: ByteArray get() = byteArrayOf(0x60) + ByteArray(28) { 0x11 }
    val rewardAddress: ByteArray get() = byteArrayOf(0xe0.toByte()) + ByteArray(28) { 0x22 }

    fun array(vararg values: ByteArray): ByteArray = array(values.asList())
    fun array(values: List<ByteArray>): ByteArray = CborArray.create().apply {
        values.forEach { add(CborReader.createFromByteArray(it).readDataItem()) }
    }.toCborByteArray()
    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray = CborMap.create(
        entries.associateTo(linkedMapOf()) { (key, value) ->
            CborReader.createFromByteArray(key).readDataItem() to CborReader.createFromByteArray(value).readDataItem()
        }
    ).toCborByteArray()
    fun integer(value: Long): ByteArray = CborInteger.create(value).toCborByteArray()
    fun bytes(value: ByteArray): ByteArray = CborByteString.create(value).toCborByteArray()
    fun tagged24(value: ByteArray): ByteArray = byteArrayOf(0xd8.toByte(), 24) + bytes(value)
    private val nullValue = CborSimple.NULL.toCborByteArray()
    private fun bool(value: Boolean) = (if (value) CborSimple.TRUE else CborSimple.FALSE).toCborByteArray()
    private fun fixed(size: Int, value: Int) = bytes(ByteArray(size) { value.toByte() })

    fun seed(index: Int): DecodedGenesisOutput {
        require(index >= 0)
        val id = ByteBuffer.allocate(32).apply { position(28); putInt(index) }.array()
        return DecodedGenesisOutput(id, address, BigInteger.valueOf(10_000_000))
    }
    fun input(txId: ByteArray, index: Int = 0): ByteArray = array(bytes(txId), integer(index.toLong()))
    fun output(address: ByteArray = this.address, coin: Long = 9_800_000, assets: List<Triple<ByteArray, ByteArray, Long>> = emptyList()): ByteArray {
        val value = if (assets.isEmpty()) integer(coin) else {
            val policies = assets.groupBy { it.first.toHexString() }
            val assetMap = map(policies.values.map { group ->
                bytes(group.first().first) to map(group.map { bytes(it.second) to integer(it.third) })
            })
            array(integer(coin), assetMap)
        }
        return array(bytes(address), value)
    }

    /** Children are included-transaction encodings; validity is omitted in their embedded representation. */
    fun transaction(
        inputs: List<ByteArray>,
        outputs: List<ByteArray> = listOf(output()),
        fee: Long = 200_000,
        collateral: List<ByteArray> = emptyList(),
        references: List<ByteArray> = emptyList(),
        collateralReturn: ByteArray? = null,
        valid: Boolean = true,
        children: List<ByteArray> = emptyList(),
        directDeposits: Map<ByteArray, Long> = emptyMap(),
    ): ByteArray {
        val entries = mutableListOf(0 to array(inputs), 1 to array(outputs), 2 to integer(fee), 3 to integer(10_000_000), 8 to integer(0))
        if (collateral.isNotEmpty()) entries += 13 to array(collateral)
        if (collateralReturn != null) entries += 16 to collateralReturn
        if (references.isNotEmpty()) entries += 18 to array(references)
        if (children.isNotEmpty()) entries += 23 to array(children.map { child ->
            val p = CborSpans.parse(child).array(); require(p.size == 4)
            val childBody = map(p[0].entries().filter { (key, _) -> key.integer().toInt() !in setOf(2, 13, 16, 17, 23, 27) }
                .map { (key, value) -> key.bytes() to value.bytes() })
            array(childBody, p[1].bytes(), p[2].bytes())
        })
        if (directDeposits.isNotEmpty()) entries += 25 to map(directDeposits.entries.map { bytes(it.key) to integer(it.value) })
        val body = map(entries.map { integer(it.first.toLong()) to it.second })
        return array(body, map(emptyList()), nullValue, bool(valid))
    }
    fun transactionId(transaction: ByteArray): ByteArray = DijkstraBlockDecoder.decodeIncludedTransaction(transaction).parent.bodyId

    fun header(block: ByteArray): ByteArray = CborSpans.parse(block).array()[0].bytes()
    fun blockHash(block: ByteArray): ByteArray = Blake2b.hash256(header(block))

    /** announcement is the encoded EB map, and previous is the preceding header hash. */
    fun block(
        era: Int,
        height: Long,
        previous: ByteArray? = null,
        transactions: List<ByteArray> = emptyList(),
        announcement: ByteArray? = null,
        certificate: Boolean = false,
    ): ByteArray {
        require(era in 1..7 && height > 0)
        require(era == 7 || (announcement == null && !certificate))
        val parsed = transactions.map { CborSpans.parse(it).array().also { p -> require(p.size == 4) } }
        val body = if (era == 7) array(array(transactions), if (certificate) array(bytes(byteArrayOf()), fixed(48, 9)) else nullValue, nullValue)
        else array(array(parsed.map { it[0].bytes() }), array(parsed.map { it[1].bytes() }),
            map(parsed.mapIndexed { index, tx -> integer(index.toLong()) to tx[2].bytes() }))
        val vrf = array(fixed(64, 3), fixed(80, 4))
        val common = mutableListOf(integer(height), integer(height), previous?.let(::bytes) ?: nullValue, fixed(32, 1), fixed(32, 2))
        if (era < 5) common += vrf
        common += vrf
        common += integer(body.size.toLong())
        common += bytes(Blake2b.hash256(body))
        if (era < 5) {
            common += fixed(32, 5); common += integer(0); common += integer(0); common += fixed(64, 6)
            common += integer(era.toLong()); common += integer(0)
        } else {
            common += array(fixed(32, 5), integer(0), integer(0), fixed(64, 6))
            common += array(integer(era.toLong()), integer(0))
            if (era == 7) {
                common += bool(certificate)
                common += announcement?.let { array(bytes(Blake2b.hash256(it)), integer(it.size.toLong())) } ?: nullValue
            }
        }
        val header = array(array(common), fixed(448, 7))
        val result = if (era == 7) array(header, body) else {
            val parts = CborSpans.parse(body).array().map { it.bytes() }
            array(listOf(header) + parts + if (era >= 4) listOf(array(parsed.indices.filter { !parsed[it][3].boolean() }.map { integer(it.toLong()) })) else emptyList())
        }
        LedgerBlockDecoder.decode(era, result)
        return result
    }

    fun leios(count: Int = 1035): Replay {
        require(count > 0)
        val seeds = List(count, ::seed)
        val transactions = seeds.map { transaction(listOf(input(it.txId))) }.sortedBy { Blake2b.hash256(it).toHexString() }
        val endorsement = map(transactions.map { bytes(Blake2b.hash256(it)) to integer(it.size.toLong()) })
        LeiosContentResolver.resolve(Blake2b.hash256(endorsement), endorsement.size.toLong(), endorsement,
            transactions.associateBy { Blake2b.hash256(it).toHexString() })
        val first = block(7, 1, announcement = endorsement)
        val second = block(7, 2, previous = blockHash(first), certificate = true)
        return Replay("perf-leios:" + "ab".repeat(256), 7, listOf(first, second), seeds, endorsement, transactions)
    }
    fun historical(era: Int, blockCount: Int = 1, transactionsPerBlock: Int = 1): Replay {
        require(era in 1..6 && blockCount >= 0 && transactionsPerBlock >= 0)
        val seeds = List(Math.multiplyExact(blockCount, transactionsPerBlock), ::seed)
        val transactions = seeds.map { transaction(listOf(input(it.txId))) }
        var previous: ByteArray? = null
        val blocks = (0 until blockCount).map { index ->
            block(era, index.toLong() + 1, previous, transactions.drop(index * transactionsPerBlock).take(transactionsPerBlock)).also { previous = blockHash(it) }
        }
        return Replay("perf-conway:" + "ab".repeat(256), era, blocks, seeds, transactions = transactions)
    }
    fun conway(blockCount: Int = 100, transactionsPerBlock: Int = 10): Replay = historical(6, blockCount, transactionsPerBlock)
    fun empty(): Replay = conway(100, 0).copy(network = "perf-empty:" + "ab".repeat(256))

    fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (replay in listOf(leios(), conway(), empty())) {
            fun add(value: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value) }
            add(replay.network.toByteArray(Charsets.UTF_8)); add(integer(replay.era.toLong()))
            replay.seeds.forEach { add(it.txId); add(it.address); add(it.coin.toByteArray()) }
            replay.blocks.forEach(::add); replay.endorsement?.let(::add); replay.transactions.forEach(::add)
        }
        return digest.digest().toHexString()
    }

    fun point(slot: Long, hash: ByteArray): ByteArray = array(integer(slot), bytes(hash))
    fun tip(slot: Long, hash: ByteArray, height: Long): ByteArray = array(point(slot, hash), integer(height))
    fun rollForward(era: Int, block: ByteArray, tipSlot: Long, tipHash: ByteArray, tipHeight: Long): ByteArray =
        array(integer(2), array(integer(era.toLong()), tagged24(header(block))), tip(tipSlot, tipHash, tipHeight))
    fun intersectFound(point: ByteArray, tip: ByteArray): ByteArray = array(integer(5), point, tip)
    fun startBatch(): ByteArray = array(integer(2))
    fun blockReply(era: Int, block: ByteArray): ByteArray = array(integer(4), tagged24(array(integer((era + 1).toLong()), block)))
    fun batchDone(): ByteArray = array(integer(5))
}
