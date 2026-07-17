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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var uninstallTarget by remember { mutableStateOf<String?>(null) }

    // The ViewModel outlives navigation, so re-resolve the engine/session and
    // reload the list whenever the screen appears (e.g. after changing the engine
    // in Settings, or installing on the backend).
    LaunchedEffect(Unit) { viewModel.onShown() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.projectName.isNotBlank()) "Packages · ${uiState.projectName}" else "Packages"
                    )
                },
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
            Text(
                if (uiState.engineIsLocal)
                    "On-device (Local) package library. Core tidyverse/easystats packages install offline; others download on demand."
                else
                    "Installed packages apply to the Remote engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.engineIsLocal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Share package library across projects",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Installs go to a library shared by all projects that opt in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.sharedLibrary,
                        onCheckedChange = { viewModel.setSharedLibrary(it) },
                    )
                }
            }
            com.rmobile.console.ui.swapPhaseLabel(uiState.swapPhase)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

            if (!uiState.engineIsLocal) {
                TextButton(onClick = viewModel::importLegacy) {
                    Text("Import packages from legacy library")
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { uninstallTarget = name }) {
                                Icon(Icons.Default.Delete, contentDescription = "Uninstall $name")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    uninstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("Uninstall $target?") },
            text = { Text("Removes $target from the shared library. Other packages that depend on it may stop loading.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(target)
                    uninstallTarget = null
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("Cancel") }
            },
        )
    }
}
