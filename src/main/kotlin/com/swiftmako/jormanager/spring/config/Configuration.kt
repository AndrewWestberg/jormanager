package com.swiftmako.jormanager.spring.config

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.moshi.adapters.JodaDateTimeAdapter
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
}