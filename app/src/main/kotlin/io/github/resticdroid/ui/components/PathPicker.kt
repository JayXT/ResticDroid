package io.github.resticdroid.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Select actual paths that the native restic process can open. */
@Composable
fun PathPicker(filesOnly: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var directory by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    val listing by produceState<Result<List<File>>?>(null, directory) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val entries = directory.listFiles() ?: error("Cannot read this folder")
                entries.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (filesOnly) "Select an exclusion list" else "Select a file or folder") },
        text = {
            Column {
                Text(directory.absolutePath)
                directory.parentFile?.let { parent ->
                    TextButton(onClick = { directory = parent }) { Text("Up") }
                }
                when {
                    listing == null -> Text("Loading…")
                    listing?.isFailure == true -> Text("Cannot read this folder")
                    listing?.getOrNull().isNullOrEmpty() -> Text("Empty folder")
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(listing?.getOrNull().orEmpty(), key = { it.absolutePath }) { file ->
                            Text(
                                text = file.name + if (file.isDirectory) "/" else "",
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (file.isDirectory) directory = file
                                    else onSelect(file.absolutePath)
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!filesOnly) {
                TextButton(
                    onClick = { onSelect(directory.absolutePath) },
                    enabled = listing?.isSuccess == true,
                ) { Text("Use this folder") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
