package dev.scrcpyandroid.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.scrcpyandroid.R
import dev.scrcpyandroid.adb.AdbKeyPair
import dev.scrcpyandroid.adb.AdbKeyStore
import dev.scrcpyandroid.adb.fingerprint
import dev.scrcpyandroid.adb.sanitizeComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyScreen(keyStore: AdbKeyStore, onBack: () -> Unit) {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var key by remember { mutableStateOf<AdbKeyPair?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }
    var confirmRegen by remember { mutableStateOf(false) }

    fun show(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    // Loads/derives everything off the main thread (key generation may happen on first load).
    fun apply(k: AdbKeyPair) {
        key = k
        comment = k.comment
    }
    LaunchedEffect(Unit) {
        val k = withContext(Dispatchers.IO) { keyStore.loadOrCreate() }
        apply(k)
    }
    LaunchedEffect(key) {
        fingerprint = key?.let { k -> withContext(Dispatchers.Default) { k.fingerprint() } } ?: ""
    }

    fun failure(res: Int, e: Throwable) = show(context.getString(res, e.message ?: e.javaClass.simpleName))

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val k = withContext(Dispatchers.IO) {
                    val pem = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw java.io.IOException("Cannot open file")
                    // Parse first: on failure the stored key is untouched.
                    val parsed = AdbKeyPair.importPem(pem, key?.comment ?: "scrcpy-android@android")
                    keyStore.save(parsed)
                    parsed
                }
                apply(k)
                changed = true
                show(context.getString(R.string.key_imported))
            } catch (e: Exception) {
                failure(R.string.key_import_failed, e)
            }
        }
    }

    fun exporter(text: () -> String?) = fun(uri: Uri?) {
        if (uri == null) return
        val content = text() ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("Cannot open file")
                    out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                }
                show(context.getString(R.string.key_exported))
            } catch (e: Exception) {
                failure(R.string.key_export_failed, e)
            }
        }
    }
    val exportPrivate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        exporter { key?.exportPrivatePem() },
    )
    val exportPublic = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        exporter { key?.exportPublic()?.plus("\n") },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val k = key
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (k == null) {
                Text(stringResource(R.string.key_loading))
            } else {
                Text(stringResource(R.string.key_fingerprint), style = MaterialTheme.typography.labelLarge)
                Text(fingerprint, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.key_comment)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    val c = sanitizeComment(comment, k.comment)
                    val updated = k.withComment(c)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { keyStore.save(updated) }
                            apply(updated)
                            changed = true
                            show(context.getString(R.string.key_comment_saved))
                        } catch (e: Exception) {
                            failure(R.string.key_error_generic, e)
                        }
                    }
                }) { Text(stringResource(R.string.key_save_comment)) }

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.key_import)) }
                OutlinedButton(
                    onClick = { exportPrivate.launch("adbkey") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.key_export_private)) }
                OutlinedButton(
                    onClick = { exportPublic.launch("adbkey.pub") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.key_export_public)) }
                OutlinedButton(
                    onClick = { confirmRegen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.key_regenerate)) }

                if (changed) {
                    Text(stringResource(R.string.key_changed_note), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text(stringResource(R.string.key_regenerate)) },
            text = { Text(stringResource(R.string.key_regenerate_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegen = false
                    scope.launch {
                        try {
                            val fresh = withContext(Dispatchers.IO) {
                                AdbKeyPair.generate(key?.comment ?: "scrcpy-android@android").also { keyStore.save(it) }
                            }
                            apply(fresh)
                            changed = true
                            show(context.getString(R.string.key_regenerated))
                        } catch (e: Exception) {
                            failure(R.string.key_error_generic, e)
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
