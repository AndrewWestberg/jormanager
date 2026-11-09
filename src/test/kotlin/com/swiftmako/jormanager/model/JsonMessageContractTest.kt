package com.swiftmako.jormanager.model

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.ReviewedWalletController
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
    fun walletAmountsRemainExactStringsAndUnavailableBalanceRemainsNull() = withJson { mapper, converter ->
        val wallet = WalletItem(1, "wallet", "payment", "address", true, 1, maximumCoin, "stake", true, maximumCoin, mapOf("asset" to maximumCoin))
        val message = requireNotNull(converter.toMessage(wallet, null))
        val json = mapper.readTree(message.payload as ByteArray)
        assertDecimalString(json["paymentAddrLovelace"], maximumCoin)
        assertDecimalString(json["stakingAddrLovelace"], maximumCoin)
        assertDecimalString(json["nativeAssetMap"]["asset"], maximumCoin)

        val unavailableWallet = wallet.copy(paymentAddrUtxoCount = null, paymentAddrLovelace = null,
            stakingAddrRegistered = null, stakingAddrLovelace = null, nativeAssetMap = null)
        val state = ChainReadState("network", "incomplete", null, null, null, false, Long.MAX_VALUE.toString())
        val unavailable = requireNotNull(converter.toMessage(WalletResult(listOf(unavailableWallet), "network", state), null))
        val result = mapper.readTree(unavailable.payload as ByteArray)
        assertThat(result["networkKey"].stringValue()).isEqualTo("network")
        assertThat(result["readState"]["networkKey"].stringValue()).isEqualTo("network")
        assertThat(result["readState"]["generation"].stringValue()).isEqualTo("9223372036854775807")
        assertThat(result["readState"]["walletReady"].booleanValue()).isFalse()
        val item = result["items"][0]
        listOf("paymentAddrUtxoCount", "paymentAddrLovelace", "stakingAddrRegistered", "stakingAddrLovelace", "nativeAssetMap")
            .forEach { assertThat(item[it].isNull).isTrue() }
        assertThat(item.propertyNames()).containsExactly("id", "name", "type", "paymentAddr", "hasPaymentKeys",
            "paymentAddrUtxoCount", "paymentAddrLovelace", "stakingAddr", "stakingAddrRegistered",
            "stakingAddrLovelace", "nativeAssetMap")
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
        val input = """{"requestId":"prepare","target":{"hostId":1,"nodeId":2},"intent":"import","confirmed":true,"spendingPassword":"synthetic-password","signingKey":"synthetic-key"}"""
        val request = converter.fromMessage(GenericMessage(input.toByteArray()), LeiosKeyRequest::class.java) as LeiosKeyRequest
        assertThat(request.spendingPassword).isEqualTo("synthetic-password")
        assertThat(request.signingKey).isEqualTo("synthetic-key")
        val json = mapper.readTree(requireNotNull(converter.toMessage(request, null)).payload as ByteArray)
        assertThat(json.has("spendingPassword")).isFalse()
        assertThat(json.has("signingKey")).isFalse()
    }

    @Test
    fun reviewedRequestsAcceptJsonTreesWithoutNarrowingMetadataIntegers() = withJson { _, converter ->
        val input = """{"requestId":"review","kind":"send","request":{"txFee":"0","metadata":{"counter":18446744073709551615}}}"""
        val request = converter.fromMessage(GenericMessage(input.toByteArray()), ReviewedWalletController.ReviewRequest::class.java) as ReviewedWalletController.ReviewRequest
        assertThat(request.request["metadata"]["counter"].isIntegralNumber).isTrue()
        assertThat(request.request["metadata"]["counter"].bigIntegerValue()).isEqualTo(maximumCoin)
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
