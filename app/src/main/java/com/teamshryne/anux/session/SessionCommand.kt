package com.teamshryne.anux.session

import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.distro.ProotArgs
import java.io.File

/**
 * Builds the exact argv + env for a proot login session.
 * Pure JVM logic (File-based) so it is unit-testable; the service only forks.
 */
object SessionCommand {
    data class Built(
        val executable: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String,
    )

    fun build(
        filesDir: File,
        prootBin: File,
        libDir: File,
        alias: String,
        term: String = "xterm-256color",
        kernelRelease: String = "6.17.0-PRoot-Distro",
        hostname: String = "localhost",
    ): Built {
        require(prootBin.isFile) { "proot binary missing at ${prootBin.absolutePath} (bootstrap incomplete)" }
        val rootfs = OciRef.rootfsDir(filesDir, alias)
        require(rootfs.isDirectory) { "container '$alias' is not installed" }
        val argv = ProotArgs.build(
            prootBin = prootBin,
            filesDir = filesDir,
            alias = alias,
            innerCmd = ProotArgs.defaultInnerCmd(),
            opts = ProotArgs.LoginOptions(kernelRelease = kernelRelease, hostname = hostname),
        )
        val prefix = File(filesDir, "usr").absolutePath
        val home = File(filesDir, "home").absolutePath
        val env = mutableMapOf(
            "PREFIX" to prefix,
            "HOME" to home,
            "TMPDIR" to "$prefix/tmp",
            // libtalloc + libandroid-shmem live next to proot; the binary's
            // RUNPATH points at the Termux prefix, so override the search path.
            "LD_LIBRARY_PATH" to libDir.absolutePath,
        )
        env.putAll(ProotArgs.guestEnv(term))
        return Built(
            executable = argv[0],
            args = argv.drop(1).toTypedArray(),
            env = env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            cwd = rootfs.absolutePath,
        )
    }
}
