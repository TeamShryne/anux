package com.teamshryne.anux.distro

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OciRefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `bare name resolves to docker hub library`() {
        val p = OciRef.parse("ubuntu")
        assertEquals("", p.registry)
        assertEquals("library/ubuntu", p.repo)
        assertEquals("latest", p.tag)
        assertEquals("library/ubuntu:latest", p.canonicalRef)
        assertEquals("ubuntu", p.alias)
    }

    @Test
    fun `explicit tag is kept`() {
        val p = OciRef.parse("ubuntu:24.04")
        assertEquals("library/ubuntu", p.repo)
        assertEquals("24.04", p.tag)
        assertEquals("ubuntu", p.alias)
    }

    @Test
    fun `custom registry detected`() {
        val p = OciRef.parse("ghcr.io/foo/bar:1.0")
        assertEquals("ghcr.io", p.registry)
        assertEquals("foo/bar", p.repo)
        assertEquals("1.0", p.tag)
        assertEquals("bar", p.alias)
    }

    @Test
    fun `host port registry detected`() {
        val p = OciRef.parse("127.0.0.1:5000/img:latest")
        assertEquals("127.0.0.1:5000", p.registry)
        assertEquals("img", p.repo)
    }

    @Test(expected = Exception::class)
    fun `empty ref rejected`() {
        OciRef.parse("  ")
    }

    @Test
    fun `isInstalled requires manifest`() {
        val filesDir = tmp.root
        assertFalse(OciRef.isInstalled(filesDir, "ubuntu"))
        // Partial rootfs from a killed install (no manifest) is NOT installed.
        OciRef.rootfsDir(filesDir, "ubuntu").resolve("usr/bin").mkdirs()
        assertFalse(OciRef.isInstalled(filesDir, "ubuntu"))
        File(filesDir, "containers/ubuntu/manifest.json").writeText("{}")
        assertTrue(OciRef.isInstalled(filesDir, "ubuntu"))
    }

    @Test
    fun `staging dir never counts as installed`() {
        val filesDir = tmp.root
        // A killed install leaves containers/<alias>.part/ behind; that must
        // not make <alias> look installed.
        File(filesDir, "containers/broken.part/rootfs/bin").mkdirs()
        File(filesDir, "containers/broken.part/manifest.json").writeText("{}")
        assertFalse(OciRef.isInstalled(filesDir, "broken"))
    }

    @Test
    fun `localName sanitizes`() {
        assertEquals("my-img", OciRef.localName("user/my-img:1.0"))
        assertEquals("ubuntu", OciRef.localName("ubuntu:24.04"))
    }
}
