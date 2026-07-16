package com.rmobile.console.ui.data

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.data.datafiles.DataUpload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onInsertFileName: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    viewModel: DataViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingLargeUpload by remember { mutableStateOf<DataUpload?>(null) }

    LaunchedEffect(Unit) { viewModel.onShown() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            var name = "upload.bin"
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            val fileUpload = DataUpload(name, size) {
                context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
            }
            if (size > 0 && viewModel.warnLargeFile(size)) pendingLargeUpload = fileUpload
            else viewModel.upload(fileUpload)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.projectName.isBlank()) "Data" else "Data — ${state.projectName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                if (state.engineIsLocal) "On-device (Local) data files — read them in code by name, e.g. read.csv(\"name\")."
                else "These files apply to the Remote engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            com.rmobile.console.ui.swapPhaseLabel(state.swapPhase)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.uploading) {
                    Text("Upload file")
                }
                if (state.uploading) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            if (state.files.isEmpty() && !state.isLoading) {
                Text(
                    "No data files yet. Upload one, then read it in code by name, e.g. read.csv(\"name\").",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.files, key = { it.name }) { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(formatByteSize(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onPreviewFile(file.name) }) { Text("View") }
                        Button(onClick = { onInsertFileName("\"${file.name}\"") }) { Text("Insert") }
                        IconButton(onClick = { viewModel.delete(file.name) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
                        }
                    }
                }
            }
        }
    }

    pendingLargeUpload?.let { up ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLargeUpload = null },
            title = { Text("Large file") },
            text = { Text("This file is over 200 MB. The on-device engine keeps files in memory and may run out of memory — the Remote engine handles large data better. Upload anyway?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.upload(up); pendingLargeUpload = null }) { Text("Upload anyway") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingLargeUpload = null }) { Text("Cancel") } },
        )
    }
}
