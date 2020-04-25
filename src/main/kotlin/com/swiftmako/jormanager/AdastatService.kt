package com.swiftmako.jormanager

import com.swiftmako.jormanager.api.AdastatBlocks
import com.swiftmako.jormanager.api.AdastatPoolBlocksResult
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AdastatService {

    @POST("poolblocks.json")
    suspend fun sendPoolBlocks(@Header("Authorization") signature: String, @Body adastatBlocks: AdastatBlocks): AdastatPoolBlocksResult
}