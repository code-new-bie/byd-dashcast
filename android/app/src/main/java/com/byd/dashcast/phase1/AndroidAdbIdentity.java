package com.byd.dashcast.phase1;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;

/**
 * Keeps this APK's non-exportable ADB private key in Android Keystore across updates.
 * @author Codex
 * @date 2026/10/10
 */
public final class AndroidAdbIdentity {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "dashcast.adb.rsa.v1";

    private AndroidAdbIdentity() {}

    /** @return existing or newly generated app identity; @throws IOException if Keystore is unavailable */
    public static synchronized AdbRsaIdentity load() throws IOException {
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER);
                generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setUserAuthenticationRequired(false).build());
                generator.generateKeyPair();
            }
            if (store.getCertificate(ALIAS) == null) {
                throw new IOException("Application ADB public key missing; no identity replaced");
            }
            return new AdbRsaIdentity((PrivateKey)store.getKey(ALIAS, null),
                    (RSAPublicKey)store.getCertificate(ALIAS).getPublicKey());
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException error) {
            throw new IOException("Android Keystore ADB identity unavailable; authentication not disabled", error);
        }
    }
}
