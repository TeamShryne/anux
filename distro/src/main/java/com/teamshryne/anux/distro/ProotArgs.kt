package com.teamshryne.anux.distro

import java.io.File

/** Proot argv builder. Mirrors proot-distro login/proot_cmd.py ordering. */
object ProotArgs {
    data class LoginOptions(
        val user: String = "root",
        val cwd: String = "/root",
        val sharedStorage: Boolean = true,
        val sharedTmp: Boolean = false,
        val extraBinds: List<Pair<String, String>> = emptyList(),
        val kernelRelease: String = "6.17.0-PRoot-Distro",
        val hostname: String = "localhost",
        val isolated: Boolean = false,
    )

    fun build(
        prootBin: File,
        filesDir: File,
        alias: String,
        innerCmd: List<String>,
        opts: LoginOptions = LoginOptions(),
    ): List<String> {
        val rootfs = OciRef.rootfsDir(filesDir, alias)
        val args = mutableListOf(prootBin.absolutePath)
        args += listOf("--kill-on-exit", "--link2symlink", "--sysvipc")
        args += listOf("--kernel-release=Linux ${opts.hostname} ${opts.kernelRelease} #1 SMP PREEMPT_DYNAMIC aarch64")
        args += listOf("-L", "--change-id=0:0")
        args += listOf("--rootfs=${rootfs.absolutePath}", "--cwd=${opts.cwd}")
        args += listOf("--bind=/dev", "--bind=/proc", "--bind=/sys")
        args += "--bind=/dev/urandom:/dev/random"
        if (!opts.isolated) {
            if (opts.sharedStorage) {
                for (candidate in listOf("/storage", "/sdcard")) {
                    if (File(candidate).exists()) args += "--bind=$candidate"
                }
            }
            // Host prefix bind (Termux compat) when present.
            val prefix = File(filesDir, "usr")
            if (prefix.isDirectory) args += "--bind=${prefix.absolutePath}"
            val home = File(filesDir, "home")
            if (home.isDirectory) args += "--bind=${home.absolutePath}"
            for ((src, dst) in opts.extraBinds) args += "--bind=$src:$dst"
        }
        args += innerCmd
        return args
    }

    fun defaultInnerCmd(): List<String> = listOf("/bin/bash", "-l")

    fun guestEnv(term: String = "xterm-256color"): Map<String, String> = mapOf(
        "HOME" to "/root",
        "USER" to "root",
        "TERM" to term,
        "COLORTERM" to "truecolor",
        "LANG" to "en_US.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "MOZ_FAKE_NO_SANDBOX" to "1",
        "PULSE_SERVER" to "127.0.0.1",
    )
}
