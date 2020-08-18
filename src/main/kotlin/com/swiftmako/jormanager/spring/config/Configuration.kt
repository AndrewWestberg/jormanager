package com.swiftmako.jormanager.spring.config

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
import com.swiftmako.jormanager.services.PooltoolService
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory


@Configuration
class Configuration {


    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getMoshi(): Moshi {
        return Moshi.Builder().add(JodaDateTimeAdapter()).build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
                .addNetworkInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                    private val log = LoggerFactory.getLogger("NET")
                    override fun log(message: String) {
                        log.info(message)
                    }
                }).setLevel(
                        HttpLoggingInterceptor.Level.NONE
//                         HttpLoggingInterceptor.Level.BODY
                ))
                .build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
                .baseUrl("http://127.0.0.1")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getPooltoolService(retrofit: Retrofit): PooltoolService {
        return retrofit.newBuilder().baseUrl("https://api.pooltool.io").build().create(PooltoolService::class.java)
    }

    @Bean("nodesChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNodesBroadcastChannel(): BroadcastChannel<Node> {
        // Channel which broadcasts nodeIds to start monitoring for blocks
        return BroadcastChannel(Channel.BUFFERED)
    }

    @Bean("newBlockChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNewBlockChannel(): BroadcastChannel<Long> {
        return BroadcastChannel(Channel.CONFLATED)
    }
}