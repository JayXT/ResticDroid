package io.github.resticdroid.engine

import androidx.test.core.app.ApplicationProvider
import io.github.resticdroid.config.Destination
import io.github.resticdroid.restic.ResticBackend
import io.github.resticdroid.secret.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class RepositoryPasswordTest {

    // The JVM has no AndroidKeyStore, so the store takes its key from here.
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun secrets() = SecretStore(ApplicationProvider.getApplicationContext()) { key }

    private val destination = Destination(
        id = "b2", name = "B2", backend = ResticBackend.B2, location = "bucket:path",
    )

    @Test
    fun `a typed password wins`() {
        val store = secrets()
        store.put(SecretStore.passwordAlias(destination.id), "stored")
        assertEquals("typed", Repositories.password(destination, "typed", store))
    }

    @Test
    fun `an empty field falls back to the stored password`() {
        val store = secrets()
        store.put(SecretStore.passwordAlias(destination.id), "stored")
        assertEquals("stored", Repositories.password(destination, "", store))
        assertEquals("stored", Repositories.password(destination, "   ", store))
    }

    @Test
    fun `an empty field with nothing stored asks for one`() {
        val error = runCatching { Repositories.password(destination, "", secrets()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Enter the repository password"))
    }
}
