package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Node
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NodeRepository : JpaRepository<Node, Long>