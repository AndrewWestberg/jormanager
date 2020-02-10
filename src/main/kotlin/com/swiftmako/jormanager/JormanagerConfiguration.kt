package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.ui.JwtAuthenticationEntryPoint
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit


@Configuration
class JormanagerConfiguration {

    @Bean
    @Scope("singleton")
    fun getConnectionPool() = ConnectionPool()

    @Bean
    @Scope("singleton")
    fun getOkHttpClient(connectionPool: ConnectionPool) = OkHttpClient.Builder()
            .connectionPool(connectionPool)
    /*
    .addInterceptor(HttpLoggingInterceptor {
        println(it)
    }.apply { level = HttpLoggingInterceptor.Level.BODY })
     */

    @Bean
    @Scope("singleton")
    fun getMoshi(): Moshi {
        // Hack the sealed class's annotation so it thinks it's "generated"

        return Moshi.Builder().build()
    }

    @Bean
    @Scope("singleton")
    fun getRetrofitBuilder(okHttpClientBuilder: OkHttpClient.Builder, moshi: Moshi) =
            Retrofit.Builder()
                    .addConverterFactory(MoshiConverterFactory.create(moshi))

    @Bean("pooltool")
    @Scope("singleton")
    fun getPooltoolRetrofit(builder: Retrofit.Builder, okHttpClientBuilder: OkHttpClient.Builder) =
            builder.baseUrl("https://api.pooltool.io/v0/")
                    .client(
                            okHttpClientBuilder
                                    .readTimeout(5, TimeUnit.SECONDS)
                                    .writeTimeout(5, TimeUnit.SECONDS)
                                    .connectTimeout(5, TimeUnit.SECONDS)
                                    .build()
                    )
                    .build()
                    .create(PooltoolService::class.java)

    @Bean("pooltoolstats")
    @Scope("singleton")
    fun getPooltoolStatsRetrofit(builder: Retrofit.Builder, okHttpClientBuilder: OkHttpClient.Builder) =
            builder.baseUrl("https://pooltool.s3-us-west-2.amazonaws.com/")
                    .client(
                            okHttpClientBuilder
                                    .readTimeout(5, TimeUnit.SECONDS)
                                    .writeTimeout(5, TimeUnit.SECONDS)
                                    .connectTimeout(5, TimeUnit.SECONDS)
                                    .build()
                    )
                    .build()
                    .create(PooltoolService::class.java)

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return Argon2PasswordEncoder()
    }

    @Bean
    fun jwtAuthenticationEntryPointBean(): JwtAuthenticationEntryPoint {
        return JwtAuthenticationEntryPoint()
    }
}