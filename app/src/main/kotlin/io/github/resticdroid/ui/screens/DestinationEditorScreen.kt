package io.github.resticdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.Destination
import io.github.resticdroid.config.OptionPolicy
import io.github.resticdroid.restic.ResticBackend
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Glyphs
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.components.BackButton
import io.github.resticdroid.ui.components.ConfirmDialog
import io.github.resticdroid.ui.components.Hint
import io.github.resticdroid.ui.components.InfoCard
import io.github.resticdroid.ui.components.Picker
import io.github.resticdroid.ui.components.SectionHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class Mode { UseExisting, CreateNew }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationEditorScreen(
    model: AppViewModel,
    navigator: Navigator,
    destinationId: String?,
    activity: FragmentActivity,
    scope: CoroutineScope,
) {
    val config by model.config.collectAsStateWithLifecycle()
    val existing = destinationId?.let { config.destination(it) }
    val creating = existing == null

    var unlocked by remember(destinationId) { mutableStateOf(creating) }
    LaunchedEffect(destinationId) {
        if (unlocked) return@LaunchedEffect
        val allowed = model.authenticate(
            activity,
            "Open repository settings",
            "Confirm it is you before ${existing?.name.orEmpty()} is shown.",
            destinationId = destinationId,
        )
        if (allowed) unlocked = true else navigator.back()
    }
    if (!unlocked) return

    var name by remember(destinationId) { mutableStateOf(existing?.name ?: "") }
    var backend by remember(destinationId) { mutableStateOf(existing?.backend ?: ResticBackend.B2) }
    var location by remember(destinationId) { mutableStateOf(existing?.location ?: "") }
    var password by remember(destinationId) { mutableStateOf("") }
    var showPassword by remember(destinationId) { mutableStateOf(false) }
    var mode by remember(destinationId) { mutableStateOf(if (creating) Mode.CreateNew else Mode.UseExisting) }
    var busy by remember(destinationId) { mutableStateOf(false) }
    var outcome by remember(destinationId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(destinationId) { mutableStateOf(false) }

    val credentials = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(existing?.id, backend) {
        credentials.clear()
        backend.credentials.forEach { field ->
            credentials[field.key] = existing?.let { model.credential(it.id, field.key) }.orEmpty()
        }
    }

    fun draft(): Destination = Destination(
        id = existing?.id ?: ConfigPaths.uniqueId(
            ConfigPaths.destinationsDir(),
            name.ifBlank { backend.id },
            fallback = "repository",
        ),
        name = name.ifBlank { backend.displayName },
        backend = backend,
        location = location.trim(),
        settings = existing?.settings.orEmpty(),
        options = existing?.options.orEmpty(),
        unknown = existing?.unknown.orEmpty(),
    )

    val ready = location.isNotBlank() &&
        (password.isNotBlank() || (existing != null && model.hasPassword(existing.id))) &&
        backend.credentials.filterNot { it.optional }.all { credentials[it.key]?.isNotBlank() == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (creating) "Add repository" else name.ifBlank { "Repository" }) },
                navigationIcon = { BackButton(navigator) },
                actions = {
                    if (!creating) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
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

            existing?.rejectedOptions?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(12.dp))
                InfoCard(OptionPolicy.explain(it))
            }

            SectionHeader("Storage")
            Picker("Type", ResticBackend.entries.map { it to it.displayName }, backend) { backend = it }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Location") },
                singleLine = true,
                prefix = if (backend.scheme.isNotEmpty()) {
                    { Text(backend.scheme) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(backend.locationHint)

            if (backend.credentials.isNotEmpty()) {
                SectionHeader("Credentials")
                Hint("Stored in the Android keystore, not in the configuration files.")
                backend.credentials.forEach { field ->
                    SecretField(
                        label = field.label + if (field.optional) " (optional)" else "",
                        value = credentials[field.key].orEmpty(),
                        secret = field.secret,
                        onChange = { credentials[field.key] = it },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (backend == ResticBackend.B2) {
                    Hint(
                        "Use an Application Key scoped to one bucket, not the master key: it " +
                            "can be revoked on its own if the phone is lost."
                    )
                }
            }

            SectionHeader("Repository password")
            SecretField(
                label = if (creating) "Password" else "Password (leave blank to keep)",
                value = password,
                secret = !showPassword,
                onChange = { password = it },
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Glyphs.VisibilityOff else Glyphs.Visibility,
                            contentDescription = if (showPassword) "Hide" else "Show",
                        )
                    }
                },
            )
            if (!creating && model.hasPassword(existing.id)) {
                Hint("A password is stored. Leave this empty to keep it, or type a new one.")
            }
            InfoCard(
                "Everything is encrypted with this password before it leaves the device, and " +
                    "there is no recovery. Write it down somewhere that is not this phone."
            )

            SectionHeader("Repository")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == Mode.CreateNew,
                    onClick = { mode = Mode.CreateNew },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Create new") }
                SegmentedButton(
                    selected = mode == Mode.UseExisting,
                    onClick = { mode = Mode.UseExisting },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Use existing") }
            }
            Hint(
                when (mode) {
                    Mode.CreateNew -> "Runs 'restic init'. It refuses if a repository is already there."
                    Mode.UseExisting -> "Opens a repository that already exists, here or on another device."
                }
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    outcome = null
                    scope.launch {
                        val result = when (mode) {
                            Mode.CreateNew -> model.createRepository(draft(), password, credentials.toMap())
                            Mode.UseExisting -> model.testConnection(draft(), password, credentials.toMap())
                        }
                        outcome = result.fold({ it }, { it.message ?: "Failed" })
                        busy = false
                    }
                },
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Text(if (mode == Mode.CreateNew) "Create repository" else "Test connection")
            }

            outcome?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.endsWith(".") && !it.contains("error", ignoreCase = true)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    model.saveDestination(draft(), password.takeIf { it.isNotBlank() }, credentials.toMap())
                    navigator.back()
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Remove ${existing.name}?",
            body = "ResticDroid forgets the location and the stored password. The repository " +
                "itself and every snapshot in it stay exactly where they are — nothing " +
                "is deleted from the storage provider.",
            confirmLabel = "Remove",
            onConfirm = {
                model.deleteDestination(existing.id)
                confirmDelete = false
                navigator.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    secret: Boolean,
    onChange: (String) -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
        ),
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth(),
    )
}
