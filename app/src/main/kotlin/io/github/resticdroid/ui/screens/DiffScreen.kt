package io.github.resticdroid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.resticdroid.ui.AppViewModel
import io.github.resticdroid.ui.Navigator
import io.github.resticdroid.ui.components.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(
    model: AppViewModel,
    navigator: Navigator,
    destinationId: String,
    from: String,
    to: String,
) {
    var body by remember(from, to) { mutableStateOf("Comparing…") }

    LaunchedEffect(from, to) {
        model.diff(destinationId, from, to)
            .onSuccess { body = it }
            .onFailure { body = it.message ?: "Failed" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${from.take(8)} → ${to.take(8)}") },
                navigationIcon = { BackButton(navigator) },
            )
        },
    ) { insets ->
        val scroll = rememberScrollState()
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(body.lines()) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(scroll),
                )
            }
        }
    }
}
