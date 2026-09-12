package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.FileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.codec.Hex
import org.springframework.security.crypto.codec.Utf8
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor

class WalletUtilsTest {
    @Test
    fun `invalid reference script size fails closed`() {
        val host = mockk<Host> { every { cardanoCliPath } returns "cardano-cli" }
        val hostConnection = mockk<HostConnection> {
            every { command(any<String>()) } returns "Reference inputs scripts size is -1 bytes."
        }
        val walletUtils =
            WalletUtils(
                hostRepository = mockk(),
                nodeRepository = mockk(),
                walletRepository = mockk(),
                fileRepository = mockk(),
                argon2PasswordEncoder = mockk(),
                spendingPasswordHash = "",
                stakingInfoAdapter = mockk(),
                queryUtxoJsonAdapter = mockk(),
                regCertAdapter = mockk(),
                ledgerDao = mockk(),
            )

        assertThrows<IOException> {
            walletUtils.queryReferenceScriptSize(
                host,
                hostConnection,
                "--mainnet",
                "--socket-path /node.socket",
                "conway",
                listOf(Utxo("hash", 0L, BigInteger.ONE, emptyList())),
            )
        }
    }

    @Test
    fun `legacy wallet key is migrated to current encryption when read`() {
        val password = "compatibility-password"
        val cleartext = """{"type":"PaymentSigningKeyShelley_ed25519","cborHex":"deadbeef"}"""
        val legacyFile =
            File(
                name = "payment.skey",
                content =
                    "000102030405060708090a0b0c0d0e0f6a3c09e6bec9ee57bdf6ad1a34954615" +
                        "c56248020ec947b6ef5bff2b34f485d645c276912001b5dd2d14117b121773e2" +
                        "49b0562d6d20e412288c55dba35a612dfbd8b0c37d11ace443e5431c3944771a",
            )
        lateinit var migratedFile: File
        val fileRepository = mockk<FileRepository> {
            every { save(any()) } answers {
                migratedFile = firstArg()
                migratedFile
            }
        }
        val passwordEncoder = mockk<Argon2PasswordEncoder> {
            every { matches(password, "hash") } returns true
        }
        val walletUtils =
            WalletUtils(
                hostRepository = mockk(),
                nodeRepository = mockk(),
                walletRepository = mockk(),
                fileRepository = fileRepository,
                argon2PasswordEncoder = passwordEncoder,
                spendingPasswordHash = "hash",
                stakingInfoAdapter = mockk(),
                queryUtxoJsonAdapter = mockk(),
                regCertAdapter = mockk(),
                ledgerDao = mockk(),
            )

        assertEquals(cleartext, walletUtils.getSKeyContent(legacyFile, password))

        val currentEncryptor = AesGcmBytesEncryptor.withPassword(password, WalletUtils.S).build()
        assertEquals(cleartext, Utf8.decode(currentEncryptor.decrypt(Hex.decode(migratedFile.content))))
        assertEquals(cleartext, walletUtils.getSKeyContent(migratedFile, password))
        verify(exactly = 1) { fileRepository.save(any()) }
    }
}
