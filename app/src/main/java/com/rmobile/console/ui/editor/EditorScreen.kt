package com.rmobile.console.ui.editor

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.ui.editor.completion.CompletionContext
import com.rmobile.console.ui.editor.completion.CompletionOps
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A token the quick-insert bar can drop at the cursor. [caret] is where the
 *  caret lands within the inserted text (e.g. 1 to sit inside `()`). */
private data class InsertToken(val label: String, val text: String, val caret: Int = -1)

private val quickInsertTokens = listOf(
    InsertToken("<-", "<- "),
    InsertToken("|>", "|> "),
    InsertToken("%>%", "%>% "),
    InsertToken("%in%", " %in% "),
    InsertToken("( )", "()", caret = 1),
    InsertToken("[ ]", "[]", caret = 1),
    InsertToken("{ }", "{}", caret = 1),
    InsertToken("c()", "c()", caret = 2),
    InsertToken("$", "$"),
    InsertToken("<<-", "<<- "),
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(
    onOpenSettings: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenProjects: () -> Unit = {},
    viewModel: EditorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAddFile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }

    // Local, cursor-aware editor state. Synced from uiState.code so history /
    // saved-script loads (which change code in the ViewModel) update the field.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(uiState.code, TextRange(uiState.code.length)))
    }
    LaunchedEffect(uiState.code) {
        if (uiState.code != field.text) {
            field = TextFieldValue(uiState.code, TextRange(uiState.code.length))
        }
    }

    val insert: (InsertToken) -> Unit = { token ->
        val result = insertAt(
            text = field.text,
            selStart = field.selection.start,
            selEnd = field.selection.end,
            insert = token.text,
            caret = if (token.caret >= 0) token.caret else token.text.length,
        )
        field = TextFieldValue(result.text, TextRange(result.cursor))
        viewModel.onCodeChanged(result.text)
    }

    // Autocomplete suggestions, computed off the UI thread and debounced, from the
    // token under the cursor against the current symbol set. Re-derives when the
    // symbol set changes.
    var strip by remember { mutableStateOf(StripState(emptyList(), "")) }
    val symbols = uiState.completionSymbols
    LaunchedEffect(symbols) {
        snapshotFlow { field }
            .debounce(120)
            .map { f ->
                if (!f.selection.collapsed) {
                    StripState(emptyList(), "")
                } else {
                    val ctx = CompletionContext(f.text, f.selection.end)
                    val prefix = CompletionOps.currentPrefix(ctx)
                    StripState(CompletionOps.suggest(prefix, symbols, limit = 30), prefix)
                }
            }
            .flowOn(Dispatchers.Default)
            .collect { strip = it }
    }

    val applyCompletion: (String) -> Unit = { symbol ->
        val ctx = CompletionContext(field.text, field.selection.end)
        CompletionOps.tokenRange(ctx)?.let { range ->
            val result = replaceRange(field.text, range, symbol)
            field = TextFieldValue(result.text, TextRange(result.cursor))
            viewModel.onCodeChanged(result.text)
        }
    }

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
                    IconButton(onClick = { showSaved = true }) {
                        Icon(Icons.Default.Star, contentDescription = "Saved scripts")
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Run history")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Reset session") },
                            onClick = {
                                menuOpen = false
                                showResetConfirm = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Packages") },
                            onClick = {
                                menuOpen = false
                                onOpenPackages()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Projects") },
                            onClick = {
                                menuOpen = false
                                onOpenProjects()
                            },
                        )
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
            FileSwitcher(
                files = uiState.project.files.map { it.name },
                activeFile = uiState.project.activeFileName,
                entryFile = uiState.project.entryFileName,
                onSwitch = viewModel::switchFile,
                onAdd = { showAddFile = true },
                onSetEntry = viewModel::setEntry,
                onRename = { renameTarget = it },
                onDelete = viewModel::deleteFile,
            )

            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    viewModel.onCodeChanged(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("R script") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                visualTransformation = transformation,
            )

            SuggestionStrip(
                strip = strip,
                onPick = applyCompletion,
                onHelp = viewModel::showHelp,
            )

            QuickInsertBar(onInsert = insert)

            Button(
                onClick = viewModel::runCode,
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Run ${uiState.project.entryFileName}")
                }
            }

            if (uiState.workspaceObjects.isNotEmpty()) {
                Text(
                    text = "Workspace: ${uiState.workspaceObjects.joinToString(", ")} " +
                        "(${uiState.workspaceObjects.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
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

    if (showSaved) {
        SavedScriptsSheet(
            scripts = uiState.savedScripts,
            canSaveCurrent = field.text.isNotBlank(),
            onSaveCurrent = { showSaveDialog = true },
            onLoad = {
                viewModel.loadScript(it)
                showSaved = false
            },
            onDelete = { viewModel.deleteScript(it.id) },
            onDismiss = { showSaved = false },
        )
    }

    if (showSaveDialog) {
        SaveScriptDialog(
            suggestedName = suggestedName(field.text),
            onConfirm = {
                viewModel.saveCurrentScript(it)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset session?") },
            text = {
                Text(
                    "This clears the R workspace on the backend — saved variables and " +
                        "attached packages are lost. Your saved scripts are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSession()
                        showResetConfirm = false
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showAddFile) {
        FileNameDialog(
            title = "New file",
            initial = "",
            onConfirm = {
                viewModel.addFile(it)
                showAddFile = false
            },
            onDismiss = { showAddFile = false },
        )
    }
    renameTarget?.let { target ->
        FileNameDialog(
            title = "Rename $target",
            initial = target,
            onConfirm = {
                viewModel.renameFile(target, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun QuickInsertBar(onInsert: (InsertToken) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        quickInsertTokens.forEach { token ->
            OutlinedButton(
                onClick = { onInsert(token) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(token.label, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Suggestions + the identifier under the cursor, for the autocomplete strip. */
private data class StripState(val suggestions: List<String>, val token: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionStrip(
    strip: StripState,
    onPick: (String) -> Unit,
    onHelp: (String) -> Unit,
) {
    if (strip.suggestions.isEmpty() && strip.token.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (strip.token.isNotEmpty()) {
            item {
                AssistChip(
                    onClick = { onHelp(strip.token) },
                    label = { Text("? ${strip.token}") },
                )
            }
        }
        items(strip.suggestions, key = { it }) { symbol ->
            // combinedClickable is the sole tap/long-press source; AssistChip's own
            // onClick is a no-op so a single tap can't fire onPick twice.
            AssistChip(
                onClick = {},
                modifier = Modifier.combinedClickable(
                    onClick = { onPick(symbol) },
                    onLongClick = { onHelp(symbol) },
                ),
                label = { Text(symbol, fontFamily = FontFamily.Monospace) },
            )
        }
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
                verticalAlignment = Alignment.CenterVertically,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedScriptsSheet(
    scripts: List<SavedScript>,
    canSaveCurrent: Boolean,
    onSaveCurrent: () -> Unit,
    onLoad: (SavedScript) -> Unit,
    onDelete: (SavedScript) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Saved scripts", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onSaveCurrent, enabled = canSaveCurrent) { Text("Save current") }
            }

            if (scripts.isEmpty()) {
                Text(
                    "No saved scripts yet. Write some R and tap \"Save current\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(scripts) { script ->
                        SavedScriptRow(
                            script = script,
                            label = dateFormat.format(Date(script.updatedAt)),
                            onClick = { onLoad(script) },
                            onDelete = { onDelete(script) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedScriptRow(
    script: SavedScript,
    label: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = script.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                text = script.code.trim().lineSequence().firstOrNull().orEmpty(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete script")
        }
    }
}

@Composable
private fun SaveScriptDialog(
    suggestedName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save script") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OutputPanel(uiState: EditorUiState, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var zoomedPlot by remember { mutableStateOf<ImageBitmap?>(null) }
    val consoleText = buildString {
        if (uiState.stdout.isNotBlank()) append(uiState.stdout)
        if (uiState.stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(uiState.stderr)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        if (uiState.timedOut) {
            item { TimeoutBanner(uiState.errorMessage) }
        } else if (uiState.errorMessage != null) {
            item {
                Text(
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        if (consoleText.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(consoleText)) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Copy output")
                }
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
        items(uiState.tables) { table ->
            RTableView(table)
        }
        items(uiState.plotsBase64) { base64Png ->
            val bitmap = remember(base64Png) { decodeBase64Png(base64Png) }
            bitmap?.let {
                Column {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "R plot output",
                        modifier = Modifier.clickable { zoomedPlot = it.asImageBitmap() },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = { sharePlotPng(context, base64Png) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share plot")
                        }
                    }
                }
            }
        }
    }

    zoomedPlot?.let { bmp -> ZoomablePlotDialog(bmp) { zoomedPlot = null } }
}

@Composable
private fun ZoomablePlotDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset += panChange
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Zoomed plot",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(state),
            )
        }
    }
}

@Composable
private fun TimeoutBanner(message: String?) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message ?: "Execution timed out.",
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** A sensible default name for a script: its first meaningful line. */
private fun suggestedName(code: String): String {
    val firstLine = code.trim().lineSequence().firstOrNull().orEmpty()
    return firstLine.removePrefix("#").trim().take(40).ifBlank { "Untitled script" }
}

private fun decodeBase64Png(base64: String) = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSwitcher(
    files: List<String>,
    activeFile: String,
    entryFile: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onSetEntry: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files) { name ->
            var menuOpen by remember { mutableStateOf(false) }
            Column {
                FilterChip(
                    selected = name == activeFile,
                    onClick = { onSwitch(name) },
                    label = {
                        Text(if (name == entryFile) "▶ $name" else name, fontFamily = FontFamily.Monospace)
                    },
                    trailingIcon = {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "File menu")
                        }
                    },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Set as entry") }, onClick = { menuOpen = false; onSetEntry(name) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename(name) })
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        enabled = files.size > 1,
                        onClick = { menuOpen = false; onDelete(name) },
                    )
                }
            }
        }
        item {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add file")
            }
        }
    }
}

@Composable
private fun FileNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name (e.g. helpers.R)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
