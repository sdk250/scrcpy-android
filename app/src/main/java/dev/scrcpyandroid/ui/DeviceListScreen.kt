package dev.scrcpyandroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.scrcpyandroid.R
import dev.scrcpyandroid.devices.DeviceEntry
import dev.scrcpyandroid.devices.DeviceRegistry
import dev.scrcpyandroid.devices.DeviceState
import dev.scrcpyandroid.devices.LinkKind
import dev.scrcpyandroid.devices.parseHostPort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    registry: DeviceRegistry,
    onOpenSettings: () -> Unit,
    onMirror: (String) -> Unit,
) {
    val devices by registry.devices.collectAsState()
    var showConnect by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showConnect = true }) {
                Text(stringResource(R.string.adb_connect))
            }
        },
    ) { padding ->
        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_devices), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(devices, key = { it.kind.name + it.id }) { d ->
                    DeviceRow(d, registry, onMirror)
                }
            }
        }
    }

    if (showConnect) {
        ConnectDialog(
            onDismiss = { showConnect = false },
            onConnect = { registry.connectTcp(it); showConnect = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(d: DeviceEntry, registry: DeviceRegistry, onMirror: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = {
                    when (d.state) {
                        DeviceState.DEVICE -> onMirror(d.id)
                        DeviceState.OFFLINE -> if (d.kind == LinkKind.TCP) {
                            parseHostPort(d.id)?.let { registry.connectTcp(it) }
                        }
                        else -> Unit
                    }
                },
                onLongClick = { menu = true },
            ),
            overlineContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (d.kind == LinkKind.USB) R.string.tag_usb else R.string.tag_tcp))
                    Text(stateLabel(d.state), color = stateColor(d.state))
                }
            },
            headlineContent = { Text(d.id) },
            supportingContent = {
                Column {
                    d.model?.let { Text(it) }
                    if (d.state == DeviceState.UNAUTHORIZED) Text(stringResource(R.string.unauthorized_hint))
                    if (d.state == DeviceState.OFFLINE && d.error != null) {
                        Text(d.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_disconnect)) },
                onClick = { menu = false; registry.disconnect(d.id) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_forget)) },
                onClick = { menu = false; registry.forget(d.id) },
            )
        }
    }
}

@Composable
private fun stateLabel(s: DeviceState): String = stringResource(
    when (s) {
        DeviceState.DEVICE -> R.string.state_device
        DeviceState.UNAUTHORIZED -> R.string.state_unauthorized
        DeviceState.OFFLINE -> R.string.state_offline
        DeviceState.CONNECTING -> R.string.state_connecting
    },
)

@Composable
private fun stateColor(s: DeviceState) = when (s) {
    DeviceState.DEVICE -> MaterialTheme.colorScheme.primary
    DeviceState.UNAUTHORIZED -> MaterialTheme.colorScheme.tertiary
    DeviceState.OFFLINE -> MaterialTheme.colorScheme.error
    DeviceState.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant
}
