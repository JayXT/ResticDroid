package io.github.resticdroid.engine

import io.github.resticdroid.config.Destination

/**
 * What to say when the storage provider refuses the account credentials.
 *
 * restic passes the provider's own HTTP status through, so a key that has been
 * revoked, expired or deleted arrives as a bare "401" - true, and no help at
 * all in deciding which of a repository's two secrets is the wrong one. Every
 * backend that authenticates can fail this way, so the rule is keyed on the
 * backend having credentials at all rather than on any one provider.
 */
internal object ProviderError {
    private val REFUSED = Regex("""(?<!\d)(401|403)(?!\d)""")

    fun explain(destination: Destination, message: String): String =
        if (destination.backend.credentials.isEmpty() || !REFUSED.containsMatchIn(message)) {
            message
        } else {
            "${destination.backend.displayName} rejected the account credentials " +
                "for '${destination.name}'."
        }
}
