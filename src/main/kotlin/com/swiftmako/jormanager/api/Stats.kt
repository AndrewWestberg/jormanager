package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Stats(
        @Json(name = "blockRecvCnt") val blockRecvCnt: Long? = null,
        @Json(name = "lastBlockContentSize") val lastBlockContentSize: Long? = null,
        @Json(name = "lastBlockDate") val lastBlockDate: String? = null,
        @Json(name = "lastBlockFees") val lastBlockFees: Long? = null,
        @Json(name = "lastBlockHash") val lastBlockHash: String? = null,
        @Json(name = "lastBlockHeight") val lastBlockHeight: String? = null,
        @Json(name = "lastBlockSum") val lastBlockSum: Long? = null,
        @Json(name = "lastBlockTime") val lastBlockTime: String? = null,
        @Json(name = "lastBlockTx") val lastBlockTx: Long? = null,
        @Json(name = "lastReceivedBlockTime") val lastReceivedBlockTime: String? = null,
        @Json(name = "nodeId") val nodeId: String? = null,
        @Json(name = "peerAvailableCnt") val peerAvailableCnt: String? = null,
        @Json(name = "peerConnectedCnt") val peerConnectedCnt: String? = null,
        @Json(name = "peerQuarantinedCnt") val peerQuarantinedCnt: String? = null,
        @Json(name = "peerUnreachableCnt") val peerUnreachableCnt: String? = null,
        @Json(name = "state") val state: String,
        @Json(name = "txRecvCnt") val txRecvCnt: Long? = null,
        @Json(name = "uptime") val uptime: Long? = null,
        @Json(name = "version") val version: String,
        val numberOfPeers: Int? = -1,
        val leader: Boolean = false,
        val passive: Boolean = false,
        val standby: Boolean = false,
        val leadershipProbationEndTimestamp: Long = 0L
)