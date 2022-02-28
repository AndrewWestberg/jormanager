package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LedgerAssetRepository : JpaRepository<LedgerAsset, Long> {

    @Query("UPDATE ledger_assets set image=:image, description=:description WHERE id=:id", nativeQuery = true)
    @Modifying
    fun updateImageAndDescription(
        @Param("id") id: Long,
        @Param("image") image: String,
        @Param("description") description: String?
    )

    @Query(
        "INSERT INTO ledger_assets (id,policy,name,image,description) VALUES (:id,:policy,:name,:image,:description)",
        nativeQuery = true
    )
    @Modifying
    fun insertLedgerAsset(
        @Param("id") id: Long,
        @Param("policy") policy: String,
        @Param("name") name: String,
        @Param("image") image: String,
        @Param("description") description: String?
    )
}