package dev.scrcpyandroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.scrcpyandroid.App

private enum class Screen { LIST, SETTINGS, KEYS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            AppTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
                BackHandler(enabled = screen != Screen.LIST) {
                    screen = if (screen == Screen.KEYS) Screen.SETTINGS else Screen.LIST
                }
                when (screen) {
                    Screen.LIST -> DeviceListScreen(
                        registry = app.registry,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onMirror = { id ->
                            startActivity(Intent(this, MirrorActivity::class.java).putExtra("deviceId", id))
                        },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        repo = app.settings,
                        onBack = { screen = Screen.LIST },
                        onOpenKeys = { screen = Screen.KEYS },
                    )
                    Screen.KEYS -> KeyScreen(
                        keyStore = app.keyStore,
                        onBack = { screen = Screen.SETTINGS },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
