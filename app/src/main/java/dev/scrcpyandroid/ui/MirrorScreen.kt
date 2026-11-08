package dev.scrcpyandroid.ui

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.scrcpyandroid.R
import dev.scrcpyandroid.scrcpy.fitCenter
import kotlin.math.roundToInt

private const val KEYCODE_BACK = 4

/**
 * Fullscreen mirror: black background, the video SurfaceView fitted (never stretched) inside it,
 * a touch layer over the whole area, the key bar and an invisible [RemoteInputView].
 * [onFinish] closes the screen (the caller finishes the activity). [onSurfaceDestroyed] runs on the
 * main thread from SurfaceHolder.Callback.surfaceDestroyed and must not return before the decoder
 * has let go of the surface.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MirrorScreen(
    vm: MirrorViewModel,
    onExit: () -> Unit,
    onFinish: () -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    val size by vm.videoSize.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val missing by vm.missingDevice.collectAsStateWithLifecycle()
    var box by remember { mutableStateOf(IntSize.Zero) }
    var inputView by remember { mutableStateOf<RemoteInputView?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler { vm.sendKey(KEYCODE_BACK) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                box = it
                vm.mapper.viewW = it.width
                vm.mapper.viewH = it.height
            },
    ) {
        // Until the first size arrives the SurfaceView fills the box, so that its surface gets
        // created (the session starts from surfaceCreated).
        val s = size
        val rect = if (s != null) fitCenter(s.displayW, s.displayH, box.width, box.height) else null
        val density = LocalDensity.current
        val surfaceModifier = if (rect != null && rect.width > 0f && rect.height > 0f) {
            with(density) {
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(rect.width.roundToInt().toDp(), rect.height.roundToInt().toDp())
            }
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = vm.onSurfaceCreated(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed()
                    })
                }
            },
            modifier = surfaceModifier,
        )

        AndroidView(
            factory = { ctx -> RemoteInputView(ctx, vm::send).also { inputView = it } },
            modifier = with(density) { Modifier.size(1.toDp()) },
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInteropFilter { ev ->
                    vm.router.onEvent(ev.actionMasked, ev.actionIndex, ev.pointers()).forEach(vm::send)
                    true
                },
        )

        KeyBar(
            landscape = landscape,
            onKey = vm::sendKey,
            onKeyboard = { inputView?.showKeyboard() },
            onExit = onExit,
        )
    }

    if (missing) {
        AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.mirror_no_device_title)) },
            text = { Text(stringResource(R.string.mirror_no_device)) },
            confirmButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.ok)) } },
        )
    }

    ended?.let { e ->
        AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(e.reason) },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            text = e.serverLog.ifBlank { stringResource(R.string.mirror_no_log) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun MotionEvent.pointers(): List<TouchRouter.Pointer> =
    List(pointerCount) { i -> TouchRouter.Pointer(getPointerId(i), getX(i), getY(i), getPressure(i)) }
