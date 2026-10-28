package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ProtocolParameters(
    @param:Json(name = "stakePoolDeposit")
    val stakePoolDeposit: BigInteger,
    @param:Json(name = "protocolVersion")
    val protocolVersion: ProtocolVersion,
    @param:Json(name = "minUTxOValue")
    val minUTxOValue: Long? = null,
    @param:Json(name = "utxoCostPerWord")
    val utxoCostPerWord: Long? = null,
    @param:Json(name = "utxoCostPerByte")
    val utxoCostPerByte: Long? = null,
    @param:Json(name = "decentralization")
    val decentralisationParam: BigDecimal? = null,
    @param:Json(name = "maxTxSize")
    val maxTxSize: Long,
    @param:Json(name = "minPoolCost")
    val minPoolCost: BigInteger,
    @param:Json(name = "txFeePerByte")
    val txFeePerByte: BigInteger,
    @param:Json(name = "maxBlockBodySize")
    val maxBlockBodySize: Long,
    @param:Json(name = "txFeeFixed")
    val txFeeFixed: BigInteger,
    @param:Json(name = "poolRetireMaxEpoch")
    val poolRetireMaxEpoch: Long,
    @param:Json(name = "maxBlockHeaderSize")
    val maxBlockHeaderSize: Long,
    @param:Json(name = "stakeAddressDeposit")
    val stakeAddressDeposit: BigInteger,
    @param:Json(name = "stakePoolTargetNum")
    val stakePoolTargetNum: Long,
    @param:Json(name = "monetaryExpansion")
    val monetaryExpansion: BigDecimal,
    @param:Json(name = "treasuryCut")
    val treasuryCut: BigDecimal,
    @param:Json(name = "poolPledgeInfluence")
    val poolPledgeInfluence: BigDecimal,
    @param:Json(name = "minFeeRefScriptCostPerByte")
    val minFeeRefScriptCostPerByte: BigDecimal? = null,
) {
    companion object {
        fun conwayDefaults(): ProtocolParameters =
            ProtocolParameters(
                stakePoolDeposit = BigInteger.valueOf(500_000_000L),
                protocolVersion = ProtocolVersion(major = 10, minor = 0),
                minUTxOValue = null,
                utxoCostPerWord = null,
                utxoCostPerByte = 4310L,
                decentralisationParam = null,
                maxTxSize = 16384L,
                minPoolCost = BigInteger.valueOf(170_000_000L),
                txFeePerByte = BigInteger.valueOf(44L),
                maxBlockBodySize = 90112L,
                txFeeFixed = BigInteger.valueOf(155_381L),
                poolRetireMaxEpoch = 18L,
                maxBlockHeaderSize = 1100L,
                stakeAddressDeposit = BigInteger.valueOf(2_000_000L),
                stakePoolTargetNum = 500L,
                monetaryExpansion = BigDecimal("0.003"),
                treasuryCut = BigDecimal("0.2"),
                poolPledgeInfluence = BigDecimal("0.3"),
                minFeeRefScriptCostPerByte = BigDecimal.valueOf(15L),
            )
    }
}
