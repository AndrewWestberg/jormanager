package com.swiftmako.jormanager.spring.config

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope


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
        return OkHttpClient.Builder().build()
    }

    @Bean("nodesChannel")
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun getNodesBroadcastChannel(): BroadcastChannel<Node> {
        // Channel which broadcasts nodeIds to start monitoring for blocks
        return BroadcastChannel(Channel.Factory.BUFFERED)
    }
}