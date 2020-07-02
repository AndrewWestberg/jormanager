package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.WalletEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WalletRepository : JpaRepository<WalletEntry, Long>