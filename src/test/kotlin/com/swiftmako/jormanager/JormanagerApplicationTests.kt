package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class JormanagerApplicationTests {
    @Test
    fun contextLoads() {
    }

    @Test
    fun testStringToBigDecimal() {
        val input = ".01"

        assertThat(input.toBigDecimal().toString()).isEqualTo("0.01")
    }
}
