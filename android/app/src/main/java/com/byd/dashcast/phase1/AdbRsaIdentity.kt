package com.byd.dashcast.phase1

import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Locale

/**
 * Standard ADB RSA-2048 signature and 524-byte Android public-key encoding.
 * @author Codex
 * @date 2026/10/10
 */
class AdbRsaIdentity(privateKey: PrivateKey?, publicKey: RSAPublicKey?) : AdbAuthenticator {
    private val privateKey: PrivateKey
    private val rsaPublicKey: RSAPublicKey

    init {
        if (privateKey == null || publicKey == null || publicKey.modulus.bitLength() != 2048 ||
            BigInteger.valueOf(65537) != publicKey.publicExponent) {
            throw IllegalArgumentException("An application-owned RSA-2048/F4 identity is required")
        }
        this.privateKey = privateKey
        this.rsaPublicKey = publicKey
    }

    @Throws(IOException::class)
    override fun sign(token: ByteArray?): ByteArray {
        if (token == null || token.size != 20) {
            throw IOException("Invalid ADB authentication challenge length")
        }
        try {
            // ADB sends a digest: SHA1withRSA would hash it twice and never authenticate.
            val signature = Signature.getInstance("NONEwithRSA")
            signature.initSign(privateKey)
            signature.update(SHA1_DIGEST_INFO)
            signature.update(token)
            val result = signature.sign()
            if (result.size != MODULUS_BYTES) {
                throw IOException("Unexpected ADB RSA signature length")
            }
            return result
        } catch (error: GeneralSecurityException) {
            throw IOException("Application ADB key could not sign the challenge", error)
        }
    }

    override fun publicKey(): ByteArray {
        val key = Base64.getEncoder().encodeToString(encodedPublicKey()) + " dashcast@headunit\u0000"
        return key.toByteArray(Charsets.US_ASCII)
    }

    /** @return fingerprint matching the standard Android ADB trust dialog, not a security signature */
    @Throws(IOException::class)
    fun fingerprint(): String {
        try {
            // Android's trust dialog uses MD5 for this display identifier, not for authentication.
            val digest = MessageDigest.getInstance("MD5").digest(encodedPublicKey())
            val text = StringBuilder(47)
            for (value in digest) {
                if (text.isNotEmpty()) {
                    text.append(':')
                }
                text.append(String.format(Locale.US, "%02X", value.toInt() and 0xff))
            }
            return text.toString()
        } catch (error: GeneralSecurityException) {
            throw IOException("ADB public-key fingerprint unavailable", error)
        }
    }

    fun encodedPublicKey(): ByteArray {
        val modulus = rsaPublicKey.modulus
        val wordBase = BigInteger.ONE.shiftLeft(32)
        val inverse = wordBase.subtract(modulus.mod(wordBase).modInverse(wordBase))
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val output = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(64)
        output.putInt(inverse.toInt())
        output.put(littleEndian(modulus))
        output.put(littleEndian(rr))
        output.putInt(rsaPublicKey.publicExponent.toInt())
        return output.array()
    }

    private companion object {
        const val MODULUS_BYTES = 256
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        fun littleEndian(number: BigInteger): ByteArray {
            val bigEndian = number.toByteArray()
            val result = ByteArray(MODULUS_BYTES)
            for (index in 0 until minOf(bigEndian.size, result.size)) {
                result[index] = bigEndian[bigEndian.size - 1 - index]
            }
            return result
        }
    }
}
