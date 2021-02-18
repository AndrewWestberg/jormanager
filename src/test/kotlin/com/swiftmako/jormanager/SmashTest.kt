package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.services.SmashService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SmashTest {

    @Test
    fun testSmashMainnetSuccess() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor(
                HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
        ).build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder().client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .baseUrl("https://smash.cardano-mainnet.iohk.io").build()
        val service = retrofit.create(SmashService::class.java)

        val response = service.exists("00beef68425d90f50c8e2102476a0f42ec850836e9affd601ecf7804")

        assertThat(response).isNotNull()
        assertThat(response?.code).isEmpty()
        assertThat(response?.poolId).isEqualTo("00beef68425d90f50c8e2102476a0f42ec850836e9affd601ecf7804")
        assertThat(response?.poolExists()).isTrue()

        Unit
    }

    @Test
    fun testSmashMainnetFailure() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor(
                HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
        ).build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder().client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .baseUrl("https://smash.cardano-mainnet.iohk.io").build()
        val service = retrofit.create(SmashService::class.java)

        val response = service.exists("806283dc3ea85e17613961737637013b7eada1afbabba9a794ed325c")

        assertThat(response).isNotNull()
        assertThat(response?.code).isEqualTo("RecordDoesNotExist")
        assertThat(response?.poolId).isEmpty()
        assertThat(response?.poolExists()).isFalse()

        Unit
    }

    @Test
    fun testSmashTestnetSuccess() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor(
                HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
        ).build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder().client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .baseUrl("https://smash.cardano-testnet.iohkdev.io").build()
        val service = retrofit.create(SmashService::class.java)

        val response = service.exists("3299895e62b13de5a5a52f4bb5726db5fb38928c8d2c21ff8d78517d") //prfit

        assertThat(response).isNotNull()
        assertThat(response?.code).isEmpty()
        assertThat(response?.poolId).isEqualTo("3299895e62b13de5a5a52f4bb5726db5fb38928c8d2c21ff8d78517d")
        assertThat(response?.poolExists()).isTrue()

        Unit
    }

    @Test
    fun testSmashTestnetFailure() = runBlocking {
        val client = OkHttpClient.Builder().addNetworkInterceptor(
                HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
        ).build()

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder().client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .baseUrl("https://smash.cardano-testnet.iohkdev.io").build()
        val service = retrofit.create(SmashService::class.java)

        val response = service.exists("806283dc3ea85e17613961737637013b7eada1afbabba9a794ed325c")

        assertThat(response).isNotNull()
        assertThat(response?.code).isEqualTo("RecordDoesNotExist")
        assertThat(response?.poolId).isEmpty()
        assertThat(response?.poolExists()).isFalse()

        Unit
    }
}