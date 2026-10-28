package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.AuthorizedOperation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthorizedOperationRepository : JpaRepository<AuthorizedOperation, String> {
    fun findByPreviewId(previewId: String): AuthorizedOperation?
    fun findByNetworkKeyAndTxId(networkKey: String, txId: String): AuthorizedOperation?
}
