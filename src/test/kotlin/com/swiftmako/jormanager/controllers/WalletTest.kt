package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.round
import org.junit.jupiter.api.Test

class WalletTest {
    private val random = SecureRandom(System.currentTimeMillis().toString().toByteArray())

    @Test
    fun `test percentage splitting`() {
        repeat(1000) {
            for (user1Percentage in 1 until 100) {
                val user2Percentage = 100 - user1Percentage
                val amountToSplit = abs(random.nextLong() % 44000000000000L)

                val user1Amount = round((user1Percentage / 100.0) * amountToSplit).toLong()
                val spentPercentage = user1Percentage
                val spentAmount = user1Amount
                val user2Amount =
                    round(((user2Percentage + spentPercentage) / 100.0) * amountToSplit).toLong() - spentAmount

                assertThat(user1Amount + user2Amount).isEqualTo(amountToSplit)

                println("$user1Amount + $user2Amount = $amountToSplit")
            }
        }
    }

    @Test
    fun `test 1_26_0 wallet parsing`() {
//        val json = """
//            |{
//            |    "77a2d704e4b5f0901a52a6d2fa88626b61312e3a1d46fe8ad2baae4c48b72824#1": {
//            |        "amount": [
//            |            210001628430,
//            |            [
//            |                [
//            |                    "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518",
//            |                    [
//            |                        [
//            |                            "ATADAcoin",
//            |                            10
//            |                        ],
//            |                        [
//            |                            "adosia",
//            |                            12345
//            |                        ]
//            |                    ]
//            |                ]
//            |            ]
//            |        ],
//            |        "address": "607e8c76538b4aa50a62e6fe015ddb58a97b2131c6bb15fc0b8eeffd3a"
//            |    }
//            |}
//            """.trimMargin()

        val json =
            """
                |{
                |    "55b0751876f5a846faa11b7aeaff979c00c42d21f1960d5610bbcf9a4fbd72ac#2": {
                |        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
                |        "value": {
                |            "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518": {
                |                "ADA": 1000,
                |                "": 1
                |            },
                |            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
                |                "BlueCheese": 3998
                |            },
                |            "ecd07b4ef62f37a68d145de8efd60c53d288dd5ffc641215120cc3db": {
                |                "": 3
                |            },
                |            "lovelace": 16405517685
                |        }
                |    },
                |    "bb82e22a3b1b78e24183d5ef7a83a5cbeb089d0ae9b0ca7f143703c3d9b11b69#0": {
                |        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
                |        "value": {
                |            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
                |                "BlueCheese": 1
                |            },
                |            "lovelace": 4820683
                |        }
                |    },
                |    "c246847b906d7ea96bc072f4e5b5b660aa514f26a2b84975fe0275da10c56f78#0": {
                |        "address": "60da0eb5ed7611482ec5089b69d870e0c56c1c45180256112398e0835b",
                |        "value": {
                |            "b45fde8ab44e77aaa825e838715c5f2a1b60013c19efa639fa96da96": {
                |                "BlueCheese": 1
                |            },
                |            "lovelace": 4820683
                |        }
                |    }
                |}
            """.trimMargin()

        val adapter = QueryUtxoJsonAdapter()
        val utxos = adapter.fromJson(json)!!

        println("$utxos")
        assertThat(utxos.size).isEqualTo(3)
        assertThat(utxos[0].nativeAssets.size).isEqualTo(4)
    }

    @Test
    fun `test 10_4_1 wallet parsing`() {
        val json =
            """
            {
                "01a0be6c18aa32bb48bafe60c49e1f1213f628b552fd3257274cf58b96c7cd22#1": {
                    "address": "addr1v88v00pdjyumu8xseh4ek04jtau9mvhnq73gq8p8jatfgnqafuxzv",
                    "datum": null,
                    "datumhash": null,
                    "inlineDatum": null,
                    "inlineDatumRaw": null,
                    "referenceScript": null,
                    "value": {
                        "682fe60c9918842b3323c43b5144bc3d52a23bd2fb81345560d73f63": {
                            "4e45574d": 149764655
                        },
                        "lovelace": 1043020
                    }
                },
                "0274ef4c0d7531bb21c15f6744668d705a436355b5ce32959b4b7665f09eb293#1": {
                    "address": "addr1v88v00pdjyumu8xseh4ek04jtau9mvhnq73gq8p8jatfgnqafuxzv",
                    "datum": null,
                    "datumhash": null,
                    "inlineDatum": null,
                    "inlineDatumRaw": null,
                    "referenceScript": null,
                    "value": {
                        "682fe60c9918842b3323c43b5144bc3d52a23bd2fb81345560d73f63": {
                            "4e45574d": 5050500
                        },
                        "lovelace": 1043020
                    }
                }
            }
            """.trimIndent()

        val adapter = QueryUtxoJsonAdapter()
        val utxos = adapter.fromJson(json)!!

        println("$utxos")
        assertThat(utxos.size).isEqualTo(2)
        assertThat(utxos[0].nativeAssets.size).isEqualTo(1)
    }
}
