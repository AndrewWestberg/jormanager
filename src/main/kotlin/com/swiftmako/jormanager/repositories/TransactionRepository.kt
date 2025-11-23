package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Transaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository : JpaRepository<Transaction, Long>
