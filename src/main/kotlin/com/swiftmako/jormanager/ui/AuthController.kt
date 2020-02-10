package com.swiftmako.jormanager.ui

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController


@RestController
@CrossOrigin
class AuthController @Autowired constructor(
        private val authenticationManager: AuthenticationManager,
        private val jwtToken: JwtToken,
        private val jwtUserDetailsService: JwtUserDetailsService
) {

    @RequestMapping(value = ["/authenticate"], method = [RequestMethod.POST])
    @Throws(Exception::class)
    fun createAuthenticationToken(@RequestBody authenticationRequest: JwtRequest): ResponseEntity<JwtResponse> {
        authenticate(authenticationRequest.username, authenticationRequest.password)
        val userDetails: UserDetails = jwtUserDetailsService.loadUserByUsername(authenticationRequest.username)
        val token = jwtToken.generateToken(userDetails)
        return ResponseEntity.ok(JwtResponse(token))
    }

    @Throws(Exception::class)
    private fun authenticate(username: String, password: String) {
        try {
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken(username, password))
        } catch (e: DisabledException) {
            throw Exception("USER_DISABLED", e)
        } catch (e: BadCredentialsException) {
            throw Exception("INVALID_CREDENTIALS", e)
        }
    }
}