package dev.scrcpyandroid.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scrcpyandroid.LanguageStore
import dev.scrcpyandroid.R

/** Remembers whether the first-use key bar hint has been dismissed. */
object KeyBarHintStore {
    private const val KEY = "keybar_hint_shown"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(LanguageStore.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markShown(context: Context) {
        context.getSharedPreferences(LanguageStore.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

/**
 * First-use explanation of the key bar, shown over the mirror next to the (expanded) bar:
 * above it in portrait, to its left in landscape.
 */
@Composable
fun KeyBarHint(landscape: Boolean, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .padding(
                    end = if (landscape) 96.dp else 16.dp,
                    bottom = if (landscape) 0.dp else 88.dp,
                    start = 16.dp,
                )
                .widthIn(max = 360.dp)
                .background(Color(0xE6202428), RoundedCornerShape(12.dp))
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp),
        ) {
            Text(stringResource(R.string.hint_keybar_title), color = Color.White, fontSize = 16.sp)
            Text(
                stringResource(if (landscape) R.string.hint_keybar_body_landscape else R.string.hint_keybar_body),
                color = Color(0xFFD0D4D8),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp, end = 8.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.hint_got_it))
            }
        }
    }
}
