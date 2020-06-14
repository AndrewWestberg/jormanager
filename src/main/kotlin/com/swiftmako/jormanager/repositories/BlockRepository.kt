package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Block
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BlockRepository : JpaRepository<Block, Long> {

    @Query("SELECT b FROM Block b WHERE b.slot = :slot")
    fun findBySlot(@Param("slot") slot: Long): Block?
}