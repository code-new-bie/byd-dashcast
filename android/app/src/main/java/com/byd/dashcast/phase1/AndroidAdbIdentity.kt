package com.byd.dashcast.phase1

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/**
 * Keeps this APK's non-exportable ADB private key in Android Keystore across updates.
 * @author Codex
 * @date 2026/10/10
 */
object AndroidAdbIdentity {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "dashcast.adb.rsa.v1"
    private const val UNAVAILABLE = "Android Keystore ADB identity unavailable; authentication not disabled"

    /** @return existing or newly generated app identity; @throws IOException if Keystore is unavailable */
    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun load(): AdbRsaIdentity {
        try {
            val store = KeyStore.getInstance(PROVIDER)
            store.load(null)
            if (!store.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
                generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false).build())
                generator.generateKeyPair()
            }
            val certificate = store.getCertificate(ALIAS)
                ?: throw IOException("Application ADB public key missing; no identity replaced")
            return AdbRsaIdentity(store.getKey(ALIAS, null) as PrivateKey?, certificate.publicKey as RSAPublicKey?)
        } catch (error: GeneralSecurityException) {
            throw IOException(UNAVAILABLE, error)
        } catch (error: IllegalArgumentException) {
            throw IOException(UNAVAILABLE, error)
        } catch (error: ClassCastException) {
            throw IOException(UNAVAILABLE, error)
        }
    }
}
