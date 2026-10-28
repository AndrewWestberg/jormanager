package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.NetworkReconfigurationReview
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NetworkReconfigurationReviewRepository : JpaRepository<NetworkReconfigurationReview, String>
