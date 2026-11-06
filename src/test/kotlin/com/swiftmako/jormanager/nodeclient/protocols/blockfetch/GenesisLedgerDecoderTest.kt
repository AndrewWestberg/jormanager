package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.toHexString
import java.math.BigInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GenesisLedgerDecoderTest {
    @Test
    fun `legacy and embedded initial funds derive the same pseudo transaction`() {
        val legacy = GenesisLedgerDecoder.shelley("""{"initialFunds":{"01":42}}""").single()
        val embedded = GenesisLedgerDecoder.shelley("""{"initialFunds":{},"extraConfig":{"initialFunds":{"data":{"01":"42"}}}}""").single()
        for (output in listOf(legacy, embedded)) {
            assertThat(output.address.toHexString()).isEqualTo("01")
            assertThat(output.txId.toHexString()).isEqualTo("ee155ace9c40292074cb6aff8c9ccdd273c81648ff1149ef36bcea6ebb8a3e25")
            assertThat(output.coin).isEqualTo(BigInteger.valueOf(42))
        }
    }

    @Test
    fun `missing null and empty no injection fall back to legacy`() {
        for (extra in listOf("null", "{}", "{\"initialFunds\":null}", "{\"initialFunds\":{}}", "{\"initialFunds\":{\"data\":null,\"file\":null}}")) {
            val outputs = GenesisLedgerDecoder.shelley("""{"initialFunds":{"01":1},"extraConfig":$extra}""")
            assertThat(outputs.single().coin).isEqualTo(BigInteger.ONE)
        }
        assertThat(GenesisLedgerDecoder.shelley("""{"initialFunds":{},"extraConfig":{"initialFunds":{"data":{}}}}""")).isEmpty()
        assertThat(GenesisLedgerDecoder.shelley("""{"initialFunds":{}}""")).isEmpty()
    }

    @Test
    fun `embedded injection conflicts with nonempty legacy even when embedded data is empty`() {
        for (data in listOf("{}", "{\"02\":2}")) {
            assertThrows<IllegalArgumentException> {
                GenesisLedgerDecoder.shelley("""{"initialFunds":{"01":1},"extraConfig":{"initialFunds":{"data":$data}}}""")
            }
        }
    }

    @Test
    fun `malformed and external sources are rejected`() {
        val invalid = listOf(
            """{"initialFunds":null}""",
            """{"initialFunds":{},"extraConfig":[]}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":[]}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"data":[]}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"file":["funds.json"],"hash":"0000000000000000000000000000000000000000000000000000000000000000"}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"file":123}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"file":["funds.json"],"data":{}}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"data":{"01":-1}}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"data":{"01":18446744073709551616}}}}""",
            """{"initialFunds":{},"extraConfig":{"initialFunds":{"data":{"01":1.5}}}}""",
            """{"initialFunds":{},"initialFunds":{}}""",
            """{"initialFunds":{}} {}""",
        )
        for (json in invalid) assertThrows<Exception> { GenesisLedgerDecoder.shelley(json) }
    }
}
