package com.rmobile.console.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.data.settings.ExecutionEngineChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Default engine for new projects", style = MaterialTheme.typography.titleMedium)
            Text(
                "New projects start on this engine. Local runs R on your device with WebR — your code and data never leave the phone; Remote sends code to the backend for full package compatibility. Change an existing project's engine from the editor's ⋮ menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.executionEngine == ExecutionEngineChoice.LOCAL,
                    onClick = { viewModel.setEngine(ExecutionEngineChoice.LOCAL) },
                    label = { Text("Local (on-device)") },
                )
                FilterChip(
                    selected = uiState.executionEngine == ExecutionEngineChoice.REMOTE,
                    onClick = { viewModel.setEngine(ExecutionEngineChoice.REMOTE) },
                    label = { Text("Remote (backend)") },
                )
            }
            Text(
                "Each project keeps its own workspace, installed packages, and data files — on both engines, isolated per project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Backend", style = MaterialTheme.typography.titleMedium)
            Text(
                "The R execution service the app sends code to. Changes apply to the next run — no rebuild needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                singleLine = true,
                isError = uiState.urlError != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                ),
                supportingText = {
                    val error = uiState.urlError
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Default: ${uiState.defaultBaseUrl}")
                    }
                },
            )

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key (optional)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                supportingText = {
                    Text("Sent as the X-API-Key header. Leave blank if the backend has no auth.")
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = viewModel::resetToDefault) {
                    Text("Reset to default")
                }
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }

            val testing = uiState.connectionTest is ConnectionTest.Testing
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Test connection")
                }
            }

            when (val result = uiState.connectionTest) {
                is ConnectionTest.Ok -> Text(
                    result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ConnectionTest.Failed -> Text(
                    result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            if (uiState.saved) {
                Text(
                    "Saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
