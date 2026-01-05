package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CalculateFeeRequest(
    @param:Json(name = "fromId")
    val fromId: Long,
    @param:Json(name = "fromIds")
    val fromIds: List<Long> = emptyList(),
    @param:Json(name = "toAccounts")
    val toAccounts: List<ToAccountFeeRequest>,
    @param:Json(name = "txOut")
    val txOut: Int,
    @param:Json(name = "isClaim")
    val isClaim: Boolean,
    @param:Json(name = "metadata")
    val metadata: String?,
    @param:Json(name = "uuid")
    val uuid: String,
) {
    // Get all from IDs, preferring fromIds list if not empty, otherwise falling back to single fromId
    fun getEffectiveFromIds(): List<Long> = fromIds.ifEmpty { listOf(fromId) }
}

