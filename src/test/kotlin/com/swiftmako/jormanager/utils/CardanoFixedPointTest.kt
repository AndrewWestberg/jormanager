package com.swiftmako.jormanager.utils

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.squareup.moshi.Moshi
import java.io.BufferedReader
import java.math.BigDecimal
import java.math.BigInteger
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardanoFixedPointTest {
    private val scale = CardanoFixedPoint.SCALE
    private val whitespace = Regex("\\s+")
    private val fixedNumber = Regex("-?\\d+\\.\\d{34}")

    @Test
    fun `signed division multiplication and rational conversion floor rather than truncate`() {
        val positive = BigInteger.valueOf(7)
        val negative = -positive
        val three = BigInteger.valueOf(3)
        assertThat(CardanoFixedPoint.floorDiv(positive, three)).isEqualTo(BigInteger.TWO)
        assertThat(CardanoFixedPoint.floorDiv(negative, three)).isEqualTo(BigInteger.valueOf(-3))
        assertThat(CardanoFixedPoint.floorDiv(positive, -three)).isEqualTo(BigInteger.valueOf(-3))
        assertThat(CardanoFixedPoint.floorDiv(negative, -three)).isEqualTo(BigInteger.TWO)
        assertThat(CardanoFixedPoint.floorDiv(BigInteger.valueOf(-6), three)).isEqualTo(BigInteger.valueOf(-2))
        assertThat(CardanoFixedPoint.floorDiv(BigInteger.ZERO, -three)).isEqualTo(BigInteger.ZERO)
        assertThat(CardanoFixedPoint.multiply(-BigInteger.ONE, BigInteger.ONE)).isEqualTo(-BigInteger.ONE)
        assertThat(CardanoFixedPoint.divide(-BigInteger.ONE, three * scale)).isEqualTo(-BigInteger.ONE)
        assertThat(CardanoFixedPoint.fromRatio(-BigInteger.ONE, three)).isEqualTo(-(scale / three) - BigInteger.ONE)
        assertThat(CardanoFixedPoint.fromRatio(BigInteger.ONE, three)).isEqualTo(scale / three)
        assertThat(CardanoFixedPoint.fromRatio(BigInteger.TWO, three)).isEqualTo(scale * BigInteger.TWO / three)
        assertThat(
            CardanoFixedPoint.fromRatio(BigInteger("9007199254740993"), BigInteger("18014398509481986")),
        ).isEqualTo(scale / BigInteger.TWO)
        assertThrows<ArithmeticException> { CardanoFixedPoint.floorDiv(BigInteger.ONE, BigInteger.ZERO) }
        assertThrows<ArithmeticException> { CardanoFixedPoint.divide(scale, BigInteger.ZERO) }
        assertThrows<ArithmeticException> { CardanoFixedPoint.fromRatio(BigInteger.ONE, BigInteger.ZERO) }
    }

    @Test
    fun `log and exponential preserve node primitive boundaries`() {
        assertThat(CardanoFixedPoint.exp(BigInteger.ZERO)).isEqualTo(scale)
        assertThat(CardanoFixedPoint.ln(scale)).isEqualTo(BigInteger.ZERO)
        assertThat(CardanoFixedPoint.ln(raw("0.9500000000000000000000000000000000")))
            .isEqualTo(raw("-0.0512932943875505334261962382072846"))
        assertThat(CardanoFixedPoint.exp(-scale))
            .isEqualTo(CardanoFixedPoint.divide(scale, CardanoFixedPoint.exp(scale)))
        assertThrows<IllegalArgumentException> { CardanoFixedPoint.ln(BigInteger.ZERO) }
        assertThrows<IllegalArgumentException> { CardanoFixedPoint.ln(-BigInteger.ONE) }
    }

    @Test
    fun `comparison includes equality in above and retains iteration limit`() {
        assertThat(CardanoFixedPoint.taylorExpCmp(scale, BigInteger.ZERO))
            .isEqualTo(CardanoFixedPoint.Comparison.ABOVE)
        assertThat(CardanoFixedPoint.taylorExpCmp(scale - BigInteger.ONE, BigInteger.ZERO))
            .isEqualTo(CardanoFixedPoint.Comparison.BELOW)
        assertThat(CardanoFixedPoint.taylorExpCmp(scale, scale * BigInteger.valueOf(1000000)))
            .isEqualTo(CardanoFixedPoint.Comparison.MAX_REACHED)
    }

    @Test
    fun `all golden arithmetic rows equal node with every C divergence explained`() {
        val divergenceRecords = loadDivergences()
        val divergences = divergenceRecords.associateBy { (it.getValue("row") as Number).toInt() to it.getValue("field") }
        assertThat(divergences.size).isEqualTo(divergenceRecords.size)
        val explained = mutableSetOf<Pair<Int, Any?>>()
        val inputDigest = MessageDigest.getInstance("SHA-256")
        val cDigest = MessageDigest.getInstance("SHA-256")
        val logBase = CardanoFixedPoint.ln(scale * BigInteger.valueOf(9) / BigInteger.TEN)
        gzipReader("golden_tests.txt.gz", inputDigest).use { input ->
            gzipReader("golden_tests_result.txt.gz", cDigest).use { cOutput ->
                gzipReader("node-arithmetic.tsv.gz").use { nodeOutput ->
                    var row = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        val fields = columns(line, 3)
                        val c = columns(requireNotNull(cOutput.readLine()) { "C corpus truncated at row $row" }, 6)
                        val node = columns(requireNotNull(nodeOutput.readLine()) { "Node corpus truncated at row $row" }, 5)
                        val x = fields[0].toBigInteger()
                        val a = fields[1].toBigInteger()
                        val b = fields[2].toBigInteger()
                        val exponent = CardanoFixedPoint.multiply(b, logBase)
                        val actual = listOf(
                            CardanoFixedPoint.exp(x),
                            -CardanoFixedPoint.ln(a),
                            scale - CardanoFixedPoint.exp(exponent),
                        )
                        val names = listOf("exp", "negativeLn", "threshold")
                        for (field in 0..2) {
                            val nodeRaw = raw(node[field])
                            val cRaw = raw(c[field])
                            assertWithMessage("row $row ${names[field]}").that(actual[field]).isEqualTo(nodeRaw)
                            val key = row to names[field]
                            val divergence = divergences[key]
                            if (cRaw != nodeRaw) {
                                requireNotNull(divergence) { "Unexplained C/node difference at $key" }
                                assertThat(divergence["cResult"]).isEqualTo(c[field])
                                assertThat(divergence["nodeResult"]).isEqualTo(node[field])
                                assertThat(divergence["input"]).isEqualTo(line)
                                explained.add(key)
                            } else {
                                assertWithMessage("spurious divergence $key").that(divergence).isNull()
                            }
                        }
                        assertWithMessage("node comparator row $row").that(
                            CardanoFixedPoint.taylorExpCmp(CardanoFixedPoint.fromRatio(scale, scale - a), -exponent),
                        ).isEqualTo(comparison(node[3]))
                        validateIterations(node[3], node[4])
                        row++
                    }
                    assertThat(row).isEqualTo(100000)
                    assertThat(cOutput.readLine()).isNull()
                    assertThat(nodeOutput.readLine()).isNull()
                }
            }
        }
        assertThat(explained).containsExactlyElementsIn(divergences.keys)
        assertThat(inputDigest.digest().toHex()).isEqualTo("54da107c827bf9d21484f88bbbe8dc140071d44aa2a6d665113a1dfd26dcad9a")
        assertThat(cDigest.digest().toHex()).isEqualTo("1918c67d0043dc4b03a430eac7c989c5e753c405b7d7e2d4d7cac177da7b1a69")
    }

    @Test
    fun `node comparison fixtures exercise production comparator`() {
        resourceReader("node-comparisons.tsv").use { reader ->
            var count = 0
            reader.forEachLine { line ->
                val fields = columns(line, 4)
                assertWithMessage("comparison fixture $count")
                    .that(CardanoFixedPoint.taylorExpCmp(fields[0].toBigInteger(), fields[1].toBigInteger()))
                    .isEqualTo(comparison(fields[2]))
                validateIterations(fields[2], fields[3])
                count++
            }
            assertThat(count).isAtLeast(2)
        }
    }

    @Test
    fun `node value diagnostics preserve all raw conversion intermediates`() {
        resourceReader("node-vectors.tsv").use { reader ->
            var count = 0
            reader.forEachLine { line ->
                val fields = columns(line, 11)
                val maximum = BigInteger.ONE.shiftLeft(8 * fields[0].toInt())
                val certNat = fields[1].toBigInteger()
                val sigma = CardanoFixedPoint.fromRatio(fields[2].toBigInteger(), fields[3].toBigInteger())
                val f = CardanoFixedPoint.fromRatio(fields[4].toBigInteger(), fields[5].toBigInteger())
                val q = if (certNat == maximum) maximum * scale else CardanoFixedPoint.fromRatio(maximum, maximum - certNat)
                assertWithMessage("sigma row $count").that(sigma).isEqualTo(fields[7].toBigInteger())
                assertWithMessage("q row $count").that(q).isEqualTo(fields[9].toBigInteger())
                if (fields[4].toBigInteger() == fields[5].toBigInteger()) {
                    assertThat(fields[8]).isEqualTo("none")
                    assertThat(fields[10]).isEqualTo("none")
                    assertThat(fields[6]).isEqualTo("true")
                } else {
                    val c = CardanoFixedPoint.ln(scale - f)
                    val x = -CardanoFixedPoint.multiply(sigma, c)
                    assertWithMessage("c row $count").that(c).isEqualTo(fields[8].toBigInteger())
                    assertWithMessage("x row $count").that(x).isEqualTo(fields[10].toBigInteger())
                    assertWithMessage("decision row $count")
                        .that(CardanoFixedPoint.taylorExpCmp(q, x) == CardanoFixedPoint.Comparison.BELOW)
                        .isEqualTo(fields[6].toBooleanStrict())
                }
                count++
            }
            assertThat(count).isGreaterThan(0)
        }
    }

    private fun raw(value: String): BigInteger {
        require(fixedNumber.matches(value)) { "Malformed E34 value: $value" }
        return BigDecimal(value).movePointRight(34).toBigIntegerExact()
    }

    private fun columns(line: String, size: Int): List<String> =
        line.trim().split(whitespace).also { require(it.size == size) { "Expected $size columns: $line" } }

    private fun comparison(name: String): CardanoFixedPoint.Comparison =
        when (name) {
            "ABOVE" -> CardanoFixedPoint.Comparison.ABOVE
            "BELOW" -> CardanoFixedPoint.Comparison.BELOW
            "MaxReached", "MAX_REACHED" -> CardanoFixedPoint.Comparison.MAX_REACHED
            else -> error("Invalid node comparison: $name")
        }

    private fun validateIterations(name: String, value: String) {
        val iterations = value.toInt()
        assertThat(iterations).isIn(1..1000)
        if (comparison(name) == CardanoFixedPoint.Comparison.MAX_REACHED) assertThat(iterations).isEqualTo(1000)
    }

    private fun resourceReader(name: String): BufferedReader = resource(name).bufferedReader(Charsets.UTF_8)

    private fun gzipReader(name: String, digest: MessageDigest? = null): BufferedReader {
        val uncompressed = GZIPInputStream(resource(name))
        return (if (digest == null) uncompressed else DigestInputStream(uncompressed, digest)).bufferedReader(Charsets.UTF_8)
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/leaderlog/$name")) { "Missing fixture $name" }

    @Suppress("UNCHECKED_CAST")
    private fun loadDivergences(): List<Map<String, Any?>> =
        resourceReader("oracle-divergences.json").use { reader ->
            val value = Moshi.Builder().build().adapter(Any::class.java).fromJson(reader.readText())
            value as List<Map<String, Any?>>
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
