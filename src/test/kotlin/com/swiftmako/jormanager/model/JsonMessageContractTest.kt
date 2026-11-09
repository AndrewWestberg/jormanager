package com.swiftmako.jormanager.model

import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import org.junit.jupiter.api.Test
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.support.GenericMessage
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class JsonMessageContractTest {
    private val maximumCoin = BigInteger("18446744073709551615")

    @Test
    fun walletAmountsRemainExactStrings() = withJson { mapper, converter ->
        val wallet = WalletItem(1, "wallet", "payment", "address", true, 1, maximumCoin, "stake", true, maximumCoin, mapOf("asset" to maximumCoin))
        val message = requireNotNull(converter.toMessage(wallet, null))
        val json = mapper.readTree(message.payload as ByteArray)
        assertDecimalString(json["paymentAddrLovelace"], maximumCoin)
        assertDecimalString(json["stakingAddrLovelace"], maximumCoin)
        assertDecimalString(json["nativeAssetMap"]["asset"], maximumCoin)

    }

    @Test
    fun followingCoverageCarriesReadinessAndExactGeneration() = withJson { mapper, converter ->
        val state = ChainReadState("network", "syncing", null, null, null, true, Long.MAX_VALUE.toString())
        val json = mapper.readTree(requireNotNull(converter.toMessage(state, null)).payload as ByteArray)
        assertThat(json["walletReady"].booleanValue()).isTrue()
        assertThat(json["generation"].isString).isTrue()
        assertThat(json["generation"].stringValue()).isEqualTo("9223372036854775807")
        assertThat(json["observedTip"].isNull).isTrue()
        assertThat(json["lastCompletePoint"].isNull).isTrue()
    }

    @Test
    fun feeAmountsAndCollectionContentsRemainExactStrings() = withJson { mapper, converter ->
        val fees = CalculateFeeResponse(maximumCoin, listOf(maximumCoin, BigInteger.ZERO), BigInteger.ZERO, maximumCoin, "review")
        val json = mapper.readTree(requireNotNull(converter.toMessage(fees, null)).payload as ByteArray)
        assertDecimalString(json["txFee"], maximumCoin)
        assertDecimalString(json["tokenFees"][0], maximumCoin)
        assertDecimalString(json["tokenFees"][1], BigInteger.ZERO)
        assertDecimalString(json["tokenKeepFee"], BigInteger.ZERO)
        assertDecimalString(json["tokenLocked"], maximumCoin)
    }

    @Test
    fun credentialInputCanBeReadButNeverAppearsInSerializedMessages() = withJson { mapper, converter ->
        val input = """{"requestId":"prepare","target":{"hostId":1,"nodeId":2},"intent":"import","spendingPassword":"synthetic-password","signingKey":"synthetic-key"}"""
        val request = converter.fromMessage(GenericMessage(input.toByteArray()), LeiosKeyRequest::class.java) as LeiosKeyRequest
        assertThat(request.spendingPassword).isEqualTo("synthetic-password")
        assertThat(request.signingKey).isEqualTo("synthetic-key")
        val json = mapper.readTree(requireNotNull(converter.toMessage(request, null)).payload as ByteArray)
        assertThat(json.has("spendingPassword")).isFalse()
        assertThat(json.has("signingKey")).isFalse()
    }


    private fun assertDecimalString(node: JsonNode, expected: BigInteger) {
        assertThat(node.isString).isTrue()
        assertThat(node.stringValue()).isEqualTo(expected.toString())
    }

    private fun withJson(check: (JsonMapper, JacksonJsonMessageConverter) -> Unit) {
        AnnotationConfigApplicationContext(JacksonAutoConfiguration::class.java).use { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            check(mapper, JacksonJsonMessageConverter(mapper))
        }
    }
}
