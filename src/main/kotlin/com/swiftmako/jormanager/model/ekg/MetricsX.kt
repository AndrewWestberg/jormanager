package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetricsX(
    @Json(name = "mempoolBytes")
    val mempoolBytes: MempoolBytes = MempoolBytes(),
    @Json(name = "txsInMempool")
    val txsInMempool: TxsInMempool = TxsInMempool(),
    @Json(name = "txsProcessedNum")
    val txsProcessedNum: TxsProcessedNum = TxsProcessedNum(),
    @Json(name = "upTime")
    val upTime: UpTime = UpTime()
)