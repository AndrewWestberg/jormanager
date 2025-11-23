package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Host
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface HostRepository : JpaRepository<Host, Long>
