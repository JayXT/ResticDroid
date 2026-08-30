package io.github.resticdroid.engine

import io.github.resticdroid.config.Config
import io.github.resticdroid.config.Destination
import io.github.resticdroid.config.Settings
import io.github.resticdroid.restic.ResticBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExclusionPolicyTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a repository inside a backed-up folder is recognised as recursive`() {
        assertTrue(ExclusionPolicy.isRecursive("/storage/emulated/0", "/storage/emulated/0/Backups/restic"))
        assertTrue(ExclusionPolicy.isRecursive("/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM"))
    }

    @Test
    fun `a repository elsewhere is not recursive`() {
        assertFalse(ExclusionPolicy.isRecursive("/storage/emulated/0/DCIM", "/storage/1234-5678/restic"))
        assertFalse(ExclusionPolicy.isRecursive("/storage/emulated/0/DCIM", "/storage/emulated/0/Documents"))
    }

    @Test
    fun `a sibling with a shared name prefix is not recursive`() {
        assertFalse(ExclusionPolicy.isRecursive("/a/DCIM", "/a/DCIM-old/restic"))
    }

    @Test
    fun `every local repository is excluded, not only the one being written to`() {
        val config = config(
            local("one", "/storage/emulated/0/Backups/one"),
            local("two", "/storage/emulated/0/Backups/two"),
            remote("b2", "bucket:path"),
        )

        val excludes = ExclusionPolicy.implicitExcludes(config, null)
        assertTrue(excludes.any { it.endsWith("/Backups/one") })
        assertTrue(excludes.any { it.endsWith("/Backups/two") })
    }

    @Test
    fun `remote repositories contribute no path exclusions`() {
        val excludes = ExclusionPolicy.implicitExcludes(config(remote("b2", "bucket:path")), null)
        assertFalse(excludes.any { it.contains("bucket") })
    }

    @Test
    fun `a relative or empty location is ignored rather than excluding everything`() {
        val excludes = ExclusionPolicy.implicitExcludes(
            config(local("bad", ""), local("relative", "some/where")),
            null,
        )
        assertFalse(excludes.contains(""))
        assertFalse(excludes.any { it.endsWith("some/where") })
    }

    @Test
    fun `the exclusion uses the canonical path, so symlinked spellings still match`() {
        val real = temp.newFolder("real")
        val link = File(temp.root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())

        val excludes = ExclusionPolicy.implicitExcludes(
            config(local("repo", File(link, "restic").absolutePath)),
            null,
        )
        assertTrue(
            "expected canonicalised path, got " + excludes,
            excludes.any { it == File(real, "restic").canonicalPath },
        )
    }

    @Test
    fun `the staging directory is excluded when given`() {
        val staging = temp.newFolder("staging")
        assertTrue(ExclusionPolicy.implicitExcludes(config(), staging).contains(staging.absolutePath))
    }

    private fun config(vararg destinations: Destination) =
        Config(Settings(), destinations.toList(), emptyList(), accessible = true)

    private fun local(id: String, path: String) =
        Destination(id = id, name = id, backend = ResticBackend.LOCAL, location = path)

    private fun remote(id: String, location: String) =
        Destination(id = id, name = id, backend = ResticBackend.B2, location = location)
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class RestCredentialFoldingTest {
    @get:org.junit.Rule
    val credentials: org.junit.rules.TemporaryFolder = org.junit.rules.TemporaryFolder()

    private fun secrets() =
        io.github.resticdroid.secret.SecretStore(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

    private fun restDestination() = io.github.resticdroid.config.Destination(
        id = "rest",
        name = "REST",
        backend = io.github.resticdroid.restic.ResticBackend.REST,
        location = "https://backup.example.com:8000/phone/",
    )

    @org.junit.Test
    fun `user and password land in the uri userinfo`() {
        val store = secrets()
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            restDestination(), "repopass", store, credentials.root,
            overrides = mapOf(
                io.github.resticdroid.restic.ResticBackend.REST_USER to "alice",
                io.github.resticdroid.restic.ResticBackend.REST_PASSWORD to "hunter2",
            ),
        )
        org.junit.Assert.assertEquals(
            "rest:https://alice:hunter2@backup.example.com:8000/phone/",
            repo.uri,
        )
    }

    @org.junit.Test
    fun `credentials do not linger in the environment as well`() {
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            restDestination(), "repopass", secrets(), credentials.root,
            overrides = mapOf(
                io.github.resticdroid.restic.ResticBackend.REST_USER to "alice",
                io.github.resticdroid.restic.ResticBackend.REST_PASSWORD to "hunter2",
            ),
        )
        org.junit.Assert.assertFalse(repo.env.containsKey(io.github.resticdroid.restic.ResticBackend.REST_USER))
        org.junit.Assert.assertFalse(repo.env.containsKey(io.github.resticdroid.restic.ResticBackend.REST_PASSWORD))
    }

    @org.junit.Test
    fun `characters that would re-delimit the url are percent encoded`() {
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            restDestination(), "repopass", secrets(), credentials.root,
            overrides = mapOf(
                io.github.resticdroid.restic.ResticBackend.REST_USER to "a@b",
                io.github.resticdroid.restic.ResticBackend.REST_PASSWORD to "p@ss:/word",
            ),
        )
        org.junit.Assert.assertEquals(1, repo.uri.count { it == '@' })
        org.junit.Assert.assertTrue(repo.uri.endsWith("@backup.example.com:8000/phone/"))
    }

    @org.junit.Test
    fun `a repository prints itself without its credentials`() {
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            restDestination(), "repopass", secrets(), credentials.root,
            overrides = mapOf(
                io.github.resticdroid.restic.ResticBackend.REST_USER to "alice",
                io.github.resticdroid.restic.ResticBackend.REST_PASSWORD to "hunter2",
            ),
        )
        org.junit.Assert.assertFalse(repo.toString().contains("hunter2"))
        org.junit.Assert.assertFalse(repo.redactedUri().contains("hunter2"))
    }

    @org.junit.Test
    fun `the stored location never contains the credentials`() {
        org.junit.Assert.assertFalse(restDestination().toIni().contains("alice"))
        org.junit.Assert.assertFalse(restDestination().toIni().contains("hunter2"))
    }

    @org.junit.Test
    fun `non-rest backends are left alone`() {
        val b2 = io.github.resticdroid.config.Destination(
            id = "b2", name = "B2",
            backend = io.github.resticdroid.restic.ResticBackend.B2,
            location = "bucket:path",
        )
        val repo = io.github.resticdroid.engine.Repositories.openWith(b2, "p", secrets(), credentials.root)
        org.junit.Assert.assertEquals("b2:bucket:path", repo.uri)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class CredentialDeliveryTest {
    @get:org.junit.Rule
    val dir: org.junit.rules.TemporaryFolder = org.junit.rules.TemporaryFolder()

    private fun secrets() = io.github.resticdroid.secret.SecretStore(
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
    )

    private fun destination(backend: io.github.resticdroid.restic.ResticBackend, location: String) =
        io.github.resticdroid.config.Destination(
            id = backend.id, name = backend.displayName, backend = backend, location = location,
        )

    @org.junit.Test
    fun `environment credentials become environment variables`() {
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            destination(io.github.resticdroid.restic.ResticBackend.B2, "bucket:path"),
            "pw", secrets(), dir.root,
            overrides = mapOf("B2_ACCOUNT_ID" to "id", "B2_ACCOUNT_KEY" to "key"),
        )
        org.junit.Assert.assertEquals("id", repo.env["B2_ACCOUNT_ID"])
        org.junit.Assert.assertEquals("key", repo.env["B2_ACCOUNT_KEY"])
        org.junit.Assert.assertEquals("b2:bucket:path", repo.uri)
    }

    @org.junit.Test
    fun `a file-delivered credential is written privately and passed as a path`() {
        val json = """{"type":"service_account","private_key":"SECRET"}"""
        val repo = io.github.resticdroid.engine.Repositories.openWith(
            destination(io.github.resticdroid.restic.ResticBackend.GS, "bucket:/path"),
            "pw", secrets(), dir.root,
            overrides = mapOf("GOOGLE_PROJECT_ID" to "proj", "GOOGLE_APPLICATION_CREDENTIALS" to json),
        )

        val path = repo.env["GOOGLE_APPLICATION_CREDENTIALS"]!!
        val file = java.io.File(path)
        org.junit.Assert.assertTrue("restic needs a real file at that path", file.isFile)
        org.junit.Assert.assertEquals(json, file.readText())
        org.junit.Assert.assertTrue("must live under the private dir", path.startsWith(dir.root.absolutePath))
        org.junit.Assert.assertFalse(repo.env.values.any { it.contains("SECRET") && it != json.takeIf { false } })
        org.junit.Assert.assertEquals("proj", repo.env["GOOGLE_PROJECT_ID"])
    }

    @org.junit.Test
    fun `rewriting the credential file does not accumulate stale copies`() {
        val d = destination(io.github.resticdroid.restic.ResticBackend.GS, "bucket:/path")
        repeat(3) { n ->
            io.github.resticdroid.engine.Repositories.openWith(
                d, "pw", secrets(), dir.root,
                overrides = mapOf("GOOGLE_APPLICATION_CREDENTIALS" to """{"run":$n}"""),
            )
        }
        org.junit.Assert.assertEquals(1, dir.root.listFiles()!!.size)
        org.junit.Assert.assertEquals("""{"run":2}""", dir.root.listFiles()!!.single().readText())
    }

    @org.junit.Test
    fun `only backends that can work on Android are offered`() {
        val ids = io.github.resticdroid.restic.ResticBackend.entries.map { it.id }
        org.junit.Assert.assertFalse(ids.contains("sftp"))
        org.junit.Assert.assertFalse(ids.contains("rclone"))
        org.junit.Assert.assertEquals(
            listOf("azure", "b2", "gs", "local", "rest", "s3", "swift"),
            ids.sorted(),
        )
    }

    @org.junit.Test
    fun `a path that is itself a repository is recognised in both directions`() {
        val repo = "/storage/emulated/0/Backups/restic"
        org.junit.Assert.assertTrue(
            ExclusionPolicy.isRecursive(repo, repo) && ExclusionPolicy.isRecursive(repo, repo)
        )
        // A repository merely inside a backed-up tree is not the same case:
        // there --exclude still works.
        org.junit.Assert.assertFalse(
            ExclusionPolicy.isRecursive(repo, "/storage/emulated/0") &&
                ExclusionPolicy.isRecursive("/storage/emulated/0", repo)
        )
    }
}
