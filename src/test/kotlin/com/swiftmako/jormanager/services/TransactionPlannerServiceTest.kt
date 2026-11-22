package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.PlannedOutput
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.SelectionMode
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WithdrawalItem
import com.swiftmako.jormanager.model.toNativeAssetMap
import java.io.IOException
import java.math.BigInteger
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TransactionPlannerServiceTest {
    private val planner = TransactionPlannerService()
    private val protocolParameters = ProtocolParameters.conwayDefaults()

    private val senderAddress =
        "addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9"
    private val recipientAddress =
        "addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu"

    private val policyA = "a0028f350aaabe0545fdcb56b039bfb08e4bb4d8c4d7c3c7d481c235"
    private val policyB = "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96"
    private val policyC = "682fe60c9918842b3323c43b5144bc3d52a23bd2fb81345560d73f63"

    private fun txHash(seed: Int): String = seed.toString(16).padStart(64, '0')

    @Test
    fun `standard coin selection selects compact UTxO subset instead of full-wallet sweep`() {
        val utxos =
            (1..500).map { idx ->
                Utxo(
                    hash = txHash(idx),
                    ix = (idx % 4).toLong(),
                    lovelace = BigInteger.valueOf((2_000_000L + (idx % 20) * 1_000_000L)),
                    nativeAssets = emptyList(),
                )
            }

        val plan =
            planner.planWalletTransaction(
                utxos = utxos,
                changeAddress = senderAddress,
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:2",
                            address = recipientAddress,
                            currency = "ada",
                            amount = BigInteger.valueOf(25_000_000L),
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )

        assertThat(plan.selectionMode).isEqualTo(SelectionMode.STANDARD)
        assertThat(plan.usedFallback).isFalse()
        assertThat(plan.selectedUtxos.size).isLessThan(15)
        assertThat(plan.estimatedTxBytes).isAtMost(protocolParameters.maxTxSize.toInt())

        val totalIn = plan.selectedUtxos.sumByBigInteger { it.lovelace }
        val totalOut = plan.outputs.sumByBigInteger { it.lovelace }
        assertThat(totalIn).isEqualTo(totalOut + plan.fee)

        val recipientOut = plan.outputs.single { it.address == recipientAddress }
        assertThat(recipientOut.lovelace).isEqualTo(BigInteger.valueOf(25_000_000L))
    }

    @Test
    fun `admin transaction planning conserves lovelace with deposits and refunds without full sweep`() {
        val utxos =
            (1..300).map { idx ->
                Utxo(
                    hash = txHash(1_000 + idx),
                    ix = 0L,
                    lovelace = BigInteger.valueOf(100_000_000L + (idx % 5) * 25_000_000L),
                    nativeAssets = emptyList(),
                )
            }

        // Pool registration requiring 500 ADA deposit and 3 signing witnesses
        val poolRegPlan =
            planner.planAdminTransaction(
                utxos = utxos,
                changeAddress = senderAddress,
                protocolParameters = protocolParameters,
                deposit = protocolParameters.stakePoolDeposit,
                depositRefund = BigInteger.ZERO,
                witnessCount = 3,
                extraPayloadBytes = 420,
            )

        assertThat(poolRegPlan.selectedUtxos.size).isLessThan(15)
        assertThat(poolRegPlan.estimatedTxBytes).isAtMost(16_384)
        assertThat(poolRegPlan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(
                poolRegPlan.outputs.sumByBigInteger { it.lovelace } +
                    protocolParameters.stakePoolDeposit +
                    poolRegPlan.fee
            )

        // Stake key deregistration refunding 2 ADA deposit
        val deregPlan =
            planner.planAdminTransaction(
                utxos = utxos,
                changeAddress = senderAddress,
                protocolParameters = protocolParameters,
                deposit = BigInteger.ZERO,
                depositRefund = protocolParameters.stakeAddressDeposit,
                witnessCount = 2,
                extraPayloadBytes = 180,
            )

        assertThat(deregPlan.selectedUtxos.size).isAtMost(4)
        assertThat(
            deregPlan.selectedUtxos.sumByBigInteger { it.lovelace } + protocolParameters.stakeAddressDeposit
        ).isEqualTo(deregPlan.outputs.sumByBigInteger { it.lovelace } + deregPlan.fee)
    }

    @Test
    fun `automatic fallback to Largest-First succeeds when Random-Improve strategy fails`() {
        val fallbackPlanner =
            TransactionPlannerService(
                randomImproveStrategyFactory = {
                    io.mockk.mockk {
                        io.mockk.every {
                            select(
                                any<String>(),
                                any<MutableList<com.bloxbean.cardano.client.api.model.Amount>>(),
                                any(),
                                any<Int>(),
                            )
                        } throws RuntimeException("Simulated Random-Improve failure")
                    }
                }
            )

        val utxos =
            listOf(
                Utxo(txHash(1), 0, BigInteger.valueOf(3_000_000L), emptyList()),
                Utxo(txHash(2), 0, BigInteger.valueOf(50_000_000L), emptyList()),
                Utxo(txHash(3), 0, BigInteger.valueOf(8_000_000L), emptyList()),
            )

        val walletPlan =
            fallbackPlanner.planWalletTransaction(
                utxos = utxos,
                changeAddress = senderAddress,
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:2",
                            address = recipientAddress,
                            currency = "ada",
                            amount = BigInteger.valueOf(12_000_000L),
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )

        assertThat(walletPlan.usedFallback).isTrue()
        assertThat(walletPlan.selectedUtxos.first().hash).isEqualTo(txHash(2))
        assertThat(walletPlan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(walletPlan.outputs.sumByBigInteger { it.lovelace } + walletPlan.fee)

        val adminPlan =
            fallbackPlanner.planAdminTransaction(
                utxos = utxos,
                changeAddress = senderAddress,
                protocolParameters = protocolParameters,
                deposit = BigInteger.valueOf(2_000_000L),
                witnessCount = 2,
            )

        assertThat(adminPlan.usedFallback).isTrue()
        assertThat(adminPlan.selectedUtxos.first().hash).isEqualTo(txHash(2))
    }

    @Test
    fun `net-unlocked-lovelace fallback handles token-heavy UTxOs whose min-ADA locks nominal lovelace`() {
        // 20 UTxOs each holding 2.5 ADA but carrying 15 native tokens (~1.7+ ADA locked in min-ADA),
        // plus 2 clean pure-ADA UTxOs holding 15 ADA each.
        val tokenHeavyUtxos =
            (1..20).map { idx ->
                val assets =
                    (1..15).map { tokenIdx ->
                        NativeAsset(
                            name = "544f4b${tokenIdx.toString(16).padStart(2, '0')}",
                            policy = policyA,
                            amount = BigInteger.TEN,
                        )
                    }
                Utxo(
                    hash = txHash(2_000 + idx),
                    ix = 0,
                    lovelace = BigInteger.valueOf(2_500_000L),
                    nativeAssets = assets,
                )
            }
        val cleanUtxos =
            listOf(
                Utxo(txHash(3_001), 0, BigInteger.valueOf(15_000_000L), emptyList()),
                Utxo(txHash(3_002), 0, BigInteger.valueOf(15_000_000L), emptyList()),
            )

        val plan =
            planner.planWalletTransaction(
                utxos = tokenHeavyUtxos + cleanUtxos,
                changeAddress = senderAddress,
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:2",
                            address = recipientAddress,
                            currency = "ada",
                            amount = BigInteger.valueOf(22_000_000L),
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )

        assertThat(plan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(plan.outputs.sumByBigInteger { it.lovelace } + plan.fee)
        for (output in plan.outputs) {
            val requiredMinAda = planner.calculateMinAda(output.address, output.nativeAssets, protocolParameters)
            assertThat(output.lovelace).isAtLeast(requiredMinAda)
        }
    }

    @Test
    fun `defragment mode sweeps dust and consolidates multi-asset UTxOs by policy ID`() {
        val dustPureAdaUtxos =
            (1..60).map { idx ->
                Utxo(
                    hash = txHash(4_000 + idx),
                    ix = 0,
                    lovelace = BigInteger.valueOf(1_100_000L + (idx % 5) * 100_000L),
                    nativeAssets = emptyList(),
                )
            }

        // 26 distinct tokens under policyA scattered across 26 UTxOs -> should consolidate into 2 balanced outputs
        // (<= 20 assets each, with tail rebalanced so neither output has < 5 tokens)
        val fragmentedPolicyAUtxos =
            (1..26).map { idx ->
                Utxo(
                    hash = txHash(5_000 + idx),
                    ix = 0,
                    lovelace = BigInteger.valueOf(1_500_000L),
                    nativeAssets =
                        listOf(
                            NativeAsset(
                                name = "4153534554${idx.toString(16).padStart(2, '0')}",
                                policy = policyA,
                                amount = BigInteger.valueOf(idx.toLong() * 10L),
                            )
                        ),
                )
            }

        // Duplicate tokens under policyB & policyC across 10 UTxOs -> should merge amounts by policy+name
        val duplicateTokenUtxos =
            (1..10).map { idx ->
                Utxo(
                    hash = txHash(6_000 + idx),
                    ix = 1,
                    lovelace = BigInteger.valueOf(1_600_000L),
                    nativeAssets =
                        listOf(
                            NativeAsset(name = "4e45574d", policy = policyC, amount = BigInteger.valueOf(100L)),
                            NativeAsset(name = "484f534b59", policy = policyB, amount = BigInteger.valueOf(500L)),
                        ),
                )
            }

        val allUtxos = dustPureAdaUtxos + fragmentedPolicyAUtxos + duplicateTokenUtxos

        val plan =
            planner.planWalletTransaction(
                utxos = allUtxos,
                changeAddress = senderAddress,
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:1",
                            address = senderAddress,
                            currency = "ada",
                            amount = BigInteger.ZERO,
                            isPercent = true,
                            percent = 100,
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )

        assertThat(plan.selectionMode).isEqualTo(SelectionMode.DEFRAGMENT)
        // All 96 fragmented UTxOs fit well within 16KB and are swept in a single pass
        assertThat(plan.selectedUtxos).hasSize(allUtxos.size)
        assertThat(plan.estimatedTxBytes).isAtMost(16_384)
        assertThat(plan.tokenLocked).isEqualTo(BigInteger.ZERO)

        val tokenOutputs = plan.outputs.filter { it.nativeAssets.isNotEmpty() }
        val pureAdaOutputs = plan.outputs.filter { it.nativeAssets.isEmpty() }

        // 26 policyA tokens -> 2 balanced policy-pure outputs (13 + 13) + 1 rolled-up small-policy output (policyB + policyC) = 3 token outputs
        assertThat(tokenOutputs).hasSize(3)
        val policyABundles = tokenOutputs.filter { out -> out.nativeAssets.all { it.policy == policyA } }
        assertThat(policyABundles).hasSize(2)
        policyABundles.forEach { bundle ->
            assertThat(bundle.nativeAssets).hasSize(13)
        }
        tokenOutputs.forEach { out ->
            assertThat(out.nativeAssets.size).isAtMost(TransactionPlannerService.NATIVE_ASSET_MAX_GROUP_SIZE)
            val expectedMinAda = planner.calculateMinAda(out.address, out.nativeAssets, protocolParameters)
            assertThat(out.lovelace).isEqualTo(expectedMinAda)
        }

        // Clean spendable pure ADA (> 10 ADA) is split into 2 parallel clean UTxOs (unfrack.it pattern)
        assertThat(pureAdaOutputs).hasSize(2)
        pureAdaOutputs.forEach { out ->
            assertThat(out.lovelace).isGreaterThan(BigInteger.valueOf(30_000_000L))
        }

        // Verify exact lovelace and multi-asset conservation
        assertThat(plan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(plan.outputs.sumByBigInteger { it.lovelace } + plan.fee)

        val consolidatedAssets = mutableMapOf<String, BigInteger>()
        plan.outputs.flatMap { it.nativeAssets }.forEach { asset ->
            val key = "${asset.policy}.${asset.name}".trimEnd('.')
            consolidatedAssets[key] = (consolidatedAssets[key] ?: BigInteger.ZERO) + asset.amount
        }
        assertThat(consolidatedAssets).isEqualTo(allUtxos.toNativeAssetMap())
    }

    @Test
    fun `defragment mode binary-searches maximum safe UTxO prefix within 16KB on 1,000-UTxO wallet`() {
        val massiveUtxos =
            (1..1_000).map { idx ->
                val assets =
                    if (idx <= 200) {
                        listOf(
                            NativeAsset(
                                name = "4e4654${(idx % 35).toString(16).padStart(2, '0')}",
                                policy = if (idx % 2 == 0) policyA else policyB,
                                amount = BigInteger.ONE,
                            )
                        )
                    } else {
                        emptyList()
                    }
                Utxo(
                    hash = txHash(10_000 + idx),
                    ix = (idx % 3).toLong(),
                    lovelace = BigInteger.valueOf(1_500_000L + (idx % 7) * 100_000L),
                    nativeAssets = assets,
                )
            }

        val plan =
            planner.planWalletTransaction(
                utxos = massiveUtxos,
                changeAddress = senderAddress,
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:1",
                            address = senderAddress,
                            currency = "ada",
                            amount = BigInteger.ZERO,
                            isPercent = true,
                            percent = 100,
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )

        assertThat(plan.selectionMode).isEqualTo(SelectionMode.DEFRAGMENT)
        assertThat(plan.selectedUtxos.size).isGreaterThan(250)
        assertThat(plan.selectedUtxos.size).isLessThan(1_000)
        assertThat(plan.estimatedTxBytes).isAtMost(16_384)
        // Unselected UTxOs are reported in tokenLocked so percentage math matches selected UTxOs
        assertThat(plan.tokenLocked).isGreaterThan(BigInteger.ZERO)
        assertThat(plan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(plan.outputs.sumByBigInteger { it.lovelace } + plan.fee)
    }

    @Test
    fun `local MinAdaCalculator and dynamic CBOR fee estimation work without private keys`() {
        val enterprisePureMinAda = planner.calculateMinAda(recipientAddress, emptyList(), protocolParameters)
        val baseAddressPureMinAda = planner.calculateMinAda(senderAddress, emptyList(), protocolParameters)
        val singleHexTokenMinAda =
            planner.calculateMinAda(
                recipientAddress,
                listOf(NativeAsset(name = "4e45574d", policy = policyC, amount = BigInteger.valueOf(1_000_000L))),
                protocolParameters,
            )
        val multiTokenMinAda =
            planner.calculateMinAda(
                recipientAddress,
                (1..12).map { idx ->
                    NativeAsset(
                        name = "544f4b454e${idx.toString(16).padStart(2, '0')}",
                        policy = policyA,
                        amount = BigInteger.valueOf(100L * idx),
                    )
                },
                protocolParameters,
            )

        assertThat(enterprisePureMinAda).isGreaterThan(BigInteger.valueOf(800_000L))
        assertThat(baseAddressPureMinAda).isGreaterThan(enterprisePureMinAda)
        assertThat(singleHexTokenMinAda).isGreaterThan(enterprisePureMinAda)
        assertThat(multiTokenMinAda).isGreaterThan(singleHexTokenMinAda)

        val oneInput = listOf(Utxo(txHash(1), 0, BigInteger.valueOf(20_000_000L), emptyList()))
        val fiveInputs = (1..5).map { Utxo(txHash(it), 0, BigInteger.valueOf(5_000_000L), emptyList()) }
        val outputs =
            listOf(
                PlannedOutput(recipientAddress, BigInteger.valueOf(10_000_000L), emptyList()),
                PlannedOutput(senderAddress, BigInteger.valueOf(9_800_000L), emptyList()),
            )

        val (fee1, size1) =
            planner.calculateFeeAndSize(
                selectedUtxos = oneInput,
                outputs = outputs,
                currentFeeEstimate = BigInteger.valueOf(200_000L),
                protocolParameters = protocolParameters,
                witnessCount = 1,
            )
        val (fee5, size5) =
            planner.calculateFeeAndSize(
                selectedUtxos = fiveInputs,
                outputs = outputs,
                currentFeeEstimate = BigInteger.valueOf(200_000L),
                protocolParameters = protocolParameters,
                witnessCount = 2,
                metadataJson = """{"674":{"msg":["JorManager SDK planner test"]}}""",
                withdrawals =
                    listOf(
                        WithdrawalItem(
                            stakingAddress = TransactionPlannerService.DEFAULT_DUMMY_STAKE_ADDRESS,
                            lovelace = BigInteger.valueOf(5_000_000L),
                        )
                    ),
            )
        val (feeWithRefScript, _) =
            planner.calculateFeeAndSize(
                selectedUtxos = fiveInputs,
                outputs = outputs,
                currentFeeEstimate = BigInteger.valueOf(200_000L),
                protocolParameters = protocolParameters,
                witnessCount = 2,
                referenceScriptBytes = 10_000L,
            )

        assertThat(size5).isGreaterThan(size1)
        assertThat(fee5).isGreaterThan(fee1)
        assertThat(feeWithRefScript).isGreaterThan(fee1)
    }

    @Test
    fun `16KB size guardrails reject oversized unsigned plans and signed envelopes`() {
        planner.validateTransactionSize(16_384, 16_384)
        val sizeEx =
            assertThrows<IOException> {
                planner.validateTransactionSize(16_385, 16_384)
            }
        assertThat(sizeEx).hasMessageThat().contains("16384")

        val validSignedJson =
            """{"type":"Tx ConwayEra","description":"","cborHex":"${"a0".repeat(512)}"}"""
        val validByteLen = planner.validateSignedEnvelopeSize(validSignedJson, protocolParameters)
        assertThat(validByteLen).isEqualTo(512)

        val oversizedSignedJson =
            """{"type":"Tx ConwayEra","description":"","cborHex":"${"ff".repeat(16_385)}"}"""
        val envEx =
            assertThrows<IOException> {
                planner.validateSignedEnvelopeSize(oversizedSignedJson, protocolParameters)
            }
        assertThat(envEx).hasMessageThat().contains("exceeds Cardano protocol maxTxSize")
    }

    @Test
    fun `performance guardrail completes UTxO selection and fee planning under 500ms for 1,000 UTxOs`() {
        val activeWalletUtxos =
            (1..1_000).map { idx ->
                val assets =
                    if (idx % 4 == 0) {
                        listOf(
                            NativeAsset(
                                name = "4d494e${(idx % 16).toString(16).padStart(2, '0')}",
                                policy = policyA,
                                amount = BigInteger.valueOf(100L),
                            )
                        )
                    } else {
                        emptyList()
                    }
                Utxo(
                    hash = txHash(20_000 + idx),
                    ix = (idx % 4).toLong(),
                    lovelace = BigInteger.valueOf(3_000_000L + (idx % 25) * 1_000_000L),
                    nativeAssets = assets,
                )
            }

        // Warm up JVM / JIT / Jackson-CBOR serializers once
        planner.planWalletTransaction(
            utxos = activeWalletUtxos,
            changeAddress = senderAddress,
            toAccountItems =
                listOf(
                    WalletAccountLineItem(
                        destinationKey = "acct:2",
                        address = recipientAddress,
                        currency = "ada",
                        amount = BigInteger.valueOf(15_000_000L),
                    )
                ),
            protocolParameters = protocolParameters,
            selectionMode = SelectionMode.STANDARD,
        )

        val standardWalletMs =
            measureNanoTime {
                val plan =
                    planner.planWalletTransaction(
                        utxos = activeWalletUtxos,
                        changeAddress = senderAddress,
                        toAccountItems =
                            listOf(
                                WalletAccountLineItem(
                                    destinationKey = "acct:2",
                                    address = recipientAddress,
                                    currency = "ada",
                                    amount = BigInteger.valueOf(45_000_000L),
                                )
                            ),
                        protocolParameters = protocolParameters,
                        selectionMode = SelectionMode.STANDARD,
                    )
                assertThat(plan.estimatedTxBytes).isAtMost(16_384)
            } / 1_000_000.0

        val standardAdminMs =
            measureNanoTime {
                val plan =
                    planner.planAdminTransaction(
                        utxos = activeWalletUtxos,
                        changeAddress = senderAddress,
                        protocolParameters = protocolParameters,
                        deposit = BigInteger.valueOf(500_000_000L),
                        witnessCount = 3,
                    )
                assertThat(plan.estimatedTxBytes).isAtMost(16_384)
            } / 1_000_000.0

        val defragmentMs =
            measureNanoTime {
                val plan =
                    planner.planWalletTransaction(
                        utxos = activeWalletUtxos,
                        changeAddress = senderAddress,
                        toAccountItems =
                            listOf(
                                WalletAccountLineItem(
                                    destinationKey = "acct:1",
                                    address = senderAddress,
                                    currency = "ada",
                                    amount = BigInteger.ZERO,
                                    isPercent = true,
                                    percent = 100,
                                )
                            ),
                        protocolParameters = protocolParameters,
                        selectionMode = SelectionMode.DEFRAGMENT,
                    )
                assertThat(plan.estimatedTxBytes).isAtMost(16_384)
            } / 1_000_000.0

        assertThat(standardWalletMs).isLessThan(500.0)
        assertThat(standardAdminMs).isLessThan(500.0)
        assertThat(defragmentMs).isLessThan(500.0)
    }
}
