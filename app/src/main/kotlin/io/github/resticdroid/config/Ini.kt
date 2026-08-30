package io.github.resticdroid.config

class Ini private constructor(private val entries: List<Pair<String, String>>) {
    fun get(key: String): String? = entries.lastOrNull { it.first == key.lowercase() }?.second

    fun all(key: String): List<String> =
        entries.filter { it.first == key.lowercase() }.map { it.second }

    fun string(key: String, default: String = ""): String = get(key)?.ifEmpty { null } ?: default

    fun bool(key: String, default: Boolean = false): Boolean = when (get(key)?.lowercase()?.trim()) {
        "yes", "true", "on", "1" -> true
        "no", "false", "off", "0" -> false
        else -> default
    }

    fun int(key: String, default: Int): Int = get(key)?.trim()?.toIntOrNull() ?: default

    fun intOrNull(key: String): Int? = get(key)?.trim()?.toIntOrNull()

    fun keysExcept(known: Set<String>): List<Pair<String, String>> =
        entries.filter { it.first !in known.map(String::lowercase) }

    fun entries(): List<Pair<String, String>> = entries

    companion object {
        fun parse(text: String): Ini {
            val entries = mutableListOf<Pair<String, String>>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                val split = line.indexOf('=')
                if (split <= 0) continue
                val key = line.substring(0, split).trim().lowercase()
                if (key.isEmpty()) continue
                entries += key to unquote(line.substring(split + 1).trim())
            }
            return Ini(entries)
        }

        private fun unquote(value: String): String =
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value.substring(1, value.length - 1)
            } else {
                value
            }

        fun quote(value: String): String =
            if (value != value.trim() || (value.startsWith('"') && value.endsWith('"'))) {
                "\"$value\""
            } else {
                value
            }
    }
}

class IniWriter {
    private val out = StringBuilder()

    fun comment(text: String): IniWriter = apply {
        text.lineSequence().forEach { out.append("# ").append(it).append('\n') }
    }

    fun blank(): IniWriter = apply { out.append('\n') }

    fun put(key: String, value: String?): IniWriter = apply {
        if (!value.isNullOrEmpty()) out.append(key).append(" = ").append(Ini.quote(value)).append('\n')
    }

    fun put(key: String, value: Boolean): IniWriter = apply {
        out.append(key).append(" = ").append(if (value) "yes" else "no").append('\n')
    }

    fun put(key: String, value: Int?): IniWriter = apply {
        if (value != null) out.append(key).append(" = ").append(value).append('\n')
    }

    fun putAll(key: String, values: List<String>): IniWriter = apply {
        values.filter { it.isNotBlank() }.forEach { put(key, it) }
    }

    fun preserve(entries: List<Pair<String, String>>): IniWriter = apply {
        if (entries.isEmpty()) return@apply
        blank()
        comment("Kept verbatim: written by a different version of ResticDroid.")
        entries.forEach { (k, v) -> put(k, v) }
    }

    fun build(): String = out.toString()
}
