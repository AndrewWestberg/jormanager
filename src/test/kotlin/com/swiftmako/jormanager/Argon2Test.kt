package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

class Argon2Test {

    @Test
    fun testArgon2() {
        val hash =
            "\$argon2id\$v=19\$m=16384,t=2,p=1\$WtnTiKs2SGivQkFTu8hnrw\$DZcoO6tD8JmpOsljVv0yD4loYbsEDda0boirSfbYoYM"
        val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
        assertThat(encoder.matches("password", hash)).isTrue()
    }
}