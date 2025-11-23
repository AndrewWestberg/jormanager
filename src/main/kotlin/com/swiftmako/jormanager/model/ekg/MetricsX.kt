package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetricsX(
    @param:Json(name = "mempoolBytes")
    val mempoolBytes: MempoolBytes = MempoolBytes(),
    @param:Json(name = "txsInMempool")
    val txsInMempool: TxsInMempool = TxsInMempool(),
    @param:Json(name = "txsProcessedNum")
    val txsProcessedNum: TxsProcessedNum = TxsProcessedNum(),
    @param:Json(name = "upTime")
    val upTime: UpTime = UpTime()
)
