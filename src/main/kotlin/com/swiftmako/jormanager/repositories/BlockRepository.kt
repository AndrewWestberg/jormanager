package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Block
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BlockRepository : JpaRepository<Block, Int>