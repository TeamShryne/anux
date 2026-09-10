package com.teamshryne.anux.distro

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DistroInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Serves a complete minimal image: index -> manifest -> config -> one layer. */
    private inner class FakeImage(val server: MockWebServer, workDir: File) {
        val layerBytes: ByteArray
        val ref: String

        init {
            val layerFile = makeLayer(workDir, "rootfs.tgz", mapOf(
                "bin/sh" to "#!/bin/sh\n",
                "etc/passwd" to "root:x:0:0::/root:/bin/sh\n",
                "etc/hosts" to "old-hosts\n",
            ))
            layerBytes = layerFile.readBytes()
            ref = "127.0.0.1:${server.port}/tiny"
        }

        fun configJson(): String = JSONObject()
            .put("config", JSONObject()
                .put("Env", JSONArray().put("PATH=/usr/bin"))
                .put("WorkingDir", "/root"))
            .toString()

        fun dispatcher(): Dispatcher {
            val layerDigest = sha(layerBytes)
            val configDigest = sha(configJson().toByteArray())
            val manifest = JSONObject()
                .put("schemaVersion", 2)
                .put("config", JSONObject().put("digest", configDigest))
                .put("layers", JSONArray().put(
                    JSONObject().put("digest", layerDigest).put("size", layerBytes.size),
                )).toString()
            val index = JSONObject()
                .put("schemaVersion", 2)
                .put("manifests", JSONArray().put(
                    JSONObject().put("digest", sha(manifest.toByteArray()))
                        .put("platform", JSONObject().put("architecture", "arm64")),
                )).toString()
            val armManifestDigest = sha(manifest.toByteArray())
            return object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    val p = req.path.orEmpty()
                    return when {
                        p.endsWith("/manifests/latest") ->
                            MockResponse().setBody(index)
                                .setHeader("Content-Type", "application/vnd.oci.image.index.v1+json")
                        p.contains("/manifests/$armManifestDigest") ->
                            MockResponse().setBody(manifest)
                                .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
                        p.contains("/blobs/$configDigest") ->
                            MockResponse().setBody(Buffer().write(configJson().toByteArray()))
                        p.contains("/blobs/$layerDigest") ->
                            MockResponse().setBody(Buffer().write(layerBytes))
                        else -> MockResponse().setResponseCode(404).setBody("nope: $p")
                    }
                }
            }
        }
    }

    @Test
    fun `full install writes rootfs and fixups`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val filesDir = tmp.newFolder("files")
            val cacheDir = tmp.newFolder("cache")
            val img = FakeImage(server, tmp.newFolder("work"))
            server.dispatcher = img.dispatcher()

            val installer = DistroInstaller(
                filesDir, cacheDir,
                DockerRegistry(useHttpForCustomRegistries = true),
            )
            val info = installer.install(img.ref, alias = "tiny", arch = CpuArch.AARCH64)
            assertEquals("tiny", info.alias)

            val rootfs = File(filesDir, "containers/tiny/rootfs")
            assertEquals("#!/bin/sh\n", File(rootfs, "bin/sh").readText())
            assertEquals(
                "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
                File(rootfs, "etc/resolv.conf").readText(),
            )
            // pre-seeded hosts from the image layer is kept (we only write when absent)
            assertEquals("old-hosts\n", File(rootfs, "etc/hosts").readText())
            assertTrue(File(filesDir, "containers/tiny/manifest.json").isFile)
            assertTrue(File(filesDir, "containers/tiny/shm").isDirectory)
            assertTrue(OciRef.isInstalled(filesDir, "tiny"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `reinstall rejected and bad names rejected`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val filesDir = tmp.newFolder("files")
            val cacheDir = tmp.newFolder("cache")
            val img = FakeImage(server, tmp.newFolder("work"))
            server.dispatcher = img.dispatcher()
            val installer = DistroInstaller(
                filesDir, cacheDir,
                DockerRegistry(useHttpForCustomRegistries = true),
            )
            installer.install(img.ref, alias = "tiny")
            try {
                installer.install(img.ref, alias = "tiny")
                fail("expected already-installed")
            } catch (e: IllegalStateException) {
                // expected
            }
            try {
                installer.install(img.ref, alias = "../evil")
                fail("expected bad name")
            } catch (e: IllegalArgumentException) {
                // expected
            }
            installer.uninstall("tiny")
            assertFalse(File(filesDir, "containers/tiny").exists())
        } finally {
            server.shutdown()
        }
    }
}

class DistroBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `backup restore round trip`() {
        val filesDir = tmp.newFolder("files")
        val rootfs = File(filesDir, "containers/demo/rootfs").apply { mkdirs() }
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/hello").writeText("hi")
        File(filesDir, "containers/demo/manifest.json").writeText("""{"alias":"demo"}""")

        val out = File(tmp.root, "demo.tar.gz")
        DistroBackup.backup(filesDir, "demo", out)
        assertTrue(out.isFile)

        File(filesDir, "containers/demo").deleteRecursively()
        val restored = DistroBackup.restore(filesDir, out)
        assertEquals("demo", restored)
        assertEquals("hi", File(filesDir, "containers/demo/rootfs/bin/hello").readText())
        assertEquals("""{"alias":"demo"}""", File(filesDir, "containers/demo/manifest.json").readText())
    }

    @Test
    fun `restore rejects traversal`() {
        val filesDir = tmp.newFolder("files2")
        // craft malicious backup by hand
        val evil = File(tmp.root, "evil.tar")
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
            evil.outputStream().buffered(),
        ).use { tar ->
            val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("x/rootfs/../../pwned")
            val b = "pwn".toByteArray()
            e.size = b.size.toLong()
            tar.putArchiveEntry(e)
            tar.write(b)
            tar.closeArchiveEntry()
        }
        try {
            DistroBackup.restore(filesDir, evil)
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertFalse(File(filesDir, "pwned").exists())
    }
}

class ContainerLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lock runs block and returns value`() {
        val v = ContainerLock.withLock(tmp.root, "a", exclusive = true) { 42 }
        assertEquals(42, v)
        // sequential re-acquire works
        ContainerLock.withLock(tmp.root, "a", exclusive = false) { }
    }
}
