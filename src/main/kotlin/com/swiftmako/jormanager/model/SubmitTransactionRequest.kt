package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class SubmitTransactionRequest(
    @param:Json(name = "spendingPassword")
    val spendingPassword: String,
    @param:Json(name = "fromId")
    val fromId: Long,
    @param:Json(name = "fromIds")
    val fromIds: List<Long> = emptyList(),
    @param:Json(name = "txFee")
    val txFee: BigInteger,
    @param:Json(name = "tokenKeepFee")
    val tokenKeepFee: BigInteger,
    @param:Json(name = "toAccounts")
    val toAccounts: List<ToAccount>,
    @param:Json(name = "isClaim")
    val isClaim: Boolean,
    @param:Json(name = "metadata")
    val metadata: String?,
) {
    // Get all from IDs, preferring fromIds list if not empty, otherwise falling back to single fromId
    fun getEffectiveFromIds(): List<Long> = fromIds.ifEmpty { listOf(fromId) }

    override fun toString(): String = "SubmitTransactionRequest(spendingPassword='************', fromId=$fromId, fromIds=$fromIds, txFee=$txFee, toAccounts=$toAccounts, isClaim=$isClaim, metadata=$metadata)"
}

