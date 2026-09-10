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
    fun `isolated mode skips extra binds`() {
        val (filesDir, prootBin) = setup().let { Triple(it.first, it.second, it.third) }
        val opts = ProotArgs.LoginOptions(
            extraBinds = listOf("/x" to "/y"),
            isolated = true,
        )
        val argv = ProotArgs.build(prootBin, filesDir, "u", listOf("true"), opts)
        assertFalse(argv.any { it == "--bind=/x:/y" })
        val full = ProotArgs.build(
            prootBin, filesDir, "u", listOf("true"),
            opts.copy(isolated = false),
        )
        assertTrue(full.any { it == "--bind=/x:/y" })
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
