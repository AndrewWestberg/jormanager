package com.swiftmako.jormanager.utils

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.StakeFraction
import com.swiftmako.jormanager.moshi.adapters.LeaderLogLedgerJsonAdapter
import com.swiftmako.jormanager.spring.config.Configuration
import java.math.BigDecimal
import java.math.BigInteger
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardanoLeaderElectionTest {
    private val scale = CardanoFixedPoint.SCALE
    private val moshi = Configuration().getMoshi()

    @Test
    fun `every node value and near-boundary vector matches prepared production thresholds`() {
        val vectors = loadVectors()
        assertThat(vectors).hasSize(352)
        val coefficients = vectors.associate { it.f to CardanoLeaderElection.prepareActiveSlotCoefficient(it.f) }
        val thresholds = vectors.associate { it.key to CardanoLeaderElection.prepare(coefficients.getValue(it.f), it.stake, it.width) }
        vectors.forEachIndexed { row, vector ->
            val threshold = thresholds.getValue(vector.key)
            assertWithMessage("node vector $row width").that(threshold.vrfSizeBytes).isEqualTo(vector.width)
            assertWithMessage("node vector $row sigma").that(threshold.sigmaRaw).isEqualTo(vector.sigma)
            assertWithMessage("node vector $row c").that(threshold.cRaw).isEqualTo(vector.c)
            assertWithMessage("node vector $row x").that(threshold.xRaw).isEqualTo(vector.x)
            assertWithMessage("node vector $row q").that(threshold.qRaw(vector.cert)).isEqualTo(vector.q)
            assertWithMessage("node vector $row eligibility").that(threshold.isLeader(vector.cert)).isEqualTo(vector.leader)
        }
        // Assert the required matrix is actually represented, not merely that the file is nonempty.
        for (width in listOf(32, 64)) {
            val maximum = BigInteger.ONE.shiftLeft(width * 8)
            for (stake in listOf(stake(0, 45000000000000000), stake(1, 45000000000000000), stake(1, 3), stake(2, 3), stake(1, 1))) {
                for (f in listOf("0.05", "0.00001", "0.12345678901234567", "0.5", "1")) {
                    for (cert in listOf(BigInteger.ZERO, BigInteger.ONE, maximum - BigInteger.ONE, maximum)) {
                        assertWithMessage("missing boundary $width $stake $f $cert")
                            .that(vectors.any { it.width == width && it.stake == stake && it.f.compareTo(BigDecimal(f)) == 0 && it.cert == cert })
                            .isTrue()
                    }
                }
            }
        }
        val nearBoundaries = vectors.drop(280).groupBy { it.key }
        assertThat(nearBoundaries).hasSize(24)
        nearBoundaries.values.forEach { triple ->
            assertThat(triple).hasSize(3)
            assertThat(triple.map { it.leader }).containsExactly(true, true, false).inOrder()
            assertThat(triple[1].cert - triple[0].cert).isEqualTo(BigInteger.ONE)
            assertThat(triple[2].cert - triple[1].cert).isEqualTo(BigInteger.ONE)
        }
    }

    @Test
    fun `repeating stake fractions retain integers and floor before exponent negation`() {
        val coefficient = CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("0.05"))
        assertThat(coefficient.cRaw).isEqualTo(BigInteger("-512932943875505334261962382072846"))
        val oneThird = CardanoLeaderElection.prepare(coefficient, stake(1, 3), 32)
        val twoThirds = CardanoLeaderElection.prepare(coefficient, stake(2, 3), 64)
        assertThat(oneThird.sigmaRaw).isEqualTo(BigInteger("3333333333333333333333333333333333"))
        assertThat(twoThirds.sigmaRaw).isEqualTo(BigInteger("6666666666666666666666666666666666"))
        assertThat(oneThird.xRaw).isEqualTo(BigInteger("170977647958501778087320794024282"))
        assertThat(twoThirds.xRaw).isEqualTo(BigInteger("341955295917003556174641588048564"))
        val large = StakeFraction(BigInteger("9007199254740993"), BigInteger("18014398509481986"))
        assertThat(large.poolStake).isEqualTo(BigInteger("9007199254740993"))
        assertThat(CardanoLeaderElection.prepare(coefficient, large, 32).sigmaRaw).isEqualTo(scale / BigInteger.TWO)
        assertThat(loadVectors().any { it.stake == large }).isTrue()
    }

    @Test
    fun `zero stake rejects equality but coefficient one elects even zero stake and bounded maximum`() {
        for (width in listOf(32, 64)) {
            val maximum = BigInteger.ONE.shiftLeft(8 * width)
            val zeroStake = stake(0, 45000000000000000)
            val ordinary = CardanoLeaderElection.prepare(CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("0.05")), zeroStake, width)
            val always = CardanoLeaderElection.prepare(CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("1.000")), zeroStake, width)
            assertThat(always.cRaw).isNull()
            assertThat(always.xRaw).isNull()
            for (cert in listOf(BigInteger.ZERO, BigInteger.ONE, maximum - BigInteger.ONE, maximum)) {
                assertThat(ordinary.isLeader(cert)).isFalse()
                assertThat(always.isLeader(cert)).isTrue()
            }
            assertThat(ordinary.qRaw(maximum)).isEqualTo(maximum * scale)
            assertThat(ordinary.qRaw(maximum - BigInteger.ONE)).isEqualTo(maximum * scale)
            for (threshold in listOf(ordinary, always)) {
                assertThrows<IllegalArgumentException> { threshold.isLeader(-BigInteger.ONE) }
                assertThrows<IllegalArgumentException> { threshold.isLeader(maximum + BigInteger.ONE) }
            }
        }
    }

    @Test
    fun `coefficient validation uses normalized decimal precision and preserves original value`() {
        for (invalid in listOf("0", "-0.05", "1.0000000000000000001", "2", "1e-20", "0.12345678901234567891")) {
            assertThrows<IllegalArgumentException>(invalid) { CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal(invalid)) }
        }
        for (valid in listOf("1e-19", "0.1234567890123456789", "0.050000000000000000000000", "1.000000000000000000000000")) {
            val original = BigDecimal(valid)
            assertThat(CardanoLeaderElection.prepareActiveSlotCoefficient(original).value).isEqualTo(original)
        }
        val plain = CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("0.05"))
        val padded = CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("0.050000000000000000000000"))
        assertThat(padded.cRaw).isEqualTo(plain.cRaw)
    }

    @Test
    fun `invalid stake fractions and VRF widths fail during preparation`() {
        for ((pool, active) in listOf(-1L to 1L, 2L to 1L, 0L to 0L, 0L to -1L)) {
            assertThrows<IllegalArgumentException> { stake(pool, active) }
        }
        val coefficient = CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal("0.05"))
        for (width in listOf(Int.MIN_VALUE, -1, 0, 31, 33, 63, 65, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { CardanoLeaderElection.prepare(coefficient, stake(1, 3), width) }
        }
    }

    @Test
    fun `production Moshi reads and writes exact decimal numbers including scientific notation`() {
        val decimalAdapter = moshi.adapter(BigDecimal::class.java)
        val genesisAdapter = moshi.adapter(GenesisShelley::class.java)
        for (literal in listOf("0.1234567890123456789", "1.234567890123456789E-1", "0.00001", "1E-19")) {
            val expected = BigDecimal(literal)
            assertThat(decimalAdapter.fromJson(literal)).isEqualTo(expected)
            assertNumericDecimal(decimalAdapter.toJson(expected), expected)
            val genesis = requireNotNull(genesisAdapter.fromJson(genesisJson(literal)))
            assertThat(genesis.activeSlotsCoeff).isEqualTo(expected)
            assertThat(CardanoLeaderElection.prepareActiveSlotCoefficient(genesis.activeSlotsCoeff).value).isEqualTo(expected)
            assertObjectDecimal(genesisAdapter.toJson(genesis), "activeSlotsCoeff", expected)
        }
        assertThat(decimalAdapter.fromJson("null")).isNull()
        assertThrows<Exception> { decimalAdapter.fromJson("\"not-a-number\"") }
        assertThrows<JsonDataException> { genesisAdapter.fromJson(genesisJson("null")) }
        assertThrows<JsonDataException> { genesisAdapter.fromJson(genesisJson("0.05").replace("\"activeSlotsCoeff\":0.05,", "")) }
        assertThrows<Exception> { genesisAdapter.fromJson(genesisJson("\"invalid\"")) }
    }

    @Test
    fun `protocol decentralization keeps exact wire name precision and null behavior`() {
        val adapter = moshi.adapter(ProtocolParameters::class.java)
        for (literal in listOf("0.0004", "4E-4", "0.1234567890123456789")) {
            val parameters = requireNotNull(adapter.fromJson(protocolJson("\"decentralization\":$literal,")))
            assertThat(parameters.decentralisationParam).isEqualTo(BigDecimal(literal))
            assertObjectDecimal(adapter.toJson(parameters), "decentralization", BigDecimal(literal))
        }
        assertThat(requireNotNull(adapter.fromJson(protocolJson(""))).decentralisationParam).isNull()
        assertThat(requireNotNull(adapter.fromJson(protocolJson("\"decentralization\":null,"))).decentralisationParam).isNull()
        assertThrows<Exception> { adapter.fromJson(protocolJson("\"decentralization\":\"invalid\",")) }
    }

    @Test
    fun `legacy ledger adapter preserves current and proposed decimals and unrounded snapshots`() {
        val adapter = LeaderLogLedgerJsonAdapter(setOf("pool"))
        val snapshots = """"esSnapshots":{"pstakeSet":${snapshot(1, 2)},"pstakeMark":${snapshot(2, 1)}}"""
        val current = "0.1234567890123456789"
        val proposed = "4.000000000000000001E-4"
        val oldVotes = (1..5).joinToString(",") { "\"v$it\":{\"_d\":$proposed}" }
        val old = """{"esPp":{"decentralisationParam":$current},$snapshots,"esLState":{"utxoState":{"ppups":{"proposals":{$oldVotes}}}}}"""
        val modern = """{"stateBefore":{"esPp":{"decentralisationParam":$current},$snapshots,"esLState":{"utxoState":{"ppups":{"proposals":[["voter",{"decentralisationParam":$proposed}]]}}}}}"""
        for (json in listOf(old, modern)) {
            val ledger = requireNotNull(adapter.fromJson(json))
            assertThat(ledger.decentralizationParameter).isEqualTo(BigDecimal(current))
            assertThat(ledger.futureDecentralizationParameter).isEqualTo(BigDecimal(proposed))
            assertThat(ledger.poolIdToSigma.getValue("pool")).isEqualTo(stake(1, 3))
            assertThat(ledger.futurePoolIdToSigma.getValue("pool")).isEqualTo(stake(2, 3))
        }
        val unchanged = requireNotNull(adapter.fromJson("""{"esPp":{"decentralisationParam":$current},$snapshots}"""))
        assertThat(unchanged.futureDecentralizationParameter).isEqualTo(BigDecimal(current))
    }

    private fun snapshot(pool: Int, other: Int): String =
        """{"stake":[[{"key hash":"a"},$pool],[{"key hash":"b"},$other]],"delegations":[[{"key hash":"a"},"pool"],[{"key hash":"b"},"other"]]}"""

    private fun genesisJson(f: String): String =
        """{"activeSlotsCoeff":$f,"networkId":"Testnet","slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2020-01-01T00:00:00Z","maxKESEvolutions":62}"""

    private fun protocolJson(d: String): String =
        """{$d"stakePoolDeposit":500000000,"protocolVersion":{"major":9,"minor":0},"maxTxSize":16384,"minPoolCost":170000000,"txFeePerByte":44,"maxBlockBodySize":90112,"txFeeFixed":155381,"poolRetireMaxEpoch":18,"maxBlockHeaderSize":1100,"stakeAddressDeposit":2000000,"stakePoolTargetNum":500,"monetaryExpansion":0.003,"treasuryCut":0.2,"poolPledgeInfluence":0.3}"""

    private fun assertNumericDecimal(json: String, expected: BigDecimal) {
        JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
            assertThat(reader.peek()).isEqualTo(JsonReader.Token.NUMBER)
            assertThat(BigDecimal(reader.nextString())).isEqualTo(expected)
            assertThat(reader.peek()).isEqualTo(JsonReader.Token.END_DOCUMENT)
        }
    }

    private fun assertObjectDecimal(json: String, name: String, expected: BigDecimal) {
        JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
            var found = false
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == name) {
                    assertThat(reader.peek()).isEqualTo(JsonReader.Token.NUMBER)
                    assertThat(BigDecimal(reader.nextString())).isEqualTo(expected)
                    found = true
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            assertThat(found).isTrue()
        }
    }

    private fun stake(pool: Long, active: Long): StakeFraction = StakeFraction(BigInteger.valueOf(pool), BigInteger.valueOf(active))

    private data class Vector(
        val width: Int,
        val cert: BigInteger,
        val stake: StakeFraction,
        val f: BigDecimal,
        val leader: Boolean,
        val sigma: BigInteger,
        val c: BigInteger?,
        val q: BigInteger,
        val x: BigInteger?,
    ) {
        val key: Triple<Int, StakeFraction, BigDecimal> get() = Triple(width, stake, f)
    }

    private fun loadVectors(): List<Vector> =
        requireNotNull(javaClass.getResourceAsStream("/leaderlog/node-vectors.tsv")).bufferedReader().use { reader ->
            reader.lineSequence().mapIndexed { row, line ->
                val columns = line.split('\t')
                require(columns.size == 11) { "Malformed node vector $row" }
                Vector(
                    columns[0].toInt(),
                    columns[1].toBigInteger(),
                    StakeFraction(columns[2].toBigInteger(), columns[3].toBigInteger()),
                    BigDecimal(columns[4]).divide(BigDecimal(columns[5])),
                    columns[6].toBooleanStrict(),
                    columns[7].toBigInteger(),
                    columns[8].takeUnless { it == "none" }?.toBigInteger(),
                    columns[9].toBigInteger(),
                    columns[10].takeUnless { it == "none" }?.toBigInteger(),
                )
            }.toList()
        }
}
