package io.github.resticdroid.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(
    context: Context,
    // Injectable only so tests can exercise the crypto: the JVM has no
    // AndroidKeyStore provider. Production never passes this.
    private val keyProvider: () -> SecretKey = ::androidKeystoreKey,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(alias: String, value: String) {
        if (value.isEmpty()) {
            remove(alias)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ciphertext
        prefs.edit().putString(alias, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    sealed interface Secret {
        data class Value(val value: String) : Secret
        object Missing : Secret
        data class Unreadable(val reason: String) : Secret
    }

    fun read(alias: String): Secret {
        val stored = prefs.getString(alias, null) ?: return Secret.Missing
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            require(blob.size > IV_LENGTH) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH))
            }
            Secret.Value(String(cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH), Charsets.UTF_8))
        }.getOrElse { Secret.Unreadable(it.javaClass.simpleName + ": " + (it.message ?: "decryption failed")) }
    }

    fun get(alias: String): String? = (read(alias) as? Secret.Value)?.value

    fun has(alias: String): Boolean = prefs.contains(alias)

    fun matchesPassword(destinationId: String, candidate: String): Boolean {
        val stored = get(passwordAlias(destinationId)) ?: return false
        return java.security.MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    fun matchesAnyPassword(candidate: String): Boolean =
        storedPasswordAliases().any { alias ->
            get(alias)?.let {
                java.security.MessageDigest.isEqual(
                    it.toByteArray(Charsets.UTF_8),
                    candidate.toByteArray(Charsets.UTF_8),
                )
            } == true
        }

    fun hasAnyPassword(): Boolean = storedPasswordAliases().isNotEmpty()

    private fun storedPasswordAliases(): List<String> =
        prefs.all.keys.filter { it.startsWith("dest/") && it.endsWith("/password") }

    fun isAuthLatched(): Boolean = prefs.getBoolean(AUTH_LATCH, false)

    fun setAuthLatch(latched: Boolean) {
        prefs.edit().putBoolean(AUTH_LATCH, latched).apply()
    }

    fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun removeAllFor(destinationId: String) {
        val prefix = destinationPrefix(destinationId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun key(): SecretKey = keyProvider()

    companion object {
        @JvmStatic
        fun androidKeystoreKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "resticdroid.secrets.v1"
        private const val PREFS_NAME = "secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val AUTH_LATCH = "require-auth-latch"

        private fun destinationPrefix(destinationId: String): String = "dest/$destinationId/"

        fun passwordAlias(destinationId: String): String =
            destinationPrefix(destinationId) + "password"

        fun credentialAlias(destinationId: String, key: String): String =
            destinationPrefix(destinationId) + key
    }
}
