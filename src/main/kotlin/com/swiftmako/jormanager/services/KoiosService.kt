package com.swiftmako.jormanager.services

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class KoiosService {
    private val restTemplate = RestTemplate()
    private val baseUrl = "https://api.koios.rest/api/v1"

    @Suppress("UNCHECKED_CAST")
    fun getGovernanceActions(): List<Map<String, Any>> {
        val url = "$baseUrl/proposal_list"
        return try {
            val response = restTemplate.getForObject(url, List::class.java) as? List<Map<String, Any>>
            response ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
