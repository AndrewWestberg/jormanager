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
    fun testSmashMainnetSuccess() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
                    ).build()

            val moshi = Moshi.Builder().build()
            val retrofit =
                Retrofit
                    .Builder()
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .baseUrl("https://smash.cardano-mainnet.iohk.io")
                    .build()
            val service = retrofit.create(SmashService::class.java)

            val response = service.exists("00beef68425d90f50c8e2102476a0f42ec850836e9affd601ecf7804")

            assertThat(response).isNotNull()
            assertThat(response?.code).isEmpty()
            assertThat(response?.poolId).isEqualTo("00beef68425d90f50c8e2102476a0f42ec850836e9affd601ecf7804")
            assertThat(response?.poolExists()).isTrue()

            Unit
        }

    @Test
    fun testSmashMainnetFailure() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
                    ).build()

            val moshi = Moshi.Builder().build()
            val retrofit =
                Retrofit
                    .Builder()
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .baseUrl("https://smash.cardano-mainnet.iohk.io")
                    .build()
            val service = retrofit.create(SmashService::class.java)

            val response = service.exists("806283dc3ea85e17613961737637013b7eada1afbabba9a794ed325c")

            assertThat(response).isNotNull()
            assertThat(response?.code).isEqualTo("RecordDoesNotExist")
            assertThat(response?.poolId).isEmpty()
            assertThat(response?.poolExists()).isFalse()

            Unit
        }

    @Test
    fun testSmashPreprodSuccess() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
                    ).build()

            val moshi = Moshi.Builder().build()
            val retrofit =
                Retrofit
                    .Builder()
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .baseUrl("https://preprod-smash.world.dev.cardano.org")
                    .build()
            val service = retrofit.create(SmashService::class.java)

            val response = service.exists("fa1a5e1e70bc2fa7f60080b214f7bf5ab14c51da4b307a1a792c1714") // ppp

            assertThat(response).isNotNull()
            assertThat(response?.code).isEmpty()
            assertThat(response?.poolId).isEqualTo("fa1a5e1e70bc2fa7f60080b214f7bf5ab14c51da4b307a1a792c1714")
            assertThat(response?.poolExists()).isTrue()

            Unit
        }

    @Test
    fun testSmashPreprodFailure() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        HttpLoggingInterceptor(HttpLoggingInterceptor.Logger.DEFAULT).setLevel(HttpLoggingInterceptor.Level.BODY)
                    ).build()

            val moshi = Moshi.Builder().build()
            val retrofit =
                Retrofit
                    .Builder()
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .baseUrl("https://preprod-smash.world.dev.cardano.org")
                    .build()
            val service = retrofit.create(SmashService::class.java)

            val response = service.exists("806283dc3ea85e17613961737637013b7eada1afbabba9a794ed325c")

            assertThat(response).isNotNull()
            assertThat(response?.code).isEqualTo("RecordDoesNotExist")
            assertThat(response?.poolId).isEmpty()
            assertThat(response?.poolExists()).isFalse()

            Unit
        }
}
