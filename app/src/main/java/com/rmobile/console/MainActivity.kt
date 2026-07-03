package com.rmobile.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rmobile.console.ui.editor.EditorScreen
import com.rmobile.console.ui.theme.RConsoleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RConsoleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorScreen()
                }
            }
        }
    }
}
