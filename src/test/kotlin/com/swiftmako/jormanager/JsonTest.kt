package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.api.LeaderBlock
import org.junit.Test

class JsonTest {

    @Test
    fun testJson() {
        val moshi = Moshi.Builder().build()
        val leaderLogJsonAdapter = moshi.adapter<List<LeaderBlock>>(Types.newParameterizedType(List::class.java, LeaderBlock::class.java)).indent("  ")
        val list = emptyList<LeaderBlock>()
        val json = leaderLogJsonAdapter.toJson(list)
        println("json: $json")
    }
}