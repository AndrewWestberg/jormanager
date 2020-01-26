package com.swiftmako.jormanager

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import java.util.Scanner


@SpringBootApplication
class JormanagerApplication

fun main(args: Array<String>) {
    when {
        args.size == 1 && args[0] == "passwd" -> {
            val encoder = Argon2PasswordEncoder()
            val rawPassword = String(System.console().readPassword("Enter Admin Password: "))
            println("\nEncoded Password: ${encoder.encode(rawPassword)}")
        }
        args.size == 2 && args[0] == "passwdtest"-> {
            val encoder = Argon2PasswordEncoder()
            val rawPassword = String(System.console().readPassword("Enter Admin Password: "))
            println("\nPassword Matches: ${encoder.matches(rawPassword, args[1])}")
        }
        else -> {
            runApplication<JormanagerApplication>(*args)
        }
    }
}

