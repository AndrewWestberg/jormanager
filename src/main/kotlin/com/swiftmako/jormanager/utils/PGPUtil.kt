package com.swiftmako.jormanager.utils

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.util.io.Streams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.NoSuchProviderException
import java.security.SecureRandom
import java.security.Security
import java.util.Date

object PGPUtil {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun decrypt(encrypted: String, passphrase: String): String {
        return String(decryptInternal(encrypted.toByteArray(Charsets.UTF_8), passphrase.toCharArray()))
    }

    fun encrypt(cleartext: String, passphrase: String): String {
        return String(encryptInternal(cleartext.toByteArray(Charsets.UTF_8), passphrase.toCharArray()))
    }

    /**
     * decrypt the passed in message stream
     *
     * @param encrypted  The message to be decrypted.
     * @param passPhrase Pass phrase (key)
     *
     * @return Clear text as a byte array.  I18N considerations are
     * not handled by this routine
     * @exception IOException
     * @exception PGPException
     * @exception NoSuchProviderException
     */
    @Throws(IOException::class, PGPException::class, NoSuchProviderException::class)
    private fun decryptInternal(
            encrypted: ByteArray,
            passPhrase: CharArray): ByteArray {
        var inputStream: InputStream = ByteArrayInputStream(encrypted)
        inputStream = PGPUtil.getDecoderStream(inputStream)
        val pgpF = JcaPGPObjectFactory(inputStream)
        val enc: PGPEncryptedDataList
        val o = pgpF.nextObject()

        //
        // the first object might be a PGP marker packet.
        //
        enc = if (o is PGPEncryptedDataList) {
            o
        } else {
            pgpF.nextObject() as PGPEncryptedDataList
        }
        val pbe = enc[0] as PGPPBEEncryptedData
        val clear = pbe.getDataStream(JcePBEDataDecryptorFactoryBuilder(JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build()).setProvider("BC").build(passPhrase))
        var pgpFact = JcaPGPObjectFactory(clear)
        val cData = pgpFact.nextObject() as PGPCompressedData
        pgpFact = JcaPGPObjectFactory(cData.dataStream)
        val ld = pgpFact.nextObject() as PGPLiteralData
        return Streams.readAll(ld.inputStream)
    }

    /**
     * Simple PGP encryptor between byte[].
     *
     * @param clearData  The test to be encrypted
     * @param passPhrase The pass phrase (key).  This method assumes that the
     * key is a simple pass phrase, and does not yet support
     * RSA or more sophisiticated keying.
     * @param fileName   File name. This is used in the Literal Data Packet (tag 11)
     * which is really inly important if the data is to be
     * related to a file to be recovered later.  Because this
     * routine does not know the source of the information, the
     * caller can set something here for file name use that
     * will be carried.  If this routine is being used to
     * encrypt SOAP MIME bodies, for example, use the file name from the
     * MIME type, if applicable. Or anything else appropriate.
     *
     * @param armor
     *
     * @return encrypted data.
     * @exception IOException
     * @exception PGPException
     * @exception NoSuchProviderException
     */
    @Throws(IOException::class, PGPException::class, NoSuchProviderException::class)
    private fun encryptInternal(
            clearData: ByteArray,
            passPhrase: CharArray,
            fileName: String = PGPLiteralData.CONSOLE,
            algorithm: Int = PGPEncryptedDataGenerator.AES_256,
            armor: Boolean = true): ByteArray {
        val compressedData = compress(clearData, fileName, CompressionAlgorithmTags.ZIP)
        val bOut = ByteArrayOutputStream()
        var out: OutputStream = bOut
        if (armor) {
            out = ArmoredOutputStream(out)
        }

        val encGen = PGPEncryptedDataGenerator(JcePGPDataEncryptorBuilder(algorithm).setWithIntegrityPacket(true).setSecureRandom(SecureRandom()).setProvider("BC"))
        encGen.addMethod(JcePBEKeyEncryptionMethodGenerator(passPhrase).setProvider("BC"))
        val encOut = encGen.open(out, compressedData.size.toLong())
        encOut.write(compressedData)
        encOut.close()
        if (armor) {
            out.close()
        }
        return bOut.toByteArray()
    }

    @Throws(IOException::class)
    private fun compress(clearData: ByteArray, fileName: String, algorithm: Int): ByteArray {
        val bOut = ByteArrayOutputStream()
        val comData = PGPCompressedDataGenerator(algorithm)
        val cos = comData.open(bOut) // open it with the final destination
        val lData = PGPLiteralDataGenerator()

        // we want to generate compressed data. This might be a user option later,
        // in which case we would pass in bOut.
        val pOut = lData.open(cos,  // the compressed output stream
                PGPLiteralData.BINARY,
                fileName,  // "filename" to store
                clearData.size.toLong(),  // length of clear data
                Date() // current time
        )
        pOut.write(clearData)
        pOut.close()
        comData.close()
        return bOut.toByteArray()
    }
}