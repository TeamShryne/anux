package com.teamshryne.anux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.teamshryne.anux.bootstrap.BootstrapManager
import com.teamshryne.anux.session.SessionCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service owning all terminal sessions (one service, N sessions —
 * one per distro launch). Sessions are created via [launch]; the pty process
 * itself starts when the UI attaches the session to a TerminalView.
 */
class AnuxService : Service() {

    inner class LocalBinder(val service: AnuxService) : Binder()

    data class SessionRecord(
        val handle: String,
        val alias: String,
        val session: TerminalSession,
        val startedAtMillis: Long = System.currentTimeMillis(),
    )

    /** A session that already exited (most recent first, capped). */
    data class FinishedEvent(
        val alias: String,
        val handle: String,
        val startedAtMillis: Long,
        val finishedAtMillis: Long,
    ) {
        val lifetimeMillis: Long get() = finishedAtMillis - startedAtMillis
    }

    private val binder = LocalBinder(this)
    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val _sessionList = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessionList: StateFlow<List<SessionRecord>> = _sessionList.asStateFlow()
    private val _finishedEvents = MutableStateFlow<List<FinishedEvent>>(emptyList())
    val finishedEvents: StateFlow<List<FinishedEvent>> = _finishedEvents.asStateFlow()

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val entry = sessions.entries.find { it.value.session == finishedSession }
            val rec = entry?.value
            if (entry != null) sessions.remove(entry.key)
            if (rec != null) {
                _finishedEvents.value = (
                    listOf(
                        FinishedEvent(rec.alias, rec.handle, rec.startedAtMillis, System.currentTimeMillis()),
                    ) + _finishedEvents.value
                ).take(20)
            }
            publish()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        if (intent?.action == ACTION_STOP_ALL) {
            sessions.values.forEach { it.session.finishIfRunning() }
            sessions.clear()
            publish()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun runningFor(alias: String): SessionRecord? =
        sessions.values.find { it.alias == alias && it.session.isRunning }

    /**
     * Bootstrap (extract proot on first run) + build argv/env, then register
     * the session. Heavy IO runs on Dispatchers.IO, but the TerminalSession
     * itself MUST be constructed on the main thread: its field initializer
     * creates a Handler bound to the calling thread's Looper (as in Termux),
     * so building it on a worker thread crashes with
     * "Can't create handler inside thread ... that has not called Looper.prepare()".
     * Safe to call from UI coroutines.
     */
    suspend fun launch(
        alias: String,
        term: String = "xterm-256color",
        kernelRelease: String = "6.17.0-PRoot-Distro",
        hostname: String = "localhost",
    ): SessionRecord {
        runningFor(alias)?.let { return it }
        val paths = withContext(Dispatchers.IO) {
            BootstrapManager(this@AnuxService).ensureInstalled()
        }
        val cmd = withContext(Dispatchers.IO) {
            SessionCommand.build(
                filesDir = filesDir,
                prootBin = paths.prootBin,
                libDir = paths.libDir,
                alias = alias,
                term = term,
                kernelRelease = kernelRelease,
                hostname = hostname,
            )
        }
        val session = withContext(Dispatchers.Main) {
            TerminalSession(
                cmd.executable,
                cmd.cwd,
                cmd.args,
                cmd.env,
                TRANSCRIPT_ROWS,
                sessionClient,
            )
        }
        session.mSessionName = alias
        val record = SessionRecord(UUID.randomUUID().toString(), alias, session)
        sessions[record.handle] = record
        publish()
        return record
    }

    fun finish(handle: String) {
        sessions.remove(handle)?.session?.finishIfRunning()
        publish()
        if (sessions.isEmpty()) stopSelf()
    }

    private fun publish() {
        _sessionList.value = sessions.values.toList()
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private fun startForegroundService() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sessions", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("anux (${sessions.size} sessions)")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "anux-sessions"
        const val NOTIF_ID = 1337
        const val TRANSCRIPT_ROWS = 2000
        const val ACTION_STOP_ALL = "com.teamshryne.anux.STOP_ALL"
    }
}
