package com.swiftmako.jormanager.services

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class GovernanceScannerService(
    private val koiosService: KoiosService
) {
    @Scheduled(fixedDelay = 60000)
    fun scanGovernanceActions() {
        // Implementation for scanning new on-chain governance actions via Koios API.
        val actions = koiosService.getGovernanceActions()
        // Here we would map and store them in the database.
    }
}
