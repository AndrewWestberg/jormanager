package com.swiftmako.jormanager.controllers.utils

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.spring.config.Configuration
import org.junit.jupiter.api.Test

class StakeAddressInfoTest {
    @Test
    fun testStakeAddressInfo() {
        val json =
            """
            |[
            |    {
            |        "address": "stake1uyenhm5yptcaf4j4dkyel9epvr94g7h36dts6599t84ma0g2msety",
            |        "delegation": null,
            |        "rewardAccountBalance": 0
            |    }
            |]
            """.trimMargin()
        val type = Types.newParameterizedType(List::class.java, StakeAddressInfo::class.java)
        val adapter = Configuration().getMoshi().adapter<List<StakeAddressInfo>>(type)
        val stakeAddressInfos = adapter.fromJson(json)

        assertThat(stakeAddressInfos?.size).isEqualTo(1)
    }
}
