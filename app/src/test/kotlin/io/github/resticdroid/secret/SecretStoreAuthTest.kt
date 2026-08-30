package io.github.resticdroid.secret

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecretStoreAuthTest {
    private lateinit var store: SecretStore

    private fun testKey(): javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val sharedKey by lazy { testKey() }

    @Before
    fun setUp() {
        store = SecretStore(ApplicationProvider.getApplicationContext()) { sharedKey }
        listOf("photos", "documents").forEach { store.removeAllFor(it) }
        store.setAuthLatch(false)
    }

    @Test
    fun `the right password for the right repository is accepted`() {
        store.put(SecretStore.passwordAlias("photos"), "correct horse battery staple")
        assertTrue(store.matchesPassword("photos", "correct horse battery staple"))
    }

    @Test
    fun `a wrong password is refused`() {
        store.put(SecretStore.passwordAlias("photos"), "correct horse battery staple")
        assertFalse(store.matchesPassword("photos", "correct horse battery stapl"))
        assertFalse(store.matchesPassword("photos", ""))
        assertFalse(store.matchesPassword("photos", "CORRECT HORSE BATTERY STAPLE"))
    }

    @Test
    fun `another repository's password does not open this one`() {
        store.put(SecretStore.passwordAlias("photos"), "photos-pw")
        store.put(SecretStore.passwordAlias("documents"), "documents-pw")
        assertFalse(store.matchesPassword("photos", "documents-pw"))
        assertTrue(store.matchesPassword("documents", "documents-pw"))
    }

    @Test
    fun `a destination with no stored password accepts nothing`() {
        assertFalse(store.matchesPassword("photos", ""))
        assertFalse(store.matchesPassword("photos", "anything"))
    }

    @Test
    fun `matchesAnyPassword accepts any configured repository`() {
        store.put(SecretStore.passwordAlias("photos"), "photos-pw")
        store.put(SecretStore.passwordAlias("documents"), "documents-pw")
        assertTrue(store.matchesAnyPassword("photos-pw"))
        assertTrue(store.matchesAnyPassword("documents-pw"))
        assertFalse(store.matchesAnyPassword("neither"))
    }

    @Test
    fun `hasAnyPassword reports whether there is anything to protect`() {
        assertFalse(store.hasAnyPassword())
        store.put(SecretStore.passwordAlias("photos"), "pw")
        assertTrue(store.hasAnyPassword())
    }

    @Test
    fun `the auth latch survives so a config-file edit cannot weaken the gate`() {
        // Someone with storage access can write "require-auth = no" into a plain
        // file. The effective setting is file-OR-latch, and clearing the latch
        // requires passing the gate, so that edit disables nothing.
        store.setAuthLatch(true)
        assertTrue(store.isAuthLatched())

        val reopened = SecretStore(ApplicationProvider.getApplicationContext()) { sharedKey }
        assertTrue("the latch must outlive the object that set it", reopened.isAuthLatched())

        store.setAuthLatch(false)
        assertFalse(store.isAuthLatched())
    }

    @Test
    fun `deleting a destination clears its password`() {
        store.put(SecretStore.passwordAlias("photos"), "pw")
        store.removeAllFor("photos")
        assertFalse(store.hasAnyPassword())
        assertFalse(store.matchesPassword("photos", "pw"))
    }
}

/** The encrypt/decrypt path itself, which the keystore seam finally makes testable. */
@RunWith(RobolectricTestRunner::class)
class SecretStoreCryptoTest {
    private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = SecretStore(ApplicationProvider.getApplicationContext()) { key }

    @Test
    fun `a secret round-trips`() {
        val s = store()
        s.put("a", "hunter2")
        assertTrue(s.get("a") == "hunter2")
    }

    @Test
    fun `the same plaintext encrypts differently each time`() {
        // A fresh random IV per call. Identical ciphertexts would tell an
        // observer of the preferences file that two destinations share a
        // password.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = store()
        s.put("a", "same-password")
        val first = context.getSharedPreferences("secrets", 0).getString("a", null)
        s.put("b", "same-password")
        val second = context.getSharedPreferences("secrets", 0).getString("b", null)
        assertTrue("IV must not be reused", first != second)
        assertTrue(s.get("a") == s.get("b"))
    }

    @Test
    fun `tampered ciphertext is rejected rather than silently mis-decrypted`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = store()
        s.put("a", "hunter2")

        val prefs = context.getSharedPreferences("secrets", 0)
        val blob = android.util.Base64.decode(prefs.getString("a", null), android.util.Base64.NO_WRAP)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        prefs.edit().putString("a", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).commit()

        // The GCM tag catches it, and the result is reported as unreadable -
        // not as "nothing stored", which would send the user looking in the
        // wrong place entirely.
        assertTrue(s.read("a") is SecretStore.Secret.Unreadable)
        assertTrue(s.get("a") == null)
    }

    @Test
    fun `an absent alias is Missing, not Unreadable`() {
        assertTrue(store().read("never-set") is SecretStore.Secret.Missing)
    }

    @Test
    fun `storing an empty value removes the entry`() {
        val s = store()
        s.put("a", "x")
        s.put("a", "")
        assertFalse(s.has("a"))
    }
}
