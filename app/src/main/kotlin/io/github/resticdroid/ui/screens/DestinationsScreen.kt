package io.github.resticdroid.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Glyphs
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.Screen
import io.github.resticdroid.ui.components.ConfirmDialog
import io.github.resticdroid.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationsScreen(
    model: AppViewModel,
    navigator: Navigator,
    activity: FragmentActivity,
    scope: CoroutineScope,
) {
    val config by model.config.collectAsStateWithLifecycle()
    var pruning by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Repositories") }) },
        floatingActionButton = {
            if (config.destinations.isNotEmpty()) {
                FloatingActionButton(onClick = { navigator.go(Screen.EditDestination(null)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add repository")
                }
            }
        },
    ) { insets ->
        if (config.destinations.isEmpty()) {
            EmptyState(
                icon = Glyphs.Repository,
                title = "No repositories yet",
                body = "Where restic keeps your encrypted snapshots: Backblaze B2, or a folder.",
                modifier = Modifier.padding(insets),
                action = {
                    ExtendedFloatingActionButton(
                        onClick = { navigator.go(Screen.EditDestination(null)) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Add a repository") },
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
            items(config.destinations, key = { it.id }) { destination ->
                val hasPassword = model.hasPassword(destination.id)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.go(Screen.EditDestination(destination.id)) }
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(destination.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                destination.uri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!hasPassword) {
                                Text(
                                    "No password stored — this repository cannot be opened",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(
                            onClick = { navigator.go(Screen.Snapshots(destination.id)) },
                            enabled = hasPassword,
                        ) {
                            Icon(Glyphs.History, contentDescription = "Browse snapshots")
                        }
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }, enabled = hasPassword) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Prune now") },
                                    onClick = { menu = false; pruning = destination.id },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    pruning?.let { id ->
        val name = config.destination(id)?.name.orEmpty()
        ConfirmDialog(
            title = "Prune $name?",
            body = "restic rewrites the repository's pack files to reclaim the space of " +
                "forgotten snapshots. It starts now, on whatever connection you are on, " +
                "and can move a lot of data over a long while. It runs in the background " +
                "and reports when it is done.",
            confirmLabel = "Prune",
            onConfirm = {
                pruning = null
                scope.launch { model.pruneNow(activity, id) }
            },
            onDismiss = { pruning = null },
        )
    }
}
