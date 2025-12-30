package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

class Argon2Test {
    @Test
    fun testArgon2() {
        val hash =
            "\$argon2id\$v=19\$m=16384,t=2,p=1\$XVQHy/OmOYTFo10yrXdfcg\$+dCg8J3QlymKYqtg/KVO3a+1UDMU2wthycP7cOmnnAk"
        val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        assertThat(encoder.matches("password", hash)).isTrue()
    }

    @Test
    fun testEncodeArgon2Password() {
        val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        val password = "password"
        val encoded = encoder.encode(password)
        assertThat(encoded).isNotEmpty()
        assertThat(encoder.matches(password, encoded)).isTrue()
        println("jormanager.spendingpassword=$encoded")
    }
}
