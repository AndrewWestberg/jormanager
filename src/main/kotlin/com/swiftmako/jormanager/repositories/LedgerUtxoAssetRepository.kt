package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerUtxoAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LedgerUtxoAssetRepository : JpaRepository<LedgerUtxoAsset, Long>