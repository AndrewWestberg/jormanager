package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Node
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface NodeRepository : JpaRepository<Node, Long> {

    @Query("SELECT COUNT(n) FROM Node n WHERE n.hostId = :hostId")
    fun countForHost(@Param("hostId") hostId: Long): Int

    @Query("SELECT n FROM Node n WHERE n.isDefault = true")
    fun findDefault(): Node?

    @Query( "SELECT n.poolId FROM Node n WHERE n.isDeleted=false AND n.poolId IS NOT NULL")
    fun findPoolIds(): List<String>
}