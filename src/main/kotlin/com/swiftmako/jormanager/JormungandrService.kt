package com.swiftmako.jormanager

import com.swiftmako.jormanager.api.JormSettings
import com.swiftmako.jormanager.api.LeaderBlock
import com.swiftmako.jormanager.api.LeaderInfo
import com.swiftmako.jormanager.api.NetworkStat
import com.swiftmako.jormanager.api.Stats
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface JormungandrService {

    @GET("v0/settings")
    suspend fun settings(): JormSettings

    @GET("v0/node/stats")
    suspend fun nodeStats(): Stats

    @GET("v0/network/stats")
    suspend fun networkStats(): List<NetworkStat>

    @GET("v0/leaders/logs")
    suspend fun getLeaderLog(): List<LeaderBlock>

    @POST("v0/leaders")
    suspend fun promoteToLeader(@Body leaderInfo: LeaderInfo): Int

    @DELETE("v0/leaders/{leader_id}")
    suspend fun removeLeadership(@Path("leader_id") leaderId: Int)

    @GET("v0/block/{block}")
    suspend fun getBlock(@Path("block") blockHash: String): ResponseBody

    @GET("v0/block/{block}/next_id")
    suspend fun getNextBlock(@Path("block") blockHash: String): ResponseBody

    @GET("v0/shutdown")
    suspend fun shutdown(): Unit

}