package com.swiftmako.jormanager.spring.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer


@Configuration
class WebConfiguration : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/hosts")
                .setViewName("forward:/index.html")
        registry.addViewController("/nodes")
                .setViewName("forward:/index.html")
        registry.addViewController("/blocks")
                .setViewName("forward:/index.html")
        registry.addViewController("/wallet")
                .setViewName("forward:/index.html")
    }
}