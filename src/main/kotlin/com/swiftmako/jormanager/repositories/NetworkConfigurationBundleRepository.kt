package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.NetworkConfigurationBundle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NetworkConfigurationBundleRepository : JpaRepository<NetworkConfigurationBundle, String> {
    fun findByValidationState(validationState: String): List<NetworkConfigurationBundle>
}
