package com.teamshryne.anux.distro

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class DockerRegistryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun manifestJson(configDigest: String, layerDigest: String) = JSONObject()
        .put("schemaVersion", 2)
        .put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
        .put("config", JSONObject().put("digest", configDigest).put("size", 10))
        .put("layers", org.json.JSONArray().put(
            JSONObject().put("digest", layerDigest).put("size", 20),
        )).toString()

    @Test
    fun `resolve direct manifest without auth`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val configJson = JSONObject()
                .put("config", JSONObject()
                    .put("Env", org.json.JSONArray().put("PATH=/usr/bin"))
                    .put("WorkingDir", "/home/u"))
                .toString()
            val configDigest = sha(configJson.toByteArray())
            val layerDigest = sha("layer".toByteArray())
            server.enqueue(
                MockResponse().setBody(manifestJson(configDigest, layerDigest))
                    .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json"),
            )
            server.enqueue(MockResponse().setBody(configJson))

            val reg = DockerRegistry(useHttpForCustomRegistries = true)
            val img = reg.resolveImage("127.0.0.1:${server.port}/img:latest", CpuArch.AARCH64)
            assertEquals(1, img.layers.size)
            assertEquals(layerDigest, img.layers[0].digest)
            assertEquals(listOf("PATH=/usr/bin"), img.env)
            assertEquals("/home/u", img.workingDir)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `bearer token flow`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return when {
                    req.path?.endsWith("/manifests/latest") == true &&
                        req.getHeader("Authorization") == null ->
                        MockResponse().setResponseCode(401).setHeader(
                            "WWW-Authenticate",
                            "Bearer realm=\"http://127.0.0.1:${server.port}/token\"," +
                                "service=\"svc\",scope=\"repository:lib/img:pull\"",
                        )
                    req.path == "/token?service=svc&scope=repository:lib/img:pull" ->
                        MockResponse().setBody("""{"token":"tok123"}""")
                    req.path?.endsWith("/manifests/latest") == true ->
                        MockResponse().setBody(
                            manifestJson(sha("{}".toByteArray()), sha("x".toByteArray())),
                        ).setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
                    req.path?.contains("/blobs/") == true ->
                        MockResponse().setBody(JSONObject().put("config", JSONObject()).toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val reg = DockerRegistry(useHttpForCustomRegistries = true)
            val img = reg.resolveImage("127.0.0.1:${server.port}/img", CpuArch.AARCH64)
            assertEquals(1, img.layers.size)
            // token path must have been hit
            assertTrue(server.requestCount >= 3)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `index picks matching arch`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val armDigest = sha("arm-manifest".toByteArray())
            val index = JSONObject()
                .put("schemaVersion", 2)
                .put("mediaType", "application/vnd.oci.image.index.v1+json")
                .put("manifests", org.json.JSONArray()
                    .put(JSONObject()
                        .put("digest", sha("amd-manifest".toByteArray()))
                        .put("platform", JSONObject().put("architecture", "amd64").put("os", "linux")))
                    .put(JSONObject()
                        .put("digest", armDigest)
                        .put("platform", JSONObject().put("architecture", "arm64").put("os", "linux"))))
                .toString()
            val manifest = manifestJson(sha("{}".toByteArray()), sha("y".toByteArray()))
            server.enqueue(MockResponse().setBody(index)
                .setHeader("Content-Type", "application/vnd.oci.image.index.v1+json"))
            server.enqueue(MockResponse().setBody(manifest)
                .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json"))
            server.enqueue(MockResponse().setBody(JSONObject().put("config", JSONObject()).toString()))

            val reg = DockerRegistry(useHttpForCustomRegistries = true)
            reg.resolveImage("127.0.0.1:${server.port}/img:1.0", CpuArch.AARCH64)
            // second request must target the arm64 manifest digest
            server.takeRequest() // tag
            val byDigest = server.takeRequest()
            assertTrue(byDigest.path!!.contains(armDigest))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blob download verifies digest`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val bytes = "hello-layer".toByteArray()
                return MockResponse().setBody(Buffer().write(bytes))
            }
        }
        server.start()
        try {
            val reg = DockerRegistry(useHttpForCustomRegistries = true)
            val ref = "127.0.0.1:${server.port}/img"
            val good = sha("hello-layer".toByteArray())
            val file = reg.downloadBlob(ref, good, tmp.root)
            assertTrue(file.isFile)
            assertEquals("hello-layer", file.readText())
            // cached hit: dispatcher would fail if called again? just call again, still fine
            reg.downloadBlob(ref, good, tmp.root)

            try {
                reg.downloadBlob(ref, sha("other".toByteArray()), tmp.newFolder())
                fail("expected DigestMismatchException")
            } catch (e: DigestMismatchException) {
                // expected
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `challenge parsing`() {
        val c = DockerRegistry.parseChallenge(
            "Bearer realm=\"https://auth.example/token\",service=\"s\",scope=\"repository:a/b:pull\"",
        )!!
        assertEquals("https://auth.example/token", c.realm)
        assertEquals("s", c.service)
        assertEquals("repository:a/b:pull", c.scope)
        assertNull(DockerRegistry.parseChallenge("Basic abc"))
    }
}
