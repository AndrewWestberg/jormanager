package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.services.MetadataService
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

class MetadataUploadTest {

    @Test
    fun testUploadMetadata() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor(
                HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
        ).build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder().client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .baseUrl("https://1v27dl8wn5.execute-api.us-west-2.amazonaws.com").build()
        val service = retrofit.create(MetadataService::class.java)

        val metadataPostInfo = service.getMetadataPostInfo()
        println(metadataPostInfo)

        val uploadService = retrofit.newBuilder().baseUrl("https://s3.us-west-2.amazonaws.com").build().create(MetadataService::class.java)

        val file = File("/home/westbam/haskell/bcsh.metadata.json")


        val response = uploadService.saveMetadataFile(
                contentType = MultipartBody.Part.createFormData("content-type", null, "application/json".toRequestBody("text/plain".toMediaTypeOrNull())),
                awsAccessKeyId = MultipartBody.Part.createFormData("AWSAccessKeyId", null, metadataPostInfo.fields.awsAccessKeyId.toRequestBody("text/plain".toMediaTypeOrNull())),
                key = MultipartBody.Part.createFormData("key", null, metadataPostInfo.fields.key.toRequestBody("text/plain".toMediaTypeOrNull())),
                policy = MultipartBody.Part.createFormData("policy", null, metadataPostInfo.fields.policy.toRequestBody("text/plain".toMediaTypeOrNull())),
                signature = MultipartBody.Part.createFormData("signature", null, metadataPostInfo.fields.signature.toRequestBody("text/plain".toMediaTypeOrNull())),
                securityToken = MultipartBody.Part.createFormData("x-amz-security-token", null, metadataPostInfo.fields.securityToken.toRequestBody("text/plain".toMediaTypeOrNull())),
                file = MultipartBody.Part.createFormData("file", metadataPostInfo.fields.key, file.asRequestBody("application/json".toMediaTypeOrNull()))
        )

        assertThat(response.isSuccessful).isTrue()
        assertThat(response.code()).isEqualTo(204)

        Unit
    }
}