package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.ekg.EkgMetrics
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface EkgService {

    @Headers("Accept: application/json")
    @GET("/")
    suspend fun getNodeMetrics(@Query("_") time: Long = System.currentTimeMillis()): EkgMetrics
}