package com.rmobile.console.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
