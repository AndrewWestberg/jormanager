package com.swiftmako.jormanager.model.ledger


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EsSnapshots(
    @Json(name = "_pstakeSet")
    val pstakeSet: PstakeSet
)