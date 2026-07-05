package com.rmobile.console.ui.editor

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.data.history.HistoryEntry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpenSettings: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showHistory by remember { mutableStateOf(false) }

    val syntaxColors = RSyntaxColors(
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        string = Color(0xFF2E7D32),
        number = Color(0xFF1565C0),
        keyword = MaterialTheme.colorScheme.primary,
    )
    val transformation = remember(syntaxColors) { RCodeVisualTransformation(syntaxColors) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("R Mobile") },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Run history")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.code,
                onValueChange = viewModel::onCodeChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("R script") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                visualTransformation = transformation,
            )

            Button(
                onClick = viewModel::runCode,
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Run")
                }
            }

            OutputPanel(uiState = uiState, modifier = Modifier.weight(1.2f))
        }
    }

    if (showHistory) {
        HistorySheet(
            history = uiState.history,
            onSelect = {
                viewModel.restoreFromHistory(it)
                showHistory = false
            },
            onClear = viewModel::clearHistory,
            onDismiss = { showHistory = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    history: List<HistoryEntry>,
    onSelect: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Run history", style = MaterialTheme.typography.titleMedium)
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }

            if (history.isEmpty()) {
                Text(
                    "No runs yet. Tap Run to start building history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(history) { entry ->
                        HistoryRow(
                            entry = entry,
                            label = dateFormat.format(Date(entry.timestampMillis)),
                            onClick = { onSelect(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = entry.code.trim().lineSequence().firstOrNull().orEmpty(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutputPanel(uiState: EditorUiState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            item {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (uiState.stdout.isNotBlank()) {
            item {
                Text(text = uiState.stdout, fontFamily = FontFamily.Monospace)
            }
        }
        if (uiState.stderr.isNotBlank()) {
            item {
                Text(
                    text = uiState.stderr,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        items(uiState.plotsBase64) { base64Png ->
            val bitmap = remember(base64Png) { decodeBase64Png(base64Png) }
            bitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "R plot output")
            }
        }
    }
}

private fun decodeBase64Png(base64: String) = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
