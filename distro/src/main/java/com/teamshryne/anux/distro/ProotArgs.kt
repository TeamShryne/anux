package com.teamshryne.anux.distro

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Proot argv builder. Mirrors proot-distro `commands/login/proot_cmd.py`
 * ordering plus `bindings.py`, `sysdata.py`, `shm.py`, `arch.py` semantics.
 *
 * On Android we are always in Termux mode (IS_TERMUX == true), so the
 * proot extensions (--kill-on-exit, --link2symlink, --sysvipc,
 * --kernel-release, -L) are always emitted unless explicitly disabled.
 */
object ProotArgs {
    /** Must match proot-distro constants.DEFAULT_FAKE_KERNEL_VERSION. */
    const val FAKE_KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
    const val DEFAULT_KERNEL_RELEASE = "6.17.0-PRoot-Distro"

    /** `uname -m` reported per arch (mirrors arch.ARCH_UNAME_M). */
    fun unameM(arch: CpuArch): String = when (arch) {
        CpuArch.AARCH64 -> "aarch64"
        CpuArch.ARM -> "armv7l"
        CpuArch.I686 -> "i686"
        CpuArch.X86_64 -> "x86_64"
        CpuArch.RISCV64 -> "riscv64"
        CpuArch.UNKNOWN -> "aarch64"
    }

    /**
     * Termux kernel-release tuple. Backslash-escaped (proot splits on `\`);
     * never use spaces here — a space-separated release breaks `uname -r`
     * parsing in guest scripts.
     */
    fun kernelReleaseArg(hostname: String, kernelRelease: String, arch: CpuArch): String =
        "--kernel-release=\\Linux\\$hostname\\$kernelRelease" +
            "\\$FAKE_KERNEL_VERSION\\${unameM(arch)}\\localdomain\\-1\\"

    /** Host env vars proot-distro harvests for ART/dalvik (constants.py). */
    val ANDROID_HOST_VARS = listOf(
        "ANDROID_ART_ROOT", "ANDROID_DATA", "ANDROID_I18N_ROOT",
        "ANDROID_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE",
    )

    /** Vars an image Env may NOT override (env.IMAGE_ENV_BLOCKED). */
    val IMAGE_ENV_BLOCKED = setOf(
        *ANDROID_HOST_VARS.toTypedArray(),
        "MOZ_FAKE_NO_SANDBOX", "PULSE_SERVER",
        "TERM", "COLORTERM",
    )

    /** Host-exec namespaces (LD_ and PROOT_ prefixes): the image must not set them. */
    fun isHostExecVar(key: String): Boolean =
        key.startsWith("LD_") || key.startsWith("PROOT_")

    data class LoginOptions(
        val user: String = "root",
        val cwd: String = "/root",
        val extraBinds: List<Pair<String, String>> = emptyList(),
        val kernelRelease: String = DEFAULT_KERNEL_RELEASE,
        val hostname: String = "localhost",
        val targetArch: CpuArch = CpuArch.AARCH64,
        val isolated: Boolean = false,
        val minimal: Boolean = false,
        val noLink2symlink: Boolean = false,
        val noSysvipc: Boolean = false,
        val noKillOnExit: Boolean = false,
        val redirectPorts: Boolean = false,
        val sharedHome: Boolean = false,
        val sharedTmp: Boolean = false,
        val sharedX11: Boolean = false,
        val emuArgs: List<String> = emptyList(),
    )

