package com.rmobile.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rmobile.console.ui.editor.EditorScreen
import com.rmobile.console.ui.editor.EditorViewModel
import com.rmobile.console.ui.packages.PackagesScreen
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

private enum class Screen { EDITOR, SETTINGS, PACKAGES, PROJECTS }

@Composable
private fun AppRoot() {
    // Lightweight in-app navigation — a small screen switch, not enough to
    // justify a navigation library. One EditorViewModel is hoisted here so the
    // editor and the project-library screen share the same project state.
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    val editorViewModel: EditorViewModel = viewModel()

    when (screen) {
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
            onOpenProjects = { screen = Screen.PROJECTS },
            viewModel = editorViewModel,
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.EDITOR })
        Screen.PACKAGES -> PackagesScreen(onBack = {
            screen = Screen.EDITOR
            // Installing/uninstalling a package changes the session's symbols; refresh.
            editorViewModel.refreshSymbols()
        })
        Screen.PROJECTS -> ProjectsScreen(
            viewModel = editorViewModel,
            onOpenEditor = { screen = Screen.EDITOR },
        )
    }
}
