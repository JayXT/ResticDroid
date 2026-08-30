package io.github.resticdroid.util

object Redact {
    private val USERINFO = Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/\s@]+@""")

    private val BARE_USERINFO = Regex("""\b([a-z0-9]+:)//?[^/\s@]*:[^/\s@]+@""")

    fun text(value: String): String =
        value
            .replace(USERINFO, "$1***@")
            .replace(BARE_USERINFO, "$1//***@")

    fun text(value: String, secrets: Collection<String>): String {
        var result = text(value)
        secrets.asSequence()
            .filter { it.length >= MIN_SECRET_LENGTH }
            .forEach { result = result.replace(it, "***") }
        return result
    }

    private const val MIN_SECRET_LENGTH = 6
}
