package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAddress
import org.springframework.data.jpa.repository.JpaRepository

interface LedgerRepository : JpaRepository<LedgerAddress, Long>