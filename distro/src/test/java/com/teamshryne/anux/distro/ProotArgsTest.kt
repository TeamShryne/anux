package com.teamshryne.anux.distro

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun setup(): Triple<java.io.File, java.io.File, java.io.File> {
        val filesDir = tmp.root
        val prootBin = java.io.File(filesDir, "proot").apply { createNewFile() }
        return Triple(filesDir, prootBin, filesDir)
    }

    @Test
    fun `flag order matches proot-distro`() {
        val (filesDir, prootBin) = setup().let { Triple(it.first, it.second, it.third) }
        val argv = ProotArgs.build(prootBin, filesDir, "ubuntu", listOf("/bin/bash", "-l"))
        assertEquals(prootBin.absolutePath, argv[0])
        val kill = argv.indexOf("--kill-on-exit")
        val rootfs = argv.indexOfFirst { it.startsWith("--rootfs=") }
        val cwd = argv.indexOfFirst { it.startsWith("--cwd=") }
        val bash = argv.indexOf("/bin/bash")
        assertTrue(kill in 1..3)
        assertTrue(rootfs > kill)
        assertTrue(cwd > rootfs)
        assertTrue(bash > cwd)
        assertTrue("--link2symlink" in argv)
        assertTrue("--sysvipc" in argv)
        assertTrue(argv.any { it.startsWith("--kernel-release=") })
    }

    @Test
    fun `isolated mode skips host binds but keeps explicit binds`() {
        // Mirrors proot-distro: --isolated drops storage/system/prefix bridges,
        // but user-supplied --bind entries are always honoured.
        val (filesDir, prootBin) = setup().let { Triple(it.first, it.second, it.third) }
        val opts = ProotArgs.LoginOptions(
            extraBinds = listOf("/x" to "/y"),
            isolated = true,
        )
        val argv = ProotArgs.build(prootBin, filesDir, "u", listOf("true"), opts)
        assertTrue(argv.any { it == "--bind=/x:/y" })
        assertFalse(argv.any { it == "--bind=/storage" })
        assertFalse(argv.any { it.startsWith("--bind=" + java.io.File(filesDir, "usr").absolutePath) })
        val full = ProotArgs.build(
            prootBin, filesDir, "u", listOf("true"),
            opts.copy(isolated = false),
        )
        assertTrue(full.any { it == "--bind=/x:/y" })
    }

    @Test
    fun `kernel release uses backslash tuple`() {
        val arg = ProotArgs.kernelReleaseArg("myhost", "6.17.0-PRoot-Distro", CpuArch.AARCH64)
        assertTrue(arg.startsWith("--kernel-release="))
        assertTrue("\\Linux\\myhost\\6.17.0-PRoot-Distro\\" in arg)
        // Backslash-separated tuple (Termux format); the embedded fake
        // version string itself contains spaces, exactly like upstream.
        assertTrue(ProotArgs.FAKE_KERNEL_VERSION in arg)
        assertTrue("aarch64" in arg)
        assertEquals("armv7l", ProotArgs.unameM(CpuArch.ARM))
    }

    @Test
    fun `resolveShell prefers passwd shell then sh`() {
        val rootfs = tmp.newFolder("rootfs-shell")
        java.io.File(rootfs, "bin").mkdirs()
        java.io.File(rootfs, "bin/sh").createNewFile()
        java.io.File(rootfs, "bin/bash").createNewFile()
        // No passwd -> first fallback that exists.
        assertEquals("/bin/sh", ProotArgs.resolveShell(rootfs))
        // Passwd pointing at bash wins when present.
        java.io.File(rootfs, "etc").mkdirs()
        java.io.File(rootfs, "etc/passwd").writeText("root:x:0:0::/root:/bin/bash\n")
        assertEquals("/bin/bash", ProotArgs.resolveShell(rootfs))
        // Passwd pointing at a missing shell falls back to sh.
        java.io.File(rootfs, "etc/passwd").writeText("root:x:0:0::/root:/bin/zsh\n")
        assertEquals("/bin/sh", ProotArgs.resolveShell(rootfs))
    }

    @Test
    fun `guestFileExists follows guest-absolute symlinks`() {
        val rootfs = tmp.newFolder("rootfs-links")
        java.io.File(rootfs, "bin").mkdirs()
        java.io.File(rootfs, "bin/busybox").createNewFile()
        val sh = java.nio.file.Path.of(rootfs.absolutePath, "bin", "sh")
        // Guest-absolute link (alpine style): dangles on the host outside
        // the rootfs, but valid in-guest. Plain isFile would say false.
        java.nio.file.Files.createSymbolicLink(sh, java.nio.file.Path.of("/bin/busybox"))
        // Guest-absolute link (alpine style): must resolve at the rootfs,
        // regardless of whether the target also exists on the host.
        assertTrue(ProotArgs.guestFileExists(rootfs, "/bin/sh"))
        assertTrue(ProotArgs.guestFileExists(rootfs, "/bin/busybox"))
        assertFalse(ProotArgs.guestFileExists(rootfs, "/bin/missing"))
        assertFalse(ProotArgs.guestFileExists(rootfs, "/../escape"))
        // resolveShell picks it up too.
        assertEquals("/bin/sh", ProotArgs.resolveShell(rootfs))
    }

    @Test
    fun `image env blocked vars are filtered`() {
        val env = ProotArgs.guestEnv(
            imageEnv = listOf(
                "MYAPP=1",
                "TERM=evil",
                "LD_LIBRARY_PATH=/evil",
                "PROOT_L2S_DIR=/evil",
                "PULSE_SERVER=evil",
            ),
        )
        assertEquals("1", env["MYAPP"])
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("127.0.0.1", env["PULSE_SERVER"])
        assertFalse(env["LD_LIBRARY_PATH"] == "/evil")
    }

    @Test
    fun `guest env has required keys`() {
        val env = ProotArgs.guestEnv()
        assertEquals("/root", env["HOME"])
        assertEquals("root", env["USER"])
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertEquals("127.0.0.1", env["PULSE_SERVER"])
    }

    @Test
    fun `arch normalization`() {
        assertEquals(CpuArch.AARCH64, CpuArch.normalize("aarch64"))
        assertEquals(CpuArch.X86_64, CpuArch.normalize("x86_64"))
        assertEquals(CpuArch.ARM, CpuArch.normalize("armv7l"))
        assertEquals("arm64", CpuArch.AARCH64.dockerArch)
        assertEquals("v7", CpuArch.ARM.dockerVariant)
    }
}
