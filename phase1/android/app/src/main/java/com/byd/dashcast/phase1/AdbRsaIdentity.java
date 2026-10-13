package com.byd.dashcast.phase1;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Locale;

/**
 * Standard ADB RSA-2048 signature and 524-byte Android public-key encoding.
 * @author Codex
 * @date 2026/10/10
 */
public final class AdbRsaIdentity implements AdbAuthenticator {
    private static final int MODULUS_BYTES = 256;
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private final PrivateKey privateKey;
    private final RSAPublicKey rsaPublicKey;

    public AdbRsaIdentity(PrivateKey privateKey, RSAPublicKey publicKey) {
        if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() != 2048
                || !BigInteger.valueOf(65537).equals(publicKey.getPublicExponent())) {
            throw new IllegalArgumentException("An application-owned RSA-2048/F4 identity is required");
        }
        this.privateKey = privateKey;
        this.rsaPublicKey = publicKey;
    }

    @Override
    public byte[] sign(byte[] token) throws IOException {
        if (token == null || token.length != 20) {
            throw new IOException("Invalid ADB authentication challenge length");
        }
        try {
            // ADB sends a digest: SHA1withRSA would hash it twice and never authenticate.
            Signature signature = Signature.getInstance("NONEwithRSA");
            signature.initSign(privateKey);
            signature.update(SHA1_DIGEST_INFO);
            signature.update(token);
            byte[] result = signature.sign();
            if (result.length != MODULUS_BYTES) {
                throw new IOException("Unexpected ADB RSA signature length");
            }
            return result;
        } catch (GeneralSecurityException error) {
            throw new IOException("Application ADB key could not sign the challenge", error);
        }
    }

    @Override
    public byte[] publicKey() {
        String key = Base64.getEncoder().encodeToString(encodedPublicKey()) + " dashcast@headunit\0";
        return key.getBytes(StandardCharsets.US_ASCII);
    }

    /** @return fingerprint matching the standard Android ADB trust dialog, not a security signature */
    public String fingerprint() throws IOException {
        try {
            // Android's trust dialog uses MD5 for this display identifier, not for authentication.
            byte[] digest = MessageDigest.getInstance("MD5").digest(encodedPublicKey());
            StringBuilder text = new StringBuilder(47);
            for (byte value : digest) {
                if (text.length() > 0) {
                    text.append(':');
                }
                text.append(String.format(Locale.US, "%02X", value & 0xff));
            }
            return text.toString();
        } catch (GeneralSecurityException error) {
            throw new IOException("ADB public-key fingerprint unavailable", error);
        }
    }

    byte[] encodedPublicKey() {
        BigInteger modulus = rsaPublicKey.getModulus();
        BigInteger wordBase = BigInteger.ONE.shiftLeft(32);
        BigInteger inverse = wordBase.subtract(modulus.mod(wordBase).modInverse(wordBase));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(modulus);
        ByteBuffer output = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(64);
        output.putInt(inverse.intValue());
        output.put(littleEndian(modulus));
        output.put(littleEndian(rr));
        output.putInt(rsaPublicKey.getPublicExponent().intValue());
        return output.array();
    }

    private static byte[] littleEndian(BigInteger number) {
        byte[] bigEndian = number.toByteArray();
        byte[] result = new byte[MODULUS_BYTES];
        for (int index = 0; index < Math.min(bigEndian.length, result.length); index++) {
            result[index] = bigEndian[bigEndian.length - 1 - index];
        }
        return result;
    }
}
