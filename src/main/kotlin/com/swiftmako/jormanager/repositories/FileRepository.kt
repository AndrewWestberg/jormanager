package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.File
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface FileRepository : JpaRepository<File, Long> {
    @Query("SELECT f FROM File f WHERE f.name = :name")
    fun findByName(
        @Param("name") name: String
    ): File?

    fun findByNameLike(
        @Param("name") name: String
    ): List<File>
}
