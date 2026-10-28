package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.PoolKeyObservation
import org.springframework.data.jpa.repository.JpaRepository

interface LeiosKeyRepository : JpaRepository<LeiosVotingKey, Long> {
    fun findByNetworkKeyAndPoolId(networkKey: String, poolId: String): List<LeiosVotingKey>
}

interface KeyInstallationRepository : JpaRepository<KeyInstallation, Long> {
    fun findByNodeIdAndHostIdOrderByObservedAtDesc(nodeId: Long, hostId: Long): List<KeyInstallation>
}

interface PoolKeyObservationRepository : JpaRepository<PoolKeyObservation, Long> {
    fun findFirstByNetworkKeyAndPoolIdOrderByObservedAtDesc(networkKey: String, poolId: String): PoolKeyObservation?
}
