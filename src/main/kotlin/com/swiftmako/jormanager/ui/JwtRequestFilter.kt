package com.swiftmako.jormanager.ui

import io.jsonwebtoken.ExpiredJwtException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class JwtRequestFilter(private val jwtTokenUtil: JwtToken) : OncePerRequestFilter() {

    @Autowired
    lateinit var jwtUserDetailsService: JwtUserDetailsService

    // Ignored paths. These will skip authentication
    private val ignoredRegex = Regex("/authenticate|/webjars/.*|/status/.*|/.*\\.png|/.*\\.ico|/.*\\.xml|/.*\\.json")

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!ignoredRegex.containsMatchIn(request.requestURI)) {
            val requestTokenHeader = request.getHeader("Authorization")
            val accessTokenParam = request.getParameter("access_token")
                    ?: request.getHeader("sec-websocket-protocol")?.let { header ->
                        if (header.contains("access_token", ignoreCase = true)) {
                            header.split(",")[1].trim()
                        } else {
                            null
                        }
                    }

            var username: String? = null

            val jwtToken: String? = requestTokenHeader?.let {
                if (requestTokenHeader.startsWith("Bearer ")) {
                    requestTokenHeader.substring(7)
                } else {
                    logger.warn("JWT Token does not begin with Bearer String: ${request.requestURL}")
                    null
                }
            } ?: accessTokenParam?.let {
                it
            } ?: run {
                logger.warn("Required Authorization Bearer header or access_token query parameter not found!: ${request.requestURL}")
                null
            }

            try {
                username = jwtTokenUtil.getUsernameFromToken(jwtToken)
            } catch (e: IllegalArgumentException) {
                logger.error("Unable to get JWT Token!", e)
            } catch (e: ExpiredJwtException) {
                logger.error("JWT Token has expired")
            }


            if (username != null && SecurityContextHolder.getContext().authentication == null) {
                val userDetails = jwtUserDetailsService.loadUserByUsername(username)

                if (jwtTokenUtil.validateToken(jwtToken, userDetails)) {

                    val usernamePasswordAuthenticationToken = UsernamePasswordAuthenticationToken(userDetails, null, userDetails.authorities).apply {
                        details = WebAuthenticationDetailsSource().buildDetails(request)
                    }

                    SecurityContextHolder.getContext().authentication = usernamePasswordAuthenticationToken
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}