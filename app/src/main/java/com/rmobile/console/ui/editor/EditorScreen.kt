package com.rmobile.console.ui.editor

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("R Mobile") }) },
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
