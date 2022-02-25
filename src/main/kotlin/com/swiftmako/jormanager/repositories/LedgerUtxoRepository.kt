package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerUtxo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LedgerUtxoRepository : JpaRepository<LedgerUtxo, Long>