package com.swiftmako.jormanager.services

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class MetadataResolutionService {
    private val restTemplate = RestTemplate()

    @Suppress("UNCHECKED_CAST")
    fun resolveMetadata(url: String): Map<String, Any>? =
        try {
            val response = restTemplate.getForObject(url, Map::class.java) as Map<String, Any>?
            sanitizeMetadata(response)
        } catch (e: Exception) {
            null
        }

    private fun sanitizeMetadata(metadata: Map<String, Any>?): Map<String, Any>? {
        // Simple sanitization to prevent XSS or injection
        return metadata?.mapValues { entry ->
            val value = entry.value
            if (value is String) {
                value.replace(Regex("<[^>]*>"), "")
            } else {
                value
            }
        }
    }
}