    fun build(
        prootBin: File,
        filesDir: File,
        alias: String,
        innerCmd: List<String>,
        opts: LoginOptions = LoginOptions(),
    ): List<String> {
        val rootfs = OciRef.rootfsDir(filesDir, alias)
        val containerDir = OciRef.containerDir(filesDir, alias)
        val args = mutableListOf(prootBin.absolutePath)
        args += opts.emuArgs

        // 1. Proot extensions (always Termux on Android).
        if (!opts.noKillOnExit) args += "--kill-on-exit"
        if (!opts.noLink2symlink) args += "--link2symlink"
        if (!opts.noSysvipc && !opts.minimal) args += "--sysvipc"
        if (!opts.minimal) args += kernelReleaseArg(opts.hostname, opts.kernelRelease, opts.targetArch)
        args += "-L"

        // 2. Fake root for normal-type containers (all of ours).
        args += "--change-id=0:0"

        // 3. rootfs / cwd / baseline binds.
        args += "--rootfs=${rootfs.absolutePath}"
        args += "--cwd=${opts.cwd}"
        args += listOf("--bind=/dev", "--bind=/proc", "--bind=/sys")

        // 4. Non-minimal binds.
        if (!opts.minimal) {
            addTermuxDevBinds(args, rootfs, containerDir)
            if (!opts.isolated) {
                addDalvikCacheBinds(args)
                args += storageBindings()
                // Host prefix/home bridges so guest can reach app utilities.
                val prefix = File(filesDir, "usr")
                if (prefix.isDirectory) args += "--bind=${prefix.absolutePath}"
                val home = File(filesDir, "home")
                if (home.isDirectory) args += "--bind=${home.absolutePath}"
            }
            if (!opts.isolated || opts.emuArgs.isNotEmpty()) {
                args += systemBindings()
                val prefix = File(filesDir, "usr")
                if (prefix.isDirectory) {
                    val bind = "--bind=${prefix.absolutePath}"
                    if (bind !in args) args += bind
                }
            }
            val termHome = File(filesDir, "home").absolutePath
            val termPrefix = File(filesDir, "usr").absolutePath
            if (opts.sharedHome) {
                args += "--bind=$termHome:/root"
            }
            if (opts.sharedTmp) args += "--bind=$termPrefix/tmp:/tmp"
            if (opts.sharedX11) args += "--bind=$termPrefix/tmp/.X11-unix:/tmp/.X11-unix"
        }

        // 5. User binds (overlap tolerated, mirroring proot-distro warning).
        for ((src, dst) in opts.extraBinds) {
            if (dst.isEmpty()) args += "--bind=$src" else args += "--bind=$src:$dst"
        }

        if (opts.redirectPorts) args += "-p"
        args += innerCmd
        return args
    }

