package dev.scrcpyandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.scrcpyandroid.R
import dev.scrcpyandroid.devices.DeviceError
import dev.scrcpyandroid.scrcpy.EndReason

/** Localized text for why a mirroring session ended. */
@Composable
fun endReasonText(r: EndReason): String = when (r) {
    EndReason.Stopped, EndReason.Cancelled, EndReason.ConnectionLost -> stringResource(R.string.end_connection_lost)
    EndReason.Unresponsive -> stringResource(R.string.end_unresponsive)
    EndReason.ServerExited -> stringResource(R.string.end_server_exited)
    EndReason.VideoEnded -> stringResource(R.string.end_video_ended)
    is EndReason.VideoError -> stringResource(R.string.end_video_error, r.detail)
    is EndReason.ControlError -> stringResource(R.string.end_control_error, r.detail)
    is EndReason.CodecMismatch -> stringResource(R.string.end_codec_mismatch, "0x%08x".format(r.received), r.requested.arg)
    is EndReason.StartFailed -> stringResource(R.string.end_start_failed, r.detail)
}

/** Localized text for why a device entry is offline. */
@Composable
fun deviceErrorText(e: DeviceError): String = when (e) {
    DeviceError.ConnectionLost -> stringResource(R.string.error_connection_lost)
    DeviceError.UsbPermissionDenied -> stringResource(R.string.error_usb_permission)
    DeviceError.TlsUnsupported -> stringResource(R.string.error_tls_unsupported)
    is DeviceError.Failed -> stringResource(R.string.error_connect_failed, e.detail)
}
