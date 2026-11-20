package io.github.resticdroid.restic

import java.net.URI

public data class SftpLocation(val host: String, val port: Int, val user: String, val path: String) {
    public val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"

    public fun accepts(pin: String?): Boolean =
        pin != null && pin.substringBefore(' ') == address && FINGERPRINT.matches(pin.substringAfter(' '))

    public fun uri(): String {
        require(host.isNotBlank() && port in 1..65535 && user.isNotBlank() && ':' !in user)
        require(path.isNotEmpty() && !path.startsWith('~'))
        val uri = URI("sftp", user, host, port, "/$path", null, null)
        require(uri.host?.removeSurrounding("[", "]") == host)
        return uri.toASCIIString()
    }

    public companion object {
        public const val IDENTITY: String = "RD_SSH_IDENTITY"
        public const val PIN: String = "RD_SSH_PIN"
        private val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/]{43}")

        public fun parse(value: String): SftpLocation {
            val uri = URI(value)
            require(uri.scheme == "sftp" && uri.host != null && uri.userInfo != null)
            require(uri.query == null && uri.fragment == null && uri.path.startsWith('/'))
            return SftpLocation(
                uri.host.removeSurrounding("[", "]"),
                if (uri.port == -1) 22 else uri.port,
                uri.userInfo, uri.path.drop(1),
            ).also { it.uri() }
        }
    }
}
