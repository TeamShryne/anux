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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
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

@Composable
fun TerminalScreen(service: AnuxService?, pendingAlias: String?) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(listOf<AnuxService.SessionRecord>()) }
    var currentHandle by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        service?.let { records = it.listSessions() }
        if (currentHandle !in records.map { it.handle }) {
            currentHandle = records.firstOrNull()?.handle
        }
    }

    DisposableEffect(service, pendingAlias) {
        if (service != null && pendingAlias != null) {
            try {
                val rec = service.launch(pendingAlias)
                currentHandle = rec.handle
            } catch (_: Exception) {
            }
        }
        refresh()
        onDispose { }
    }

    val current = records.find { it.handle == currentHandle }?.session

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records) { rec ->
                AssistChip(
                    onClick = { currentHandle = rec.handle },
                    label = { Text(rec.alias) },
                    leadingIcon = {
                        Text(if (rec.session.isRunning) "●" else "○")
                    },
                )
            }
        }
        if (current != null && service != null) {
            val viewClient = remember { stubViewClient() }
            val terminalView = remember(context) {
                TerminalView(context, null).apply { setTerminalViewClient(viewClient) }
            }
            AndroidView(
                factory = { terminalView },
                update = { v ->
                    if (v.currentSession != current) v.attachSession(current)
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {
                    currentHandle?.let { service.finish(it); refresh() }
                }) { Text("Kill") }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No session yet", style = MaterialTheme.typography.titleLarge)
                Text("Pick a distro and hit Launch.")
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Launch from Distros tab") }
            }
        }
    }
}
