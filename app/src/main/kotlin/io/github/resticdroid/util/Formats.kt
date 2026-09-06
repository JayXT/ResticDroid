package io.github.resticdroid.util

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formats {
    fun bytes(value: Long?): String {
        if (value == null) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return String.format(Locale.US, if (size >= 100) "%.0f %s" else "%.1f %s", size, units[unit])
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    }

    fun nextRun(millis: Long, now: Long = System.currentTimeMillis()): String {
        val delta = millis - now
        if (delta <= 0) return "due now"
        val clock = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(millis))
        return when {
            delta < 60_000 -> "in under a minute"
            delta < 3_600_000 -> "in ${delta / 60_000}m, at $clock"
            delta < 86_400_000 -> "in ${delta / 3_600_000}h, at $clock"
            else -> {
                val date = java.text.SimpleDateFormat("d MMM", Locale.US).format(java.util.Date(millis))
                "$date at $clock"
            }
        }
    }

    /**
     * A snapshot's timestamp, in this device's time zone.
     *
     * The offset in the string is not decoration. Go hardcodes the local zone
     * to UTC on Android - zoneinfo_android.go sets it and never reads $TZ - so
     * restic stamps every snapshot made here with Z, while the same repository
     * holds snapshots from a desktop stamped with its own offset. The instants
     * agree; only the wall clocks differ, and reading the digits off the string
     * showed a backup taken at noon as having happened at ten.
     */
    fun snapshotTime(rfc3339: String, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching {
            OffsetDateTime.parse(rfc3339).atZoneSameInstant(zone).format(SNAPSHOT_TIME)
        }.getOrElse {
            val date = rfc3339.substringBefore('T')
            val time = rfc3339.substringAfter('T', "").take(5)
            if (time.isEmpty()) date else "$date $time"
        }

    private val SNAPSHOT_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
