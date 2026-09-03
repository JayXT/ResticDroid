package io.github.resticdroid.engine

import io.github.resticdroid.config.Destination
import io.github.resticdroid.restic.Delivery
import io.github.resticdroid.restic.ResticRepository
import io.github.resticdroid.secret.SecretStore
import java.io.File

object Repositories {
    fun openOrFail(
        destination: Destination,
        secrets: SecretStore,
        credentialDir: File,
    ): Result<ResticRepository> =
        when (val secret = secrets.read(SecretStore.passwordAlias(destination.id))) {
            is SecretStore.Secret.Value ->
                runCatching { build(destination, secret.value, secrets, credentialDir) }

            SecretStore.Secret.Missing -> Result.failure(
                IllegalStateException("no password stored for '${destination.name}'")
            )

            is SecretStore.Secret.Unreadable -> Result.failure(
                IllegalStateException(
                    "the stored password for '${destination.name}' could not be decrypted " +
                        "(${secret.reason}). Open the repository and enter it again."
                )
            )
        }

    /**
     * Drops the files [Delivery.PrivateFile] wrote for a repository.
     *
     * They hold the credential in clear text - a service-account key, say - so
     * they must not outlive the keystore entries the same repository owned.
     */
    fun forgetCredentials(destinationId: String, credentialDir: File) {
        credentialDir.listFiles { f -> f.name.startsWith("$destinationId.") }
            ?.forEach { it.delete() }
    }

    fun openWith(
        destination: Destination,
        password: String,
        secrets: SecretStore,
        credentialDir: File,
        overrides: Map<String, String> = emptyMap(),
    ): ResticRepository =
        build(destination, password(destination, password, secrets), secrets, credentialDir, overrides)

    // The editor never fills the password field back in - the keystore is
    // write-only by design - so an empty field on a saved repository means
    // "the one already stored", as it does for the credential fields.
    fun password(destination: Destination, typed: String, secrets: SecretStore): String {
        if (typed.isNotBlank()) return typed
        return when (val secret = secrets.read(SecretStore.passwordAlias(destination.id))) {
            is SecretStore.Secret.Value -> secret.value
            SecretStore.Secret.Missing ->
                throw IllegalStateException("Enter the repository password.")
            is SecretStore.Secret.Unreadable -> throw IllegalStateException(
                "The stored password could not be decrypted (${secret.reason}). Enter it again."
            )
        }
    }

    private fun build(
        destination: Destination,
        password: String,
        secrets: SecretStore,
        credentialDir: File,
        overrides: Map<String, String> = emptyMap(),
    ): ResticRepository {
        val env = destination.settings.toMutableMap()
        var user: String? = null
        var secret: String? = null

        // A credential that cannot be read is reported, never skipped. Handing
        // restic a backend with no account key does not fail as "no account
        // key": it fails as the storage provider's own 401, which sends the
        // user to their B2 account looking for a fault that is on this device.
        destination.backend.credentials.forEach { field ->
            val value = overrides[field.key]?.takeIf { it.isNotEmpty() }
                ?: when (val stored = secrets.read(SecretStore.credentialAlias(destination.id, field.key))) {
                    is SecretStore.Secret.Value -> stored.value
                    SecretStore.Secret.Missing -> null
                    is SecretStore.Secret.Unreadable -> throw IllegalStateException(
                        "The stored ${field.label} for '${destination.name}' could not be " +
                            "decrypted (${stored.reason}). Open the repository and enter it again."
                    )
                }

            if (value.isNullOrEmpty()) {
                if (!field.optional) {
                    throw IllegalStateException(
                        "No ${field.label} is stored for '${destination.name}'. " +
                            "Open the repository and enter it again."
                    )
                }
                return@forEach
            }

            when (field.delivery) {
                Delivery.Environment -> env[field.key] = value
                Delivery.UrlUser -> user = value
                Delivery.UrlPassword -> secret = value
                Delivery.PrivateFile -> env[field.key] = writePrivately(
                    credentialDir, destination.id, field.key, value,
                )
            }
        }

        return ResticRepository(
            uri = withUserinfo(destination.uri, user, secret),
            password = password,
            env = env,
            options = destination.options,
        )
    }

    private fun withUserinfo(uri: String, user: String?, password: String?): String {
        if (user.isNullOrEmpty()) return uri
        val marker = "://"
        val at = uri.indexOf(marker)
        if (at < 0) return uri

        val head = uri.substring(0, at + marker.length)
        val tail = uri.substring(at + marker.length)
        if (tail.substringBefore('/').contains('@')) return uri

        val userinfo = buildString {
            append(encode(user))
            if (!password.isNullOrEmpty()) {
                append(':')
                append(encode(password))
            }
        }
        return "$head$userinfo@$tail"
    }

    private fun writePrivately(dir: File, destinationId: String, key: String, value: String): String {
        dir.mkdirs()
        val file = File(dir, "$destinationId.${key.lowercase()}")
        file.writeText(value)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return file.absolutePath
    }

    private fun encode(value: String): String = buildString {
        value.forEach { c ->
            if (c.isLetterOrDigit() || c in "-._~") {
                append(c)
            } else {
                c.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                    append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
    }
}
