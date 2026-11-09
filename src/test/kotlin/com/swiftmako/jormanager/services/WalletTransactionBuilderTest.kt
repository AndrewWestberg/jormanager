package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.utils.CborSpans
import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper

class WalletTransactionBuilderTest {
    private val policy = "22".repeat(28)
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun input(value: Long = 100_000_000, assets: List<NativeAsset> = emptyList()) = Utxo("11".repeat(32), 0, n(value), assets)
    private fun output(value: Long, assets: List<NativeAsset> = emptyList()) = WalletTransactionBuilder.Output("recipient", n(value), assets)
    private inner class Fixture {
        val host = mockk<Host>()
        val connection = mockk<HostConnection>()
        val builder = WalletTransactionBuilder(ObjectMapper())
        val builds = mutableListOf<List<String>>()
        val commands = mutableListOf<List<String>>()
        var minimum = 1_000_000L
        var fee: (Int) -> Long = { 200_000 }
        var envelope = ""
        var id = ""
        var params = """{"maxTxSize":16384,"maxValueSize":5000}"""
        init {
            every { host.cardanoCliPath } returns "cli"
            every { connection.commandReadFile("body") } answers { envelope }
            every { connection.commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                commands.add(args)
                when (args[3]) {
                    "calculate-min-required-utxo" -> "Lovelace $minimum"
                    "calculate-min-fee" -> "${fee(builds.size)} Lovelace"
                    "txid" -> id
                    "build-raw" -> {
                        builds.add(args)
                        val outs = args.indices.filter { args[it] == "--tx-out" }.map { i ->
                            val parts = args[i + 1].split('+')
                            val assets = parts.drop(2).map { component ->
                                val pair = component.split(' ')
                                val asset = pair[1].split('.')
                                Triple(asset[0].hexToByteArray(), asset.getOrElse(1) { "" }.hexToByteArray(), pair[0].toLong())
                            }
                            F.output(coin = parts[1].toLong(), assets = assets)
                        }
                        val txFee = args[args.indexOf("--fee") + 1].toLong()
                        val body = F.map(listOf(F.integer(0) to F.array(F.input("11".repeat(32).hexToByteArray())),
                            F.integer(1) to F.array(outs), F.integer(2) to F.integer(txFee)))
                        val encoded = F.array(body, F.map(emptyList()), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte()))
                        val hex = encoded.toHexString()
                        id = SubmittedTransactionDecoder.decodeSubmitted(hex, "babbage").id
                        envelope = """{"cborHex":"$hex"}"""
                        ""
                    }
                    else -> error("Unexpected CLI call $args")
                }
            }
        }
        fun build(inputs: List<Utxo> = listOf(input()), outputs: List<WalletTransactionBuilder.Output> = listOf(output(40_000_000)),
                  withdrawal: Long = 0, deposit: Long = 0, refund: Long = 0, witnesses: Int = 1,
                  extra: List<String> = emptyList(), validFrom: Long? = null, validUntil: Long? = null) =
            builder.build(host, connection, "babbage", inputs, outputs, "change", n(withdrawal), n(deposit), n(refund),
                extraArgs = extra, validFrom = validFrom, validUntil = validUntil,
                protocolParameters = params, protocolPath = "params", witnessCount = witnesses, bodyPath = "body")
        fun finalOutputs() = builds.last().indices.filter { builds.last()[it] == "--tx-out" }.map { builds.last()[it + 1] }
    }

    @Test fun balancesAndConvergesWithoutLosingAssets() {
        val f = Fixture()
        f.fee = { if (it == 1) 23 else 200_000 }
        val token = NativeAsset("01", policy, n(9))
        val result = f.build(inputs = listOf(input(100_000_000, listOf(token))), outputs = listOf(output(40_000_000, listOf(token.copy(amount = n(4))))))
        assertEquals(3, f.builds.size)
        assertEquals(listOf("recipient+40000000+4 $policy.01", "change+59800000+5 $policy.01"), f.finalOutputs())
        assertEquals(f.id, result.txId)
        assertEquals(f.envelope, result.textEnvelope)
        val feeCall = f.commands.last { it[3] == "calculate-min-fee" }
        assertEquals("1", feeCall[feeCall.indexOf("--witness-count") + 1])
        assertEquals("0", feeCall[feeCall.indexOf("--reference-script-size") + 1])
        assertFalse(f.builds.last().contains("--change-address"))
    }

    @Test fun preservesExtraArgumentsAndValidity() {
        val f = Fixture()
        f.build(extra = listOf("--metadata-json-file", "meta", "--certificate-file", "cert"), validFrom = 10, validUntil = 20)
        val args = f.builds.last()
        assertTrue(args.containsAll(listOf("--metadata-json-file", "meta", "--certificate-file", "cert", "--invalid-before", "10", "--invalid-hereafter", "20")))
    }

    @Test fun withdrawalsDepositsRefundsAndPoolUpdateOrRetirement() {
        val f = Fixture()
        f.build(withdrawal = 2_000_000, deposit = 3_000_000, refund = 4_000_000, witnesses = 3)
        assertEquals("change+62800000", f.finalOutputs().last())
        assertEquals("3", f.commands.last { it[3] == "calculate-min-fee" }.let { it[it.indexOf("--witness-count") + 1] })
        val update = Fixture(); update.build(deposit = 0, refund = 0)
        assertEquals("change+59800000", update.finalOutputs().last())
        val retirement = Fixture(); retirement.build(deposit = 0, refund = 0)
        assertEquals("change+59800000", retirement.finalOutputs().last())
    }

    @Test fun exactZeroChangeAndNoDustDonation() {
        val f = Fixture(); f.build(inputs = listOf(input(40_200_000)))
        assertEquals(listOf("recipient+40000000"), f.finalOutputs())
        val dust = Fixture(); assertThrows<IllegalArgumentException> { dust.build(inputs = listOf(input(40_300_000))) }
        val short = Fixture(); assertThrows<IllegalArgumentException> { short.build(inputs = listOf(input(40_100_000))) }
        val lowRecipient = Fixture(); assertThrows<IllegalArgumentException> { lowRecipient.build(outputs = listOf(output(1))) }
    }

    @Test fun validatesAssetsAndDuplicateReferences() {
        val f = Fixture()
        assertThrows<IllegalArgumentException> { f.build(inputs = listOf(input(), input())) }
        assertThrows<IllegalArgumentException> { f.build(outputs = listOf(output(40_000_000, listOf(NativeAsset("01", policy, n(1)))))) }
        assertThrows<IllegalArgumentException> { f.build(inputs = listOf(input(100_000_000, listOf(NativeAsset("01", policy, n(-1))))) ) }
        val noAda = Fixture(); assertThrows<IllegalArgumentException> {
            noAda.build(inputs = listOf(input(40_200_000, listOf(NativeAsset("01", policy, n(1))))))
        }
    }

    @Test fun enforcesValueAndWitnessDependentTransactionLimits() {
        val value = Fixture(); value.params = """{"maxTxSize":16384,"maxValueSize":1}"""
        assertThrows<IllegalArgumentException> { value.build(inputs = listOf(input(100_000_000, listOf(NativeAsset("01", policy, n(2))))) ) }
        val size = Fixture(); size.params = """{"maxTxSize":300,"maxValueSize":5000}"""
        assertThrows<IllegalArgumentException> { size.build(witnesses = 4) }
        val small = Fixture(); small.params = size.params; small.build(witnesses = 1)
    }

    @Test fun stopsAfterSixteenNonconvergingBodies() {
        val f = Fixture(); f.fee = { it.toLong() }
        assertThrows<IllegalArgumentException> { f.build() }
        assertEquals(16, f.builds.size)
    }

    @Test fun feeDependentFullSendUsesSameBodyAsFixedSubmission() {
        val estimate = Fixture()
        estimate.builder.build(estimate.host, estimate.connection, "babbage", listOf(input()), emptyList(), "change",
            protocolParameters = estimate.params, protocolPath = "params", witnessCount = 1, bodyPath = "body",
            referenceScriptSize = 123, outputsForFee = { fee -> listOf(WalletTransactionBuilder.Output("recipient", n(100_000_000) - fee)) })
        assertEquals(listOf("recipient+99800000"), estimate.finalOutputs())
        assertEquals("123", estimate.commands.last { it[3] == "calculate-min-fee" }.let { it[it.indexOf("--reference-script-size") + 1] })
        val actual = Fixture()
        actual.build(outputs = listOf(output(99_800_000)))
        assertEquals(actual.finalOutputs(), estimate.finalOutputs())
    }

    @Test fun feeDependentSendRetainsNativeAssetsAndMatchesFixedSubmission() {
        val token = NativeAsset("01", policy, n(9))
        val estimate = Fixture()
        estimate.builder.build(estimate.host, estimate.connection, "babbage", listOf(input(100_000_000, listOf(token))),
            emptyList(), "change", protocolParameters = estimate.params, protocolPath = "params",
            witnessCount = 1, bodyPath = "body", outputsForFee = { fee ->
                listOf(WalletTransactionBuilder.Output("recipient", n(99_000_000) - fee, listOf(token.copy(amount = n(4)))))
            })
        assertEquals(listOf("recipient+98800000+4 $policy.01", "change+1000000+5 $policy.01"), estimate.finalOutputs())
        val actual = Fixture()
        actual.build(inputs = listOf(input(100_000_000, listOf(token))), outputs = listOf(output(98_800_000, listOf(token.copy(amount = n(4))))))
        assertEquals(actual.finalOutputs(), estimate.finalOutputs())
    }

    @Test fun multiClaimAndLargeFixedAmountsUseIdenticalFeeAccounting() {
        val inputValue = 9_007_199_254_900_000L
        val reward = 4_000_000L
        val destination = output(2_000_000)
        val estimate = Fixture()
        estimate.builder.build(estimate.host, estimate.connection, "babbage", listOf(input(inputValue)),
            emptyList(), "change", withdrawals = n(reward), extraArgs = listOf("--withdrawal", "stake1+2000000", "--withdrawal", "stake2+2000000"),
            validUntil = 21700, protocolParameters = estimate.params, protocolPath = "params", witnessCount = 3,
            bodyPath = "body", outputsForFee = { listOf(destination) })
        val actual = Fixture()
        actual.build(inputs = listOf(input(inputValue)), outputs = listOf(destination), withdrawal = reward,
            witnesses = 3, extra = listOf("--withdrawal", "stake1+2000000", "--withdrawal", "stake2+2000000"), validUntil = 21700)
        assertEquals(actual.finalOutputs(), estimate.finalOutputs())
        assertEquals("3", estimate.commands.last { it[3] == "calculate-min-fee" }.let { it[it.indexOf("--witness-count") + 1] })
    }
}
