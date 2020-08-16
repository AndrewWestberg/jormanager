package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProtocolParameters(
    @Json(name = "poolDeposit")
    val poolDeposit: Long,
    @Json(name = "protocolVersion")
    val protocolVersion: ProtocolVersion,
    @Json(name = "minUTxOValue")
    val minUTxOValue: Long,
    @Json(name = "decentralisationParam")
    val decentralisationParam: Double,
    @Json(name = "maxTxSize")
    val maxTxSize: Long,
    @Json(name = "minPoolCost")
    val minPoolCost: Long,
    @Json(name = "minFeeA")
    val minFeeA: Long,
    @Json(name = "maxBlockBodySize")
    val maxBlockBodySize: Long,
    @Json(name = "minFeeB")
    val minFeeB: Long,
    @Json(name = "eMax")
    val eMax: Long,
    @Json(name = "extraEntropy")
    val extraEntropy: ExtraEntropy,
    @Json(name = "maxBlockHeaderSize")
    val maxBlockHeaderSize: Long,
    @Json(name = "keyDeposit")
    val keyDeposit: Long,
    @Json(name = "nOpt")
    val nOpt: Long,
    @Json(name = "rho")
    val rho: Double,
    @Json(name = "tau")
    val tau: Double,
    @Json(name = "a0")
    val a0: Double
)