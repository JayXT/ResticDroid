package io.github.resticdroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.resticdroid.BuildConfig
import io.github.resticdroid.secret.BiometricGate
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.Screen
import io.github.resticdroid.ui.components.Hint
import io.github.resticdroid.ui.components.InfoCard
import io.github.resticdroid.ui.components.SectionHeader
import io.github.resticdroid.ui.components.SwitchRow
import io.github.resticdroid.work.RunLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    model: AppViewModel,
    navigator: Navigator,
    activity: FragmentActivity,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val config by model.config.collectAsStateWithLifecycle()
    val settings = config.settings
    val biometrics = remember { BiometricGate.availability(activity) }

    var hostname by remember(settings.hostname) { mutableStateOf(settings.hostname) }
    var showResticLicence by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) { RunLog.recent(15) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Security")
            SwitchRow(
                title = "Confirm before backing up or restoring",
                subtitle = "Fingerprint, or the repository password. Scheduled runs are never prompted.",
                checked = model.requireAuth(),
                onChange = { scope.launch { model.setRequireAuth(activity, it) } },
            )

            if (model.requireAuth() && biometrics != BiometricGate.Availability.AVAILABLE) {
                Hint("No fingerprint enrolled — the repository password will be asked for instead.")
            }

            SectionHeader("Snapshots")
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Hostname") },
                placeholder = { Text(android.os.Build.MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Names this device in snapshots. Empty uses the model.")
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = { model.saveSettings(settings.copy(hostname = hostname.trim())) },
            ) { Text("Save hostname") }

            SectionHeader("Configuration")
            InfoCard(
                "Plain text at ${model.configRoot()}\n\n" +
                    "Edit it with any text editor; the app follows. Passwords and API keys " +
                    "are kept in the Android keystore, not in these files."
            )

            SectionHeader("Recent runs")
            if (logs.isEmpty()) {
                Hint("Nothing has run yet.")
            } else {
                logs.forEach { log ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { navigator.go(Screen.Log(log.absolutePath)) }
                    ) {
                        Text(
                            log.name.removeSuffix(".log"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            SectionHeader("About")
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("ResticDroid ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "restic ${model.resticVersion()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showResticLicence = true },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "GNU GPL v3 or later. No trackers, and no connection to anything but your " +
                    "own repositories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    // restic is BSD-2 licensed and ships inside the APK, so its notice has to
    // be reachable from the app, not only from the source tree.
    if (showResticLicence) {
        AlertDialog(
            onDismissRequest = { showResticLicence = false },
            title = { Text("restic licence") },
            text = {
                Text(
                    model.resticLicence(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showResticLicence = false }) { Text("Close") }
            },
        )
    }
}
