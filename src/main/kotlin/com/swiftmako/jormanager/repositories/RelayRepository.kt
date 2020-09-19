package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Relay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface RelayRepository : JpaRepository<Relay, Long> {

    @Query("SELECT r FROM Relay r WHERE r.nodeId = :nodeId")
    fun findByNodeId(@Param("nodeId") id: Long): List<Relay>
}