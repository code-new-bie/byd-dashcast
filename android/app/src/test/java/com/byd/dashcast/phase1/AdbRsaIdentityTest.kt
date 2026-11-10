package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/**
 * Independent RSA public-operation checks, not a signature implementation verifying itself.
 * @author Codex
 * @date 2026/10/10
 */
class AdbRsaIdentityTest {
    @Test
    fun signsOriginalDigestWithoutHashingItAgain() {
        val token = ByteArray(20)
        for (index in token.indices) {
            token[index] = (index + 1).toByte()
        }
        val verifier = Cipher.getInstance("RSA/ECB/NoPadding")
        verifier.init(Cipher.DECRYPT_MODE, pair.public)
        val decoded = verifier.doFinal(identity().sign(token))
        val expected = ByteArray(256)
        expected[1] = 1
        expected.fill(0xff.toByte(), 2, 220)
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        System.arraycopy(digestInfo, 0, expected, 221, digestInfo.size)
        System.arraycopy(token, 0, expected, 236, token.size)
        assertArrayEquals(expected, decoded)
    }

    @Test
    fun exportsAndroidKeyWithConsistentMontgomeryParameters() {
        val encoded = identity().encodedPublicKey()
        assertEquals(524, encoded.size)
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.getInt())
        val inverse = Integer.toUnsignedLong(buffer.getInt())
        val modulus = readInteger(buffer)
        val rr = readInteger(buffer)
        assertEquals((pair.public as RSAPublicKey).modulus, modulus)
        assertEquals(BigInteger.valueOf(0xffffffffL), modulus.multiply(BigInteger.valueOf(inverse))
            .mod(BigInteger.ONE.shiftLeft(32)))
        assertEquals(BigInteger.valueOf(2).modPow(BigInteger.valueOf(4096), modulus), rr)
        assertEquals(65537, buffer.getInt())
        val wire = String(identity().publicKey(), Charsets.US_ASCII)
        assertTrue(wire.endsWith(" dashcast@headunit\u0000"))
        assertArrayEquals(encoded, Base64.getDecoder().decode(wire.substring(0, wire.indexOf(' '))))
        assertTrue(identity().fingerprint().matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){15}")))
    }

    @Test
    fun rejectsMalformedAuthenticationTokens() {
        assertThrows(IOException::class.java) { identity().sign(ByteArray(19)) }
        assertThrows(IOException::class.java) { identity().sign(ByteArray(21)) }
        assertThrows(IOException::class.java) { identity().sign(null) }
    }

    @Test
    fun rejectsUnsupportedKeySize() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val small = generator.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            AdbRsaIdentity(small.private, small.public as RSAPublicKey)
        }
    }

    companion object {
        private lateinit var pair: KeyPair

        @BeforeClass
        @JvmStatic
        fun createTestKey() {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            pair = generator.generateKeyPair()
        }

        private fun identity(): AdbRsaIdentity = AdbRsaIdentity(pair.private, pair.public as RSAPublicKey)

        private fun readInteger(buffer: ByteBuffer): BigInteger {
            val little = ByteArray(256)
            buffer.get(little)
            val big = ByteArray(256)
            for (index in little.indices) {
                big[index] = little[little.size - 1 - index]
            }
            return BigInteger(1, big)
        }
    }
}
