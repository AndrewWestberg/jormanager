package com.swiftmako.jormanager.services

import com.bloxbean.cardano.client.api.MinAdaCalculator
import com.bloxbean.cardano.client.coinselection.impl.LargestFirstUtxoSelectionStrategy
import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.PlannedOutput
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.SelectionMode
import com.swiftmako.jormanager.model.TransactionPlan
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
    fun `defragment mode consolidates ada-only UTxOs into a single output without splitting`() {
        val twoPureAdaUtxos =
            listOf(
                Utxo(hash = txHash(7_001), ix = 0, lovelace = BigInteger.valueOf(4_998_662_750L), nativeAssets = emptyList()),
                Utxo(hash = txHash(7_002), ix = 1, lovelace = BigInteger.valueOf(4_998_662_750L), nativeAssets = emptyList()),
            )

        val plan =
            planner.planWalletTransaction(
                utxos = twoPureAdaUtxos,
                changeAddress = senderAddress,
                toAccountItems = emptyList(),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )

        assertThat(plan.selectionMode).isEqualTo(SelectionMode.DEFRAGMENT)
        assertThat(plan.selectedUtxos).hasSize(2)
        assertThat(plan.outputs).hasSize(1)
        assertThat(plan.outputs.single().address).isEqualTo(senderAddress)
        assertThat(plan.outputs.single().nativeAssets).isEmpty()
        assertThat(plan.outputs.single().lovelace + plan.fee)
            .isEqualTo(twoPureAdaUtxos.sumByBigInteger { it.lovelace })
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

    @Test
    fun `net-unlocked selection preserves tie order and matches full 20-token bundle min-ADA across shifted chunks and CBOR widths`() {
        val deterministicPlanner =
            TransactionPlannerService(
                randomImproveStrategyFactory = { supplier ->
                    LargestFirstUtxoSelectionStrategy(supplier, false)
                },
                largestFirstStrategyFactory = { supplier ->
                    LargestFirstUtxoSelectionStrategy(supplier, false)
                },
            )

        // Requested token lives on UTxO #1 alongside an early-sorted policyC token
        val requestedTokenName = "5245513031"
        val requestedUtxo =
            Utxo(
                hash = txHash(30_001),
                ix = 0,
                lovelace = BigInteger.valueOf(2_200_000L),
                nativeAssets =
                    listOf(
                        NativeAsset(name = requestedTokenName, policy = policyB, amount = BigInteger.valueOf(50L)),
                        NativeAsset(name = "41413031", policy = policyC, amount = BigInteger.valueOf(20L)),
                    ),
            )

        // 30 token-heavy UTxOs: each has 3.5 ADA nominal lovelace (> 2.5 ADA pure UTxOs) so nominal Largest-First
        // picks them first, but each carries 16 unique tokens + 4 shared boundary tokens (~2.3 ADA min-ADA per 20-token
        // bundle, leaving only ~1.2 ADA net unlocked) so nominal attempts fail and escalate to selectByNetUnlockedLovelace.
        // Shared tokens cross CBOR unsigned int width boundaries (23->24, 255->256, 65535->65536, 4294967295->4294967296)
        // while unique tokens across policyC < policyA < policyB shift 20-token chunk boundaries on every candidate.
        val cborBoundaryAmounts =
            listOf(
                BigInteger.valueOf(20L),
                BigInteger.valueOf(250L),
                BigInteger.valueOf(65_530L),
                BigInteger.valueOf(4_294_967_290L),
            )
        val tokenHeavyUtxos =
            (1..30).map { idx ->
                val sharedAssets =
                    (1..4).map { t ->
                        val sharedIdx = ((idx + t) % 8) + 1
                        val policy =
                            when (sharedIdx % 3) {
                                0 -> policyC
                                1 -> policyA
                                else -> policyB
                            }
                        NativeAsset(
                            name = "424e44${sharedIdx.toString(16).padStart(2, '0')}",
                            policy = policy,
                            amount = cborBoundaryAmounts[t - 1],
                        )
                    }
                val uniqueAssets =
                    (1..16).map { u ->
                        val policy =
                            when ((idx + u) % 3) {
                                0 -> policyC
                                1 -> policyA
                                else -> policyB
                            }
                        NativeAsset(
                            name = "554e51${idx.toString(16).padStart(2, '0')}${u.toString(16).padStart(2, '0')}",
                            policy = policy,
                            amount = BigInteger.valueOf(15L + u),
                        )
                    }
                Utxo(
                    hash = txHash(31_000 + idx),
                    ix = 0,
                    lovelace = BigInteger.valueOf(2_150_000L),
                    nativeAssets = sharedAssets + uniqueAssets,
                )
            }

        // Pure-ADA UTxOs with deliberate lovelace ties (2.0 ADA each < 2.15 ADA nominal, but 2.0 ADA > ~0.3 ADA net unlocked)
        val tiedPureUtxos =
            (1..8).map { idx ->
                Utxo(
                    hash = txHash(32_000 + idx),
                    ix = idx.toLong(),
                    lovelace = BigInteger.valueOf(2_000_000L),
                    nativeAssets = emptyList(),
                )
            }

        val allUtxos = listOf(requestedUtxo) + tokenHeavyUtxos + tiedPureUtxos
        val items =
            listOf(
                WalletAccountLineItem(
                    destinationKey = "acct:2",
                    address = recipientAddress,
                    currency = "ada",
                    amount = BigInteger.valueOf(20_000_000L),
                ),
                WalletAccountLineItem(
                    destinationKey = "acct:2",
                    address = recipientAddress,
                    currency = "$policyB.$requestedTokenName",
                    amount = BigInteger.valueOf(20L),
                ),
            )

        val plan1 =
            deterministicPlanner.planWalletTransaction(
                utxos = allUtxos,
                changeAddress = senderAddress,
                toAccountItems = items,
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )
        val plan2 =
            deterministicPlanner.planWalletTransaction(
                utxos = allUtxos,
                changeAddress = senderAddress,
                toAccountItems = items,
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )

        assertThat(plan1).isEqualTo(plan2)
        assertThat(plan1.usedFallback).isTrue()

        // Tied pure-ADA UTxOs must all be selected first (after requestedUtxo) in exact original input order
        val selectedPureHashes = plan1.selectedUtxos.filter { it.nativeAssets.isEmpty() }.map { it.hash }
        assertThat(selectedPureHashes).isEqualTo(tiedPureUtxos.map { it.hash })

        // Multiple 20-token change outputs must be produced and match exact reference min-ADA per chunk
        val changeTokenOutputs = plan1.outputs.filter { it.address == senderAddress && it.nativeAssets.isNotEmpty() }
        assertThat(changeTokenOutputs.size).isGreaterThan(4)

        val expectedLeftoverMap =
            plan1.selectedUtxos
                .toNativeAssetMap()
                .mapValues { (k, v) ->
                    if (k == "$policyB.$requestedTokenName") v - BigInteger.valueOf(20L) else v
                }.filterValues { it > BigInteger.ZERO }
        val expectedSortedAssets =
            expectedLeftoverMap
                .map { (cur, amt) -> deterministicPlanner.currencyToNativeAsset(cur, amt) }
                .sortedWith(compareBy<NativeAsset> { it.policy }.thenBy { it.name })
        val expectedChunks = expectedSortedAssets.chunked(TransactionPlannerService.NATIVE_ASSET_MAX_GROUP_SIZE)
        val expectedKeepFee =
            expectedChunks.sumByBigInteger { chunk ->
                deterministicPlanner.calculateMinAda(senderAddress, chunk, protocolParameters)
            }

        assertThat(plan1.tokenKeepFee).isEqualTo(expectedKeepFee)
        assertThat(plan1.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(plan1.outputs.sumByBigInteger { it.lovelace } + plan1.fee)

        // Verify admin net-unlocked fallback when pure-ADA pool (16 ADA) < deposit (18 ADA)
        val adminPlan =
            deterministicPlanner.planAdminTransaction(
                utxos = tokenHeavyUtxos + tiedPureUtxos,
                changeAddress = senderAddress,
                protocolParameters = protocolParameters,
                deposit = BigInteger.valueOf(18_000_000L),
                witnessCount = 2,
            )
        assertThat(adminPlan.usedFallback).isTrue()
        assertThat(adminPlan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(adminPlan.outputs.sumByBigInteger { it.lovelace } + BigInteger.valueOf(18_000_000L) + adminPlan.fee)
    }

    @Test
    fun `defragment ordering partitions all UTxO buckets cleanly and preserves input order on ties`() {
        val uniqueCleanPolicy1 = "11".repeat(28)
        val uniqueCleanPolicy2 = "22".repeat(28)

        // Non-fragmented multi-asset UTxOs: 6 assets (>= 5, <= 20) under policies appearing on only 1 UTxO each, tied lovelace
        val nonFragmentedToken1 =
            Utxo(
                hash = txHash(40_001),
                ix = 0,
                lovelace = BigInteger.valueOf(3_500_000L),
                nativeAssets = (1..6).map { NativeAsset("434c4e0$it", uniqueCleanPolicy1, BigInteger.TEN) },
            )
        val nonFragmentedToken2 =
            Utxo(
                hash = txHash(40_002),
                ix = 0,
                lovelace = BigInteger.valueOf(3_500_000L),
                nativeAssets = (1..6).map { NativeAsset("434c4e0$it", uniqueCleanPolicy2, BigInteger.TEN) },
            )

        // Larger pure-ADA UTxOs (>= 10 ADA) with tied lovelace (15 ADA), plus one 25 ADA funding UTxO
        val fundingUtxo = Utxo(txHash(40_010), 0, BigInteger.valueOf(25_000_000L), emptyList())
        val largePure1 = Utxo(txHash(40_011), 0, BigInteger.valueOf(15_000_000L), emptyList())
        val largePure2 = Utxo(txHash(40_012), 0, BigInteger.valueOf(15_000_000L), emptyList())

        // Fragmented token UTxOs (< 5 assets) with tied (size, lovelace)
        val fragToken1 =
            Utxo(
                hash = txHash(40_021),
                ix = 0,
                lovelace = BigInteger.valueOf(2_000_000L),
                nativeAssets = listOf(NativeAsset("4652414731", policyA, BigInteger.ONE)),
            )
        val fragToken2 =
            Utxo(
                hash = txHash(40_022),
                ix = 0,
                lovelace = BigInteger.valueOf(2_000_000L),
                nativeAssets = listOf(NativeAsset("4652414732", policyA, BigInteger.ONE)),
            )

        // Dust pure-ADA UTxOs (< 10 ADA) with tied lovelace
        val dustPure1 = Utxo(txHash(40_031), 0, BigInteger.valueOf(1_500_000L), emptyList())
        val dustPure2 = Utxo(txHash(40_032), 0, BigInteger.valueOf(1_500_000L), emptyList())

        // Interleave inputs in reverse bucket order to ensure partitioning + stable tie sorting determines exact output order
        val scrambledInput =
            listOf(
                nonFragmentedToken1,
                largePure1,
                fragToken1,
                dustPure1,
                nonFragmentedToken2,
                largePure2,
                fragToken2,
                dustPure2,
                fundingUtxo,
            )

        val plan =
            planner.planWalletTransaction(
                utxos = scrambledInput,
                changeAddress = senderAddress,
                toAccountItems = emptyList(),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )

        assertThat(plan.selectedUtxos)
            .containsExactly(
                fundingUtxo,
                dustPure1,
                dustPure2,
                fragToken1,
                fragToken2,
                largePure1,
                largePure2,
                nonFragmentedToken1,
                nonFragmentedToken2,
            ).inOrder()
    }

    @Test
    fun `synthetic duplicate hash-ix UTxOs fall back to structural Utxo membership and invalid Bech32 uses DUMMY_ADDRESS`() {
        val sharedHash = txHash(50_001)
        val dupPureLarge = Utxo(hash = sharedHash, ix = 0, lovelace = BigInteger.valueOf(40_000_000L), nativeAssets = emptyList())
        val dupPureSmall = Utxo(hash = sharedHash, ix = 0, lovelace = BigInteger.valueOf(5_000_000L), nativeAssets = emptyList())
        val dupTokenUtxo =
            Utxo(
                hash = sharedHash,
                ix = 0,
                lovelace = BigInteger.valueOf(3_000_000L),
                nativeAssets = listOf(NativeAsset("445550", policyA, BigInteger.TEN)),
            )
        val utxosWithDuplicateRefs = listOf(dupPureLarge, dupPureSmall, dupTokenUtxo)

        val standardPlan =
            planner.planWalletTransaction(
                utxos = utxosWithDuplicateRefs,
                changeAddress = "invalid_bech32_change_address",
                toAccountItems =
                    listOf(
                        WalletAccountLineItem(
                            destinationKey = "acct:2",
                            address = "invalid_bech32_recipient_address",
                            currency = "ada",
                            amount = BigInteger.valueOf(10_000_000L),
                        )
                    ),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.STANDARD,
            )

        // dupTokenUtxo shares (hash, ix) with selected pure-ADA UTxO(s), yet must still be counted in tokenLocked!
        assertThat(standardPlan.selectedUtxos).doesNotContain(dupTokenUtxo)
        assertThat(standardPlan.tokenLocked).isEqualTo(dupTokenUtxo.lovelace)
        assertThat(standardPlan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(standardPlan.outputs.sumByBigInteger { it.lovelace } + standardPlan.fee)

        val adminPlan =
            planner.planAdminTransaction(
                utxos = utxosWithDuplicateRefs,
                changeAddress = "invalid_bech32_admin_address",
                protocolParameters = protocolParameters,
                deposit = BigInteger.valueOf(2_000_000L),
            )
        assertThat(adminPlan.selectedUtxos).doesNotContain(dupTokenUtxo)
        assertThat(adminPlan.tokenLocked).isEqualTo(dupTokenUtxo.lovelace)

        val defragPlan =
            planner.planWalletTransaction(
                utxos = utxosWithDuplicateRefs,
                changeAddress = "invalid_bech32_change_address",
                toAccountItems = emptyList(),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )
        assertThat(defragPlan.selectedUtxos).hasSize(3)
        assertThat(defragPlan.selectedUtxos.sumByBigInteger { it.lovelace })
            .isEqualTo(defragPlan.outputs.sumByBigInteger { it.lovelace } + defragPlan.fee)

        val dummyAddressMinAda =
            planner.calculateMinAda(
                address = "not_bech32_at_all",
                nativeAssets = listOf(NativeAsset("445550", policyA, BigInteger.TEN)),
                protocolParameters = protocolParameters,
            )
        val explicitDummyMinAda =
            planner.calculateMinAda(
                address = MinAdaCalculator.DUMMY_ADDRESS,
                nativeAssets = listOf(NativeAsset("445550", policyA, BigInteger.TEN)),
                protocolParameters = protocolParameters,
            )
        assertThat(dummyAddressMinAda).isEqualTo(explicitDummyMinAda)
    }

    @Test
    fun `scaling guardrail plans 2,500 multi-asset UTxOs deterministically across STANDARD, DEFRAGMENT, and admin flows`() {
        val deterministicPlanner =
            TransactionPlannerService(
                randomImproveStrategyFactory = { supplier ->
                    LargestFirstUtxoSelectionStrategy(supplier, false)
                },
                largestFirstStrategyFactory = { supplier ->
                    LargestFirstUtxoSelectionStrategy(supplier, false)
                },
            )

        val multiAssetWallet2500 =
            (1..2_500).map { idx ->
                val assets =
                    when {
                        idx % 3 == 0 ->
                            (1..((idx % 5) + 1)).map { t ->
                                NativeAsset(
                                    name = "544f4b${((idx + t) % 45).toString(16).padStart(2, '0')}",
                                    policy = if ((idx + t) % 2 == 0) policyA else policyB,
                                    amount = BigInteger.valueOf(10L * t),
                                )
                            }
                        idx % 7 == 0 ->
                            (1..6).map { t ->
                                NativeAsset(
                                    name = "424e44${t.toString(16).padStart(2, '0')}",
                                    policy = policyC,
                                    amount = BigInteger.valueOf(100L + t),
                                )
                            }
                        else -> emptyList()
                    }
                Utxo(
                    hash = txHash(60_000 + idx),
                    ix = (idx % 4).toLong(),
                    lovelace =
                        if (assets.isEmpty()) {
                            BigInteger.valueOf(1_400_000L + (idx % 12) * 800_000L)
                        } else {
                            BigInteger.valueOf(2_200_000L + (idx % 6) * 200_000L)
                        },
                    nativeAssets = assets,
                )
            }

        // Warm up once
        deterministicPlanner.planWalletTransaction(
            utxos = multiAssetWallet2500.subList(0, 500),
            changeAddress = senderAddress,
            toAccountItems = emptyList(),
            protocolParameters = protocolParameters,
            selectionMode = SelectionMode.DEFRAGMENT,
        )

        var defragPlan1: TransactionPlan? = null
        val defrag2500Ms =
            measureNanoTime {
                defragPlan1 =
                    deterministicPlanner.planWalletTransaction(
                        utxos = multiAssetWallet2500,
                        changeAddress = senderAddress,
                        toAccountItems = emptyList(),
                        protocolParameters = protocolParameters,
                        selectionMode = SelectionMode.DEFRAGMENT,
                    )
            } / 1_000_000.0
        val defragPlan2 =
            deterministicPlanner.planWalletTransaction(
                utxos = multiAssetWallet2500,
                changeAddress = senderAddress,
                toAccountItems = emptyList(),
                protocolParameters = protocolParameters,
                selectionMode = SelectionMode.DEFRAGMENT,
            )

        assertThat(defragPlan1).isEqualTo(defragPlan2)
        assertThat(defragPlan1!!.estimatedTxBytes).isAtMost(16_384)
        assertThat(defrag2500Ms).isLessThan(500.0)

        var standardPlan: TransactionPlan? = null
        val standard2500Ms =
            measureNanoTime {
                standardPlan =
                    deterministicPlanner.planWalletTransaction(
                        utxos = multiAssetWallet2500,
                        changeAddress = senderAddress,
                        toAccountItems =
                            listOf(
                                WalletAccountLineItem(
                                    destinationKey = "acct:2",
                                    address = recipientAddress,
                                    currency = "ada",
                                    amount = BigInteger.valueOf(30_000_000L),
                                )
                            ),
                        protocolParameters = protocolParameters,
                        selectionMode = SelectionMode.STANDARD,
                    )
            } / 1_000_000.0
        assertThat(standardPlan!!.estimatedTxBytes).isAtMost(16_384)
        assertThat(standard2500Ms).isLessThan(500.0)

        var adminPlan: TransactionPlan? = null
        val admin2500Ms =
            measureNanoTime {
                adminPlan =
                    deterministicPlanner.planAdminTransaction(
                        utxos = multiAssetWallet2500,
                        changeAddress = senderAddress,
                        protocolParameters = protocolParameters,
                        deposit = BigInteger.valueOf(2_000_000L),
                        witnessCount = 2,
                    )
            } / 1_000_000.0
        assertThat(adminPlan!!.estimatedTxBytes).isAtMost(16_384)
        assertThat(admin2500Ms).isLessThan(500.0)
    }
}
