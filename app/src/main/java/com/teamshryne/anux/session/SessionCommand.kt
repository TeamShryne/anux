package com.teamshryne.anux.session

import com.teamshryne.anux.distro.ContainerLock
import com.teamshryne.anux.distro.CpuArch
import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.distro.ProotArgs
import java.io.File
import java.nio.file.Files

/**
 * Builds the exact argv + env for a proot login session.
 * Pure JVM logic (File-based) so it is unit-testable; the service only forks.
 *
 * Mirrors proot-distro `commands/login/__init__.py`: shell resolved from
 * the guest passwd, image Env + WorkingDir from manifest.json, clean guest
 * env (never inherit the host env wholesale), PROOT_L2S_DIR pinning, and
 * termux-profile injection so `su -` keeps the login-time environment.
 */
object SessionCommand {
    data class Built(
        val executable: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String,
    )

    /** Vars never written into the profile.d snippet (env._PROFILE_INJECT_SKIP). */
    private val PROFILE_SKIP = setOf(
        "HOME", "USER", "TERM", "COLORTERM",
        "PATH",
        "PROOT_NO_SECCOMP", "PROOT_VERBOSE", "PROOT_L2S_DIR",
        "LD_PRELOAD", "LD_LIBRARY_PATH",
    )
    private val ENV_KEY_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun build(
        filesDir: File,
        prootBin: File,
        libDir: File,
        alias: String,
        term: String = "xterm-256color",
        kernelRelease: String = ProotArgs.DEFAULT_KERNEL_RELEASE,
        hostname: String = "localhost",
        extraEnv: Map<String, String> = emptyMap(),
    ): Built = ContainerLock.withLock(filesDir, alias, exclusive = false) {
        buildLocked(filesDir, prootBin, libDir, alias, term, kernelRelease, hostname, extraEnv)
    }

    private fun buildLocked(
        filesDir: File,
        prootBin: File,
        libDir: File,
        alias: String,
        term: String,
        kernelRelease: String,
        hostname: String,
        extraEnv: Map<String, String>,
    ): Built {
        require(prootBin.isFile) { "proot binary missing at ${prootBin.absolutePath} (bootstrap incomplete)" }
        val rootfs = OciRef.rootfsDir(filesDir, alias)
        require(rootfs.isDirectory) { "container '$alias' is not installed" }
        val manifest = readManifest(filesDir, alias)

        // Resolve the guest shell: root's passwd shell first, then fallbacks.
        // A rootfs dir can exist from a failed install without a usable shell;
        // fail here with a clear error instead of dying instantly with no message.
        val shell = ProotArgs.resolveShell(rootfs)
        // Guest-aware check: guest-absolute symlinks (e.g. /bin/sh ->
        // /bin/busybox) dangle on the host, so plain isFile lies here.
        val shellOk = ProotArgs.guestFileExists(rootfs, shell)
        require(shellOk) {
            "container '$alias' has no shell (bin/sh missing — install incomplete, delete and reinstall)"
        }

        val guestCwd = manifest.workingDir.ifEmpty { "/root" }
        val targetArch = runCatching { CpuArch.valueOf(manifest.arch) }
            .getOrDefault(CpuArch.AARCH64)
        val imageEnv = manifest.env

        val prefix = File(filesDir, "usr").absolutePath
        File(prefix, "tmp").apply { mkdirs() }
        val guestEnv = ProotArgs.guestEnv(
            term = term,
            prefix = prefix,
            imageEnv = imageEnv,
            extraEnv = extraEnv,
            user = "root",
            home = "/root",
        )
        injectTermuxProfile(rootfs, guestEnv)

        val argv = ProotArgs.build(
            prootBin = prootBin,
            filesDir = filesDir,
            alias = alias,
            innerCmd = listOf(shell, "-l"),
            opts = ProotArgs.LoginOptions(
                cwd = guestCwd,
                kernelRelease = kernelRelease,
                hostname = hostname,
                targetArch = targetArch,
            ),
        )
        // Pin PROOT_L2S_DIR so concurrent sessions share one location instead
        // of racing (first session implicit, second explicit). Always create it.
        val l2sDir = File(rootfs, ".l2s").apply { mkdirs() }

        val env = mutableMapOf(
            "PREFIX" to prefix,
            // Host-writable tmp for proot itself (it stages binds there);
            // also valid in-guest since $PREFIX is bound at the same path
            // (Termux does exactly this instead of /tmp, absent on Android).
            "TMPDIR" to "$prefix/tmp",
            // libtalloc + libandroid-shmem live next to proot; the binary's
            // RUNPATH points at the Termux prefix, so override the search path.
            // (Guest glibc binaries ignore these names; nothing of ours shadows libc.)
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "PROOT_L2S_DIR" to l2sDir.absolutePath,
        )
        // Explicitly never propagate a host LD_PRELOAD into the guest.
        env.putAll(guestEnv)
        for (key in listOf("PROOT_NO_SECCOMP", "PROOT_VERBOSE")) {
            System.getenv(key)?.let { if (it.isNotEmpty()) env[key] = it }
        }
        env.remove("LD_PRELOAD")
        return Built(
            executable = argv[0],
            args = argv.drop(1).toTypedArray(),
            env = env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            cwd = rootfs.absolutePath,
        )
    }

