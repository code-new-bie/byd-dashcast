package com.byd.dashcast.phase1;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Independent RSA public-operation checks, not a signature implementation verifying itself.
 * @author Codex
 * @date 2026/10/10
 */
public final class AdbRsaIdentityTest {
    private static KeyPair pair;

    @BeforeClass
    public static void createTestKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    @Test
    public void signsOriginalDigestWithoutHashingItAgain() throws Exception {
        byte[] token = new byte[20];
        for (int index = 0; index < token.length; index++) {
            token[index] = (byte)(index + 1);
        }
        Cipher verifier = Cipher.getInstance("RSA/ECB/NoPadding");
        verifier.init(Cipher.DECRYPT_MODE, pair.getPublic());
        byte[] decoded = verifier.doFinal(identity().sign(token));
        byte[] expected = new byte[256];
        expected[1] = 1;
        Arrays.fill(expected, 2, 220, (byte)0xff);
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        System.arraycopy(digestInfo, 0, expected, 221, digestInfo.length);
        System.arraycopy(token, 0, expected, 236, token.length);
        assertArrayEquals(expected, decoded);
    }

    @Test
    public void exportsAndroidKeyWithConsistentMontgomeryParameters() throws Exception {
        byte[] encoded = identity().encodedPublicKey();
        assertEquals(524, encoded.length);
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, buffer.getInt());
        long inverse = Integer.toUnsignedLong(buffer.getInt());
        BigInteger modulus = readInteger(buffer);
        BigInteger rr = readInteger(buffer);
        assertEquals(((RSAPublicKey)pair.getPublic()).getModulus(), modulus);
        assertEquals(BigInteger.valueOf(0xffffffffL), modulus.multiply(BigInteger.valueOf(inverse))
                .mod(BigInteger.ONE.shiftLeft(32)));
        assertEquals(BigInteger.valueOf(2).modPow(BigInteger.valueOf(4096), modulus), rr);
        assertEquals(65537, buffer.getInt());
        String wire = new String(identity().publicKey(), StandardCharsets.US_ASCII);
        assertTrue(wire.endsWith(" dashcast@headunit\0"));
        assertArrayEquals(encoded, Base64.getDecoder().decode(wire.substring(0, wire.indexOf(' '))));
        assertTrue(identity().fingerprint().matches("[0-9A-F]{2}(:[0-9A-F]{2}){15}"));
    }

    @Test
    public void rejectsMalformedAuthenticationTokens() {
        assertThrows(IOException.class, () -> identity().sign(new byte[19]));
        assertThrows(IOException.class, () -> identity().sign(new byte[21]));
        assertThrows(IOException.class, () -> identity().sign(null));
    }

    @Test
    public void rejectsUnsupportedKeySize() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair small = generator.generateKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> new AdbRsaIdentity(small.getPrivate(), (RSAPublicKey)small.getPublic()));
    }

    private static AdbRsaIdentity identity() {
        return new AdbRsaIdentity(pair.getPrivate(), (RSAPublicKey)pair.getPublic());
    }

    private static BigInteger readInteger(ByteBuffer buffer) {
        byte[] little = new byte[256];
        buffer.get(little);
        byte[] big = new byte[256];
        for (int index = 0; index < little.length; index++) {
            big[index] = little[little.length - 1 - index];
        }
        return new BigInteger(1, big);
    }
}
