package dev.scrcpyandroid.ui

import android.media.MediaCodecList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import dev.scrcpyandroid.R
import dev.scrcpyandroid.scrcpy.SettingsRepository
import dev.scrcpyandroid.scrcpy.VideoCodec

private val MAX_SIZES = listOf(0, 2560, 1920, 1600, 1280, 1024)
private val BITRATES_MBPS = listOf(2, 4, 8, 16, 24, 32)
private val FPS = listOf(30, 60, 90, 120)

private fun hasHevcDecoder(): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit, onOpenKeys: () -> Unit) {
    var opts by remember { mutableStateOf(repo.load()) }
    val h265Probe by produceState<Boolean?>(null) { value = withContext(Dispatchers.Default) { hasHevcDecoder() } }
    val h265 = h265Probe == true
    // Saved H.265 without a decoder falls back to H.264 (once the probe has finished).
    LaunchedEffect(h265Probe) {
        if (h265Probe == false && opts.codec == VideoCodec.H265) {
            opts = opts.copy(codec = VideoCodec.H264)
            repo.save(opts)
        }
    }
    fun update(o: dev.scrcpyandroid.scrcpy.ScrcpyOptions) {
        opts = o
        repo.save(o)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val native = stringResource(R.string.native_size)
            Choice(
                stringResource(R.string.setting_max_size), MAX_SIZES, opts.maxSize,
                { if (it == 0) native else it.toString() },
            ) { update(opts.copy(maxSize = it)) }
            val mbps = R.string.unit_mbps
            Choice(
                stringResource(R.string.setting_bitrate), BITRATES_MBPS, opts.bitRate / 1_000_000,
                { stringResource(mbps, it) },
            ) { update(opts.copy(bitRate = it * 1_000_000)) }
            Choice(
                stringResource(R.string.setting_fps), FPS, opts.maxFps,
                { stringResource(R.string.unit_fps, it) },
            ) { update(opts.copy(maxFps = it)) }
            Choice(
                stringResource(R.string.setting_codec), VideoCodec.entries, opts.codec,
                {
                    stringResource(
                        when {
                            it == VideoCodec.H264 -> R.string.codec_h264
                            h265 -> R.string.codec_h265
                            else -> R.string.codec_h265_unsupported
                        },
                    )
                },
                enabled = { it == VideoCodec.H264 || h265 },
            ) { update(opts.copy(codec = it)) }
            ListItem(
                modifier = Modifier.toggleable(opts.turnScreenOff) { update(opts.copy(turnScreenOff = it)) },
                headlineContent = { Text(stringResource(R.string.setting_screen_off)) },
                trailingContent = { Switch(checked = opts.turnScreenOff, onCheckedChange = null) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenKeys),
                headlineContent = { Text(stringResource(R.string.key_management)) },
            )
        }
    }
}

@Composable
private fun <T> Choice(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(label(selected)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(label(o)) },
                    enabled = enabled(o),
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}