    /**
     * Minimal manifest read (workingDir/arch/env only). Hand-rolled string
     * parsing — deliberately not org.json, which is an android.jar stub that
     * throws in local JVM unit tests. Missing/corrupt manifest -> defaults.
     */
    internal data class ManifestData(
        val workingDir: String = "",
        val arch: String = CpuArch.AARCH64.name,
        val env: List<String> = emptyList(),
    )

    internal fun readManifest(filesDir: File, alias: String): ManifestData {
        val f = File(filesDir, "containers/$alias/manifest.json")
        if (!f.isFile) return ManifestData()
        return runCatching {
            val text = f.readText()
            val workingDir = unescapeJsonString(
                Regex("\"workingDir\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .find(text)?.groupValues?.get(1).orEmpty(),
            )
            val arch = unescapeJsonString(
                Regex("\"arch\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .find(text)?.groupValues?.get(1).orEmpty(),
            ).ifEmpty { CpuArch.AARCH64.name }
            val envBlock = Regex("\"env\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1).orEmpty()
            val env = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(envBlock)
                .map { unescapeJsonString(it.groupValues[1]) }
                .toList()
            ManifestData(workingDir, arch, env)
        }.getOrDefault(ManifestData())
    }

    /** Minimal JSON string unescaper. org.json escapes `/` as `\/`, which the
     * naive substring reader would otherwise leak into paths (`\/root`). */
    internal fun unescapeJsonString(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    '/', '\\', '"' -> { out.append(e); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            out.append(code.toChar()); i += 6
                        } else {
                            out.append(c); i++
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /**
     * Write etc/profile.d/termux-profile.sh so login shells re-export the
     * login-time env after /etc/profile resets it (mirrors env.py).
     * Skips safely when profile.d is absent or not a real directory.
     */
    internal fun injectTermuxProfile(rootfs: File, env: Map<String, String>) {
        val profileD = File(rootfs, "etc/profile.d")
        if (!profileD.isDirectory || Files.isSymbolicLink(profileD.toPath())) return
        val snippet = File(profileD, "termux-profile.sh")
        runCatching {
            // Drop the name first so a planted symlink is removed, not followed.
            if (Files.isSymbolicLink(snippet.toPath())) Files.deleteIfExists(snippet.toPath())
            // Our own prefix bin (guest PATH already carries it); fall back to the
            // Termux default only when nothing usable is present.
            val ownBin = env["PATH"]?.split(":")?.firstOrNull { it.endsWith("/usr/bin") }
            val bins = listOfNotNull(ownBin
                ?: System.getenv("TERMUX__PREFIX")?.let { "$it/bin" }
                ?: "/data/data/com.termux/files/usr/bin").distinct()
            val lines = mutableListOf<String>()
            for (bin in bins) {
                lines += "case \":\${PATH}:\" in"
                lines += "  *\":$bin:\"*) ;;"
                lines += "  *) export PATH=\"\${PATH}:$bin\" ;;"
                lines += "esac"
            }
            // Legacy filename from the PATH-only era.
            File(profileD, "termux-prefix.sh").delete()
            for (key in env.keys.sorted()) {
                if (key in PROFILE_SKIP) continue
                if (!ENV_KEY_RE.matches(key)) continue
                val escaped = env[key].orEmpty().replace("'", "'\\''")
                lines += "export $key='$escaped'"
            }
            // Write to a temp name then rename so we never truncate through a link.
            val tmp = File(profileD, "termux-profile.sh.tmp")
            if (Files.isSymbolicLink(tmp.toPath())) Files.deleteIfExists(tmp.toPath())
            tmp.writeText(lines.joinToString("\n") + "\n")
            snippet.delete()
            if (!tmp.renameTo(snippet)) {
                snippet.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
