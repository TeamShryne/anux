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
import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.distro.ProotArgs
import com.teamshryne.anux.shell.AnuxShellEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service owning all terminal sessions (one service, N sessions —
 * one per distro launch). Sessions are created lazily: TerminalSession spawns
 * the pty process on first updateSize(), triggered by TerminalView.attachSession().
 */
class AnuxService : Service() {

    inner class LocalBinder(val service: AnuxService) : Binder()

    data class SessionRecord(
        val handle: String,
        val alias: String,
        val session: TerminalSession,
    )

    private val binder = LocalBinder(this)
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessions.entries.find { it.value.session == finishedSession }?.let {
                sessions.remove(it.key)
                updateNotification()
            }
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
            stopAll()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun listSessions(): List<SessionRecord> = sessions.values.toList()

    fun sessionForAlias(alias: String): SessionRecord? =
        sessions.values.find { it.alias == alias && it.session.isRunning }

    /**
     * Create (or reuse) a proot session for [alias]. The pty process starts when
     * the UI attaches the session to a TerminalView.
     */
    fun launch(alias: String): SessionRecord {
        sessionForAlias(alias)?.let { return it }
        val shellEnv = AnuxShellEnvironment(filesDir)
        shellEnv.ensureDirs()
        val prootBin = findProotBin()
            ?: throw IllegalStateException("proot binary missing (bootstrap not done yet)")
        val argv = ProotArgs.build(
            prootBin = prootBin,
            filesDir = filesDir,
            alias = alias,
            innerCmd = ProotArgs.defaultInnerCmd(),
        )
        val envList = (shellEnv.hostEnv() + ProotArgs.guestEnv())
            .map { (k, v) -> "$k=$v" }.toTypedArray()
        val cwd = OciRef.rootfsDir(filesDir, alias).absolutePath
        val session = TerminalSession(
            prootBin.absolutePath,
            cwd,
            argv.drop(1).toTypedArray(),
            envList,
            TRANSCRIPT_ROWS,
            sessionClient,
        )
        session.mSessionName = alias
        val record = SessionRecord(UUID.randomUUID().toString(), alias, session)
        sessions[record.handle] = record
        updateNotification()
        return record
    }

    fun finish(handle: String) {
        sessions.remove(handle)?.session?.finishIfRunning()
        updateNotification()
        if (sessions.isEmpty()) stopSelf()
    }

    private fun stopAll() {
        sessions.values.forEach { it.session.finishIfRunning() }
        sessions.clear()
    }

    private fun findProotBin(): File? {
        val candidates = listOf(
            File(filesDir, "usr/bin/proot"),
            File(applicationInfo.nativeLibraryDir, "libproot.so"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
            ?: candidates.firstOrNull { it.isFile }
    }

    private fun startForegroundService() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sessions", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
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
