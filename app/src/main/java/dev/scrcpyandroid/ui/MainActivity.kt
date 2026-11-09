package dev.scrcpyandroid.ui

import android.content.Context
import dev.scrcpyandroid.LanguageStore
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LanguageStore.wrap(newBase))

    /** Debounces double taps on a DEVICE row, which would otherwise start two mirror activities. */
    private var lastMirrorAt = 0L

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
                        onMirror = ::openMirror,
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        repo = app.settings,
                        language = LanguageStore.get(this),
                        onLanguageChange = { lang ->
                            LanguageStore.set(this, lang)
                            recreate()
                        },
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

    private fun openMirror(id: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMirrorAt < MIRROR_DEBOUNCE_MS) return
        lastMirrorAt = now
        startActivity(Intent(this, MirrorActivity::class.java).putExtra(MirrorActivity.EXTRA_DEVICE_ID, id))
    }

    /**
     * singleTask: USB_DEVICE_ATTACHED (manifest filter) is delivered here instead of stacking
     * another list. Nothing to do: [dev.scrcpyandroid.devices.UsbMonitor] handles the attach.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private companion object {
        const val MIRROR_DEBOUNCE_MS = 1000L
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
