package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger

/** Byron's epoch-relative slots require the selected network's genesis epochSlots. */
object ByronBlockDecoder {
    fun decode(bytes: ByteArray, epochSlots: Long): DecodedLedgerBlock {
        require(epochSlots > 0)
        val root = CborSpans.parse(bytes).array(); require(root.size == 2)
        val tag = uint(root[0]).toInt(); require(tag == 0 || tag == 1)
        val boundary = tag == 0
        val block = root[1].array(); require(block.size == 3)
        val detail = header(block[0], boundary, epochSlots)
        if (boundary) block[1].array()
        val transactions = if (boundary) emptyList() else {
            val body = block[1].array(); require(body.size == 4)
            body[0].array().map { item ->
                val txaux = item.array(); require(txaux.size == 2)
                val tx = txaux[0].array(); require(tx.size == 3)
                val inputs = tx[0].array().map { input ->
                    val p = input.array(); require(p.size == 2 && uint(p[0]) == BigInteger.ZERO) { "Unsupported Byron input variant" }
                    val embedded = CborSpans.parse(bytes(p[1].tagged(24), null)).array(); require(embedded.size == 2)
                    val ix = uint(embedded[1]); require(ix <= BigInteger.valueOf(65535))
                    DecodedInput(bytes(embedded[0], 32), ix.toInt())
                }
                val outputs = tx[1].array().map { output ->
                    val p = output.array(); require(p.size == 2)
                    val address = p[0].bytes()
                    GenesisLedgerDecoder.validateByronAddress(address)
                    DecodedOutput(output, address, uint(p[1]), emptyList(), null, null)
                }
                require(inputs.isNotEmpty() && outputs.isNotEmpty())
                tx[2].entries(); txaux[1].array()
                val member = DecodedTransactionMember(txaux[0].hash256(), txaux[0], txaux[1], null,
                    inputs, outputs, emptyList(), emptyList(), null, null, null, emptyList(), emptyList(),
                    emptyList(), emptyList(), emptyList(), DecodedWitnessData(emptyList(), emptyList(), emptyList()), null, emptyMap())
                DecodedTransactionBatch(item, member, emptyList(), true)
            }
        }
        return DecodedLedgerBlock(block[0], block[1], transactions, null, null, byronHeader = detail, encoded = bytes)
    }

    /** The caller unwraps the N2N nested context and supplies its boundary tag. */
    fun decodeHeader(bytes: ByteArray, boundary: Boolean, epochSlots: Long): DecodedByronHeader =
        header(CborSpans.parse(bytes), boundary, epochSlots)

    private fun header(raw: CborSpan, boundary: Boolean, epochSlots: Long): DecodedByronHeader {
        require(epochSlots > 0)
        val h = raw.array(); require(h.size == 5)
        val previous = bytes(h[1], 32)
        val consensus = h[3].array()
        val epoch: BigInteger
        val relative: BigInteger
        val difficulty: BigInteger
        if (boundary) {
            require(consensus.size == 2)
            epoch = uint(consensus[0]); relative = BigInteger.ZERO
            difficulty = uint(consensus[1].array().single())
        } else {
            require(consensus.size == 4)
            val slot = consensus[0].array(); require(slot.size == 2)
            epoch = uint(slot[0]); relative = uint(slot[1]); require(relative < BigInteger.valueOf(epochSlots))
            difficulty = uint(consensus[2].array().single())
        }
        val absoluteSlot = epoch.multiply(BigInteger.valueOf(epochSlots)).add(relative)
        val tag = if (boundary) 0 else 1
        val headerHash = Blake2b.hash256(byteArrayOf(0x82.toByte(), tag.toByte()), raw.bytes())
        return DecodedByronHeader(boundary, headerHash, previous, difficulty, absoluteSlot)
    }
    private fun uint(span: CborSpan): BigInteger {
        require(span.major == 0); return span.integer()
    }
    private fun bytes(span: CborSpan, size: Int?): ByteArray {
        require(span.major == 2)
        return span.payload().also { require(size == null || it.size == size) }
    }
}
