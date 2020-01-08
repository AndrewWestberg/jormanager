package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory


@Configuration
class JormanagerConfiguration {

    @Bean
    @Scope("singleton")
    fun getProcessStartQueue(@Value("\${jormanager.nodecount}") nodecount: Int): Channel<Int> {
        val processStartQueue = Channel<Int>(nodecount)
        runBlocking {
            for (processNumber in 0 until nodecount) {
                processStartQueue.send(processNumber)
            }
        }
        return processStartQueue
    }

    @Bean
    @Scope("singleton")
    fun getOkHttpClient() = OkHttpClient.Builder()
            /*
            .addInterceptor(HttpLoggingInterceptor {
                println(it)
            }.apply { level = HttpLoggingInterceptor.Level.BODY })
             */
            .build()

    @Bean
    @Scope("singleton")
    fun getMoshi(): Moshi {
        // Hack the sealed class's annotation so it thinks it's "generated"

        return Moshi.Builder().build()
    }

    @Bean
    @Scope("singleton")
    fun getRetrofitBuilder(okHttpClient: OkHttpClient, moshi: Moshi) =
            Retrofit.Builder()
                    .client(okHttpClient)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))

    @Bean("pooltool")
    @Scope("singleton")
    fun getPooltoolRetrofit(builder: Retrofit.Builder) =
            builder.baseUrl("https://api.pooltool.io/v0/")
                    .build()
                    .create(PooltoolService::class.java)

}