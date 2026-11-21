package dev.xiaonuoboard.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the Gemini API key) at rest with a non-exportable AES-256-GCM key held in
 * the Android Keystore. Values are stored as `enc:v1:<iv>:<ciphertext>` (Base64). If the Keystore
 * is unavailable (some custom ROMs), it degrades to plaintext so the app keeps working.
 */
object SecretCipher {
    private const val TAG = "SecretCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xiaonuo_secret_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore unavailable, storing secret unencrypted: ${t.message}")
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored // legacy / fallback plaintext
        return try {
            val parts = stored.removePrefix(PREFIX).split(':')
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not decrypt secret: ${t.message}")
            ""
        }
    }
}
