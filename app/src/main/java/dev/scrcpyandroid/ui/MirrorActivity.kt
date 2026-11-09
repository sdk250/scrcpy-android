package dev.scrcpyandroid.ui

import android.content.Context
import dev.scrcpyandroid.LanguageStore
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.scrcpyandroid.App
import dev.scrcpyandroid.BuildConfig
import dev.scrcpyandroid.devices.DeviceState
import dev.scrcpyandroid.scrcpy.ControlMessages
import dev.scrcpyandroid.scrcpy.ScrcpySession
import dev.scrcpyandroid.scrcpy.SessionEvent
import dev.scrcpyandroid.scrcpy.TouchMapper
import dev.scrcpyandroid.scrcpy.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Fullscreen mirroring of one device. Expects extra [EXTRA_DEVICE_ID]. */
class MirrorActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LanguageStore.wrap(newBase))

    private val vm: MirrorViewModel by viewModels {
        val app = application as App
        val id = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        viewModelFactory { initializer { MirrorViewModel(app, id) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Orientation follows the remote display; collected on the main thread.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.videoSize.collect { s ->
                    if (s != null) {
                        requestedOrientation = if (s.displayW > s.displayH) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MirrorScreen(
                    vm = vm,
                    onExit = {
                        vm.stopByUser()
                        finish()
                    },
                    onFinish = ::finish,
                    onSurfaceDestroyed = {
                        if (isChangingConfigurations) {
                            // Recreation for a config change not listed in the manifest: the
                            // retained ViewModel restarts the session on the new surface.
                            vm.detachForRecreation()
                        } else {
                            vm.onSurfaceDestroyed()
                            finish()
                        }
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Plan decision: leaving the foreground ends mirroring (no decoding without a surface).
        if (!isChangingConfigurations) {
            vm.stopByUser()
            finish()
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}

/**
 * Owns the [ScrcpySession] of one [MirrorActivity]. All state used by the UI (sizes, [mapper],
 * [router]) is updated on the main thread only.
 */
class MirrorViewModel(private val app: App, private val deviceId: String) : ViewModel() {
    val mapper = TouchMapper()
    val router = TouchRouter(mapper)

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: ScrcpySession? = null
    private var eventsJob: Job? = null

    /** Set before any user-initiated stop, so its Ended event shows no dialog. */
    @Volatile private var userStopped = false

    private val _videoSize = MutableStateFlow<SessionEvent.VideoSize?>(null)
    val videoSize: StateFlow<SessionEvent.VideoSize?> = _videoSize

    private val _ended = MutableStateFlow<SessionEvent.Ended?>(null)
    /** Set when the session ended on its own (not by the user). */
    val ended: StateFlow<SessionEvent.Ended?> = _ended

    private val _missingDevice = MutableStateFlow(!deviceAvailable())
    val missingDevice: StateFlow<Boolean> = _missingDevice

    private fun deviceAvailable(): Boolean =
        app.registry.connection(deviceId) != null &&
            app.registry.devices.value.any { it.id == deviceId && it.state == DeviceState.DEVICE }

    /** Main thread. Starts the session on the first surface; later surfaces are ignored. */
    fun onSurfaceCreated(surface: Surface) {
        if (session != null || userStopped || _missingDevice.value) return
        val conn = app.registry.connection(deviceId)
        if (conn == null || !deviceAvailable()) {
            _missingDevice.value = true
            return
        }
        val s = ScrcpySession(
            conn = conn,
            options = app.settings.load(),
            version = BuildConfig.SCRCPY_VERSION,
            serverBytes = { app.assets.open(SERVER_ASSET).use { it.readBytes() } },
            scope = sessionScope,
        )
        session = s
        // viewModelScope runs on Dispatchers.Main.immediate: all UI state changes on the main thread.
        eventsJob = viewModelScope.launch { s.events.collect(::onEvent) }
        sessionScope.launch { s.start { codec, onSize -> VideoDecoder(surface, codec, onSize) } }
    }

    /**
     * Main thread, from surfaceDestroyed: stops the session and blocks until the decoder has let go
     * of the surface (bounded), as the surface must not disappear under the codec.
     */
    fun onSurfaceDestroyed() {
        stopByUser()
        session?.let(::awaitReleasedBlocking)
    }

    /**
     * Main thread, from surfaceDestroyed while the activity is being recreated: the current session
     * (bound to the dying surface) is stopped silently and forgotten, so the next
     * [onSurfaceCreated] — from the recreated activity — starts a fresh one instead of leaving a
     * black screen.
     */
    fun detachForRecreation() {
        val s = session ?: return
        eventsJob?.cancel() // its Ended(Stopped) must not show a dialog
        eventsJob = null
        s.stop()
        awaitReleasedBlocking(s)
        session = null
    }

    private fun awaitReleasedBlocking(s: ScrcpySession) {
        runBlocking { withTimeoutOrNull(RELEASE_TIMEOUT_MS) { s.awaitReleased() } }
    }

    fun stopByUser() {
        userStopped = true
        session?.stop()
    }

    fun send(msg: ByteArray) {
        session?.send(msg)
    }

    /** Sends a key press (down + up). */
    fun sendKey(keycode: Int) {
        send(ControlMessages.keycode(0, keycode))
        send(ControlMessages.keycode(1, keycode))
    }

    private fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.VideoSize -> {
                // Touch coordinates live in session space; layout uses the display size.
                mapper.videoW = e.sessionW
                mapper.videoH = e.sessionH
                _videoSize.value = e
            }
            is SessionEvent.Ended -> if (!userStopped) _ended.value = e
        }
    }

    override fun onCleared() {
        session?.stop()
        sessionScope.cancel()
    }

    companion object {
        private const val SERVER_ASSET = "scrcpy-server"
        private const val RELEASE_TIMEOUT_MS = 2000L
    }
}
