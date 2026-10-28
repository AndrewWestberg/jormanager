package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger

/** Dijkstra only. Dispatch older eras to their own decoder. */
object DijkstraBlockDecoder {
    private val commonKeys = setOf(0, 1, 3, 4, 5, 7, 8, 9, 11, 14, 15, 18, 19, 20, 21, 22, 24, 25, 26)
    private val topKeys = commonKeys + setOf(2, 13, 16, 17, 23, 27)
    private val maxWord64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val minInt64 = BigInteger.ONE.shiftLeft(63).negate()
    private val maxInt64 = BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE)

    fun decodeBlock(bytes: ByteArray): DecodedLedgerBlock {
        val root = CborSpans.parse(bytes).array().also { require(it.size == 2) { "Dijkstra block must have two elements" } }
        val header = root[0]
        require(header.array().size == 2) { "Invalid Dijkstra header" }
        val body = root[1].array().also { require(it.size == 3) { "Invalid Dijkstra block body" } }
        return DecodedLedgerBlock(header, root[1], body[0].array().map { batch(it, true) }, nullable(body[1]), nullable(body[2]))
    }

    fun decodeSubmittedTransaction(bytes: ByteArray): DecodedTransactionBatch = batch(CborSpans.parse(bytes), false)

    private fun batch(raw: CborSpan, included: Boolean): DecodedTransactionBatch {
        val parts = raw.array()
        val valid: Boolean?
        val auxiliary: CborSpan
        if (included) {
            require(parts.size == 4) { "Block transaction must have four elements" }
            auxiliary = parts[2]
            valid = parts[3].boolean()
        } else {
            require(parts.size == 3 || parts.size == 4) { "Submitted transaction must have three or four elements" }
            if (parts.size == 4) require(parts[2].boolean()) { "Submitted legacy validity flag must be true" }
            auxiliary = parts.last()
            valid = null
        }
        val parent = member(parts[0], parts[1], auxiliary, true)
        val children = parent.fields[23]?.let { setItems(it, true).map { child ->
            val c = child.array()
            require(c.size == 3) { "Child transaction must have three elements" }
            member(c[0], c[1], c[2], false)
        } } ?: emptyList()
        val ids = HashSet<String>()
        for (child in children) require(ids.add(child.bodyId.toHexString())) { "Duplicate child transaction body identity" }
        return DecodedTransactionBatch(raw, parent, children, valid)
    }

    private fun member(body: CborSpan, witnesses: CborSpan, aux: CborSpan, top: Boolean): DecodedTransactionMember {
        val fields = sparse(body, if (top) topKeys else commonKeys)
        require(fields.keys.containsAll(if (top) setOf(0, 1, 2) else setOf(0, 1))) { "Missing required transaction body field" }
        val witnessData = witnessSet(witnesses)
        val inputs = inputs(fields.getValue(0), false)
        val outputs = fields.getValue(1).array().map(::output)
        val collateral = fields[13]?.let { inputs(it, true) } ?: emptyList()
        val references = fields[18]?.let { inputs(it, true) } ?: emptyList()
        for (key in listOf(3, 8, 21)) fields[key]?.let(::coin)
        fields[22]?.let { require(coin(it).signum() > 0) { "Donation must be positive" } }
        fields[15]?.let { require(unsigned(it) <= BigInteger.ONE) { "Invalid network id" } }
        for (key in listOf(7, 11)) fields[key]?.let { require(byteString(it).size == 32) { "Invalid hash length at field $key" } }
        for (key in listOf(4, 20)) fields[key]?.let { setItems(it, true) }
        fields[14]?.let(::guards)
        fields[19]?.let { require(it.entries().isNotEmpty()) { "Empty voting procedures" } }
        fields[24]?.let(::requiredGuards)
        return DecodedTransactionMember(
            Blake2b.hash256(body.bytes()), body, witnesses, nullable(aux), inputs, outputs, collateral, references,
            fields[16]?.let(::output), fields[2]?.let(::coin), fields[17]?.let(::coin),
            fields[5]?.let(::accountAmounts) ?: emptyList(), fields[25]?.let(::accountAmounts) ?: emptyList(),
            fields[26]?.let(::intervals) ?: emptyList(), fields[27]?.let(::intervals) ?: emptyList(),
            fields[9]?.let { assets(it, true) } ?: emptyList(), witnessData, nullable(aux)?.let(::auxiliary), fields
        )
    }

    private fun sparse(span: CborSpan, allowed: Set<Int>): Map<Int, CborSpan> {
        val result = LinkedHashMap<Int, CborSpan>()
        for ((k, v) in span.entries()) {
            val n = unsigned(k)
            require(n.bitLength() <= 31) { "Invalid sparse field key" }
            val key = n.toInt()
            require(key in allowed) { "Unknown or forbidden field $key at offset ${k.start}" }
            require(result.put(key, v) == null) { "Duplicate field $key at offset ${k.start}" }
        }
        return result
    }

    private fun setItems(span: CborSpan, nonempty: Boolean): List<CborSpan> {
        val items = (if (span.major == 6) span.tagged(258) else span).array()
        require(!nonempty || items.isNotEmpty()) { "Empty set at offset ${span.start}" }
        return items
    }

    private fun inputs(span: CborSpan, nonempty: Boolean): List<DecodedInput> {
        val seen = HashSet<String>()
        return setItems(span, nonempty).map {
            val pair = it.array()
            require(pair.size == 2) { "Invalid input" }
            val hash = byteString(pair[0])
            require(hash.size == 32) { "Invalid input transaction id" }
            val index = unsigned(pair[1])
            require(index <= BigInteger.valueOf(65535)) { "Input index out of range" }
            require(seen.add(hash.toHexString() + ":" + index)) { "Duplicate transaction input" }
            DecodedInput(hash, index.toInt())
        }
    }

    private fun output(span: CborSpan): DecodedOutput {
        val address: CborSpan
        val value: CborSpan
        val datum: CborSpan?
        val script: CborSpan?
        if (span.major == 4) {
            val p = span.array()
            require(p.size in 2..3) { "Invalid legacy output" }
            address = p[0]; value = p[1]; datum = p.getOrNull(2); script = null
            datum?.let { require(byteString(it).size == 32) { "Invalid datum hash" } }
        } else {
            val p = sparse(span, setOf(0, 1, 2, 3))
            address = requireNotNull(p[0]) { "Missing output address" }; value = requireNotNull(p[1]) { "Missing output value" }
            datum = p[2]; script = p[3]
            datum?.let {
                val d = it.array(); require(d.size == 2) { "Invalid datum option" }
                val kind = unsigned(d[0]); require(kind <= BigInteger.ONE) { "Unknown datum option" }
                when (kind.toInt()) {
                    0 -> require(byteString(d[1]).size == 32) { "Invalid datum hash" }
                    1 -> byteString(d[1].tagged(24))
                    else -> throw IllegalArgumentException("Unknown datum option")
                }
            }
            script?.let { byteString(it.tagged(24)) }
        }
        val amount: BigInteger
        val assetList: List<DecodedAsset>
        if (value.major == 4) {
            val v = value.array(); require(v.size == 2) { "Invalid multiasset value" }
            amount = coin(v[0]); assetList = assets(v[1], false)
        } else { amount = coin(value); assetList = emptyList() }
        return DecodedOutput(span, byteString(address), amount, assetList, datum, script)
    }

    private fun assets(span: CborSpan, mint: Boolean): List<DecodedAsset> {
        val result = mutableListOf<DecodedAsset>()
        val policies = uniqueBytesMap(span, true)
        for ((policy, names) in policies) {
            require(policy.size == 28) { "Invalid policy id" }
            for ((name, value) in uniqueBytesMap(names, true)) {
                require(name.size <= 32) { "Asset name exceeds 32 bytes" }
                val quantity = value.integer()
                if (mint) require(quantity != BigInteger.ZERO && quantity >= minInt64 && quantity <= maxInt64) { "Invalid mint quantity" }
                else require(quantity.signum() > 0 && quantity <= maxWord64) { "Invalid output asset quantity" }
                result.add(DecodedAsset(policy, name, quantity))
            }
        }
        return result
    }

    private fun accountAmounts(span: CborSpan): List<DecodedAccountAmount> = uniqueBytesMap(span, true).map { (address, value) ->
        DecodedAccountAmount(address, coin(value))
    }

    private fun intervals(span: CborSpan): List<DecodedAccountInterval> = uniqueBytesMap(span, true).map { (address, value) ->
        if (value.major == 0) DecodedAccountInterval(address, null, null, coin(value))
        else {
            val bounds = value.array(); require(bounds.size == 2) { "Invalid account interval" }
            val lower = nullable(bounds[0])?.let(::coin); val upper = nullable(bounds[1])?.let(::coin)
            require(lower != null || upper != null) { "Account interval has no bounds" }
            DecodedAccountInterval(address, lower, upper, null)
        }
    }

    private fun uniqueBytesMap(span: CborSpan, nonempty: Boolean): List<Pair<ByteArray, CborSpan>> {
        val entries = span.entries(); require(!nonempty || entries.isNotEmpty()) { "Empty map at offset ${span.start}" }
        val keys = HashSet<String>()
        return entries.map { (k, v) ->
            val key = byteString(k); require(keys.add(key.toHexString())) { "Duplicate byte-string map key" }; key to v
        }
    }

    private fun witnessSet(span: CborSpan): DecodedWitnessData {
        val fields = sparse(span, (0..8).toSet())
        val scripts = mutableListOf<DecodedScript>()
        val datums = mutableListOf<CborSpan>()
        val redeemers = mutableListOf<DecodedRedeemer>()
        for ((key, value) in fields) {
            if (key != 5) {
                val entries = setItems(value, true)
                when (key) {
                    1, 3, 6, 7, 8 -> {
                        val language = when (key) { 1 -> 0; 3 -> 1; 6 -> 2; 7 -> 3; else -> 4 }
                        entries.forEach {
                            if (language == 0) nativeScript(it) else byteString(it)
                            scripts.add(DecodedScript(language, it))
                        }
                    }
                    4 -> datums.addAll(entries)
                    0, 2 -> entries.forEach {
                        val witness = it.array()
                        require(witness.size == if (key == 0) 2 else 4) { "Invalid key witness" }
                        require(byteString(witness[0]).size == 32 && byteString(witness[1]).size == 64) { "Invalid key witness length" }
                        if (key == 2) {
                            require(byteString(witness[2]).size == 32) { "Invalid bootstrap chain code" }
                            byteString(witness[3])
                        }
                    }
                }
            } else {
                val entries = value.entries(); require(entries.isNotEmpty()) { "Empty redeemers" }
                val pointers = HashSet<Pair<Int, BigInteger>>()
                for ((pointer, payload) in entries) {
                    val p = pointer.array(); require(p.size == 2) { "Invalid redeemer pointer" }
                    val tag = unsigned(p[0]); val index = unsigned(p[1])
                    require(tag <= BigInteger.valueOf(6) && index.bitLength() <= 32) { "Invalid redeemer pointer" }
                    require(pointers.add(tag.toInt() to index)) { "Duplicate redeemer pointer" }
                    val data = payload.array(); require(data.size == 2) { "Invalid redeemer" }
                    val units = data[1].array(); require(units.size == 2 && units.all { unsigned(it) <= maxInt64 }) { "Invalid execution units" }
                    redeemers.add(DecodedRedeemer(tag.toInt(), index, data[0], unsigned(units[0]), unsigned(units[1])))
                }
            }
        }
        return DecodedWitnessData(scripts, datums, redeemers)
    }

    private fun nativeScript(span: CborSpan) {
        val p = span.array()
        require(p.isNotEmpty()) { "Empty native script" }
        val kind = unsigned(p[0]); require(kind <= BigInteger.valueOf(6)) { "Unknown native script variant" }
        when (kind.toInt()) {
            0 -> { require(p.size == 2 && byteString(p[1]).size == 28) { "Invalid native key script" } }
            1, 2 -> { require(p.size == 2); p[1].array().forEach(::nativeScript) }
            3 -> {
                require(p.size == 3)
                val threshold = p[1].integer()
                require(threshold >= minInt64 && threshold <= maxInt64)
                p[2].array().forEach(::nativeScript)
            }
            4, 5 -> { require(p.size == 2); coin(p[1]) }
            6 -> { require(p.size == 2); credential(p[1]) }
            else -> throw IllegalArgumentException("Unknown native script variant")
        }
    }

    private fun credential(span: CborSpan) {
        val p = span.array()
        require(p.size == 2 && unsigned(p[0]) <= BigInteger.ONE && byteString(p[1]).size == 28) { "Invalid credential" }
    }

    private fun guards(span: CborSpan) {
        val entries = setItems(span, false)
        val credentials = entries.firstOrNull()?.major == 4
        val seen = HashSet<String>()
        for (entry in entries) {
            val identity = if (credentials) {
                credential(entry)
                val p = entry.array()
                unsigned(p[0]).toString() + ":" + byteString(p[1]).toHexString()
            } else {
                val hash = byteString(entry)
                require(hash.size == 28) { "Invalid guard key hash" }
                "0:" + hash.toHexString()
            }
            require(seen.add(identity)) { "Duplicate guard" }
        }
    }

    private fun requiredGuards(span: CborSpan) {
        val entries = span.entries()
        require(entries.isNotEmpty()) { "Empty required guards" }
        val seen = HashSet<String>()
        for ((key, _) in entries) {
            credential(key)
            val parts = key.array()
            val identity = unsigned(parts[0]).toString() + ":" + byteString(parts[1]).toHexString()
            require(seen.add(identity)) { "Duplicate required guard" }
        }
    }

    private fun auxiliary(span: CborSpan): DecodedAuxiliaryData {
        var metadata: CborSpan? = null
        val scripts = mutableListOf<DecodedScript>()
        fun addScripts(language: Int, items: CborSpan) {
            for (script in items.array()) {
                if (language == 0) nativeScript(script) else byteString(script)
                scripts.add(DecodedScript(language, script))
            }
        }
        when (span.major) {
            5 -> metadata = span
            4 -> {
                val parts = span.array()
                require(parts.size == 2) { "Invalid auxiliary data array" }
                metadata = parts[0]
                addScripts(0, parts[1])
            }
            6 -> {
                val fields = sparse(span.tagged(259), (0..5).toSet())
                metadata = fields[0]
                for (key in 1..5) fields[key]?.let { addScripts(key - 1, it) }
            }
            else -> throw IllegalArgumentException("Invalid auxiliary data")
        }
        metadata?.let { entries ->
            for ((key, value) in entries.entries()) {
                coin(key)
                metadatum(value)
            }
        }
        return DecodedAuxiliaryData(metadata, scripts)
    }

    private fun metadatum(value: CborSpan) {
        when (value.major) {
            0, 1 -> value.integer()
            2, 3 -> require(value.payload().size <= 64) { "Metadata string exceeds 64 bytes" }
            4 -> value.array().forEach(::metadatum)
            5 -> value.entries().forEach { (key, item) -> metadatum(key); metadatum(item) }
            else -> throw IllegalArgumentException("Invalid metadata at offset ${value.start}")
        }
    }

    private fun nullable(span: CborSpan): CborSpan? = if (span.isNull()) null else span
    private fun byteString(span: CborSpan): ByteArray { require(span.major == 2) { "Expected bytes at offset ${span.start}" }; return span.payload() }
    private fun unsigned(span: CborSpan): BigInteger { require(span.major == 0) { "Expected unsigned integer at offset ${span.start}" }; return span.integer() }
    private fun coin(span: CborSpan): BigInteger = unsigned(span).also { require(it <= maxWord64) { "Coin exceeds uint64" } }
}