    /** Resolve the login shell inside [rootfs]: root's passwd shell or fallback. */
    fun resolveShell(rootfs: File): String {
        val passwdShell = runCatching {
            File(rootfs, "etc/passwd").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("root:") }
                ?.split(":")?.getOrNull(6)?.trim()
                .orEmpty()
        }.getOrDefault("")
        val candidates = buildList {
            if (passwdShell.startsWith("/")) add(passwdShell)
            add("/bin/sh")
            add("/usr/bin/sh")
            add("/bin/bash")
            add("/usr/bin/bash")
        }.distinct()
        return candidates.firstOrNull { File(rootfs, it.trimStart('/')).isFile }
            ?: "/bin/sh"
    }

    fun defaultInnerCmd(rootfs: File): List<String> = listOf(resolveShell(rootfs), "-l")

    /**
     * Guest environment. Precedence (later wins), mirroring env.py:
     * baseline -> image Env (filtered) -> Android host vars -> extra -> HOME/USER/TERM.
     * Then `$PREFIX/bin` is appended to PATH so guest tools reach host utilities.
     */
    fun guestEnv(
        term: String = "xterm-256color",
        prefix: String? = null,
        imageEnv: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        user: String = "root",
        home: String = "/root",
    ): Map<String, String> {
        val env = mutableMapOf(
            "PATH" to defaultPathEnv(prefix),
            "MOZ_FAKE_NO_SANDBOX" to "1",
            "PULSE_SERVER" to "127.0.0.1",
            "LANG" to "en_US.UTF-8",
        )
        for (entry in imageEnv) {
            val key = entry.substringBefore("=")
            val value = entry.substringAfter("=", "")
            if (key.isEmpty() || key in IMAGE_ENV_BLOCKED || isHostExecVar(key)) continue
            env[key] = value
        }
        // Default (non-isolated, non-minimal) mode: harvest Android runtime vars
        // from the host process so guest ART/dalvik tooling locates the runtime.
        for (key in ANDROID_HOST_VARS) {
            System.getenv(key)?.let { if (it.isNotEmpty()) env[key] = it }
        }
        env.putAll(extraEnv)
        env["HOME"] = home
        env["USER"] = user
        env["TERM"] = term.ifEmpty { "xterm-256color" }
        System.getenv("COLORTERM")?.let { if (it.isNotEmpty()) env["COLORTERM"] = it }
            ?: env.putIfAbsent("COLORTERM", "truecolor")
        if (prefix != null) {
            val termBin = "$prefix/bin"
            val parts = env["PATH"].orEmpty().split(":").filter { it.isNotEmpty() && it != termBin }
            env["PATH"] = (parts + termBin).joinToString(":")
        }
        return env
    }

    /** Backwards-compatible baseline (no prefix/image context). */
    fun baselineEnv(term: String = "xterm-256color"): Map<String, String> =
        guestEnv(term = term)

    fun defaultPathEnv(prefix: String? = null): String {
        // Mirrors constants.DEFAULT_PATH_ENV (Termux branch).
        var path = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" +
            ":/usr/local/games:/usr/games"
        if (prefix != null) path += ":$prefix/bin:/system/bin:/system/xbin"
        return path
    }

    // -- binds --------------------------------------------------------

    internal fun addTermuxDevBinds(args: MutableList<String>, rootfs: File, containerDir: File) {
        args += "--bind=/dev/urandom:/dev/random"
        if (!lexists(File("/dev/fd"))) args += "--bind=/proc/self/fd:/dev/fd"
        val names = listOf(0 to "stdin", 1 to "stdout", 2 to "stderr")
        for ((i, name) in names) {
            if (!lexists(File("/dev/$name")) && File("/proc/self/fd/$i").exists()) {
                args += "--bind=/proc/self/fd/$i:/dev/$name"
            }
        }
        args += fakeSysdataBindings(rootfs, containerDir)
        ensureGuestTmp(rootfs)
        val shm = ensureShm(containerDir)
        if (shm != null) args += "--bind=${shm.absolutePath}:/dev/shm"
    }

    internal fun addDalvikCacheBinds(args: MutableList<String>) {
        for (dir in listOf(
            "/data/app",
            "/data/dalvik-cache",
            "/data/misc/apexdata/com.android.art/dalvik-cache",
        )) {
            val f = File(dir)
            if (f.isDirectory && f.canExecute()) args += "--bind=$dir"
        }
        val appsDir = File("/data/data/com.termux/files/apps")
        if (appsDir.isDirectory) args += "--bind=${appsDir.absolutePath}"
    }

    /** Mirrors bindings.storage_bindings with canRead in place of os.access. */
    fun storageBindings(): List<String> {
        val binds = mutableListOf<String>()
        if (File("/storage").canRead()) {
            binds += "--bind=/storage"
            if (File("/storage/emulated/0").canRead()) {
                binds += "--bind=/storage/emulated/0:/sdcard"
                binds += "--bind=/storage/emulated/0:/mnt/sdcard"
            }
        } else {
            for (p in listOf("/storage/self/primary", "/storage/emulated/0", "/sdcard")) {
                if (File(p).canRead()) {
                    binds += "--bind=$p:/mnt/sdcard"
                    binds += "--bind=$p:/sdcard"
                    binds += "--bind=$p:/storage/emulated/0"
                    binds += "--bind=$p:/storage/self/primary"
                    break
                }
            }
        }
        return binds
    }

    /** Mirrors bindings.system_bindings with realpath + readability checks. */
    fun systemBindings(): List<String> {
        val binds = mutableListOf<String>()
        for (path in listOf(
            "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
            "/linkerconfig/ld.config.txt",
            "/linkerconfig/com.android.art/ld.config.txt",
            "/plat_property_contexts", "/property_contexts",
        )) {
            val real = runCatching { File(path).canonicalFile }.getOrNull() ?: continue
            if (!real.exists()) continue
            if (real.isDirectory) {
                if (real.canExecute()) binds += "--bind=${real.absolutePath}"
            } else if (real.isFile) {
                val readable = runCatching {
                    real.inputStream().use { it.read() }
                    true
                }.getOrDefault(false)
                if (readable) binds += "--bind=${real.absolutePath}"
            }
        }
        return binds
    }

    // -- sysdata / shm (mirrors sysdata.py + shm.py, simplified) --------

    /** Fake /proc,/sys stub contents bound over unreadable host entries. */
    val SYS_VERSION: String
        get() = "Linux version $DEFAULT_KERNEL_RELEASE (proot@termux) " +
            "(gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) $FAKE_KERNEL_VERSION\n"

    val fakeEntries: Map<String, Pair<String, String>>
        get() = mapOf(
            "loadavg" to ("/proc/loadavg" to "0.12 0.07 0.02 2/165 765\n"),
            "stat" to ("/proc/stat" to FAKE_STAT),
            "uptime" to ("/proc/uptime" to "124.08 932.80\n"),
            "version" to ("/proc/version" to SYS_VERSION),
            "vmstat" to ("/proc/vmstat" to FAKE_VMSTAT),
            "sysctl_entry_cap_last_cap" to ("/proc/sys/kernel/cap_last_cap" to "40\n"),
            "sysctl_inotify_max_user_watches" to
                ("/proc/sys/fs/inotify/max_user_watches" to "4096\n"),
            "sysctl_kernel_overflowuid" to ("/proc/sys/kernel/overflowuid" to "65534\n"),
            "sysctl_kernel_overflowgid" to ("/proc/sys/kernel/overflowgid" to "65534\n"),
        )

    fun sysdataDir(containerDir: File): File = File(containerDir, "sysdata")

    fun ensureSysdata(rootfs: File, containerDir: File): File {
        val dir = sysdataDir(containerDir).apply { mkdirs() }
        File(dir, "sys_empty").apply { mkdirs() }
        for ((name, entry) in fakeEntries) {
            val (_, content) = entry
            val f = File(dir, name)
            if (f.isFile && !Files.isSymbolicLink(f.toPath())) continue
            runCatching {
                if (Files.isSymbolicLink(f.toPath())) Files.deleteIfExists(f.toPath())
                if (!f.isFile) f.writeText(content)
            }
        }
        return dir
    }

    fun fakeSysdataBindings(rootfs: File, containerDir: File): List<String> {
        val dir = ensureSysdata(rootfs, containerDir)
        val binds = mutableListOf<String>()
        if (File(dir, "sys_empty").isDirectory) {
            binds += "--bind=${File(dir, "sys_empty").absolutePath}:/sys/fs/selinux"
        }
        for ((name, entry) in fakeEntries) {
            val (real, _) = entry
            val readable = runCatching {
                File(real).inputStream().use { it.read() }
                true
            }.getOrDefault(false)
            if (readable) continue
            val stub = File(dir, name)
            if (stub.isFile) binds += "--bind=${stub.absolutePath}:$real"
        }
        return binds
    }

    fun ensureGuestTmp(rootfs: File) {
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        chmod1777(tmp)
    }

    fun ensureShm(containerDir: File): File? {
        val shm = File(containerDir, "shm").apply { mkdirs() }
        chmod1777(shm)
        return if (shm.isDirectory) shm else null
    }

    internal fun chmod1777(dir: File) {
        runCatching { dir.setReadable(true, false) }
        runCatching { dir.setWritable(true, false) }
        runCatching { dir.setExecutable(true, false) }
    }

    internal fun lexists(f: File): Boolean =
        runCatching { Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(f.exists())

    // Trimmed but functional fake /proc/stat + /proc/vmstat.
    private const val FAKE_STAT = """cpu  1957 0 2877 93280 262 342 254 87 0 0
cpu0 31 0 226 12027 82 10 4 9 0 0
cpu1 45 0 664 11144 21 263 233 12 0 0
cpu2 494 0 537 11283 27 10 3 8 0 0
cpu3 359 0 234 11723 24 26 5 7 0 0
intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
ctxt 140223
btime 1680020856
processes 772
procs_running 2
procs_blocked 0
softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
"""
    private const val FAKE_VMSTAT = """nr_free_pages 1743136
nr_zone_inactive_anon 179281
nr_zone_active_anon 7183
nr_inactive_anon 179281
nr_active_anon 7183
nr_unevictable 642
nr_mapped 8905
nr_file_pages 253569
nr_dirty 0
nr_writeback 0
nr_shmem 178741
pgpgin 890508
pgpgout 0
pswpin 0
pswpout 0
pgalloc_normal 1328079
pgfree 3077011
pgfault 176973
pgmajfault 488
oom_kill 0
"""
}
