package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.WalletEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface WalletRepository : JpaRepository<WalletEntry, Long> {

    @Query("SELECT w FROM WalletEntry w WHERE w.deleted = false")
    fun findAllNotDeleted(): List<WalletEntry>
}