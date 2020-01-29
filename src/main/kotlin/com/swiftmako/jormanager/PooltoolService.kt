package com.swiftmako.jormanager

import com.swiftmako.jormanager.api.PooltoolResult
import retrofit2.http.GET
import retrofit2.http.Query

interface PooltoolService {

    @GET("sharemytip")
    suspend fun shareMyTip(
            @Query("poolid") poolId: String,
            @Query("userid") userId: String,
            @Query("genesispref") genesisPref: String,
            @Query("mytip") lastBlockHeight: String,
            @Query("lasthash") lastBlockHash: String,
            @Query("lastpool") lastPoolId: String,
            @Query("lastparent") lastParent: String,
            @Query("lastslot") lastSlot: String,
            @Query("lastepoch") lastEpoch: String,
            @Query("jormver") jormVersion: String?
    ): PooltoolResult
}