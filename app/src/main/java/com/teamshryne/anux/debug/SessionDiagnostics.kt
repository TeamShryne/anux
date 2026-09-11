package com.teamshryne.anux.debug

import com.teamshryne.anux.distro.CpuArch
import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.distro.ProotArgs
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
        SHELLS.firstOrNull { ProotArgs.guestFileExists(rootfs, "/$it") }

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
        val containerDir = OciRef.containerDir(filesDir, alias)
        out += CheckResult("rootfs dir", rootfs.isDirectory, rootfs.absolutePath)
        if (rootfs.isDirectory) {
            val shell = runCatching { ProotArgs.resolveShell(rootfs) }.getOrNull()
            // Guest-aware: guest-absolute symlinks dangle on the host.
            val shellOk = shell != null && guestShell(rootfs) != null
            out += CheckResult(
                "guest shell",
                shellOk,
                shell?.let { "resolved login shell: $it" }
                    ?: "none of ${SHELLS.joinToString()} — install incomplete, delete and reinstall",
            )
            val top = runCatching { rootfs.list()?.sorted()?.take(30) }.getOrNull() ?: emptyList()
            out += CheckResult("rootfs top-level", top.isNotEmpty(), top.joinToString())
            out += CheckResult("rootfs entries", true, countEntries(rootfs))
            val manifest = File(filesDir, "containers/$alias/manifest.json")
            out += CheckResult(
                "manifest.json",
                manifest.isFile,
                if (manifest.isFile) manifest.readText().take(400) else "missing",
            )
            val shm = File(containerDir, "shm")
            out += CheckResult("shm dir", shm.isDirectory, "${shm.absolutePath} (bound as /dev/shm)")
            val sysdata = File(containerDir, "sysdata")
            val stubs = runCatching { sysdata.list()?.size ?: 0 }.getOrDefault(0)
            out += CheckResult(
                "sysdata stubs",
                sysdata.isDirectory && stubs > 0,
                "${sysdata.absolutePath} entries=$stubs (fake /proc + /sys/fs/selinux)",
            )
            val profile = File(rootfs, "etc/profile.d/termux-profile.sh")
            out += CheckResult(
                "termux-profile.sh",
                !File(rootfs, "etc/profile.d").isDirectory || profile.isFile,
                if (profile.isFile) "present (login env survives su -)" else "absent (written at next launch)",
            )
        }

        try {
            val cmd = SessionCommand.build(filesDir, proot, File(filesDir, "usr/lib"), alias)
            val full = listOf(cmd.executable) + cmd.args
            val env = cmd.env.toMap()
            out += CheckResult("launch argv", true, full.joinToString(" "))
            val kernel = full.firstOrNull { it.startsWith("--kernel-release=") }
            out += CheckResult(
                "kernel-release format",
                kernel != null && "\\Linux\\" in kernel,
                kernel ?: "missing --kernel-release",
            )
            out += CheckResult(
                "launch env",
                env["HOME"] == "/root" && env["PROOT_L2S_DIR"]?.isNotEmpty() == true &&
                    !cmd.env.any { it.startsWith("LD_PRELOAD=") },
                "HOME=${env["HOME"]} PROOT_L2S_DIR=${env["PROOT_L2S_DIR"]} " +
                    "TMPDIR=${env["TMPDIR"]} LD_PRELOAD stripped=${!cmd.env.any { it.startsWith("LD_PRELOAD=") }}",
            )
            val hasShm = full.any { it.endsWith(":/dev/shm") }
            out += CheckResult("/dev/shm bind", hasShm, if (hasShm) "container shm bound" else "missing — shm apps will fail")
        } catch (e: Exception) {
            out += CheckResult("launch argv", false, "${e.javaClass.simpleName}: ${e.message}")
        }
        if (rootfs.isDirectory) out += guestExecReadiness(rootfs)
        if (rootfs.isDirectory) out += prootExecMatrix(filesDir, alias)
        return out
    }

    /**
     * Runs proot directly (no pty) with progressively richer argv against the
     * installed rootfs to isolate which flag/condition makes guest exec fail.
     * Each probe reports exit code + first output lines.
     */
    private fun prootExecMatrix(filesDir: File, alias: String): CheckResult {
        val prootBin = File(filesDir, "usr/bin/proot")
        val libDir = File(filesDir, "usr/lib")
        val rootfs = OciRef.rootfsDir(filesDir, alias)
        val l2s = File(rootfs, ".l2s").apply { mkdirs() }
        val tmpDir = File(filesDir, "usr/tmp").apply { mkdirs() }
        if (!prootBin.isFile) return CheckResult("proot exec matrix", false, "proot missing")
        val baseEnv = mapOf(
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_L2S_DIR" to l2s.absolutePath,
            "HOME" to "/root",
            "PATH" to "/usr/bin:/bin",
        )
        // Resolve the guest shell the way launch does.
        val shell = ProotArgs.resolveShell(rootfs)
        val probes = listOf(
            "minimal-echo" to listOf("--rootfs=${rootfs.absolutePath}", "/bin/echo", "PROBE1"),
            "direct-busybox" to listOf("--rootfs=${rootfs.absolutePath}", "/bin/busybox", "echo", "PROBE2"),
            "via-symlink-sh" to listOf("--rootfs=${rootfs.absolutePath}", shell, "-c", "echo PROBE3"),
            "change-id" to listOf("--change-id=0:0", "--rootfs=${rootfs.absolutePath}", "/bin/echo", "PROBE4"),
            "no-link2symlink" to listOf(
                "--kill-on-exit", "--sysvipc", "-L", "--change-id=0:0",
                "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
                "--bind=/dev", "--bind=/proc", "--bind=/sys",
                "/bin/echo", "PROBE5",
            ),
            "full-argv-echo" to (
                ProotArgs.build(
                    prootBin, filesDir, alias, listOf("/bin/echo", "PROBE6"),
                    ProotArgs.LoginOptions(
                        cwd = "/root",
                        targetArch = CpuArch.AARCH64,
                    ),
                ).drop(1)
                ),
        )
        val lines = mutableListOf<String>()
        var ok = false
        for ((name, args) in probes) {
            try {
                val proc = ProcessBuilder(listOf(prootBin.absolutePath) + args)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(baseEnv) }
                    .start()
                val done = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) {
                    proc.destroyForcibly()
                    lines += "$name: TIMEOUT"
                    continue
                }
                val out = proc.inputStream.bufferedReader().readText().trim()
                    .replace(Regex("\\s+"), " ").take(300)
                lines += "$name: exit=${proc.exitValue()} $out"
                if (proc.exitValue() == 0 && "PROBE" in out) ok = true
            } catch (e: Exception) {
                lines += "$name: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return CheckResult("proot exec matrix", ok, lines.joinToString("\n"))
    }

    /**
     * Reports exact on-disk state (mode/owner/link) of every file the guest
     * exec chain touches: the shell, its link target, and the ELF
     * interpreter. proot reports EACCES here while host exec of our own
     * binaries works, so coarse exists()/canExecute() is not enough.
     */
    private fun guestExecReadiness(rootfs: File): CheckResult {
        val lines = mutableListOf<String>()
        var ok = true
        for (guest in listOf("/bin/sh", "/bin/busybox", "/lib/ld-musl-aarch64.so.1", "/lib/libc.musl-aarch64.so.1", "/etc/passwd")) {
            val rel = guest.trimStart('/')
            val p = rootfs.canonicalFile.toPath().resolve(rel)
            val nofollow = arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val isLink = runCatching { java.nio.file.Files.isSymbolicLink(p) }.getOrDefault(false)
            val target = if (isLink) {
                runCatching { java.nio.file.Files.readSymbolicLink(p).toString() }.getOrDefault("?")
            } else null
            val mode = runCatching {
                Integer.toOctalString((java.nio.file.Files.getAttribute(p, "unix:mode", *nofollow) as Int) and 0xFFF)
            }.getOrDefault("?")
            val owner = runCatching { java.nio.file.Files.getOwner(p, *nofollow).name }.getOrDefault("?")
            val size = runCatching { java.nio.file.Files.size(p) }.getOrDefault(-1)
            val exists = runCatching { java.nio.file.Files.exists(p, *nofollow) }.getOrDefault(false)
            // Resolve through links the way the kernel would (guest-anchored).
            val effective = ProotArgs.guestFileExists(rootfs, guest)
            lines += "$guest link=$isLink${if (target != null) "->$target" else ""} mode=$mode owner=$owner size=$size exists=$exists effective=$effective"
            if (guest != "/etc/passwd" && !effective) ok = false
        }
        return CheckResult("guest exec readiness", ok, lines.joinToString("\n"))
    }

    private fun Array<String>.toMap(): Map<String, String> =
        associate { it.substringBefore("=") to it.substringAfter("=", "") }

    private fun countEntries(root: File): String {
        val cap = 200_000
        val n = runCatching { root.walkTopDown().take(cap + 1).count() }.getOrDefault(-1)
        if (n < 0) return "unreadable"
        return if (n > cap) "$cap+ (capped)" else n.toString()
    }
}
