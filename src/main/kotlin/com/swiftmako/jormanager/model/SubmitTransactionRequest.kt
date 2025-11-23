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
    override fun toString(): String = "SubmitTransactionRequest(spendingPassword='************', fromId=$fromId, txFee=$txFee, toAccounts=$toAccounts, isClaim=$isClaim, metadata=$metadata)"
}
