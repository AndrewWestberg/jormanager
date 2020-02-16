package com.swiftmako.jormanager.utils

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.InvalidPropertiesFormatException
import java.util.concurrent.TimeUnit

@Component
class JormanagerProperties : ReloadableProperties() {
    private val logger = LoggerFactory.getLogger(JormanagerProperties::class.java)

    private val durationRegex = Regex("^\\s*(?:(\\d+)d)?\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?\\s*(?:(\\d+)ms)?\\s*\$")

    fun getStringProperty(key: String) = environment.getProperty(key, "")

    fun getLongProperty(key: String) = environment.getProperty(key, "0").toLong()

    fun getIntProperty(key: String) = environment.getProperty(key, "0").toInt()

    fun getSecondsProperty(key: String) = environment.getProperty(key, "0").toSecondsLong()

    fun getMillisProperty(key: String) = environment.getProperty(key, "0").toMillisLong()

    fun getDoubleProperty(key: String) = environment.getProperty(key, "0.0").toDouble()

    fun getBooleanProperty(key: String) = environment.getProperty(key, "false").toBoolean()

    fun getBooleanListProperty(key: String): List<Boolean> {
        return environment.getProperty(key)?.let { value ->
            value.split(",").map { booleanString -> booleanString.trim().toBoolean() }
        } ?: emptyList()
    }

    override fun propertiesReloaded() {

    }

    private fun String.toSecondsLong(): Long {
        durationRegex.matchEntire(this)?.let { matchResult ->
            val days = matchResult.groupValues[1].toLongOrNull() ?: 0L
            val hours = matchResult.groupValues[2].toLongOrNull() ?: 0L
            val minutes = matchResult.groupValues[3].toLongOrNull() ?: 0L
            val seconds = matchResult.groupValues[4].toLongOrNull() ?: 0L
            val millis = matchResult.groupValues[5].toLongOrNull() ?: 0L

            return (TimeUnit.DAYS.toSeconds(days) +
                    TimeUnit.HOURS.toSeconds(hours) +
                    TimeUnit.MINUTES.toSeconds(minutes) +
                    seconds +
                    TimeUnit.MILLISECONDS.toSeconds(millis)
                    )
        } ?: throw InvalidPropertiesFormatException("Expected value like: ##d ##h ##m ##s ##ms, but was: $this")
    }

    private fun String.toMillisLong(): Long {
        durationRegex.matchEntire(this)?.let { matchResult ->
            val days = matchResult.groupValues[1].toLongOrNull() ?: 0L
            val hours = matchResult.groupValues[2].toLongOrNull() ?: 0L
            val minutes = matchResult.groupValues[3].toLongOrNull() ?: 0L
            val seconds = matchResult.groupValues[4].toLongOrNull() ?: 0L
            val millis = matchResult.groupValues[5].toLongOrNull() ?: 0L

            return (TimeUnit.DAYS.toMillis(days) +
                    TimeUnit.HOURS.toMillis(hours) +
                    TimeUnit.MINUTES.toMillis(minutes) +
                    TimeUnit.SECONDS.toMillis(seconds) +
                    millis
                    )
        } ?: throw InvalidPropertiesFormatException("Expected value like: ##d ##h ##m ##s ##ms, but was: $this")
    }
}