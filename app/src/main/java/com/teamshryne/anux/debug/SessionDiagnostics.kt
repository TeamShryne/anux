package com.teamshryne.anux.debug

import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.session.SessionCommand
import java.io.File

/**
 * On-device launch checks for a distro alias. Pure JVM / File-based so the
 * Debug screen can run it off the main thread (and unit tests can cover it).
 * Answers "why did my session die instantly": missing proot, missing libs,
 * incomplete rootfs (no shell), or bad argv.
 */
data class CheckResult(val name: String, val ok: Boolean, val detail: String)

object SessionDiagnostics {
    val SHELLS = listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")

    fun guestShell(rootfs: File): String? =
        SHELLS.firstOrNull { File(rootfs, it).isFile }

    fun run(filesDir: File, alias: String, abiLabel: String?): List<CheckResult> {
        val out = mutableListOf<CheckResult>()
        out += CheckResult("device ABI", true, abiLabel ?: "unknown")
        val freeMb = runCatching { filesDir.usableSpace / 1024 / 1024 }.getOrDefault(-1)
        out += CheckResult(
            "storage free",
            freeMb < 0 || freeMb > 500,
            if (freeMb < 0) "unknown" else "${freeMb}MB free (full images need ~500MB+)",
        )

        val proot = File(filesDir, "usr/bin/proot")
        out += CheckResult(
            "proot binary",
            proot.isFile && proot.canExecute(),
            "${proot.absolutePath} exists=${proot.isFile} exec=${proot.canExecute()}" +
                if (proot.isFile) " size=${proot.length()}" else "",
        )
        listOf("usr/lib/libtalloc.so.2", "usr/lib/libandroid-shmem.so").forEach { rel ->
            val f = File(filesDir, rel)
            out += CheckResult("lib ${rel.substringAfterLast('/')}", f.isFile, "$rel exists=${f.isFile}")
        }

        val rootfs = OciRef.rootfsDir(filesDir, alias)
        out += CheckResult("rootfs dir", rootfs.isDirectory, rootfs.absolutePath)
        if (rootfs.isDirectory) {
            val shell = guestShell(rootfs)
            out += CheckResult(
                "guest shell",
                shell != null,
                shell ?: "none of ${SHELLS.joinToString()} — install incomplete, delete and reinstall",
            )
            val top = runCatching { rootfs.list()?.sorted()?.take(30) }.getOrNull() ?: emptyList()
            out += CheckResult("rootfs top-level", top.isNotEmpty(), top.joinToString())
            out += CheckResult("rootfs entries", true, countEntries(rootfs))
            val manifest = File(filesDir, "containers/$alias/manifest.json")
            out += CheckResult(
                "manifest.json",
                manifest.isFile,
                if (manifest.isFile) manifest.readText().take(300) else "missing",
            )
        }

        try {
            val cmd = SessionCommand.build(filesDir, proot, File(filesDir, "usr/lib"), alias)
            out += CheckResult(
                "launch argv",
                true,
                (listOf(cmd.executable) + cmd.args).joinToString(" "),
            )
        } catch (e: Exception) {
            out += CheckResult("launch argv", false, "${e.javaClass.simpleName}: ${e.message}")
        }
        return out
    }

    private fun countEntries(root: File): String {
        val cap = 200_000
        val n = runCatching { root.walkTopDown().take(cap + 1).count() }.getOrDefault(-1)
        if (n < 0) return "unreadable"
        return if (n > cap) "$cap+ (capped)" else n.toString()
    }
}
