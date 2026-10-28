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
        val details = header(header)
        val body = root[1].array().also { require(it.size == 3) { "Invalid Dijkstra block body" } }
        val leios = nullable(body[1])
        leios?.let {
            val cert = it.array()
            require(cert.size == 2 && byteString(cert[0]).size <= 8192 && byteString(cert[1]).size == 48) { "Invalid Leios certificate" }
        }
        val peras = nullable(body[2])
        peras?.let(::byteString)
        require(details.containsLeiosCertificate == (leios != null)) { "Header certificate flag does not match body" }
        return DecodedLedgerBlock(header, root[1], body[0].array().map { batch(it, true) }, leios, peras, details, encoded = bytes)
    }

    private fun header(span: CborSpan): DecodedDijkstraHeader {
        val outer = span.array()
        require(outer.size == 2) { "Invalid Dijkstra header" }
        val h = outer[0].array()
        require(h.size == 12) { "Invalid Dijkstra header body" }
        fun fixed(s: CborSpan, size: Int): ByteArray = byteString(s).also { require(it.size == size) { "Invalid header field length" } }
        val block = coin(h[0]); val slot = coin(h[1])
        val previous = nullable(h[2])?.let { fixed(it, 32) }
        val issuer = fixed(h[3], 32); val vrfKey = fixed(h[4], 32)
        val vrf = h[5].array(); require(vrf.size == 2)
        val vrfResult = fixed(vrf[0], 64); val vrfProof = fixed(vrf[1], 80)
        val size = unsigned(h[6]); require(size.bitLength() <= 32)
        val bodyHash = fixed(h[7], 32)
        val ocert = h[8].array(); require(ocert.size == 4)
        fixed(ocert[0], 32); coin(ocert[1]); coin(ocert[2]); fixed(ocert[3], 64)
        val version = h[9].array(); require(version.size == 2)
        val major = unsigned(version[0]); val software = unsigned(version[1])
        require(major.bitLength() <= 32 && software.bitLength() <= 32)
        val contains = h[10].boolean()
        val announcement = nullable(h[11])?.let {
            val a = it.array(); require(a.size == 2)
            val n = unsigned(a[1]); require(n.bitLength() <= 32)
            DecodedEbAnnouncement(fixed(a[0], 32), n)
        }
        fixed(outer[1], 448)
        return DecodedDijkstraHeader(outer[0], block, slot, previous, size, bodyHash, major, software, contains, announcement, issuer, vrfKey, vrfResult, vrfProof)
    }

    fun decodeHeader(bytes: ByteArray): DecodedDijkstraHeader = header(CborSpans.parse(bytes))

    fun decodeSubmittedTransaction(bytes: ByteArray): DecodedTransactionBatch = batch(CborSpans.parse(bytes), false)

    fun decodeIncludedTransaction(bytes: ByteArray): DecodedTransactionBatch = batch(CborSpans.parse(bytes), true)

    fun decodeChildTransaction(bytes: ByteArray): DecodedTransactionMember {
        val parts = CborSpans.parse(bytes).array()
        require(parts.size == 3) { "Child transaction must have three elements" }
        return member(parts[0], parts[1], parts[2], false)
    }

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

    internal fun member(body: CborSpan, witnesses: CborSpan, aux: CborSpan?, top: Boolean, era: Int = 7): DecodedTransactionMember {
        require(era in 1..7)
        val historical = era < 7
        val fields = sparse(body, if (historical) (0..22).toSet() else if (top) topKeys else commonKeys)
        require(fields.keys.containsAll(if (top) setOf(0, 1, 2) else setOf(0, 1))) { "Missing required transaction body field" }
        val witnessData = witnessSet(witnesses, historical)
        val inputs = inputs(fields.getValue(0), false)
        val outputs = fields.getValue(1).array().map { output(it, era) }
        val collateral = fields[13]?.let { inputs(it, true) } ?: emptyList()
        val references = fields[18]?.let { inputs(it, true) } ?: emptyList()
        for (key in listOf(3, 8, 21)) fields[key]?.let(::coin)
        fields[22]?.let { require(coin(it).signum() > 0) { "Donation must be positive" } }
        fields[15]?.let { require(unsigned(it) <= BigInteger.ONE) { "Invalid network id" } }
        for (key in listOf(7, 11)) fields[key]?.let { require(byteString(it).size == 32) { "Invalid hash length at field $key" } }
        val certificates = fields[4]?.let {
            val entries = if (era < 6) it.array() else setItems(it, true)
            entries.map { cert -> certificate(cert, historical) }
        } ?: emptyList()
        val proposals = fields[20]?.let { setItems(it, true).map(::proposal) } ?: emptyList()
        val votes = fields[19]?.let(::votes) ?: emptyList()
        val guardEntries = fields[14]?.let { guards(it, !historical) } ?: emptyList()
        val requiredGuardEntries = fields[24]?.let(::requiredGuards) ?: emptyList()
        return DecodedTransactionMember(
            body.hash256(), body, witnesses, aux, inputs, outputs, collateral, references,
            fields[16]?.let { output(it, era) }, fields[2]?.let(::coin), fields[17]?.let(::coin),
            fields[5]?.let { if (era < 6) accountAmountsAllowEmpty(it) else accountAmounts(it) } ?: emptyList(),
            fields[25]?.let(::accountAmounts) ?: emptyList(),
            fields[26]?.let(::intervals) ?: emptyList(), fields[27]?.let(::intervals) ?: emptyList(),
            fields[9]?.let { assets(it, true, era) } ?: emptyList(), witnessData, aux?.let(::nullable)?.let(::auxiliary), fields,
            certificates, votes, proposals, guardEntries, requiredGuardEntries
        )
    }

    private fun fixedBytes(span: CborSpan, size: Int): ByteArray =
        byteString(span).also { require(it.size == size) { "Invalid byte-string length at ${span.start}" } }

    private fun decodedCredential(span: CborSpan): DecodedCredential {
        credential(span)
        val p = span.array()
        return DecodedCredential(unsigned(p[0]).toInt(), byteString(p[1]))
    }

    private fun anchor(span: CborSpan) {
        val a = span.array(); require(a.size == 2 && a[0].major == 3 && a[0].payload().size <= 128)
        fixedBytes(a[1], 32)
    }

    private fun drep(span: CborSpan) {
        val p = span.array(); require(p.isNotEmpty())
        val kind = unsigned(p[0]).toInt()
        require(kind in 0..3 && p.size == if (kind < 2) 2 else 1)
        if (kind < 2) fixedBytes(p[1], 28)
    }

    private fun certificate(raw: CborSpan, historical: Boolean): DecodedCertificate {
        val p = raw.array(); require(p.isNotEmpty())
        val tag = unsigned(p[0]).toInt()
        require(historical || tag !in setOf(0, 1, 5, 6)) { "Forbidden Dijkstra certificate $tag" }
        fun arity(n: Int) { require(p.size == n) { "Invalid certificate $tag" } }
        fun cred(i: Int) = decodedCredential(p[i])
        fun pool(i: Int) = fixedBytes(p[i], 28)
        fun optionalAnchor(i: Int): CborSpan? = nullable(p[i])?.also(::anchor)
        return when (tag) {
            0, 1 -> { arity(2); DecodedCertificate(raw, tag, cred(1)) }
            5 -> { arity(4); fixedBytes(p[1], 28); fixedBytes(p[2], 28); fixedBytes(p[3], 32); DecodedCertificate(raw, tag) }
            6 -> { arity(2); p[1].array(); DecodedCertificate(raw, tag) }
            2 -> { arity(3); DecodedCertificate(raw, tag, cred(1), pool(2)) }
            3 -> {
                require(p.size == 10 || p.size == 11) { "Invalid pool registration" }
                val hasBls = p.size == 11
                val bls = if (hasBls) nullable(p[3]) else null
                val key = bls?.array()?.also { require(it.size == 2) }
                val base = if (hasBls) 4 else 3
                val margin = p[base + 2].tagged(30).array(); require(margin.size == 2)
                val numerator = coin(margin[0]); val denominator = coin(margin[1])
                require(denominator.signum() > 0 && numerator <= denominator)
                val owners = setItems(p[base + 4], false).map { fixedBytes(it, 28) }
                require(owners.map { it.toHexString() }.toSet().size == owners.size)
                p[base + 5].array().forEach(::relay)
                val metadata = nullable(p[base + 6])?.also {
                    val m = it.array(); require(m.size == 2 && m[0].major == 3 && m[0].payload().size <= 128)
                    byteString(m[1])
                }
                val params = DecodedPoolParameters(pool(1), fixedBytes(p[2], 32),
                    key?.let { fixedBytes(it[0], 96) }, key?.let { fixedBytes(it[1], 48) },
                    coin(p[base]), coin(p[base + 1]), numerator, denominator,
                    accountAddress(byteString(p[base + 3])), owners, p[base + 5], metadata)
                DecodedCertificate(raw, tag, pool = params.operator, poolParameters = params)
            }
            4 -> { arity(3); DecodedCertificate(raw, tag, pool = pool(1), epoch = coin(p[2])) }
            7, 8, 17 -> { arity(3); DecodedCertificate(raw, tag, cred(1), amount = coin(p[2])) }
            9 -> { arity(3); drep(p[2]); DecodedCertificate(raw, tag, cred(1), delegateRepresentative = p[2]) }
            10 -> { arity(4); drep(p[3]); DecodedCertificate(raw, tag, cred(1), pool(2), delegateRepresentative = p[3]) }
            11 -> { arity(4); DecodedCertificate(raw, tag, cred(1), pool(2), coin(p[3])) }
            12 -> { arity(4); drep(p[2]); DecodedCertificate(raw, tag, cred(1), amount = coin(p[3]), delegateRepresentative = p[2]) }
            13 -> { arity(5); drep(p[3]); DecodedCertificate(raw, tag, cred(1), pool(2), coin(p[4]), delegateRepresentative = p[3]) }
            14 -> { arity(3); DecodedCertificate(raw, tag, cred(1), otherCredential = cred(2)) }
            15, 18 -> { arity(3); DecodedCertificate(raw, tag, cred(1), anchor = optionalAnchor(2)) }
            16 -> { arity(4); DecodedCertificate(raw, tag, cred(1), amount = coin(p[2]), anchor = optionalAnchor(3)) }
            else -> throw IllegalArgumentException("Unknown certificate tag $tag")
        }
    }

    private fun relay(span: CborSpan) {
        val p = span.array(); require(p.isNotEmpty())
        fun port(s: CborSpan) { nullable(s)?.let { require(unsigned(it) <= BigInteger.valueOf(65535)) } }
        fun dns(s: CborSpan) { require(s.major == 3 && s.payload().size <= 128) }
        when (unsigned(p[0]).toInt()) {
            0 -> {
                require(p.size == 4); port(p[1])
                nullable(p[2])?.let { fixedBytes(it, 4) }; nullable(p[3])?.let { fixedBytes(it, 16) }
            }
            1 -> { require(p.size == 3); port(p[1]); dns(p[2]) }
            2 -> { require(p.size == 2); dns(p[1]) }
            else -> throw IllegalArgumentException("Unknown relay variant")
        }
    }

    private fun votes(span: CborSpan): List<DecodedVote> {
        val result = mutableListOf<DecodedVote>()
        val voters = HashSet<String>()
        val entries = span.entries(); require(entries.isNotEmpty())
        for ((voter, actions) in entries) {
            val v = voter.array(); require(v.size == 2)
            val kind = unsigned(v[0]); require(kind <= BigInteger.valueOf(4))
            val hash = fixedBytes(v[1], 28)
            require(voters.add("$kind:${hash.toHexString()}")) { "Duplicate voter" }
            val seen = HashSet<String>()
            val actionEntries = actions.entries(); require(actionEntries.isNotEmpty())
            for ((action, procedure) in actionEntries) {
                val id = action.array(); require(id.size == 2)
                val tx = fixedBytes(id[0], 32); val index = unsigned(id[1]); require(index <= BigInteger.valueOf(65535))
                require(seen.add("${tx.toHexString()}:$index")) { "Duplicate governance action" }
                val vote = procedure.array(); require(vote.size == 2)
                val choice = unsigned(vote[0]); require(choice <= BigInteger.TWO)
                val a = nullable(vote[1])?.also(::anchor)
                result.add(DecodedVote(kind.toInt(), hash, tx, index.toInt(), choice.toInt(), a))
            }
        }
        return result
    }

    private fun proposal(raw: CborSpan): DecodedProposal {
        val p = raw.array(); require(p.size == 4)
        val action = p[2].array(); require(action.isNotEmpty())
        val kind = unsigned(action[0]); require(kind <= BigInteger.valueOf(6))
        require(action.size == listOf(4, 3, 3, 2, 5, 3, 1)[kind.toInt()]) { "Invalid governance action" }
        anchor(p[3])
        fun actionId(s: CborSpan): DecodedGovernanceActionId? = nullable(s)?.let {
            val id = it.array(); require(id.size == 2)
            val ix = unsigned(id[1]); require(ix <= BigInteger.valueOf(65535))
            DecodedGovernanceActionId(fixedBytes(id[0], 32), ix.toInt())
        }
        fun hash(s: CborSpan): ByteArray? = nullable(s)?.let { fixedBytes(it, 28) }
        val previous = if (kind.toInt() in listOf(0, 1, 3, 4, 5)) actionId(action[1]) else null
        var withdrawals = emptyList<DecodedAccountAmount>()
        var guardrail: ByteArray? = null
        var removed = emptyList<DecodedCredential>()
        var added = emptyList<DecodedCommitteeMember>()
        var numerator: BigInteger? = null
        var denominator: BigInteger? = null
        var parameters: CborSpan? = null
        var constitution: CborSpan? = null
        var major: BigInteger? = null
        var minor: BigInteger? = null
        when (kind.toInt()) {
            0 -> { protocolUpdate(action[2]); parameters = action[2]; guardrail = hash(action[3]) }
            1 -> {
                val version = action[2].array(); require(version.size == 2)
                major = unsigned(version[0]); minor = unsigned(version[1])
                require(major <= BigInteger.valueOf(13) && minor.bitLength() <= 32)
            }
            2 -> { withdrawals = accountAmountsAllowEmpty(action[1]); guardrail = hash(action[2]) }
            4 -> {
                removed = setItems(action[2], false).map(::decodedCredential)
                val seen = HashSet<String>()
                added = action[3].entries().map { (credential, epoch) ->
                    val c = decodedCredential(credential)
                    require(seen.add("${c.kind}:${c.hash.toHexString()}"))
                    DecodedCommitteeMember(c, coin(epoch))
                }
                val ratio = action[4].tagged(30).array(); require(ratio.size == 2)
                numerator = coin(ratio[0]); denominator = coin(ratio[1])
                require(denominator.signum() > 0 && numerator <= denominator)
            }
            5 -> {
                val c = action[2].array(); require(c.size == 2); anchor(c[0]); hash(c[1])
                constitution = action[2]
            }
        }
        return DecodedProposal(raw, coin(p[0]), accountAddress(byteString(p[1])), kind.toInt(), p[2], p[3],
            previous, withdrawals, guardrail, removed, added, numerator, denominator, parameters, constitution, major, minor)
    }

    private fun protocolUpdate(span: CborSpan) {
        val allowed = (0..11).toSet() + (16..56).toSet()
        val fields = sparse(span, allowed)
        fun uint(s: CborSpan, bits: Int, positive: Boolean = false) {
            val n = unsigned(s); require(n.bitLength() <= bits && (!positive || n.signum() > 0))
        }
        fun ratio(s: CborSpan, unit: Boolean = false, positive: Boolean = false) {
            val r = s.tagged(30).array(); require(r.size == 2)
            val a = coin(r[0]); val b = coin(r[1])
            require(b.signum() > 0 && (!positive || a.signum() > 0) && (!unit || a <= b))
        }
        fun units(s: CborSpan) {
            val u = s.array(); require(u.size == 2 && u.all { unsigned(it) <= maxInt64 })
        }
        for ((key, value) in fields) when (key) {
            0, 1, 5, 6, 16, 17, 30, 31, 55, 56 -> coin(value)
            2, 3, 7, 22, 28, 29, 32, 34, 35, 40, 41, 42, 45, 46, 48, 49 -> uint(value, 32)
            36 -> uint(value, 32, true)
            4, 8, 23, 24, 27, 43, 51, 52 -> uint(value, 16)
            9, 33 -> ratio(value)
            10, 11, 39, 44, 54 -> ratio(value, unit = true)
            37, 50 -> ratio(value, positive = true)
            38 -> nullable(value)?.let { ratio(it) }
            53 -> nullable(value)?.let { uint(it, 32) }
            18 -> {
                val models = sparse(value, (0..255).toSet())
                models.values.forEach { model ->
                    model.array().forEach { n -> require(n.integer() in minInt64..maxInt64) }
                }
            }
            19 -> { val p = value.array(); require(p.size == 2); p.forEach { ratio(it) } }
            20, 21, 47 -> units(value)
            25, 26 -> {
                val thresholds = value.array(); require(thresholds.size == if (key == 25) 5 else 10)
                thresholds.forEach { ratio(it, unit = true) }
            }
            else -> throw IllegalArgumentException("Unknown protocol parameter $key")
        }
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
        val seen = HashSet<String>()
        for (item in items) require(seen.add(item.hash256().toHexString())) { "Duplicate set item at ${item.start}" }
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

    private fun output(span: CborSpan, era: Int): DecodedOutput {
        val address: CborSpan
        val value: CborSpan
        val datum: CborSpan?
        val script: CborSpan?
        var datumKind: Int? = null
        var scriptDetails: DecodedScript? = null
        if (span.major == 4) {
            val p = span.array()
            require(p.size in 2..3) { "Invalid legacy output" }
            address = p[0]; value = p[1]; datum = p.getOrNull(2); script = null
            datum?.let { require(byteString(it).size == 32) { "Invalid datum hash" }; datumKind = 0 }
        } else {
            val p = sparse(span, setOf(0, 1, 2, 3))
            address = requireNotNull(p[0]) { "Missing output address" }; value = requireNotNull(p[1]) { "Missing output value" }
            datum = p[2]; script = p[3]
            datum?.let {
                val d = it.array(); require(d.size == 2) { "Invalid datum option" }
                val kind = unsigned(d[0]); require(kind <= BigInteger.ONE) { "Unknown datum option" }
                datumKind = kind.toInt()
                when (kind.toInt()) {
                    0 -> require(byteString(d[1]).size == 32) { "Invalid datum hash" }
                    1 -> plutusData(CborSpans.parse(byteString(d[1].tagged(24))))
                    else -> throw IllegalArgumentException("Unknown datum option")
                }
            }
            script?.let {
                val encoded = CborSpans.parse(byteString(it.tagged(24)))
                val parts = encoded.array(); require(parts.size == 2)
                val language = unsigned(parts[0]); require(language <= BigInteger.valueOf(4))
                if (language == BigInteger.ZERO) nativeScript(parts[1]) else byteString(parts[1])
                scriptDetails = DecodedScript(language.toInt(), parts[1])
            }
        }
        val amount: BigInteger
        val assetList: List<DecodedAsset>
        if (value.major == 4) {
            val v = value.array(); require(v.size == 2) { "Invalid multiasset value" }
            amount = coin(v[0]); assetList = assets(v[1], false, era)
        } else { amount = coin(value); assetList = emptyList() }
        return DecodedOutput(span, byteString(address), amount, assetList, datum, script, datumKind, scriptDetails)
    }

    private fun assets(span: CborSpan, mint: Boolean, era: Int): List<DecodedAsset> {
        val result = mutableListOf<DecodedAsset>()
        val policies = uniqueBytesMap(span, era >= 7)
        for ((policy, names) in policies) {
            require(policy.size == 28) { "Invalid policy id" }
            for ((name, value) in uniqueBytesMap(names, era >= 6)) {
                require(name.size <= 32) { "Asset name exceeds 32 bytes" }
                val quantity = value.integer()
                if (mint) require(quantity >= minInt64 && quantity <= maxInt64 && (era < 6 || quantity != BigInteger.ZERO)) { "Invalid mint quantity" }
                else require(value.major == 0 && quantity <= maxWord64 && (era < 6 || quantity.signum() > 0)) { "Invalid output asset quantity" }
                if (quantity == BigInteger.ZERO) continue
                result.add(DecodedAsset(policy, name, quantity))
            }
        }
        return result
    }

    private fun accountAmounts(span: CborSpan): List<DecodedAccountAmount> = uniqueBytesMap(span, true).map { (address, value) ->
        DecodedAccountAmount(accountAddress(address), coin(value))
    }

    private fun accountAmountsAllowEmpty(span: CborSpan): List<DecodedAccountAmount> = uniqueBytesMap(span, false).map { (address, value) ->
        DecodedAccountAmount(accountAddress(address), coin(value))
    }

    private fun intervals(span: CborSpan): List<DecodedAccountInterval> = uniqueBytesMap(span, true).map { (address, value) ->
        accountAddress(address)
        if (value.major == 0) DecodedAccountInterval(address, null, null, coin(value))
        else {
            val bounds = value.array(); require(bounds.size == 2) { "Invalid account interval" }
            val lower = nullable(bounds[0])?.let(::coin); val upper = nullable(bounds[1])?.let(::coin)
            require(lower != null || upper != null) { "Account interval has no bounds" }
            DecodedAccountInterval(address, lower, upper, null)
        }
    }

    private fun accountAddress(bytes: ByteArray): ByteArray {
        require(bytes.size == 29 && (bytes[0].toInt() and 0xee) == 0xe0) { "Invalid account address" }
        return bytes
    }

    private fun uniqueBytesMap(span: CborSpan, nonempty: Boolean): List<Pair<ByteArray, CborSpan>> {
        val entries = span.entries(); require(!nonempty || entries.isNotEmpty()) { "Empty map at offset ${span.start}" }
        val keys = HashSet<String>()
        return entries.map { (k, v) ->
            val key = byteString(k); require(keys.add(key.toHexString())) { "Duplicate byte-string map key" }; key to v
        }
    }

    private fun witnessSet(span: CborSpan, historical: Boolean): DecodedWitnessData {
        val fields = sparse(span, (0..8).toSet())
        val scripts = mutableListOf<DecodedScript>()
        val datums = mutableListOf<CborSpan>()
        val redeemers = mutableListOf<DecodedRedeemer>()
        for ((key, value) in fields) {
            if (key != 5) {
                val entries = setItems(value, !historical)
                when (key) {
                    1, 3, 6, 7, 8 -> {
                        val language = when (key) { 1 -> 0; 3 -> 1; 6 -> 2; 7 -> 3; else -> 4 }
                        entries.forEach {
                            if (language == 0) nativeScript(it) else byteString(it)
                            scripts.add(DecodedScript(language, it))
                        }
                    }
                    4 -> { entries.forEach(::plutusData); datums.addAll(entries) }
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
                if (historical && value.major == 4) {
                    val pointers = HashSet<Pair<Int, BigInteger>>()
                    for (entry in value.array()) {
                        val p = entry.array(); require(p.size == 4)
                        val tag = unsigned(p[0]); val index = unsigned(p[1])
                        require(tag <= BigInteger.valueOf(5) && index.bitLength() <= 32 && pointers.add(tag.toInt() to index))
                        val units = p[3].array(); require(units.size == 2 && units.all { unsigned(it) <= maxInt64 })
                        plutusData(p[2])
                        redeemers.add(DecodedRedeemer(tag.toInt(), index, p[2], unsigned(units[0]), unsigned(units[1])))
                    }
                    continue
                }
                val entries = value.entries(); require(historical || entries.isNotEmpty()) { "Empty redeemers" }
                val pointers = HashSet<Pair<Int, BigInteger>>()
                for ((pointer, payload) in entries) {
                    val p = pointer.array(); require(p.size == 2) { "Invalid redeemer pointer" }
                    val tag = unsigned(p[0]); val index = unsigned(p[1])
                    require(tag <= BigInteger.valueOf(6) && index.bitLength() <= 32) { "Invalid redeemer pointer" }
                    require(pointers.add(tag.toInt() to index)) { "Duplicate redeemer pointer" }
                    val data = payload.array(); require(data.size == 2) { "Invalid redeemer" }
                    val units = data[1].array(); require(units.size == 2 && units.all { unsigned(it) <= maxInt64 }) { "Invalid execution units" }
                    plutusData(data[0])
                    redeemers.add(DecodedRedeemer(tag.toInt(), index, data[0], unsigned(units[0]), unsigned(units[1])))
                }
            }
        }
        return DecodedWitnessData(scripts, datums, redeemers)
    }

    private fun nativeScript(span: CborSpan) {
        val pending = ArrayDeque<CborSpan>()
        pending.addLast(span)
        while (pending.isNotEmpty()) {
            val p = pending.removeLast().array()
            require(p.isNotEmpty()) { "Empty native script" }
            val kind = unsigned(p[0]); require(kind <= BigInteger.valueOf(6)) { "Unknown native script variant" }
            when (kind.toInt()) {
                0 -> require(p.size == 2 && byteString(p[1]).size == 28) { "Invalid native key script" }
                1, 2 -> { require(p.size == 2); pending.addAll(p[1].array()) }
                3 -> {
                    require(p.size == 3)
                    val threshold = p[1].integer()
                    require(threshold >= minInt64 && threshold <= maxInt64)
                    pending.addAll(p[2].array())
                }
                4, 5 -> { require(p.size == 2); coin(p[1]) }
                6 -> { require(p.size == 2); credential(p[1]) }
                else -> throw IllegalArgumentException("Unknown native script variant")
            }
        }
    }

    private fun credential(span: CborSpan) {
        val p = span.array()
        require(p.size == 2 && unsigned(p[0]) <= BigInteger.ONE && byteString(p[1]).size == 28) { "Invalid credential" }
    }

    private fun guards(span: CborSpan, nonempty: Boolean): List<DecodedCredential> {
        val entries = setItems(span, nonempty)
        val credentials = entries.firstOrNull()?.major == 4
        val result = mutableListOf<DecodedCredential>()
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
            result.add(if (credentials) decodedCredential(entry) else DecodedCredential(0, byteString(entry)))
        }
        return result
    }

    private fun requiredGuards(span: CborSpan): List<DecodedRequiredGuard> {
        val entries = span.entries()
        require(entries.isNotEmpty()) { "Empty required guards" }
        val seen = HashSet<String>()
        val result = mutableListOf<DecodedRequiredGuard>()
        for ((key, value) in entries) {
            credential(key)
            val parts = key.array()
            val identity = unsigned(parts[0]).toString() + ":" + byteString(parts[1]).toHexString()
            require(seen.add(identity)) { "Duplicate required guard" }
            nullable(value)?.let(::plutusData)
            result.add(DecodedRequiredGuard(decodedCredential(key), nullable(value)))
        }
        return result
    }

    private fun plutusData(span: CborSpan) {
        val pending = ArrayDeque<CborSpan>()
        pending.addLast(span)
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            when (item.major) {
                0, 1 -> item.integer()
                2 -> {
                    if (item.argument == null) item.children.forEach { require(it.payload().size <= 64) }
                    else require(item.payload().size <= 64)
                }
                4, 5 -> pending.addAll(item.children)
                6 -> {
                    val tag = requireNotNull(item.argument)
                    when {
                        tag == BigInteger.TWO || tag == BigInteger.valueOf(3) -> {
                            val b = item.children.single(); require(b.major == 2)
                            pending.addLast(b)
                        }
                        tag == BigInteger.valueOf(102) -> {
                            val p = item.children.single().array(); require(p.size == 2)
                            coin(p[0]); pending.addAll(p[1].array())
                        }
                        tag in BigInteger.valueOf(121)..BigInteger.valueOf(127) ||
                            tag in BigInteger.valueOf(1280)..BigInteger.valueOf(1400) ->
                            pending.addAll(item.children.single().array())
                        else -> throw IllegalArgumentException("Unknown Plutus data tag $tag")
                    }
                }
                else -> throw IllegalArgumentException("Invalid Plutus data at ${item.start}")
            }
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
            val labels = HashSet<BigInteger>()
            for ((key, value) in entries.entries()) {
                require(labels.add(coin(key))) { "Duplicate metadata label" }
                metadatum(value)
            }
        }
        return DecodedAuxiliaryData(metadata, scripts)
    }

    private fun metadatum(value: CborSpan) {
        val pending = ArrayDeque<CborSpan>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            when (item.major) {
                0, 1 -> item.integer()
                2, 3 -> require(item.payload().size <= 64) { "Metadata string exceeds 64 bytes" }
                4, 5 -> pending.addAll(item.children)
                else -> throw IllegalArgumentException("Invalid metadata at offset ${item.start}")
            }
        }
    }

    private fun nullable(span: CborSpan): CborSpan? = if (span.isNull()) null else span
    private fun byteString(span: CborSpan): ByteArray { require(span.major == 2) { "Expected bytes at offset ${span.start}" }; return span.payload() }
    private fun unsigned(span: CborSpan): BigInteger { require(span.major == 0) { "Expected unsigned integer at offset ${span.start}" }; return span.integer() }
    private fun coin(span: CborSpan): BigInteger = unsigned(span).also { require(it <= maxWord64) { "Coin exceeds uint64" } }
}
