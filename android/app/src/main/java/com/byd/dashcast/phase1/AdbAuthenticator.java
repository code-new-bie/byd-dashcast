package com.byd.dashcast.phase1;

import java.io.IOException;

/**
 * Application-owned ADB identity; never exposes a private key.
 * @author Codex
 * @date 2026/10/10
 */
public interface AdbAuthenticator {
    /**
     * @param token 20-byte challenge (already a SHA-1 digest)
     * @return RSA signature
     * @throws IOException on signing failure
     */
    byte[] sign(byte[] token) throws IOException;

    /** @return Android-format Base64 public key, comment and NUL; @throws IOException on failure */
    byte[] publicKey() throws IOException;
}
