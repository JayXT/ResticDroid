package io.github.resticdroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.resticdroid.ui.components.EmptyState
import io.github.resticdroid.ui.components.PasswordDialog
import io.github.resticdroid.ui.screens.DestinationEditorScreen
import io.github.resticdroid.ui.screens.DestinationsScreen
import io.github.resticdroid.ui.screens.DiffScreen
import io.github.resticdroid.ui.screens.LogScreen
import io.github.resticdroid.ui.screens.ProfileEditorScreen
import io.github.resticdroid.ui.screens.ProfilesScreen
import io.github.resticdroid.ui.screens.SettingsScreen
import io.github.resticdroid.ui.screens.SnapshotScreen
import io.github.resticdroid.ui.screens.SnapshotsScreen
import kotlinx.coroutines.CoroutineScope

@Composable
fun ResticDroidUi(activity: FragmentActivity, scope: CoroutineScope) {
    val model: AppViewModel = viewModel()
    val navigator = rememberNavigator()
    val snackbar = remember { SnackbarHostState() }

    val granted by model.storageGranted.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val challenge by model.challenge.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            model.say(null)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifications = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(granted) {
            if (granted && activity.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    challenge?.let { PasswordDialog(it) }

    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!granted) {
            StorageGate(onGranted = model::refreshPermissions)
            return@Surface
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (navigator.current.isRoot()) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = navigator.current is Screen.Profiles,
                            onClick = { navigator.select(Screen.Profiles) },
                            icon = { Icon(Glyphs.Backup, contentDescription = null) },
                            label = { Text("Profiles") },
                        )
                        NavigationBarItem(
                            selected = navigator.current is Screen.Destinations,
                            onClick = { navigator.select(Screen.Destinations) },
                            icon = { Icon(Glyphs.Repository, contentDescription = null) },
                            label = { Text("Repositories") },
                        )
                        NavigationBarItem(
                            selected = navigator.current is Screen.Settings,
                            onClick = { navigator.select(Screen.Settings) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                        )
                    }
                }
            },
        ) { insets ->
            Box(Modifier.padding(insets)) {
                when (val screen = navigator.current) {
                    is Screen.Profiles ->
                        ProfilesScreen(model, navigator, activity, scope)

                    is Screen.Destinations ->
                        DestinationsScreen(model, navigator, activity, scope)

                    is Screen.Settings ->
                        SettingsScreen(model, navigator, activity, scope)

                    is Screen.EditProfile ->
                        ProfileEditorScreen(model, navigator, screen.profileId)

                    is Screen.EditDestination ->
                        DestinationEditorScreen(model, navigator, screen.destinationId, activity, scope)

                    is Screen.Snapshots ->
                        SnapshotsScreen(model, navigator, screen.destinationId, activity, scope)

                    is Screen.Snapshot ->
                        SnapshotScreen(model, navigator, screen.destinationId, screen.snapshotId, activity, scope)

                    is Screen.Diff ->
                        DiffScreen(model, navigator, screen.destinationId, screen.from, screen.to)

                    is Screen.Log ->
                        LogScreen(navigator, screen.path)
                }
            }
        }
    }
}

private fun Screen.isRoot(): Boolean =
    this is Screen.Profiles || this is Screen.Destinations || this is Screen.Settings

@Composable
private fun StorageGate(onGranted: () -> Unit) {
    val context = LocalContext.current
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) onGranted() }

    EmptyState(
        icon = Glyphs.Folder,
        title = "ResticDroid needs access to your files",
        body = "Backups run on a schedule, so it has to read your files without asking " +
            "each time. It reads only what your profiles list, and writes only to the " +
            "repositories you configure.",
        action = {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacyLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) {
                Text("Grant access")
            }
        },
    )
}
