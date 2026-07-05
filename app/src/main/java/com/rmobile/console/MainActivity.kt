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
import com.rmobile.console.ui.editor.EditorScreen
import com.rmobile.console.ui.packages.PackagesScreen
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

private enum class Screen { EDITOR, SETTINGS, PACKAGES }

@Composable
private fun AppRoot() {
    // Lightweight in-app navigation — the app has two screens, not enough to
    // justify a navigation library.
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }

    when (screen) {
        Screen.EDITOR -> EditorScreen(
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenPackages = { screen = Screen.PACKAGES },
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.EDITOR })
        Screen.PACKAGES -> PackagesScreen(onBack = { screen = Screen.EDITOR })
    }
}
