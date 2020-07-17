package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
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
}