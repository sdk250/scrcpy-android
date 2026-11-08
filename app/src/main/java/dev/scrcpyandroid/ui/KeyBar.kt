package dev.scrcpyandroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scrcpyandroid.R
import kotlin.math.roundToInt

/** Android keycodes sent by the key bar. */
private object Keys {
    const val BACK = 4
    const val HOME = 3
    const val APP_SWITCH = 187
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
}

private data class BarButton(@StringRes val label: Int, val onClick: () -> Unit)

private val HANDLE_LONG = 56.dp
private val HANDLE_SHORT = 14.dp
private val BAR_THICKNESS = 48.dp
private val BAR_BG = Color(0x99000000)

/**
 * Collapsible key bar on the screen edge (bottom in portrait, right in landscape) with a draggable
 * semi-transparent handle; tapping the handle toggles the bar. [onKey] gets a keycode to send
 * (as a down/up pair) by the caller.
 */
@Composable
fun KeyBar(
    landscape: Boolean,
    onKey: (Int) -> Unit,
    onKeyboard: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Handle position along its edge, as a fraction of the free span (0 = start, 1 = end).
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    val buttons = remember(onKey, onKeyboard, onExit) {
        listOf(
            BarButton(R.string.key_back) { onKey(Keys.BACK) },
            BarButton(R.string.key_home) { onKey(Keys.HOME) },
            BarButton(R.string.key_recents) { onKey(Keys.APP_SWITCH) },
            BarButton(R.string.key_volume_up) { onKey(Keys.VOLUME_UP) },
            BarButton(R.string.key_volume_down) { onKey(Keys.VOLUME_DOWN) },
            BarButton(R.string.key_power) { onKey(Keys.POWER) },
            BarButton(R.string.key_keyboard, onKeyboard),
            BarButton(R.string.key_exit, onExit),
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val spanPx = with(density) { ((if (landscape) maxHeight else maxWidth) - HANDLE_LONG).toPx() }.coerceAtLeast(1f)
        val currentSpan by rememberUpdatedState(spanPx)
        val handleModifier = Modifier
            .offset {
                val o = (fraction * currentSpan).roundToInt()
                if (landscape) IntOffset(0, o) else IntOffset(o, 0)
            }
            .size(
                width = if (landscape) HANDLE_SHORT else HANDLE_LONG,
                height = if (landscape) HANDLE_LONG else HANDLE_SHORT,
            )
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x80FFFFFF))
            .pointerInput(landscape) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val d = if (landscape) drag.y else drag.x
                    fraction = (fraction + d / currentSpan).coerceIn(0f, 1f)
                }
            }
            .clickable { expanded = !expanded }

        if (landscape) {
            Row(Modifier.align(Alignment.TopEnd).fillMaxHeight()) {
                Box(handleModifier)
                if (expanded) {
                    Column(Modifier.fillMaxHeight().width(BAR_THICKNESS + 16.dp).background(BAR_BG)) {
                        buttons.forEach { b -> BarKey(b, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Box(handleModifier)
                if (expanded) {
                    Row(Modifier.fillMaxWidth().height(BAR_THICKNESS).background(BAR_BG)) {
                        buttons.forEach { b -> BarKey(b, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarKey(b: BarButton, modifier: Modifier) {
    Box(modifier.clickable(onClick = b.onClick), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(b.label),
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
