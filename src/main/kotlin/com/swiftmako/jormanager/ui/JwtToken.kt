package com.swiftmako.jormanager.ui

import com.swiftmako.jormanager.utils.JormanagerProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.util.Date


@Component
class JwtToken @Autowired constructor(
        properties: JormanagerProperties
) {

    private val validityDurationMs = properties.getMillisProperty("jormanager.jwt.expiration")
    private val secret = properties.getStringProperty("jormanager.jwt.secret")

    fun getUsernameFromToken(token: String?): String {
        return getClaimFromToken(token) { claims -> claims.subject }
    }


    fun getExpirationDateFromToken(token: String?): Date {
        return getClaimFromToken(token) { claims -> claims.expiration }
    }

    fun <T> getClaimFromToken(token: String?, claimsResolver: (claims: Claims) -> T): T {
        val claims = getAllClaimsFromToken(token)
        return claimsResolver.invoke(claims)
    }


    private fun getAllClaimsFromToken(token: String?): Claims {
        return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).body
    }


    private fun isTokenExpired(token: String?): Boolean {
        val expiration: Date = getExpirationDateFromToken(token)
        return expiration.before(Date())
    }


    fun generateToken(userDetails: UserDetails): String {
        val claims: Map<String, Any> = mutableMapOf()
        return doGenerateToken(claims, userDetails.username)
    }


    private fun doGenerateToken(claims: Map<String, Any>, subject: String): String {
        return Jwts.builder().setClaims(claims).setSubject(subject).setIssuedAt(Date(System.currentTimeMillis()))
                .setExpiration(Date(System.currentTimeMillis() + validityDurationMs))
                .signWith(SignatureAlgorithm.HS512, secret).compact()
    }


    fun validateToken(token: String?, userDetails: UserDetails): Boolean {
        val username = getUsernameFromToken(token)
        return username == userDetails.username && !isTokenExpired(token)
    }

}