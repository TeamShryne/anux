package com.teamshryne.anux.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.teamshryne.anux.data.AnuxSettings
import com.teamshryne.anux.service.AnuxService

private fun stubViewClient(): TerminalViewClient = object : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

private val EXTRA_KEYS = listOf(
    "ESC" to "\u001b",
    "TAB" to "\t",
    "/" to "/",
    "-" to "-",
    "|" to "|",
    "↑" to "\u001b[A",
    "↓" to "\u001b[B",
    "←" to "\u001b[D",
    "→" to "\u001b[C",
    "^C" to "\u0003",
)

@Composable
fun TerminalScreen(
    service: AnuxService?,
    pendingAlias: String?,
    settings: AnuxSettings,
    onConsumePending: () -> Unit,
) {
    val context = LocalContext.current
    val records by (service?.sessionList?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf(emptyList()) })
    var currentHandle by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // When the guest process dies instantly (bad rootfs/proot), the record is
    // created then removed within a second: buttons flash, then "No session
    // yet" with no explanation. Remember launch time to name it instead.
    var launchedAt by remember { mutableStateOf(0L) }

    LaunchedEffect(service, pendingAlias) {
        if (service != null && pendingAlias != null) {
            try {
                val rec = service.launch(
                    alias = pendingAlias,
                    term = "xterm-256color",
                    kernelRelease = settings.kernelRelease,
                    hostname = settings.hostname,
                )
                currentHandle = rec.handle
                launchedAt = System.currentTimeMillis()
                error = null
            } catch (e: Exception) {
                error = "Launch failed: ${e.message}"
            }
            onConsumePending()
        }
    }

    // Keep selection valid as sessions come and go.
    LaunchedEffect(records) {
        val cur = currentHandle
        if (cur != null && records.none { it.handle == cur }) {
            if (launchedAt > 0 && System.currentTimeMillis() - launchedAt < 5000) {
                error = "Session exited immediately — open the Debug tab for launch checks and logs."
            }
            launchedAt = 0L
            currentHandle = records.firstOrNull { it.session.isRunning }?.handle
        } else if (cur == null) {
            currentHandle = records.firstOrNull { it.session.isRunning }?.handle
        }
    }

    val current = records.find { it.handle == currentHandle }?.session

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records, key = { it.handle }) { rec ->
                AssistChip(
                    onClick = { currentHandle = rec.handle },
                    label = { Text(rec.alias) },
                    leadingIcon = { Text(if (rec.session.isRunning) "●" else "○") },
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (current != null && service != null) {
            val viewClient = remember { stubViewClient() }
            val terminalView = remember(context, settings.fontSize) {
                TerminalView(context, null).apply {
                    setTerminalViewClient(viewClient)
                    setTextSize(settings.fontSize)
                }
            }
            AndroidView(
                factory = { terminalView },
                update = { v ->
                    if (v.currentSession !== current) v.attachSession(current)
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(EXTRA_KEYS) { (label, seq) ->
                    TextButton(onClick = {
                        val bytes = seq.toByteArray()
                        current.write(bytes, 0, bytes.size)
                    }) { Text(label) }
                }
                item {
                    OutlinedButton(onClick = {
                        launchedAt = 0L
                        currentHandle?.let { service.finish(it) }
                    }) { Text("Kill") }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No session yet", style = MaterialTheme.typography.titleLarge)
                Text("Install a distro, then Launch it.")
            }
        }
    }
}
