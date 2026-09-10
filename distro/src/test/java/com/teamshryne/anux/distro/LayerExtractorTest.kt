package com.teamshryne.anux.distro

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

/** Builds a gzip layer tar from path->content (null content = directory). */
internal fun makeLayer(dir: File, name: String, entries: Map<String, String?>): File {
    val out = File(dir, name)
    TarArchiveOutputStream(GZIPOutputStream(out.outputStream().buffered())).use { tar ->
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
        for ((path, content) in entries) {
            val e = TarArchiveEntry(path)
            if (content == null) {
                tar.putArchiveEntry(e)
                tar.closeArchiveEntry()
            } else {
                val bytes = content.toByteArray()
                e.size = bytes.size.toLong()
                e.mode = 0b100_644
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
    return out
}

class LayerExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `strip detection scores rootfs dirs`() {
        assertEquals(0, LayerExtractor.detectStripCount(listOf("bin/sh", "etc/passwd")))
        assertEquals(1, LayerExtractor.detectStripCount(listOf("layer/bin/sh", "layer/etc/passwd")))
        assertEquals(2, LayerExtractor.detectStripCount(listOf("a/b/usr/bin/x")))
    }

    @Test
    fun `basic layer extracts`() {
        val layer = makeLayer(tmp.root, "l.tgz", mapOf(
            "bin/sh" to "#!/bin/sh\n",
            "etc/passwd" to "root:x:0:0::/root:/bin/sh\n",
        ))
        val rootfs = File(tmp.root, "rootfs")
        LayerExtractor.applyLayer(layer, rootfs)
        assertEquals("#!/bin/sh\n", File(rootfs, "bin/sh").readText())
        assertTrue(File(rootfs, "etc/passwd").isFile)
    }

    @Test
    fun `whiteout removes previous file`() {
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/old.conf").writeText("old")
        File(rootfs, "etc/keep.conf").writeText("keep")

        val layer = makeLayer(tmp.root, "l2.tgz", mapOf(
            "etc/.wh.old.conf" to "",
            "etc/new.conf" to "new",
        ))
        LayerExtractor.applyLayer(layer, rootfs)
        assertFalse(File(rootfs, "etc/old.conf").exists())
        assertEquals("keep", File(rootfs, "etc/keep.conf").readText())
        assertEquals("new", File(rootfs, "etc/new.conf").readText())
    }

    @Test
    fun `opaque dir clears stale files`() {
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        File(rootfs, "var/lib").mkdirs()
        File(rootfs, "var/lib/stale").writeText("stale")

        val layer = makeLayer(tmp.root, "l3.tgz", mapOf(
            "var/lib/.wh..wh..opq" to "",
            "var/lib/fresh" to "fresh",
        ))
        LayerExtractor.applyLayer(layer, rootfs)
        assertFalse(File(rootfs, "var/lib/stale").exists())
        assertEquals("fresh", File(rootfs, "var/lib/fresh").readText())
    }

    @Test
    fun `path traversal rejected`() {
        val layer = makeLayer(tmp.root, "evil.tgz", mapOf(
            "../../evil.txt" to "x",
            "bin/ok" to "ok",
        ))
        val rootfs = File(tmp.root, "rootfs2")
        LayerExtractor.applyLayer(layer, rootfs)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertEquals("ok", File(rootfs, "bin/ok").readText())
    }

    @Test
    fun `sanitize blocks escapes`() {
        val rootfs = File(tmp.root, "r")
        assertNull(LayerExtractor.sanitize(rootfs, "../../x"))
        assertNull(LayerExtractor.sanitize(rootfs, "/absolute"))
        assertNotNull(LayerExtractor.sanitize(rootfs, "bin/sh"))
    }
}
