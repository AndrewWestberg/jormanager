package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.NodeConfigurationSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NodeConfigurationSnapshotRepository : JpaRepository<NodeConfigurationSnapshot, String> {
    fun findByNodeId(nodeId: Long): List<NodeConfigurationSnapshot>
}
