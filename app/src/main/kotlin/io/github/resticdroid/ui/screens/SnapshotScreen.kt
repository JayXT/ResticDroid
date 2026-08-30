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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import io.github.resticdroid.restic.ResticSnapshot
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.Screen
import io.github.resticdroid.ui.components.BackButton
import io.github.resticdroid.ui.components.ConfirmDialog
import io.github.resticdroid.ui.components.Hint
import io.github.resticdroid.ui.components.SectionHeader
import io.github.resticdroid.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SnapshotScreen(
    model: AppViewModel,
    navigator: Navigator,
    destinationId: String,
    snapshotId: String,
    activity: FragmentActivity,
    scope: CoroutineScope,
) {
    var snapshot by remember(snapshotId) { mutableStateOf<ResticSnapshot?>(null) }
    var siblings by remember(snapshotId) { mutableStateOf<List<ResticSnapshot>>(emptyList()) }
    val cached = remember(snapshotId) { model.cachedFiles(snapshotId) }
    var files by remember(snapshotId) { mutableStateOf(cached?.files.orEmpty()) }
    var truncated by remember(snapshotId) { mutableStateOf(cached?.truncated == true) }
    var loadingFiles by remember(snapshotId) { mutableStateOf(false) }
    var error by remember(snapshotId) { mutableStateOf<String?>(null) }
    var confirmForget by remember(snapshotId) { mutableStateOf(false) }
    var choosingCompare by remember(snapshotId) { mutableStateOf(false) }

    LaunchedEffect(snapshotId) {
        model.snapshots(destinationId)
            .onSuccess { all ->
                siblings = all
                snapshot = all.firstOrNull { it.id == snapshotId || it.shortId == snapshotId }
            }
            .onFailure { error = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(snapshot?.shortId ?: "Snapshot") },
                navigationIcon = { BackButton(navigator) },
            )
        },
    ) { insets ->
        val snap = snapshot
        if (snap == null) {
            Column(Modifier.fillMaxSize().padding(insets).padding(16.dp)) {
                Text(error ?: "Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Text(Formats.snapshotTime(snap.time), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${snap.hostname} · ${snap.shortId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snap.summary?.let {
                    Text(
                        "${it.totalFilesProcessed} files · ${Formats.bytes(it.totalBytesProcessed)} · " +
                            "${Formats.bytes(it.dataAdded)} added",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (snap.tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        snap.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }

                SectionHeader("Paths")
                snap.paths.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosingCompare = true }) { Text("Compare") }
                    OutlinedButton(onClick = { confirmForget = true }) { Text("Forget") }
                }

                SectionHeader("Files")
                if (files.isEmpty() && !loadingFiles) {
                    Button(
                        onClick = {
                            loadingFiles = true
                            scope.launch {
                                model.files(destinationId, snap.id)
                                    .onSuccess { (list, cut) -> files = list; truncated = cut }
                                    .onFailure { error = it.message; model.say(it.message) }
                                loadingFiles = false
                            }
                        },
                    ) { Text("List files") }
                    Hint("Read from the repository on demand, so it is not paid for until asked.")
                }
                if (loadingFiles) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Reading…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // No key: the list is built once and never reordered, and two nodes
            // with the same path would crash a keyed list rather than merely look odd.
            items(files) { file ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        file.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (!file.directory) {
                        Text(
                            Formats.bytes(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }

            if (truncated) {
                item { Hint("Only the first files are listed. Restore to see everything.") }
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget this snapshot?",
            body = "The snapshot is removed from the repository. Its data stays until the " +
                "repository is pruned, and other snapshots keep whatever they share with it.",
            confirmLabel = "Forget",
            onConfirm = {
                confirmForget = false
                scope.launch {
                    val ok = model.authenticate(
                        activity,
                        "Forget snapshot",
                        "Confirm it is you before removing ${snapshot?.shortId.orEmpty()}.",
                        destinationId,
                    )
                    if (!ok) return@launch
                    model.forgetSnapshot(destinationId, snapshotId)
                        .onSuccess { model.say(it); navigator.back() }
                        .onFailure { model.say(it.message) }
                }
            },
            onDismiss = { confirmForget = false },
        )
    }

    if (choosingCompare) {
        AlertDialog(
            onDismissRequest = { choosingCompare = false },
            title = { Text("Compare with") },
            text = {
                LazyColumn {
                    items(siblings.filter { it.id != snapshotId }, key = { it.id }) { other ->
                        TextButton(
                            onClick = {
                                choosingCompare = false
                                navigator.go(Screen.Diff(destinationId, other.id, snapshotId))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${Formats.snapshotTime(other.time)}  ${other.shortId}",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingCompare = false }) { Text("Cancel") } },
        )
    }
}

