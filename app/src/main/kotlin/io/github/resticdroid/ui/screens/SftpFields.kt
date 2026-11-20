package io.github.resticdroid.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.resticdroid.restic.SftpLocation
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.components.ConfirmDialog
import io.github.resticdroid.ui.components.Hint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SftpFields(
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val path: String = "",
) {
    fun endpoint(): SftpLocation? = runCatching {
        SftpLocation(host.trim().removeSurrounding("[", "]"), port.toInt(), user.trim(), path)
            .also { it.uri() }
    }.getOrNull()

    companion object {
        fun from(uri: String?): SftpFields {
            val endpoint = uri?.let { runCatching { SftpLocation.parse(it) }.getOrNull() }
            return endpoint?.let { SftpFields(it.host, it.port.toString(), it.user, it.path) } ?: SftpFields()
        }
    }
}

@Composable
internal fun SftpEditor(
    fields: SftpFields,
    change: (SftpFields) -> Unit,
    credentials: MutableMap<String, String>,
    model: AppViewModel,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val endpoint = fields.endpoint()
    val identity = credentials[SftpLocation.IDENTITY].orEmpty()
    val pin = credentials[SftpLocation.PIN]
    var candidate by remember(endpoint?.address) { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SftpField("Host", fields.host, !busy) { change(fields.copy(host = it)) }
    SftpField("Port", fields.port, !busy) { change(fields.copy(port = it)) }
    SftpField("Username", fields.user, !busy) { change(fields.copy(user = it)) }
    SftpField("Repository path", fields.path, !busy) { change(fields.copy(path = it)) }
    Hint("Use phone for a folder in your SSH home, or /path for an absolute path.")

    if (identity.isBlank()) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    credentials[SftpLocation.IDENTITY] = model.generateSshIdentity()
                    message = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message
                } finally {
                    busy = false
                }
            }
        }) { Text("Generate SSH key") }
    } else {
        OutlinedButton(onClick = {
            val publicKey = identity.substringAfter(' ', "")
            if (publicKey.startsWith("ssh-ed25519 ")) {
                val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@OutlinedButton
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey))
                message = "Public key copied. Authorize it on the server."
            }
        }) { Text("Copy public key") }
    }

    if (endpoint?.accepts(pin) == true) Hint("Approved host key: " + pin?.substringAfter(' '))
    OutlinedButton(enabled = endpoint != null && !busy, onClick = {
        endpoint ?: return@OutlinedButton
        busy = true
        scope.launch {
            try {
                candidate = model.probeSshHost(endpoint)
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }) { Text("Verify host key") }
    candidate?.let { offered ->
        ConfirmDialog(
            title = "Trust this SSH host key?",
            body = "Compare this fingerprint with the one supplied by the server administrator.\n\n$offered",
            confirmLabel = "Trust",
            onConfirm = {
                if (endpoint?.accepts(offered) == true) credentials[SftpLocation.PIN] = offered
                candidate = null
            },
            onDismiss = { candidate = null },
        )
    }
    message?.let { Hint(it) }
}


@Composable
private fun SftpField(label: String, value: String, enabled: Boolean, update: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = update,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
