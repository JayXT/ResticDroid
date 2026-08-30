package io.github.resticdroid.work

import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.util.Redact
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class RunLog private constructor(private val file: File) {
    private var written = 0

    // Capped: a backup of a phone's storage can hit an unreadable file hundreds
    // of thousands of times, and the log is read back into one Text.
    fun line(text: String) {
        if (written > MAX_LINES) return
        written++
        val out = if (written > MAX_LINES) "… further lines omitted" else Redact.text(text)
        runCatching { file.appendText("${timestamp()} $out\n") }
    }

    fun path(): String = file.absolutePath

    private fun timestamp(): String = TIME.format(LocalDateTime.now())

    companion object {
        private const val MAX_LINES = 2_000

        // DateTimeFormatter, not SimpleDateFormat: a backup and a prune log at
        // the same instant on different worker threads, and SimpleDateFormat is
        // not safe to share.
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

        fun open(profileId: String): RunLog {
            val dir = ConfigPaths.logDir().apply { mkdirs() }
            val file = File(dir, "${STAMP.format(LocalDateTime.now())}-$profileId.log")
            return RunLog(file)
        }

        fun prune(keep: Int) {
            if (keep <= 0) return
            runCatching {
                ConfigPaths.logDir().listFiles { f -> f.isFile && f.name.endsWith(".log") }
                    ?.sortedByDescending { it.name }
                    ?.drop(keep)
                    ?.forEach { it.delete() }
            }
        }

        fun recent(limit: Int = 20): List<File> =
            ConfigPaths.logDir().listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
                ?.take(limit)
                .orEmpty()
    }
}
