package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.metadata.MetadataPostInfo
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface MetadataService {
    @Headers(
        "Accept: application/json",
        "x-api-key: R4Cw3SNhYt2HUEDaVfv8T9oPgdq32lag9gZUPPu4"
    )
    @GET("/default/generatePutS3Object")
    suspend fun getMetadataPostInfo(): MetadataPostInfo

    @Multipart
    @POST("/cardanostakehouse.com")
    suspend fun saveMetadataFile(
        @Part contentType: MultipartBody.Part,
        @Part awsAccessKeyId: MultipartBody.Part,
        @Part key: MultipartBody.Part,
        @Part policy: MultipartBody.Part,
        @Part signature: MultipartBody.Part,
        @Part securityToken: MultipartBody.Part,
        @Part file: MultipartBody.Part
    ): Response<Unit>
}
