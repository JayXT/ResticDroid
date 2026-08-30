package io.github.resticdroid.config

object OptionPolicy {
    // An allowlist, not a denylist. The config directory is writable by
    // anything with storage access, and restic's --password-command runs a
    // shell command that outranks RESTIC_PASSWORD: an appended line would
    // otherwise be arbitrary code execution at the next unattended run.
    private val FLAGS: Set<String> = setOf(
        "--no-cache",
        "--cleanup-cache",
        "--no-lock",
        "--verbose",
        "--quiet",
    )

    private val FLAGS_WITH_VALUE: Set<String> = setOf(
        "--limit-upload",
        "--limit-download",
        "--pack-size",
        "--compression",
        "--retry-lock",
    )

    data class Result(
        val accepted: List<String>,
        val rejected: List<String>,
    )

    fun filter(options: List<String>): Result {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        var index = 0
        while (index < options.size) {
            val token = options[index].trim()
            val name = token.substringBefore('=')
            val inlineValue = token.contains('=')

            when {
                name in FLAGS && !inlineValue -> {
                    accepted += token
                    index++
                }

                name in FLAGS_WITH_VALUE && inlineValue -> {
                    accepted += token
                    index++
                }

                name in FLAGS_WITH_VALUE -> {
                    val value = options.getOrNull(index + 1)
                    if (value == null || value.startsWith("-")) {
                        rejected += token
                        index++
                    } else {
                        accepted += token
                        accepted += value
                        index += 2
                    }
                }

                else -> {
                    rejected += token
                    index++
                }
            }
        }
        return Result(accepted, rejected)
    }

    fun explain(rejected: List<String>): String =
        "Ignored unsupported or unsafe entries: " + rejected.joinToString(", ") +
            ". Allowed options: " + (FLAGS + FLAGS_WITH_VALUE).sorted().joinToString(", ") +
            ". Allowed settings: the backend's own variables (" +
            SettingPolicy.PREFIXES.joinToString(", ") { it + "*" } + ")."
}

object SettingPolicy {
    // Same reasoning as OptionPolicy, one layer down: a "setting." line becomes
    // an environment variable for restic, and RESTIC_PASSWORD_COMMAND or
    // LD_PRELOAD in that position is arbitrary code execution at the next
    // unattended run. Only the backends' own variables get through.
    val PREFIXES: List<String> = listOf("AWS_", "AZURE_", "B2_", "GOOGLE_", "OS_", "ST_")

    fun accepts(key: String): Boolean = PREFIXES.any { key.startsWith(it) }
}
