package com.rmobile.console.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    onBack: () -> Unit,
    viewModel: PackagesViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packages") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.packageName,
                    onValueChange = viewModel::onPackageNameChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("CRAN package") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                Button(
                    onClick = viewModel::install,
                    enabled = !uiState.installing && uiState.packageName.isNotBlank(),
                ) {
                    if (uiState.installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("Install")
                    }
                }
            }

            uiState.message?.let { message ->
                Text(
                    text = message,
                    color = if (uiState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (uiState.log.isNotBlank()) {
                Text(text = uiState.log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            Text("Installed", style = MaterialTheme.typography.titleMedium)
            if (uiState.installed.isEmpty()) {
                Text(
                    "No packages installed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.installed) { name ->
                        Text(text = name, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
