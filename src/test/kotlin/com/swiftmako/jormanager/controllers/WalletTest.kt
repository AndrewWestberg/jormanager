package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.round

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
                val user2Amount = round(((user2Percentage + spentPercentage) / 100.0) * amountToSplit).toLong() - spentAmount

                assertThat(user1Amount + user2Amount).isEqualTo(amountToSplit)

                println("$user1Amount + $user2Amount = $amountToSplit")
            }
        }
    }

    @Test
    fun `test mary wallet parsing`() {
        val json = """
            |{
            |    "77a2d704e4b5f0901a52a6d2fa88626b61312e3a1d46fe8ad2baae4c48b72824#1": {
            |        "amount": [
            |            210001628430,
            |            [
            |                [
            |                    "34250edd1e9836f5378702fbf9416b709bc140e04f668cc355208518",
            |                    [
            |                        [
            |                            "ATADAcoin",
            |                            10
            |                        ],
            |                        [
            |                            "adosia",
            |                            12345
            |                        ]
            |                    ]
            |                ]
            |            ]
            |        ],
            |        "address": "607e8c76538b4aa50a62e6fe015ddb58a97b2131c6bb15fc0b8eeffd3a"
            |    }
            |}
            """.trimMargin()

        val adapter = QueryUtxoJsonAdapter()
        val utxos = adapter.fromJson(json)!!

        println("$utxos")
        assertThat(utxos.size).isEqualTo(1)
        assertThat(utxos[0].nativeAssets.size).isEqualTo(2)

    }
}