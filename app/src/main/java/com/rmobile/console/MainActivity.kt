package com.rmobile.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.ui.data.DataScreen
import com.rmobile.console.ui.editor.EditorScreen
import com.rmobile.console.ui.editor.EditorViewModel
import com.rmobile.console.ui.packages.PackagesScreen
import com.rmobile.console.ui.preview.PreviewScreen
import com.rmobile.console.ui.projects.ProjectsScreen
import com.rmobile.console.ui.settings.SettingsScreen
import com.rmobile.console.ui.theme.RConsoleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RConsoleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS, DATA, PREVIEW }

@Composable
private fun AppRoot() {
    // Lightweight in-app navigation — a small screen switch, not enough to
    // justify a navigation library. One EditorViewModel is hoisted here so the
    // editor and the project-library screen share the same project state.
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    var previewSource by rememberSaveable { mutableStateOf("file") }
    var previewName by rememberSaveable { mutableStateOf("") }
    val editorViewModel: EditorViewModel = viewModel()

    when (screen) {
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
            onOpenProjects = { screen = Screen.PROJECTS },
            onOpenData = { screen = Screen.DATA },
            onPreviewObject = { name ->
                previewSource = "object"; previewName = name; screen = Screen.PREVIEW
            },
            viewModel = editorViewModel,
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.EDITOR })
        Screen.PACKAGES -> PackagesScreen(onBack = {
            screen = Screen.EDITOR
            // Installing/uninstalling a package changes the session's symbols; refresh.
            editorViewModel.refreshSymbols()
            // The shared-library toggle lives on the Packages screen (its own ViewModel);
            // re-sync it so the editor's next run resolves the right library key.
            editorViewModel.refreshActiveProjectSettings()
        })
        Screen.PROJECTS -> ProjectsScreen(
            viewModel = editorViewModel,
            onOpenEditor = { screen = Screen.EDITOR },
        )
        Screen.DATA -> DataScreen(
            onBack = { screen = Screen.EDITOR },
            onInsertFileName = { name ->
                editorViewModel.insertText(name)
                screen = Screen.EDITOR
            },
            onPreviewFile = { name ->
                previewSource = "file"; previewName = name; screen = Screen.PREVIEW
            },
        )
        Screen.PREVIEW -> PreviewScreen(
            source = previewSource,
            name = previewName,
            onBack = { screen = if (previewSource == "file") Screen.DATA else Screen.EDITOR },
        )
    }
}
