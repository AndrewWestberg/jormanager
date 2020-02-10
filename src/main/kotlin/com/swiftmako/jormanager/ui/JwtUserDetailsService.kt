package com.swiftmako.jormanager.ui

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Component


@Component
class JwtUserDetailsService @Autowired constructor(
        @Value("\${jormanager.admin.username}") private val adminUsername: String,
        @Value("\${jormanager.admin.password}") private val adminPassword: String
) : UserDetailsService {

    @Throws(UsernameNotFoundException::class)
    override fun loadUserByUsername(username: String): UserDetails {
        if (username != adminUsername) {
            throw UsernameNotFoundException("User not found with username: $username")
        }
        return User(adminUsername, adminPassword, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))

    }
}