package io.github.resticdroid.config

import io.github.resticdroid.restic.ResticBackend
import io.github.resticdroid.restic.RetentionPolicy

data class Destination(
    val id: String,
    val name: String,
    val backend: ResticBackend,
    val location: String,
    val settings: Map<String, String> = emptyMap(),
    val options: List<String> = emptyList(),
    val rejectedOptions: List<String> = emptyList(),
    val unknown: List<Pair<String, String>> = emptyList(),
) {
    val uri: String get() = backend.uriFor(location)

    fun toIni(): String = IniWriter()
        .comment("ResticDroid destination: $name")
        .comment("")
        .comment("backend   one of: " + ResticBackend.entries.joinToString(", ") { it.id })
        .comment("location  ${backend.locationHint}")
        .comment("")
        .comment("Credentials are NOT stored here. They are held in the Android")
        .comment("keystore, hardware-backed where the device supports it. This file")
        .comment("is safe to copy around; it contains no secrets.")
        .blank()
        .put("name", name)
        .put("backend", backend.id)
        .put("location", location)
        .apply {
            if (settings.isNotEmpty()) {
                blank()
                comment("Non-secret backend settings, passed to restic as environment variables.")
                settings.toSortedMap().forEach { (k, v) -> put("setting.${k.lowercase()}", v) }
            }
            if (options.isNotEmpty()) {
                blank()
                comment("Extra flags passed to restic before the subcommand.")
                putAll("option", options)
            }
        }
        .preserve(unknown)
        .build()

    companion object {
        internal val KNOWN = setOf("name", "backend", "location", "option")

        fun fromIni(id: String, ini: Ini): Destination {
            val backend = ResticBackend.byId(ini.string("backend", "local")) ?: ResticBackend.LOCAL
            val allowedOptions = OptionPolicy.filter(ini.all("option"))
            val declared = ini.entries()
                .filter { it.first.startsWith("setting.") }
                .map { it.first.removePrefix("setting.").uppercase() to it.second }
            val (settings, refused) = declared.partition { SettingPolicy.accepts(it.first) }
            return Destination(
                id = id,
                name = ini.string("name", id),
                backend = backend,
                location = ini.string("location"),
                settings = settings.toMap(),
                options = allowedOptions.accepted,
                rejectedOptions = allowedOptions.rejected +
                    refused.map { "setting.${it.first.lowercase()}" },
                unknown = ini.keysExcept(KNOWN).filterNot { it.first.startsWith("setting.") },
            )
        }
    }
}

sealed interface Schedule {
    object Manual : Schedule
    data class Interval(val hours: Int) : Schedule
    data class Daily(val hour: Int, val minute: Int) : Schedule

    fun serialize(): String = when (this) {
        Manual -> "manual"
        is Interval -> "every ${hours}h"
        is Daily -> "daily %02d:%02d".format(hour, minute)
    }

    companion object {
        fun parse(text: String?): Schedule {
            val value = text?.trim()?.lowercase() ?: return Manual
            Regex("""^every\s+(\d+)\s*h$""").find(value)?.let {
                // toIntOrNull, not toInt: these files are edited by hand, and a
                // run of digits too long for an Int must not throw out of a load.
                return Interval(it.groupValues[1].toIntOrNull()?.coerceIn(1, 24 * 365) ?: return Manual)
            }
            Regex("""^daily\s+(\d{1,2}):(\d{2})$""").find(value)?.let {
                val h = it.groupValues[1].toIntOrNull() ?: return Manual
                val m = it.groupValues[2].toIntOrNull() ?: return Manual
                if (h in 0..23 && m in 0..59) return Daily(h, m)
            }
            return Manual
        }
    }
}

data class Conditions(
    val requireCharging: Boolean = false,
    val requireUnmetered: Boolean = true,
    val requireIdle: Boolean = false,
    val minBatteryPercent: Int = 0,
    val wifiSsid: List<String> = emptyList(),
) {
    companion object {
        val Default: Conditions = Conditions()
    }
}

