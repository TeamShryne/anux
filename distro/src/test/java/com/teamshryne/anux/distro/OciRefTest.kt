package com.teamshryne.anux.distro

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun `isInstalled detects rootfs`() {
        val filesDir = tmp.root
        assertFalse(OciRef.isInstalled(filesDir, "ubuntu"))
        OciRef.rootfsDir(filesDir, "ubuntu").resolve("usr/bin").mkdirs()
        assertTrue(OciRef.isInstalled(filesDir, "ubuntu"))
    }

    @Test
    fun `localName sanitizes`() {
        assertEquals("my-img", OciRef.localName("user/my-img:1.0"))
        assertEquals("ubuntu", OciRef.localName("ubuntu:24.04"))
    }
}
