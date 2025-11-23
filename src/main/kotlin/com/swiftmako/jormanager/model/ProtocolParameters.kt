package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
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
    val decentralisationParam: Double?,
    @param:Json(name = "maxTxSize")
    val maxTxSize: Long,
    @param:Json(name = "minPoolCost")
    val minPoolCost: Long,
    @param:Json(name = "txFeePerByte")
    val txFeePerByte: Long,
    @param:Json(name = "maxBlockBodySize")
    val maxBlockBodySize: Long,
    @param:Json(name = "txFeeFixed")
    val txFeeFixed: Long,
    @param:Json(name = "poolRetireMaxEpoch")
    val poolRetireMaxEpoch: Long,
    @param:Json(name = "maxBlockHeaderSize")
    val maxBlockHeaderSize: Long,
    @param:Json(name = "stakeAddressDeposit")
    val stakeAddressDeposit: BigInteger,
    @param:Json(name = "stakePoolTargetNum")
    val stakePoolTargetNum: Long,
    @param:Json(name = "monetaryExpansion")
    val monetaryExpansion: Double,
    @param:Json(name = "treasuryCut")
    val treasuryCut: Double,
    @param:Json(name = "poolPledgeInfluence")
    val poolPledgeInfluence: Double
)
