package com.teamshryne.anux

import com.teamshryne.anux.session.SessionCommand
import com.teamshryne.anux.shell.AnuxShellEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionCommandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun setup(): Triple<File, File, File> {
        val filesDir = tmp.root
        val prootBin = File(filesDir, "usr/bin/proot").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        val libDir = File(filesDir, "usr/lib").apply { mkdirs() }
        File(filesDir, "containers/ubuntu/rootfs/bin").mkdirs()
        File(filesDir, "containers/ubuntu/rootfs/bin/sh").createNewFile()
        return Triple(filesDir, prootBin, libDir)
    }

    @Test
    fun `build produces ordered argv and env`() {
        val (filesDir, prootBin, libDir) = setup()
        val cmd = SessionCommand.build(filesDir, prootBin, libDir, "ubuntu")
        assertEquals(prootBin.absolutePath, cmd.executable)
        assertTrue(cmd.args.contains("--kill-on-exit"))
        assertTrue(cmd.args.last() == "-l")
        assertTrue(cmd.cwd.endsWith("containers/ubuntu/rootfs"))
        val env = cmd.env.toMap()
        assertEquals(libDir.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("root", env["USER"])
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
    }

    @Test
    fun `manifest with escaped slashes parses to clean paths`() {
        // org.json escapes / as \/ on-device; the reader must unescape or
        // proot gets --cwd=\/root and the session dies instantly.
        val (filesDir, prootBin, libDir) = setup()
        File(filesDir, "containers/ubuntu/manifest.json").writeText(
            """{"workingDir":"\/root","arch":"AARCH64","env":["PATH=\/usr\/bin"]}""",
        )
        val cmd = SessionCommand.build(filesDir, prootBin, libDir, "ubuntu")
        assertTrue(cmd.args.any { it == "--cwd=/root" })
        val env = cmd.env.toMap()
        assertTrue(env["PATH"]!!.split(":").contains("/usr/bin"))
        assertFalse(cmd.args.any { it.contains("\\") && it.startsWith("--cwd=") })
    }

    @Test
    fun `missing proot throws`() {
        val (filesDir, _, libDir) = setup()
        try {
            SessionCommand.build(filesDir, File(filesDir, "nope"), libDir, "ubuntu")
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("proot"))
        }
    }

    @Test
    fun `missing rootfs throws`() {
        val (filesDir, prootBin, libDir) = setup()
        try {
            SessionCommand.build(filesDir, prootBin, libDir, "ghost")
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not installed"))
        }
    }

    @Test
    fun `rootfs without shell throws`() {
        val (filesDir, prootBin, libDir) = setup()
        File(filesDir, "containers/noshell/rootfs/bin").mkdirs()
        try {
            SessionCommand.build(filesDir, prootBin, libDir, "noshell")
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no shell"))
        }
    }

    private fun Array<String>.toMap(): Map<String, String> =
        associate { it.substringBefore("=") to it.substringAfter("=", "") }

    @Test
    fun `shell env exposes prefix layout`() {
        val env = AnuxShellEnvironment(tmp.newFolder("f"))
        val host = env.hostEnv()
        assertTrue(host["PREFIX"]!!.endsWith("usr"))
        assertTrue(host["HOME"]!!.endsWith("home"))
        assertTrue(host["PATH"]!!.startsWith(host["PREFIX"]!!))
    }
}
