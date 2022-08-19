package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ProtocolParameters(
    @Json(name = "stakePoolDeposit")
    val stakePoolDeposit: BigInteger,
    @Json(name = "protocolVersion")
    val protocolVersion: ProtocolVersion,
    @Json(name = "minUTxOValue")
    val minUTxOValue: Long? = null,
    @Json(name = "utxoCostPerWord")
    val utxoCostPerWord: Long? = null,
    @Json(name = "utxoCostPerByte")
    val utxoCostPerByte: Long? = null,
    @Json(name = "decentralization")
    val decentralisationParam: Double?,
    @Json(name = "maxTxSize")
    val maxTxSize: Long,
    @Json(name = "minPoolCost")
    val minPoolCost: Long,
    @Json(name = "txFeePerByte")
    val txFeePerByte: Long,
    @Json(name = "maxBlockBodySize")
    val maxBlockBodySize: Long,
    @Json(name = "txFeeFixed")
    val txFeeFixed: Long,
    @Json(name = "poolRetireMaxEpoch")
    val poolRetireMaxEpoch: Long,
    @Json(name = "maxBlockHeaderSize")
    val maxBlockHeaderSize: Long,
    @Json(name = "stakeAddressDeposit")
    val stakeAddressDeposit: BigInteger,
    @Json(name = "stakePoolTargetNum")
    val stakePoolTargetNum: Long,
    @Json(name = "monetaryExpansion")
    val monetaryExpansion: Double,
    @Json(name = "treasuryCut")
    val treasuryCut: Double,
    @Json(name = "poolPledgeInfluence")
    val poolPledgeInfluence: Double
)