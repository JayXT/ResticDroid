package io.github.resticdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.resticdroid.restic.ResticSnapshot
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Glyphs
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.Screen
import io.github.resticdroid.ui.components.BackButton
import io.github.resticdroid.ui.components.EmptyState
import io.github.resticdroid.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotsScreen(
    model: AppViewModel,
    navigator: Navigator,
    destinationId: String,
    activity: FragmentActivity,
    scope: CoroutineScope,
) {
    val config by model.config.collectAsStateWithLifecycle()
    val destination = config.destination(destinationId)

    var snapshots by remember { mutableStateOf<List<ResticSnapshot>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf<ResticSnapshot?>(null) }
    var reload by remember(destinationId) { mutableStateOf(0) }

    // The unlock and the listing both live in the view model, so coming back
    // from a snapshot neither asks for a fingerprint again nor re-reads the
    // repository. reload is the way to ask for a fresh read on purpose.
    LaunchedEffect(destinationId, reload) {
        val allowed = model.unlock(
            activity,
            destinationId,
            "Browse snapshots",
            "Confirm it is you before opening ${destination?.name.orEmpty()}.",
        )
        if (!allowed) {
            navigator.back()
            return@LaunchedEffect
        }
        loading = true
        model.snapshots(destinationId, refresh = reload > 0)
            .onSuccess { snapshots = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destination?.name ?: "Snapshots") },
                navigationIcon = { BackButton(navigator) },
                actions = {
                    IconButton(onClick = { reload++ }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Read the repository again")
                    }
                },
            )
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                error != null -> EmptyState(
                    icon = Glyphs.History,
                    title = "Could not open the repository",
                    body = error.orEmpty(),
                )

                snapshots.isEmpty() -> EmptyState(
                    icon = Glyphs.History,
                    title = "No snapshots yet",
                    body = "Run a profile that points here and its first snapshot will appear.",
                )

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(snapshots, key = { it.id }) { snapshot ->
                        SnapshotCard(
                            snapshot,
                            onOpen = { navigator.go(Screen.Snapshot(destinationId, snapshot.id)) },
                            onRestore = { restoring = snapshot },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    restoring?.let { snapshot ->
        RestoreDialog(
            snapshot = snapshot,
            originalTarget = model.originalLocationTarget(),
            copyTarget = model.copyRestoreTarget(),
            onDismiss = { restoring = null },
            onConfirm = { target ->
                restoring = null
                scope.launch {
                    val allowed = model.authenticate(
                        activity,
                        "Restore files",
                        "Confirm it is you before restoring snapshot ${snapshot.shortId}.",
                        destinationId = destinationId,
                    )
                    if (!allowed) {
                        model.say("Cancelled")
                        return@launch
                    }
                    model.working("Restoring…") {
                        model.restore(destinationId, snapshot.id, target)
                    }
                        .onSuccess { model.say(it) }
                        .onFailure { model.say(it.message ?: "Restore failed") }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotCard(
    snapshot: ResticSnapshot,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Formats.snapshotTime(snapshot.time),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    snapshot.paths.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(snapshot.shortId)
                        append(" · ")
                        append(snapshot.hostname)
                        snapshot.summary?.let {
                            append(" · ")
                            append(Formats.bytes(it.totalBytesProcessed))
                        }
                        if (snapshot.tags.isNotEmpty()) {
                            append(" · ")
                            append(snapshot.tags.joinToString(", "))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

@Composable
private fun RestoreDialog(
    snapshot: ResticSnapshot,
    originalTarget: String,
    copyTarget: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var toOriginal by remember(snapshot) { mutableStateOf(false) }
    var target by remember(snapshot) { mutableStateOf(copyTarget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore ${snapshot.shortId}") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !toOriginal,
                        onClick = { toOriginal = false; target = copyTarget },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = {},
                    ) { Text("New folder") }
                    SegmentedButton(
                        selected = toOriginal,
                        onClick = { toOriginal = true; target = originalTarget },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = {},
                    ) { Text("In place") }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    if (toOriginal) {
                        "Files go back where they came from, replacing what is there now."
                    } else {
                        "Files are written into a folder of their own. Nothing already on " +
                            "the device is touched."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it; toOriginal = it.trim() == originalTarget },
                    label = { Text("Restore into") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target) },
                enabled = target.isNotBlank(),
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
