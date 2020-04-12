package com.swiftmako.jormanager.ui

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter


@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
class WebSecurityConfig @Autowired constructor(
        private val jwtAuthenticationEntryPoint: JwtAuthenticationEntryPoint,
        private val jwtUserDetailsService: JwtUserDetailsService,
        private val jwtRequestFilter: JwtRequestFilter
) : WebSecurityConfigurerAdapter() {

    @Autowired
    @Throws(Exception::class)
    fun configureGlobal(auth: AuthenticationManagerBuilder, passwordEncoder: PasswordEncoder) {
        auth.userDetailsService<UserDetailsService>(jwtUserDetailsService).passwordEncoder(passwordEncoder)
    }


    override fun configure(httpSecurity: HttpSecurity) {
        httpSecurity.csrf()
                .disable()
                .authorizeRequests().antMatchers("/authenticate", "/webjars/**", "/status/**", "/blocks/**", "/api/blocks/**", "/assets/**", "/favicon/**", "*.png", "*.ico", "*.xml", "*.json").permitAll()
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                .and()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)

        httpSecurity.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter::class.java)
    }

    @Bean
    override fun authenticationManagerBean(): AuthenticationManager {
        return super.authenticationManagerBean()
    }
}