package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.pooltool.PooltoolResponse
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface PooltoolService {
    @POST("/v1/sendstats")
    suspend fun sendStats(
        @Body pooltoolStats: PooltoolStats
    ): Response<PooltoolResponse>
}
