package com.teamshryne.anux.ui

import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teamshryne.anux.bootstrap.BootstrapManager
import com.teamshryne.anux.debug.CheckResult
import com.teamshryne.anux.debug.SessionDiagnostics
import com.teamshryne.anux.service.AnuxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics for "session dies instantly" reports: live sessions,
 * recently finished sessions with lifetimes, per-alias launch checks
 * (proot, libs, rootfs, shell, argv) and this app's recent logcat output.
 */
@Composable
fun DebugScreen(service: AnuxService?, filesDir: File) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val records by (service?.sessionList?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf(emptyList()) })
    val finished by (service?.finishedEvents?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf(emptyList()) })

    var alias by remember { mutableStateOf("alpine") }
    var checks by remember { mutableStateOf<List<CheckResult>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("Tap Refresh to load this app's recent logs.") }
    var loadingLogs by remember { mutableStateOf(false) }

    fun runChecks() {
        val a = alias.trim()
        if (a.isEmpty() || checking) return
        checking = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val appCtx = context.applicationContext
                val abi = runCatching {
                    BootstrapManager(appCtx).abiDirName()
                }.getOrElse { "unsupported (${it.message})" }
                val out = mutableListOf<CheckResult>()
                // Bootstrap first (idempotent) so the proot checks below reflect
                // steady state rather than "app was just installed".
                val boot = runCatching { BootstrapManager(appCtx).ensureInstalled() }
                out += CheckResult(
                    "bootstrap",
                    boot.isSuccess,
                    boot.fold(
                        { "proot+libs extracted to files/usr (abi=$abi)" },
                        { "${it.javaClass.simpleName}: ${it.message}" },
                    ),
                )
                out += probeApkAssets(appCtx, abi)
                out += SessionDiagnostics.run(filesDir, a, abi)
                out += execContextCheck(filesDir)
                out += prootSmokeTest(
                    File(filesDir, "usr/bin/proot"),
                    File(filesDir, "usr/lib"),
                )
                out += execProbeCheck(appCtx, filesDir)
                out.toList()
            }
            checks = res
            checking = false
        }
    }

    fun loadLogs() {
        if (loadingLogs) return
        loadingLogs = true
        scope.launch {
            logs = withContext(Dispatchers.IO) { readOwnLogs(400) }
            loadingLogs = false
        }
    }

    LaunchedEffect(service) { runChecks() }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debug logs", style = MaterialTheme.typography.headlineMedium)
        Text(
            "When a session dies instantly, the cause is here: checks, argv and logs.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Live sessions (${records.size})", style = MaterialTheme.typography.titleMedium)
                if (records.isEmpty()) {
                    Text("none", style = MaterialTheme.typography.bodySmall)
                } else {
                    records.forEach { rec ->
                        Text(
                            "● ${rec.alias} handle=${rec.handle.take(8)} running=${rec.session.isRunning}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recently finished", style = MaterialTheme.typography.titleMedium)
                if (finished.isEmpty()) {
                    Text("none yet — launch a distro first", style = MaterialTheme.typography.bodySmall)
                } else {
                    finished.forEach { ev ->
                        val instant = ev.lifetimeMillis < 3000
                        Text(
                            (if (instant) "✗ " else "○ ") +
                                "${ev.alias} lived ${ev.lifetimeMillis}ms" +
                                if (instant) " (exited immediately)" else "",
                            color = if (instant) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "ended ${timeOf(ev.finishedAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ev.tail.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    "last output:\n${ev.tail.trim().take(1500)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Launch checks", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Alias") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { runChecks() }, enabled = !checking) {
                        Text(if (checking) "…" else "Run")
                    }
                }
                checks?.forEach { c ->
                    Text(
                        (if (c.ok) "✓ " else "✗ ") + c.name,
                        color = if (c.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SelectionContainer {
                        Text(
                            c.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App logs", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { loadLogs() }, enabled = !loadingLogs) {
                        Text(if (loadingLogs) "…" else "Refresh")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(buildReport(alias, checks, finished.map {
                            buildString {
                                append("${it.alias} lived ${it.lifetimeMillis}ms")
                                if (it.tail.isNotBlank()) append("\nlast output:\n${it.tail.trim().take(1500)}")
                            }
                        }, logs)))
                    }) { Text("Copy report") }
                }
                SelectionContainer {
                    Text(logs, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun timeOf(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

private fun buildReport(
    alias: String,
    checks: List<CheckResult>?,
    finished: List<String>,
    logs: String,
): String = buildString {
    appendLine("anux debug report for '$alias'")
    appendLine("--- checks ---")
    checks?.forEach { appendLine("${if (it.ok) "OK" else "FAIL"} ${it.name}: ${it.detail}") }
        ?: appendLine("(checks not run)")
    appendLine("--- recently finished ---")
    if (finished.isEmpty()) appendLine("(none)") else finished.forEach { appendLine(it) }
    appendLine("--- app logs ---")
    appendLine(logs)
}

/** Distinguishes "APK shipped no binaries" from "not extracted yet". */
private fun probeApkAssets(ctx: android.content.Context, abi: String): CheckResult {
    return runCatching {
        val top = ctx.assets.list("proot/$abi")?.toList() ?: emptyList()
        val libs = ctx.assets.list("proot/$abi/lib")?.toList() ?: emptyList()
        val ok = "proot" in top &&
            "libtalloc.so.2" in libs && "libandroid-shmem.so" in libs
        CheckResult(
            "proot assets in APK",
            ok,
            "proot/$abi=$top proot/$abi/lib=$libs",
        )
    }.getOrElse {
        CheckResult("proot assets in APK", false, "${it.javaClass.simpleName}: ${it.message}")
    }
}

/** This process's own recent logcat output; readable without extra permissions. */
private fun readOwnLogs(maxLines: Int): String {
    val pid = Process.myPid().toString()
    val attempts = listOf(
        arrayOf("logcat", "-d", "--pid", pid, "-t", maxLines.toString(), "*:V"),
        arrayOf("logcat", "-d", "-t", maxLines.toString(), "*:V"),
    )
    for (args in attempts) {
        val text = runCatching {
            val p = Runtime.getRuntime().exec(args)
            p.inputStream.bufferedReader().readText().trim()
        }.getOrNull().orEmpty()
        if (text.isNotEmpty()) return text.take(60_000)
    }
    return "logcat unavailable or empty"
}

/**
 * Exact exec context for the proot binary: octal mode/owner (coarse
 * canExecute() is not enough) plus the mount options of the filesystem
 * holding filesDir (a noexec mount fails execve with EACCES).
 */
private fun execContextCheck(filesDir: File): CheckResult {
    val proot = File(filesDir, "usr/bin/proot")
    val stat = runCatching {
        val st = android.system.Os.stat(proot.absolutePath)
        "mode=${Integer.toOctalString(st.st_mode and 0xFFF)} " +
            "uid=${st.st_uid} gid=${st.st_gid} size=${st.st_size}"
    }.getOrDefault("stat failed")
    val mount = runCatching {
        val path = filesDir.canonicalPath
        File("/proc/self/mountinfo").readLines().mapNotNull { line ->
            val f = line.split(" ")
            if (f.size < 9) return@mapNotNull null
            Triple(f[4], f[5], f[8])
        }.filter { (mp, _, _) -> path == mp || path.startsWith(mp.trimEnd('/') + "/") }
            .maxByOrNull { it.first.length }
            ?.let { (mp, opts, fstype) -> "mp=$mp fstype=$fstype opts=$opts" }
            ?: "mount not found"
    }.getOrDefault("mountinfo unreadable")
    val ok = proot.canExecute() && "noexec" !in mount
    return CheckResult("exec context", ok, "$stat | $mount")
}

/**
 * Three-way exec experiment to isolate EACCES:
 * 1. system shell (baseline — exec works at all?),
 * 2. system shell copied into files/ + chmod 755 (does ANY private-dir
 *    binary exec, or is the location blocked?),
 * 3. sha256(APK asset) vs sha256(extracted proot) (extraction faithful?).
 */
private fun execProbeCheck(appCtx: android.content.Context, filesDir: File): CheckResult {
    val lines = mutableListOf<String>()
    var ok = true
    // 1. Baseline.
    try {
        val p = ProcessBuilder("/system/bin/sh", "-c", "exit 42").start()
        p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        lines += "system-sh exit=${p.exitValue()}"
        if (p.exitValue() != 42) ok = false
    } catch (e: Exception) {
        ok = false
        lines += "system-sh ${e.javaClass.simpleName}: ${e.message}"
    }
    // 2. Same-dir control.
    try {
        val probe = File(filesDir, "usr/bin/probe-sh")
        File("/system/bin/sh").copyTo(probe, overwrite = true)
        android.system.Os.chmod(probe.absolutePath, 0b111_101_101)
        val p = ProcessBuilder(probe.absolutePath, "-c", "exit 43").start()
        p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        lines += "private-sh exit=${p.exitValue()}"
        if (p.exitValue() != 43) ok = false
    } catch (e: Exception) {
        ok = false
        lines += "private-sh ${e.javaClass.simpleName}: ${e.message}"
    } finally {
        runCatching { File(filesDir, "usr/bin/probe-sh").delete() }
    }
    // 3. Extraction fidelity.
    try {
        val abi = com.teamshryne.anux.bootstrap.BootstrapManager(appCtx).abiDirName()
        val mdAsset = java.security.MessageDigest.getInstance("SHA-256")
        appCtx.assets.open("proot/$abi/proot").use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                mdAsset.update(buf, 0, n)
            }
        }
        val mdFile = java.security.MessageDigest.getInstance("SHA-256")
        File(filesDir, "usr/bin/proot").inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                mdFile.update(buf, 0, n)
            }
        }
        fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }.take(16)
        val match = mdAsset.digest().contentEquals(mdFile.digest())
        lines += "sha asset=${hex(mdAsset.digest())} file=${hex(mdFile.digest())} match=$match"
        if (!match) ok = false
    } catch (e: Exception) {
        ok = false
        lines += "sha ${e.javaClass.simpleName}: ${e.message}"
    }
    return CheckResult("exec probes", ok, lines.joinToString(" | "))
}

/**
 * Runs `proot --version` directly via ProcessBuilder (no pty). If this
 * fails with EACCES too, the problem is file-level; if it works, the
 * problem is specific to the TerminalSession/libtermux exec path.
 */
private fun prootSmokeTest(prootBin: File, libDir: File): CheckResult {    if (!prootBin.isFile) return CheckResult("proot smoke test", false, "binary missing")
    return try {
        val proc = ProcessBuilder(prootBin.absolutePath, "--version")
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = libDir.absolutePath }
            .start()
        val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return CheckResult("proot smoke test", false, "timed out after 15s")
        }
        val out = proc.inputStream.bufferedReader().readText().trim().take(500)
        CheckResult("proot smoke test", proc.exitValue() == 0, "exit=${proc.exitValue()} out=$out")
    } catch (e: Exception) {
        CheckResult("proot smoke test", false, "${e.javaClass.simpleName}: ${e.message}")
    }
}
