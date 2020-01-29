package com.swiftmako.jormanager.utils

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JormanagerProperties : ReloadableProperties() {
    private val logger = LoggerFactory.getLogger(JormanagerProperties::class.java)

    fun getStringProperty(key: String) = environment.getProperty(key, "")

    fun getLongProperty(key: String) = environment.getProperty(key, "0").toLong()

    fun getIntProperty(key: String) = environment.getProperty(key, "0").toInt()

    fun getBooleanProperty(key: String) = environment.getProperty(key, "false").toBoolean()

    fun getBooleanListProperty(key: String): List<Boolean> {
        return environment.getProperty(key)?.let { value ->
            value.split(", ").map { booleanString -> booleanString.toBoolean() }
        } ?: emptyList()
    }

    override fun propertiesReloaded() {

    }
}