package io.github.resticdroid.ui.screens

import io.github.resticdroid.ui.Glyphs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.resticdroid.config.Profile
import io.github.resticdroid.config.Schedule
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.Screen
import io.github.resticdroid.ui.components.EmptyState
import io.github.resticdroid.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    model: AppViewModel,
    navigator: Navigator,
    activity: FragmentActivity,
    scope: CoroutineScope,
) {
    val config by model.config.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val nextRuns by model.nextRuns.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Backup profiles") }) },
        floatingActionButton = {
            if (config.profiles.isNotEmpty()) {
                FloatingActionButton(onClick = { navigator.go(Screen.EditProfile(null)) }) {
                    Icon(Icons.Default.Add, contentDescription = "New backup profile")
                }
            }
        },
    ) { insets ->
        if (config.profiles.isEmpty()) {
            EmptyState(
                icon = Glyphs.Backup,
                title = "No backup profiles yet",
                body = "A profile says what to back up, where to send it, and when.",
                modifier = Modifier.padding(insets),
                action = {
                    ExtendedFloatingActionButton(
                        onClick = { navigator.go(Screen.EditProfile(null)) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Create a profile") },
                    )
                },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(config.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    destinationName = config.destination(profile.destinationId)?.name,
                    nextRun = nextRuns[profile.id],
                    running = progress[profile.id] != null,
                    progressFraction = progress[profile.id]?.fraction,
                    progressText = progress[profile.id]?.text,
                    onOpen = { navigator.go(Screen.EditProfile(profile.id)) },
                    onRun = {
                        scope.launch {
                            val authorised = model.authenticate(
                                activity,
                                "Start backup",
                                "Confirm it is you before ${profile.name} runs.",
                                destinationId = profile.destinationId,
                            )
                            if (authorised) model.runNow(profile.id) else model.say("Cancelled")
                        }
                    },
                    onStop = { model.stop(profile.id) },
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    destinationName: String?,
    nextRun: Long?,
    running: Boolean,
    progressFraction: Float?,
    progressText: String?,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val problems = profile.validate()

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle(profile, destinationName, problems),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problems.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (problems.isEmpty() && profile.schedule != Schedule.Manual) {
                        Text(
                            text = nextRun
                                ?.let { "Next run ${Formats.nextRun(it)}" }
                                ?: "Not scheduled",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (nextRun == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(Glyphs.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(onClick = onRun, enabled = problems.isEmpty()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Back up now")
                    }
                }
            }

            if (running) {
                Spacer(Modifier.height(12.dp))
                if (progressFraction != null) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                progressText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun subtitle(profile: Profile, destinationName: String?, problems: List<String>): String {
    if (problems.isNotEmpty()) return problems.joinToString("; ").replaceFirstChar(Char::uppercase)

    val where = destinationName ?: "unknown repository"
    val when_ = when (val s = profile.schedule) {
        Schedule.Manual -> "manual"
        is Schedule.Interval -> "every ${s.hours}h"
        is Schedule.Daily -> "daily at %02d:%02d".format(s.hour, s.minute)
    }
    val what = when {
        profile.paths.isEmpty() && profile.includeApps -> "installed apps"
        profile.includeApps -> "${profile.paths.size} folders + apps"
        profile.paths.size == 1 -> profile.paths.first().substringAfterLast('/')
        else -> "${profile.paths.size} folders"
    }
    val state = if (profile.enabled) "" else " · paused"
    return "$what → $where · $when_$state"
}
