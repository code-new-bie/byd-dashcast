package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Application-owned ADB identity; never exposes a private key.
 * @author Codex
 * @date 2026/10/10
 */
interface AdbAuthenticator {
    /**
     * @param token 20-byte challenge (already a SHA-1 digest)
     * @return RSA signature
     * @throws IOException on signing failure
     */
    @Throws(IOException::class)
    fun sign(token: ByteArray?): ByteArray

    /** @return Android-format Base64 public key, comment and NUL; @throws IOException on failure */
    @Throws(IOException::class)
    fun publicKey(): ByteArray
}
