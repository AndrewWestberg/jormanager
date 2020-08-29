package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Relay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RelayRepository : JpaRepository<Relay, Long>