package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LedgerAssetRepository : JpaRepository<Long, LedgerAsset>