package io.github.resticdroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.resticdroid.ui.PasswordChallenge

@Composable
fun PasswordDialog(challenge: PasswordChallenge) {
    var password by remember(challenge) { mutableStateOf("") }
    var wrong by remember(challenge) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { challenge.cancel() },
        title = { Text(challenge.title) },
        text = {
            Column {
                Text(challenge.subtitle, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        wrong = false
                    },
                    label = { Text("Repository password") },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text("That is not the password for this repository.") }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!challenge.submit(password)) wrong = true },
                enabled = password.isNotEmpty(),
            ) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = { challenge.cancel() }) { Text("Cancel") }
        },
    )
}
