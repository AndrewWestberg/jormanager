package com.swiftmako.jormanager.spring.config

import com.google.common.truth.Truth.assertThat
import org.joda.time.DateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoshiConfigurationTest {
    private val moshi = Configuration().getMoshi()

    @Test
    fun configMapKeysAndValuesAreNotParsedAsDates() {
        val values = requireNotNull(moshi.adapter(Map::class.java).lenient().fromJson(
            """{
                "AlonzoGenesisFile": "alonzo-genesis.json",
                "RequiresNetworkMagic": "RequiresMagic",
                "TestShelleyHardForkAtEpoch": 0,
                "TraceOptions": {"": {"backends": ["Stdout MachineFormat"]}}
            }"""
        ))

        assertThat(values["AlonzoGenesisFile"]).isEqualTo("alonzo-genesis.json")
        assertThat(values["RequiresNetworkMagic"]).isEqualTo("RequiresMagic")
        assertThat((values["TestShelleyHardForkAtEpoch"] as Number).toLong()).isEqualTo(0L)
        val trace = (values["TraceOptions"] as Map<*, *>)[""] as Map<*, *>
        assertThat(trace["backends"]).isEqualTo(listOf("Stdout MachineFormat"))
    }

    @Test
    fun dateAdapterPreservesTimestampParsingAndWireFormat() {
        val adapter = moshi.adapter(DateTime::class.java)
        val timestamp = requireNotNull(adapter.fromJson("\"2026-10-18T00:00:00Z\""))

        assertThat(timestamp.millis).isEqualTo(1792281600000L)
        assertThat(adapter.toJson(timestamp)).isEqualTo("\"20261018T000000.000Z\"")
    }

    @Test
    fun dateAdapterPreservesNullsAndRejectsInvalidDates() {
        val adapter = moshi.adapter(DateTime::class.java)

        assertThat(adapter.fromJson("null")).isNull()
        assertThat(adapter.toJson(null)).isEqualTo("null")
        assertThrows<IllegalArgumentException> { adapter.fromJson("\"not-a-date\"") }
    }
}