data class Profile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val paths: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val excludeFiles: List<String> = emptyList(),
    val destinationId: String = "",
    val tags: List<String> = emptyList(),
    val manualTags: List<String> = emptyList(),
    val schedule: Schedule = Schedule.Manual,
    val conditions: Conditions = Conditions.Default,
    val retention: RetentionPolicy = RetentionPolicy.Default,
    val pruneDays: Int? = null,
    val groupByTags: Boolean = false,
    val excludeCaches: Boolean = true,
    val includeApps: Boolean = false,
    val unknown: List<Pair<String, String>> = emptyList(),
) {
    fun validate(): List<String> = buildList {
        if (paths.isEmpty() && !includeApps) add("no path is configured")
        if (destinationId.isBlank()) add("no destination is configured")
    }

    fun toIni(): String = IniWriter()
        .comment("ResticDroid backup profile: $name")
        .comment("")
        .comment("path        directory to back up; repeat the key for more than one")
        .comment("exclude     restic exclude pattern; repeat the key for more than one")
        .comment("exclude-file  file of patterns; a bare name is looked up in ../exclude.d")
        .comment("destination id of a file in ../destinations.d, without the .conf")
        .comment("schedule    manual | every <N>h | daily HH:MM")
        .comment("tag         snapshot tag; repeat the key for more than one. The")
        .comment("            profile's name is always added as a tag too. Retention")
        .comment("            only ever touches snapshots carrying all of them, so a")
        .comment("            repository shared with another machine stays safe.")
        .comment("manual-tag  same, but only on a run you start by hand")
        .comment("")
        .comment("Conditions apply to scheduled runs only. A backup you start by hand")
        .comment("runs regardless.")
        .blank()
        .put("name", name)
        .put("enabled", enabled)
        .put("destination", destinationId)
        .blank()
        .putAll("path", paths)
        .apply { if (excludes.isNotEmpty()) blank() }
        .putAll("exclude", excludes)
        .apply { if (excludeFiles.isNotEmpty()) blank() }
        .putAll("exclude-file", excludeFiles)
        .apply { if (tags.isNotEmpty()) blank() }
        .putAll("tag", tags)
        .putAll("manual-tag", manualTags)
        .blank()
        .put("schedule", schedule.serialize())
        .put("require-charging", conditions.requireCharging)
        .put("require-unmetered", conditions.requireUnmetered)
        .put("require-idle", conditions.requireIdle)
        .comment("0 disables the battery check")
        .put("min-battery", conditions.minBatteryPercent)
        .comment("Restrict to these Wi-Fi networks; leave unset for any network")
        .putAll("wifi-ssid", conditions.wifiSsid)
        .blank()
        .comment("Retention, applied by 'restic forget' after a successful run.")
        .comment("Remove every keep-* line to keep all snapshots forever.")
        .put("keep-last", retention.last)
        .put("keep-hourly", retention.hourly)
        .put("keep-daily", retention.daily)
        .put("keep-weekly", retention.weekly)
        .put("keep-monthly", retention.monthly)
        .put("keep-yearly", retention.yearly)
        .put("keep-within", retention.within)
        .comment("Days between prunes. Unset prunes after every backup; 0 never does.")
        .put("prune", pruneDays)
        .comment("Apply the policy to each tag combination separately, so a run you")
        .comment("started by hand is kept apart from a scheduled one. Off, restic")
        .comment("groups by host and paths instead, and editing the paths above")
        .comment("leaves the older snapshots in a group that never ages out.")
        .put("group-by-tags", groupByTags)
        .blank()
        .comment("Skip directories tagged as caches (CACHEDIR.TAG).")
        .put("exclude-caches", excludeCaches)
        .blank()
        .comment("Also back up the APKs of apps you installed yourself. App data is")
        .comment("not included: reading it needs root, which ResticDroid does not use.")
        .put("include-apps", includeApps)
        .preserve(unknown)
        .build()

    companion object {
        internal val KNOWN = setOf(
            "name", "enabled", "destination", "path", "exclude", "exclude-file", "tag",
            "manual-tag", "prune", "schedule",
            "require-charging", "require-unmetered", "require-idle", "min-battery",
            "wifi-ssid", "keep-last", "keep-hourly", "keep-daily", "keep-weekly",
            "keep-monthly", "keep-yearly", "keep-within", "exclude-caches", "include-apps",
            "group-by-tags",
        )

        fun fromIni(id: String, ini: Ini): Profile = Profile(
            id = id,
            name = ini.string("name", id),
            enabled = ini.bool("enabled", true),
            paths = ini.all("path").filter { it.isNotBlank() },
            excludes = ini.all("exclude").filter { it.isNotBlank() },
            excludeFiles = ini.all("exclude-file").filter { it.isNotBlank() },
            destinationId = ini.string("destination"),
            tags = ini.all("tag").filter { it.isNotBlank() },
            manualTags = ini.all("manual-tag").filter { it.isNotBlank() },
            schedule = Schedule.parse(ini.get("schedule")),
            conditions = Conditions(
                requireCharging = ini.bool("require-charging", false),
                requireUnmetered = ini.bool("require-unmetered", true),
                requireIdle = ini.bool("require-idle", false),
                minBatteryPercent = ini.int("min-battery", 0).coerceIn(0, 100),
                wifiSsid = ini.all("wifi-ssid").filter { it.isNotBlank() },
            ),
            retention = RetentionPolicy(
                last = ini.intOrNull("keep-last"),
                hourly = ini.intOrNull("keep-hourly"),
                daily = ini.intOrNull("keep-daily"),
                weekly = ini.intOrNull("keep-weekly"),
                monthly = ini.intOrNull("keep-monthly"),
                yearly = ini.intOrNull("keep-yearly"),
                within = ini.get("keep-within"),
            ),
            pruneDays = ini.intOrNull("prune")?.coerceAtLeast(0),
            groupByTags = ini.bool("group-by-tags", false),
            excludeCaches = ini.bool("exclude-caches", true),
            includeApps = ini.bool("include-apps", false),
            unknown = ini.keysExcept(KNOWN),
        )
    }
}

data class Settings(
    val requireAuth: Boolean = true,
    val hostname: String = "",
    val logRetention: Int = 20,
    val unknown: List<Pair<String, String>> = emptyList(),
) {
    fun toIni(): String = IniWriter()
        .comment("ResticDroid settings")
        .comment("")
        .comment("This directory IS the application's configuration. Edit these files")
        .comment("with any text editor; ResticDroid picks the changes up immediately.")
        .comment("There is nothing to import or export.")
        .blank()
        .comment("Require biometric or device-credential authentication before an")
        .comment("interactive backup, restore or snapshot browse. Scheduled runs are")
        .comment("never prompted: they would fail at 3am with nobody to answer.")
        .put("require-auth", requireAuth)
        .blank()
        .comment("Hostname recorded in snapshots. Empty means the device model.")
        .put("hostname", hostname)
        .blank()
        .comment("How many run logs to keep in log/.")
        .put("log-retention", logRetention)
        .preserve(unknown)
        .build()

    companion object {
        internal val KNOWN = setOf("require-auth", "hostname", "log-retention")

        fun fromIni(ini: Ini): Settings = Settings(
            requireAuth = ini.bool("require-auth", true),
            hostname = ini.string("hostname"),
            logRetention = ini.int("log-retention", 20).coerceIn(0, 500),
            unknown = ini.keysExcept(KNOWN),
        )
    }
}
