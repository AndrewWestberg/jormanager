package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.SmashExistsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface SmashService {

    @GET("/api/v1/exists/{poolId}")
    suspend fun exists(@Path("poolId") poolId: String): SmashExistsResponse?
}