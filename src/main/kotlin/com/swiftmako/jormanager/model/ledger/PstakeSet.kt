package com.swiftmako.jormanager.model.ledger


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PstakeSet(
    @Json(name = "_stake")
    val stake: List<List<Any>>,
    @Json(name = "_delegations")
    val delegations: List<List<Any>>
)