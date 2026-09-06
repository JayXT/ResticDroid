package io.github.resticdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.resticdroid.config.Conditions
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.Profile
import io.github.resticdroid.config.Schedule
import io.github.resticdroid.engine.DirectoryScheme
import io.github.resticdroid.engine.ExclusionPolicy
import io.github.resticdroid.restic.ResticBackend
import io.github.resticdroid.restic.RetentionPolicy
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.components.BackButton
import io.github.resticdroid.ui.components.ConfirmDialog
import io.github.resticdroid.ui.components.Hint
import io.github.resticdroid.ui.components.InfoCard
import io.github.resticdroid.ui.components.Picker
import io.github.resticdroid.ui.components.SectionHeader
import io.github.resticdroid.ui.components.SwitchRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(
    model: AppViewModel,
    navigator: Navigator,
    profileId: String?,
) {
    val config by model.config.collectAsStateWithLifecycle()
    val existing = profileId?.let { config.profile(it) }
    val creating = existing == null

    var name by remember(profileId) { mutableStateOf(existing?.name ?: "") }
    var enabled by remember(profileId) { mutableStateOf(existing?.enabled ?: true) }
    var destinationId by remember(profileId) {
        mutableStateOf(existing?.destinationId ?: config.destinations.firstOrNull()?.id.orEmpty())
    }
    var pathsText by remember(profileId) { mutableStateOf(existing?.paths.orEmpty().joinToString("\n")) }
    var excludesText by remember(profileId) { mutableStateOf(existing?.excludes.orEmpty().joinToString("\n")) }
    var includeApps by remember(profileId) { mutableStateOf(existing?.includeApps ?: false) }
    var tagsText by remember(profileId) { mutableStateOf(existing?.tags.orEmpty().joinToString("\n")) }
    var manualTagsText by remember(profileId) { mutableStateOf(existing?.manualTags.orEmpty().joinToString("\n")) }
    var pruneText by remember(profileId) { mutableStateOf(existing?.pruneDays?.toString().orEmpty()) }
    var excludeFilesText by remember(profileId) {
        mutableStateOf(existing?.excludeFiles.orEmpty().joinToString("\n"))
    }
    var schedule by remember(profileId) { mutableStateOf(existing?.schedule ?: Schedule.Manual) }
    var conditions by remember(profileId) { mutableStateOf(existing?.conditions ?: Conditions.Default) }
    var retention by remember(profileId) { mutableStateOf(existing?.retention ?: RetentionPolicy.Default) }
    var groupByTags by remember(profileId) { mutableStateOf(existing?.groupByTags ?: false) }
    var confirmDelete by remember(profileId) { mutableStateOf(false) }

    fun currentPaths() = pathsText.lines().map(String::trim).filter(String::isNotEmpty)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (creating) "New profile" else name.ifBlank { "Profile" }) },
                navigationIcon = { BackButton(navigator) },
                actions = {
                    if (!creating) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (creating) {
                SectionHeader("Start from")
                Hint("Starting points — everything below stays editable.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectoryScheme.all().forEach { scheme ->
                        AssistChip(
                            onClick = {
                                if (name.isBlank()) name = scheme.name
                                pathsText = DirectoryScheme.existingPaths(scheme).joinToString("\n")
                                excludesText = scheme.excludes.joinToString("\n")
                                includeApps = scheme.includeApps
                            },
                            label = { Text(scheme.name) },
                        )
                    }
                }
            }

            SectionHeader("What to back up")
            OutlinedTextField(
                value = pathsText,
                onValueChange = { pathsText = it },
                label = { Text("Folders, one per line") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Hint("Absolute paths. /storage/emulated/0 is internal shared storage.")
            if (includeApps && currentPaths().isEmpty()) {
                Hint("APKs are gathered when the backup runs, so no path is listed here.")
            }

            SwitchRow(
                title = "Include installed apps",
                subtitle = "Backs up the APKs of apps you installed yourself. App data needs " +
                    "root and is not included.",
                checked = includeApps,
                onChange = { includeApps = it },
            )

            OutlinedTextField(
                value = excludesText,
                onValueChange = { excludesText = it },
                label = { Text("Exclude patterns, one per line") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Hint("restic patterns, e.g. **/.thumbnails or *.tmp")

            OutlinedTextField(
                value = excludeFilesText,
                onValueChange = { excludeFilesText = it },
                label = { Text("Exclude files, one per line (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )
            Hint("A bare name is looked up in ResticDroid/exclude.d; an absolute path is used as written.")

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags, one per line") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )
            Hint("Recorded on every snapshot, along with the profile's name.")

            OutlinedTextField(
                value = manualTagsText,
                onValueChange = { manualTagsText = it },
                label = { Text("Tags for runs you start by hand (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )

            SectionHeader("Where to send it")
            if (config.destinations.isEmpty()) {
                InfoCard("No repositories yet. Add one under Repositories first.")
            } else {
                Picker(
                    label = "Repository",
                    options = config.destinations.map { it.id to it.name },
                    selected = destinationId,
                    onSelect = { destinationId = it },
                )

                val destination = config.destination(destinationId)
                if (destination?.backend == ResticBackend.LOCAL) {
                    val overlapping = currentPaths().filter {
                        ExclusionPolicy.isRecursive(it, destination.location)
                    }
                    if (overlapping.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Hint(
                            "This repository lives inside ${overlapping.first()} and is excluded " +
                                "automatically, so the backup cannot copy itself."
                        )
                    }
                }
            }

            SectionHeader("When")
            ScheduleEditor(schedule) { schedule = it }

            if (schedule != Schedule.Manual) {
                SectionHeader("Only run when")
                SwitchRow(
                    title = "Charging",
                    checked = conditions.requireCharging,
                    onChange = { conditions = conditions.copy(requireCharging = it) },
                )
                SwitchRow(
                    title = "On an unmetered network",
                    subtitle = "Wi-Fi and other connections not marked as metered.",
                    checked = conditions.requireUnmetered,
                    onChange = { conditions = conditions.copy(requireUnmetered = it) },
                )
                SwitchRow(
                    title = "Device is idle",
                    subtitle = "Screen off and not in use for a while.",
                    checked = conditions.requireIdle,
                    onChange = { conditions = conditions.copy(requireIdle = it) },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    if (conditions.minBatteryPercent == 0) {
                        "Battery level: any"
                    } else {
                        "Battery at least ${conditions.minBatteryPercent}%"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = conditions.minBatteryPercent.toFloat(),
                    onValueChange = {
                        conditions = conditions.copy(minBatteryPercent = (it / 5).toInt() * 5)
                    },
                    valueRange = 0f..95f,
                    steps = 18,
                )
                Hint("Ignored while charging.")

                OutlinedTextField(
                    value = conditions.wifiSsid.joinToString("\n"),
                    onValueChange = {
                        conditions = conditions.copy(
                            wifiSsid = it.lines().map(String::trim).filter(String::isNotEmpty)
                        )
                    },
                    label = { Text("Only on these Wi-Fi networks (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                )
                Hint("One per line; empty means any network. Needs the location permission.")
            }

            SectionHeader("Keep snapshots")
            RetentionEditor(retention) { retention = it }
            PruneEditor(pruneText) { pruneText = it }
            SwitchRow(
                title = "Count tag combinations separately",
                subtitle = "Keep a run you started by hand apart from a scheduled one, " +
                    "instead of grouping by the folders backed up.",
                checked = groupByTags,
                onChange = { groupByTags = it },
            )

            SectionHeader("Status")
            SwitchRow(
                title = "Enabled",
                subtitle = "A paused profile keeps its settings but never runs on a schedule.",
                checked = enabled,
                onChange = { enabled = it },
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val id = existing?.id ?: ConfigPaths.uniqueId(
                        ConfigPaths.profilesDir(),
                        name.ifBlank { "profile" },
                    )
                    model.saveProfile(
                        Profile(
                            id = id,
                            name = name.ifBlank { id },
                            enabled = enabled,
                            paths = currentPaths(),
                            excludes = excludesText.lines().map(String::trim).filter(String::isNotEmpty),
                            excludeFiles = excludeFilesText.lines().map(String::trim).filter(String::isNotEmpty),
                            destinationId = destinationId,
                            tags = tagsText.lines().map(String::trim).filter(String::isNotEmpty),
                            manualTags = manualTagsText.lines().map(String::trim).filter(String::isNotEmpty),
                            schedule = schedule,
                            conditions = conditions,
                            retention = retention,
                            groupByTags = groupByTags,
                            pruneDays = pruneText.toIntOrNull(),
                            excludeCaches = existing?.excludeCaches ?: true,
                            includeApps = includeApps,
                            unknown = existing?.unknown.orEmpty(),
                        )
                    )
                    navigator.back()
                },
                enabled = destinationId.isNotBlank() &&
                    (currentPaths().isNotEmpty() || includeApps),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Delete ${existing.name}?",
            body = "The profile is removed and its schedule cancelled. Snapshots already " +
                "in the repository are not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                model.deleteProfile(existing.id)
                confirmDelete = false
                navigator.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ScheduleEditor(schedule: Schedule, onChange: (Schedule) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = schedule is Schedule.Manual,
            onClick = { onChange(Schedule.Manual) },
            label = { Text("Manual") },
        )
        FilterChip(
            selected = schedule is Schedule.Daily,
            onClick = { onChange(Schedule.Daily(3, 0)) },
            label = { Text("Daily") },
        )
        FilterChip(
            selected = schedule is Schedule.Interval,
            onClick = { onChange(Schedule.Interval(6)) },
            label = { Text("Every N hours") },
        )
    }

    when (schedule) {
        is Schedule.Daily -> {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = schedule.hour.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.coerceIn(0, 23)?.let { onChange(schedule.copy(hour = it)) }
                    },
                    label = { Text("Hour") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = schedule.minute.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.coerceIn(0, 59)?.let { onChange(schedule.copy(minute = it)) }
                    },
                    label = { Text("Minute") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Hint("Android batches background work, so a run starts near this time, not on it.")
        }

        is Schedule.Interval -> {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = schedule.hours.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.coerceIn(1, 168)?.let { onChange(Schedule.Interval(it)) }
                },
                label = { Text("Hours between runs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Schedule.Manual -> Hint("Runs only when you tap the play button.")
    }
}

@Composable
private fun PruneEditor(days: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = days,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text("Days between prunes (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Retention runs after every backup. Pruning reclaims the space and is the slow part: leave this empty to prune every time, or 0 to leave it to a desktop.")
}

@Composable
private fun RetentionEditor(retention: RetentionPolicy, onChange: (RetentionPolicy) -> Unit) {
    Hint("Kept after each backup; the rest are pruned. Clear a field to drop that rule.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Last", retention.last, Modifier.weight(1f)) { onChange(retention.copy(last = it)) }
        NumberField("Hourly", retention.hourly, Modifier.weight(1f)) { onChange(retention.copy(hourly = it)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Daily", retention.daily, Modifier.weight(1f)) { onChange(retention.copy(daily = it)) }
        NumberField("Weekly", retention.weekly, Modifier.weight(1f)) { onChange(retention.copy(weekly = it)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Monthly", retention.monthly, Modifier.weight(1f)) { onChange(retention.copy(monthly = it)) }
        NumberField("Yearly", retention.yearly, Modifier.weight(1f)) { onChange(retention.copy(yearly = it)) }
    }
}

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text -> onChange(text.takeIf { it.isNotBlank() }?.toIntOrNull()?.coerceIn(0, 9999)) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}
