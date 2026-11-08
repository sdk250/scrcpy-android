package dev.scrcpyandroid.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.scrcpyandroid.R
import dev.scrcpyandroid.devices.HostPort
import dev.scrcpyandroid.devices.parseHostPort

@Composable
fun ConnectDialog(onDismiss: () -> Unit, onConnect: (HostPort) -> Unit) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val submit = {
        val hp = parseHostPort(text)
        if (hp == null) {
            invalid = true
        } else {
            onConnect(hp)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; invalid = false },
                placeholder = { Text(stringResource(R.string.connect_hint)) },
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.connect_invalid)) }
                } else {
                    null
                },
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.connect_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
