package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.*
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.repositories.*
import com.swiftmako.jormanager.services.SubmittedTransactionDecoder
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import io.mockk.*
import java.math.BigInteger
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class WalletFeeEstimatorTest {
    private val policy = "22".repeat(28)
    private val json = jacksonObjectMapper()
    private val params = """{"maxTxSize":16384,"maxValueSize":5000}"""
    private fun n(value: Long) = BigInteger.valueOf(value)

    private fun checkEstimate(claim: Boolean, asset: Boolean, percent: Boolean) {
        val wallets = mockk<WalletUtils>()
        val accounts = mockk<WalletRepository>()
        val files = mockk<FileRepository>()
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val cardano = mockk<CardanoRepository>()
        val protocols = mockk<ProtocolParametersRepository>()
        val messages = mockk<SimpMessagingTemplate>()
        val genesis = mockk<JsonAdapter<GenesisShelley>>()
        val tip = mockk<JsonAdapter<QueryTip>>()
        val node = mockk<Node>(relaxed = true)
        val host = mockk<Host>(relaxed = true)
        val key = File(1, "key", "{}")
        val payer = WalletEntry(1, "payer", "payment", "source", paymentSkey = key, stakingAddr = "stake1")
        val stake = WalletEntry(2, "stake", "payment", "other", paymentSkey = key, stakingAddr = "stake2")
        every { node.id } returns 1L
        every { node.hostId } returns 1L
        every { node.genesisShelleyFileId } returns 1L
        every { node.name } returns "node"
        every { host.id } returns 1L
        every { host.nodeHomePath } returns "/node"
        every { host.cardanoCliPath } returns "cli"
        every { nodes.findDefault() } returns node
        every { hosts.findById(1L) } returns Optional.of(host)
        every { files.findById(1L) } returns Optional.of(key)
        every { accounts.findById(1L) } returns Optional.of(payer)
        every { accounts.findById(2L) } returns Optional.of(stake)
        every { genesis.fromJson(any<String>()) } returns mockk<GenesisShelley> { every { networkId } returns "mainnet"; every { networkMagic } returns 1L }
        every { tip.fromJson(any<String>()) } returns QueryTip("babbage", hash = "aa".repeat(32), slot = 100)
        every { cardano.getEra(any(), any(), any(), any()) } returns "babbage"
        every { protocols.query(any(), any(), any(), any(), any(), any()) } returns params
        val token = NativeAsset("01", policy, n(9))
        val input = Utxo("11".repeat(32), 0, n(9_007_199_254_900_000L), if (asset) listOf(token) else emptyList())
        every { wallets.getUtxos(any(), any(), any(), any(), any(), any()) } returns listOf(input)
        every { wallets.queryReferenceScriptSize(any(), any(), any(), any(), any(), any()) } returns 0L
        every { wallets.getWalletItem(any(), any(), any(), any(), any(), any()) } returns mockk<WalletItem> { every { stakingAddrLovelace } returns n(2_000_000) }
        val response = slot<Any>()
        every { messages.convertAndSend(any<String>(), capture(response)) } just Runs
        val builder = WalletTransactionBuilder(json)
        val controller = WalletController(wallets, accounts, files, nodes, hosts, tip, genesis, mockk(), messages, cardano, protocols, mockk(), builder, json)
        val destinations = if (asset) listOf(ToAccountFeeRequest("$policy.01", -1, "destination", n(4)),
            ToAccountFeeRequest("ada", -1, "destination", n(1), if (percent) 100 else null))
            else listOf(ToAccountFeeRequest("ada", if (claim) 1 else -1, if (claim) "" else "destination", n(if (claim) 4_000_000 else 40_000_000), if (percent) 100 else null))
        val request = CalculateFeeRequest(1, if (claim) listOf(1, 2) else emptyList(), destinations, destinations.size, claim, null, "fixture")
        var envelope = ""
        var id = ""
        var lastOutputs = emptyList<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { envelope }
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                when {
                    "calculate-min-required-utxo" in args -> "Lovelace 1000000"
                    "calculate-min-fee" in args -> {
                        val size = json.readTree(envelope).path("cborHex").asString().length / 2
                        val witnesses = args[args.indexOf("--witness-count") + 1].toInt()
                        "${155381 + 44 * (size + witnesses * 110)} Lovelace"
                    }
                    "txid" in args -> id
                    "build-raw" in args -> {
                        lastOutputs = args.indices.filter { args[it] == "--tx-out" }.map { args[it + 1] }
                        val outputs = lastOutputs.map { value ->
                            val parts = value.split('+')
                            val assets = parts.drop(2).map { component ->
                                val pair = component.split(' '); val unit = pair[1].split('.')
                                Triple(unit[0].hexToByteArray(), unit.getOrElse(1) { "" }.hexToByteArray(), pair[0].toLong())
                            }
                            F.output(coin = parts[1].toLong(), assets = assets)
                        }
                        val fee = args[args.indexOf("--fee") + 1].toLong()
                        val body = F.map(listOf(F.integer(0) to F.array(F.input("11".repeat(32).hexToByteArray())),
                            F.integer(1) to F.array(outputs), F.integer(2) to F.integer(fee)))
                        val hex = F.array(body, F.map(emptyList()), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte())).toHexString()
                        id = SubmittedTransactionDecoder.decodeSubmitted(hex, "babbage").id
                        envelope = """{"cborHex":"$hex"}"""
                        ""
                    }
                    "tip" in args -> "{}"
                    else -> ""
                }
            }
            val method = WalletController::class.java.getDeclaredMethod("calculateTxFee", CalculateFeeRequest::class.java)
            method.isAccessible = true
            method.invoke(controller, request)
            val result = response.captured as SocketResponse.Success<*>
            val fee = (result.data as CalculateFeeResponse).txFee
            val estimatedOutputs = lastOutputs.toList()
            val fixedOutputs = estimatedOutputs.filterNot { it.startsWith("source+") && it != "source+4000000" }.map { value ->
                val parts = value.split('+')
                WalletTransactionBuilder.Output(parts[0], parts[1].toBigInteger(), parts.drop(2).map { component ->
                    val pair = component.split(' '); val unit = pair[1].split('.')
                    NativeAsset(unit.getOrElse(1) { "" }, unit[0], pair[0].toBigInteger())
                })
            }
            val conn = HostConnection(host, node)
            val built = builder.build(host, conn, "babbage", listOf(input), fixedOutputs, "source",
                withdrawals = if (claim) n(4_000_000) else BigInteger.ZERO,
                extraArgs = if (claim) listOf("--withdrawal", "stake1+2000000", "--withdrawal", "stake2+2000000") else emptyList(),
                validUntil = 21700, protocolParameters = params, protocolPath = "params", witnessCount = if (claim) 3 else 1, bodyPath = "body")
            val body = com.swiftmako.jormanager.utils.CborSpans.parse(json.readTree(built.textEnvelope).path("cborHex").asString().hexToByteArray()).array().first()
            assertEquals(fee, body.entries().single { it.first.integer() == BigInteger.TWO }.second.integer())
            assertEquals(estimatedOutputs, lastOutputs)
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test fun fixedAmount() = checkEstimate(false, false, false)
    @Test fun fullAdaAmount() = checkEstimate(false, false, true)
    @Test fun assetChange() = checkEstimate(false, true, true)
    @Test fun multiClaim() = checkEstimate(true, false, false)
}
