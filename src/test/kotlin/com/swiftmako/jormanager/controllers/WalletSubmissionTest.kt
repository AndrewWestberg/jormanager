package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.*
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.repositories.*
import com.swiftmako.jormanager.services.SubmittedTransactionDecoder
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.*
import java.math.BigInteger
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class WalletSubmissionTest {
    private fun n(value: Long) = BigInteger.valueOf(value)
    private inner class Fixture(val era: String) {
        val json = jacksonObjectMapper()
        val wallets = mockk<WalletUtils>()
        val accounts = mockk<WalletRepository>()
        val files = mockk<FileRepository>()
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val tip = mockk<JsonAdapter<QueryTip>>()
        val genesis = mockk<JsonAdapter<GenesisShelley>>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolParametersRepository>()
        val node = mockk<Node>(relaxed = true)
        val host = mockk<Host>(relaxed = true)
        val key = File(1, "key", "encrypted")
        val fundsAddress = Bech32.encode("addr_test", F.address)
        val stakeAddress = Bech32.encode("addr_test", byteArrayOf(0x60) + ByteArray(28) { 0x22 })
        val funds = WalletEntry(1, "funds", "payment", fundsAddress, paymentSkey = key)
        val stake = WalletEntry(2, "stake", "stake", stakeAddress, paymentSkey = key, stakingAddr = "stake-address", stakingSkey = key, stakingVkey = key)
        val roots = mapOf(fundsAddress to Utxo("11".repeat(32), 0, n(10_000_000), emptyList()),
            stakeAddress to Utxo("22".repeat(32), 0, n(5_000_000), emptyList()))
        val ledger = LedgerDao(eras, json, mockk(relaxed = true), MutableStateFlow(null))
        var envelope = ""
        var id = ""
        var reject = false
        var registered = false
        val commands = mutableListOf<List<String>>()
        val controller: WalletController
        init {
            every { node.hostId } returns 1
            every { node.genesisShelleyFileId } returns 1
            every { node.name } returns "node"
            every { host.cardanoCliPath } returns "cli"
            every { host.nodeHomePath } returns "/node"
            every { nodes.findDefault() } returns node
            every { hosts.findById(1) } returns Optional.of(host)
            every { files.findById(1) } returns Optional.of(key)
            every { accounts.findById(1) } returns Optional.of(funds)
            every { accounts.findById(2) } returns Optional.of(stake)
            every { genesis.fromJson(any<String>()) } returns mockk<GenesisShelley> { every { networkId } returns "mainnet" }
            every { tip.fromJson(any<String>()) } returns QueryTip(era, slot = 100)
            every { eras.getEra(any(), any(), any(), any()) } returns era
            every { eras.networkKey(node) } returns "network"
            every { parameters.query(any(), any(), any(), any(), any(), any()) } returns """{"maxTxSize":16384,"maxValueSize":5000,"stakeAddressDeposit":2000000}"""
            every { wallets.isValidSpendingPassword("secret") } returns true
            every { wallets.getUtxos(any(), any(), any(), any(), any(), any()) } answers {
                val address = arg<String>(5)
                ledger.liveUtxos("network", address, listOf(roots.getValue(address)))
            }
            every { wallets.getStakeAddressInfo(any(), any(), any(), any(), any(), any()) } answers {
                if (registered) listOf(StakeAddressInfo("stake-address", stakeAddressDeposit = n(2_000_000))) else emptyList()
            }
            every { wallets.queryReferenceScriptSize(any(), any(), any(), any(), any(), any()) } returns 0
            every { wallets.getSKeyContent(key, "secret") } returns "signing-key"
            controller = WalletController(wallets, accounts, files, nodes, hosts, tip, genesis, mockk(), mockk(), eras, parameters, ledger, WalletTransactionBuilder(json), json)
        }
        fun balance(address: String) = ledger.liveUtxos("network", address, listOf(roots.getValue(address))).sumOf { it.lovelace }
        fun run(action: () -> Unit) {
            mockkConstructor(HostConnection::class)
            try {
                every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
                every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } just Runs
                every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { envelope }
                every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                    val args = firstArg<List<String>>()
                    commands += args
                    when {
                        "calculate-min-required-utxo" in args -> "Lovelace 1000000"
                        "calculate-min-fee" in args -> "200000 Lovelace"
                        "txid" in args -> id
                        "submit" in args && reject -> throw IllegalStateException("node rejected registration")
                        "build-raw" in args -> {
                            val inputs = args.indices.filter { args[it] == "--tx-in" }.map {
                                val reference = args[it + 1].split('#'); F.input(reference[0].hexToByteArray(), reference[1].toInt())
                            }
                            val outputs = args.indices.filter { args[it] == "--tx-out" }.map {
                                val value = args[it + 1].split('+'); F.output(Bech32.decode(value[0]).bytes, value[1].toLong())
                            }
                            val body = F.map(listOf(F.integer(0) to F.array(inputs), F.integer(1) to F.array(outputs),
                                F.integer(2) to F.integer(args[args.indexOf("--fee") + 1].toLong())))
                            val hex = (if (era == "dijkstra") F.array(body, F.map(emptyList()), byteArrayOf(0xf6.toByte()))
                                else F.array(body, F.map(emptyList()), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte()))).toHexString()
                            id = SubmittedTransactionDecoder.decodeSubmitted(hex, era).id
                            envelope = """{"cborHex":"$hex"}"""
                            ""
                        }
                        else -> "{}"
                    }
                }
                action()
            } finally { unmockkConstructor(HostConnection::class) }
        }
    }

    @Test fun registrationUsesEitherFeeAccountWithoutLockingRejectedFunds() {
        for (era in listOf("conway", "dijkstra")) for (payer in listOf(1L, 2L)) {
            val f = Fixture(era)
            f.run {
                val request = UpdateStakingAddressRequest(2, "secret", payer, true)
                f.reject = true
                assertTrue(f.controller.updateStakingAddress(request) is SocketResponse.Error)
                assertEquals(n(10_000_000), f.balance(f.fundsAddress))
                assertEquals(n(5_000_000), f.balance(f.stakeAddress))
                f.reject = false
                assertTrue(f.controller.updateStakingAddress(request) is SocketResponse.Success)
                assertEquals(n(if (payer == 1L) 7_800_000 else 10_000_000), f.balance(f.fundsAddress))
                assertEquals(n(if (payer == 2L) 2_800_000 else 5_000_000), f.balance(f.stakeAddress))
                assertTrue(f.commands.any { "registration-certificate" in it && it[it.indexOf("--key-reg-deposit-amt") + 1] == "2000000" })
            }
        }
    }

    @Test fun deregistrationRefundsRecordedDeposit() {
        val f = Fixture("dijkstra")
        f.registered = true
        f.run {
            assertTrue(f.controller.updateStakingAddress(UpdateStakingAddressRequest(2, "secret", 1, false)) is SocketResponse.Success)
            assertEquals(n(11_800_000), f.balance(f.fundsAddress))
        }
    }

    @Test fun acknowledgedSendUpdatesLiveChange() {
        val f = Fixture("dijkstra")
        f.run {
            val request = SubmitTransactionRequest("secret", 1, emptyList(), n(200_000), BigInteger.ZERO,
                listOf(ToAccount("ada", 2, "", n(1_000_000), null, BigInteger.ZERO)), false, null)
            assertTrue(f.controller.submitTransaction(request) is SocketResponse.Success)
            assertEquals(n(8_800_000), f.balance(f.fundsAddress))
            assertEquals(n(6_000_000), f.balance(f.stakeAddress))
        }
    }

    @Test fun decimalStringAmountsDeserializeExactly() {
        val request = jacksonObjectMapper().readValue("""{"spendingPassword":"secret","fromId":1,"txFee":"18446744073709551615","tokenKeepFee":"0","toAccounts":[{"currency":"ada","account":-1,"address":"destination","amount":"18446744073709551615","percent":null,"tokenFee":"0"}],"isClaim":false,"metadata":null}""", SubmitTransactionRequest::class.java)
        assertEquals(BigInteger("18446744073709551615"), request.txFee)
        assertEquals(request.txFee, request.toAccounts.single().amount)
    }

    @Test fun wrongPasswordDoesNotEnterSubmission() {
        val wallets = mockk<WalletUtils>()
        val ledger = mockk<LedgerDao>()
        every { wallets.isValidSpendingPassword("wrong") } returns false
        val controller = WalletController(wallets, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), mockk(), ledger, mockk(), jacksonObjectMapper())
        val request = SubmitTransactionRequest("wrong", 1, emptyList(), BigInteger.ZERO, BigInteger.ZERO, emptyList(), false, null)
        assertTrue(controller.submitTransaction(request) is SocketResponse.Error)
        verify(exactly = 0) { ledger.withSubmissionLock(any<() -> String>()) }
    }
}
