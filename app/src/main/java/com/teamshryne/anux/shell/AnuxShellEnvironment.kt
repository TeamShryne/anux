package com.teamshryne.anux.shell

import java.io.File

/**
 * Host shell environment for anux, mirroring TermuxShellEnvironment semantics
 * with our own applicationId. Guest env is built by distro.ProotArgs at login.
 */
class AnuxShellEnvironment(private val filesDir: File) {
    val prefixDir: File get() = File(filesDir, "usr")
    val homeDir: File get() = File(filesDir, "home")
    val tmpDir: File get() = File(prefixDir, "tmp")

    fun ensureDirs() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
    }

    fun hostEnv(term: String = "xterm-256color"): Map<String, String> {
        val prefix = prefixDir.absolutePath
        return mapOf(
            "PREFIX" to prefix,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "$prefix/bin:/system/bin:/system/xbin",
            "LANG" to "en_US.UTF-8",
            "TERM" to term,
            "COLORTERM" to "truecolor",
        )
    }
}
