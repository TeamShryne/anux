package com.teamshryne.anux.distro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RegistryException(message: String, cause: Throwable? = null) : IOException(message, cause)
class DigestMismatchException(digest: String) : IOException("digest mismatch for $digest")

data class ImageBlobRef(val digest: String, val size: Long)

data class ResolvedImage(
    val canonicalRef: String,
    val configDigest: String,
    val configJson: String,
    val layers: List<ImageBlobRef>,
    val env: List<String>,
    val workingDir: String,
)

/**
 * Minimal OCI/Docker registry client (pull-only), ported from
 * proot-distro helpers/docker sources. Blocking OkHttp calls run on Dispatchers.IO.
 *
 * @param useHttpForCustomRegistries allow plain http for host:port registries
 *   (used by tests; production registries stay https).
 */
class DockerRegistry(
    private val client: OkHttpClient = defaultHttpClient(),
    private val useHttpForCustomRegistries: Boolean = false,
) {
    suspend fun resolveImage(imageRef: String, arch: CpuArch): ResolvedImage =
        withContext(Dispatchers.IO) {
            val parsed = OciRef.parse(imageRef)
            val base = baseUrl(parsed.registry)
            val session = AuthedSession(base, parsed.repo)

            val (firstBody, firstType) = session.getManifest("${parsed.tag}")
            val manifestBody: String = if (isIndex(firstType) || looksLikeIndex(firstBody)) {
                val digest = pickPlatform(firstBody, arch)
                session.getManifest(digest).first
            } else {
                firstBody
            }

            val manifest = JSONObject(manifestBody)
            val configDigest = manifest.getJSONObject("config").getString("digest")
            val layers = manifest.getJSONArray("layers").let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ImageBlobRef(o.getString("digest"), o.optLong("size", -1L))
                }
            }
            if (layers.isEmpty()) throw RegistryException("image has no layers: $imageRef")

            val configJson = session.getBlob(configDigest)
            val config = JSONObject(configJson).optJSONObject("config") ?: JSONObject()
            // Docker uses "Env" (capital E); accept lowercase too.
            val envArr = config.optJSONArray("Env") ?: config.optJSONArray("env")
            val env = envArr?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val workingDir = config.optString("WorkingDir", "").ifEmpty { "/root" }

            ResolvedImage(parsed.canonicalRef, configDigest, configJson, layers, env, workingDir)
        }

    /** Download a blob to [destDir]/<sha256>, verifying the digest. Returns the file. */
    suspend fun downloadBlob(
        imageRef: String,
        digest: String,
        destDir: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val parsed = OciRef.parse(imageRef)
        val base = baseUrl(parsed.registry)
        val session = AuthedSession(base, parsed.repo)
        val algoAndHex = digest.split(":", limit = 2)
        if (algoAndHex.size != 2 || algoAndHex[0] != "sha256") {
            throw RegistryException("unsupported digest: $digest")
        }
        val hex = algoAndHex[1]
        destDir.mkdirs()
        val dest = File(destDir, hex)
        if (dest.isFile && sha256Hex(dest) == hex) return@withContext dest

        val tmp = File.createTempFile("blob-", ".part", destDir)
        try {
            session.downloadBlob(digest, tmp, onProgress)
            if (sha256Hex(tmp) != hex) throw DigestMismatchException(digest)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw RegistryException("cannot move blob into cache")
            }
            dest
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    // -- internals ----------------------------------------------------------

    private inner class AuthedSession(val base: String, val repo: String) {
        var token: String? = null

        fun getManifest(reference: String): Pair<String, String> {
            val url = "$base/v2/$repo/manifests/$reference"
            val accept = listOf(
                "application/vnd.oci.image.index.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json",
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.v2+json",
            ).joinToString(", ")
            val req = Request.Builder().url(url).header("Accept", accept).apply {
                token?.let { header("Authorization", "Bearer $it") }
            }.build()
            val res = client.newCall(req).execute()
            res.use {
                if (it.code == 401 && token == null) {
                    token = fetchToken(it.header("WWW-Authenticate"))
                    return getManifest(reference)
                }
                if (!it.isSuccessful) throw RegistryException("manifest $reference: HTTP ${it.code}")
                val body = it.body?.string() ?: throw RegistryException("empty manifest body")
                return body to (it.header("Content-Type").orEmpty())
            }
        }

        fun getBlob(digest: String): String {
            val url = "$base/v2/$repo/blobs/$digest"
            val res = client.newCall(
                Request.Builder().url(url).apply {
                    token?.let { header("Authorization", "Bearer $it") }
                }.build(),
            ).execute()
            res.use {
                if (it.code == 401 && token == null) {
                    token = fetchToken(it.header("WWW-Authenticate"))
                    return getBlob(digest)
                }
                if (!it.isSuccessful) throw RegistryException("blob $digest: HTTP ${it.code}")
                return it.body?.string() ?: throw RegistryException("empty blob body")
            }
        }

        fun downloadBlob(digest: String, dest: File, onProgress: ((Long, Long) -> Unit)?) {
            val url = "$base/v2/$repo/blobs/$digest"
            fun attempt(): Unit {
                val res = client.newCall(
                    Request.Builder().url(url).apply {
                        token?.let { header("Authorization", "Bearer $it") }
                    }.build(),
                ).execute()
                res.use {
                    if (it.code == 401 && token == null) {
                        token = fetchToken(it.header("WWW-Authenticate"))
                        return attempt()
                    }
                    if (!it.isSuccessful) throw RegistryException("blob $digest: HTTP ${it.code}")
                    val body = it.body ?: throw RegistryException("empty blob body")
                    val total = body.contentLength()
                    var done = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (onProgress != null && done - lastReport >= 256 * 1024) {
                                    lastReport = done
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                    onProgress?.invoke(done, total)
                }
            }
            attempt()
        }

        private fun fetchToken(wwwAuth: String?): String {
            val c = wwwAuth?.let { parseChallenge(it) }
                ?: throw RegistryException("401 without Bearer challenge")
            val url = buildString {
                append(c.realm)
                append(if ('?' in c.realm) '&' else '?')
                if (c.service.isNotEmpty()) append("service=${c.service}&")
                append("scope=${c.scope.ifEmpty { "repository:$repo:pull" }}")
            }
            client.newCall(Request.Builder().url(url).build()).execute().use {
                if (!it.isSuccessful) throw RegistryException("token endpoint: HTTP ${it.code}")
                val json = JSONObject(it.body?.string().orEmpty())
                return json.optString("token").ifEmpty { json.optString("access_token") }
                    .ifEmpty { throw RegistryException("token endpoint returned no token") }
            }
        }
    }

    internal fun baseUrl(registry: String): String {
        if (registry.isEmpty() || registry == "docker.io" || registry == "index.docker.io") {
            return "https://registry-1.docker.io"
        }
        if (registry.startsWith("http://") || registry.startsWith("https://")) return registry
        return if (useHttpForCustomRegistries) "http://$registry" else "https://$registry"
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun parseChallenge(header: String): AuthChallenge? {
            if (!header.startsWith("Bearer ", ignoreCase = true)) return null
            val params = Regex("(\\w+)=\"([^\"]*)\"")
                .findAll(header.drop(7))
                .associate { it.groupValues[1] to it.groupValues[2] }
            val realm = params["realm"] ?: return null
            return AuthChallenge(realm, params["service"].orEmpty(), params["scope"].orEmpty())
        }

        internal fun isIndex(contentType: String): Boolean =
            "manifest.list" in contentType || "image.index" in contentType

        internal fun looksLikeIndex(body: String): Boolean {
            if (body.isEmpty() || body[0] != '{') return false
            return runCatching {
                val json = JSONObject(body)
                val mediaType = json.optString("mediaType")
                if ("manifest.list" in mediaType || "image.index" in mediaType) return true
                json.has("manifests")
            }.getOrDefault(false)
        }

        private fun archMatches(entryArch: String, arch: CpuArch): Boolean {
            val a = entryArch.lowercase()
            val want = arch.dockerArch.lowercase()
            if (a == want) return true
            // Tolerate registry aliases: aarch64<->arm64, x86_64<->amd64, i386/i686<->386.
            return when (want) {
                "arm64" -> a == "aarch64"
                "amd64" -> a == "x86_64"
                "386" -> a == "i386" || a == "i686" || a == "x86"
                "arm" -> a == "armhfp" || a == "armhf"
                else -> false
            }
        }

        private fun variantMatches(entryVariant: String, arch: CpuArch): Boolean {
            val v = entryVariant.lowercase()
            // Registries often omit variant; treat that as a wildcard.
            if (v.isEmpty()) return true
            val want = arch.dockerVariant?.lowercase()
            if (v == want) return true
            return when (arch) {
                // Docker Hub reports arm64 as v8; accept a missing/empty match too.
                CpuArch.AARCH64 -> v == "v8"
                // 32-bit ARM devices can run older variants; prefer v7 but accept v6/v5.
                CpuArch.ARM -> v == "v6" || v == "v5"
                else -> false
            }
        }

        internal fun pickPlatform(indexJson: String, arch: CpuArch): String {
            val manifests = JSONObject(indexJson).getJSONArray("manifests")
            val available = mutableListOf<String>()
            // First pass: linux + arch + variant match (mirrors proot-distro arch mapping).
            for (i in 0 until manifests.length()) {
                val m = manifests.getJSONObject(i)
                val p = m.optJSONObject("platform")
                val a = p?.optString("architecture").orEmpty()
                val os = p?.optString("os").orEmpty().lowercase()
                val v = p?.optString("variant", "").orEmpty()
                if (a.isNotEmpty()) {
                    available += "$os/$a${if (v.isNotEmpty()) "/$v" else ""}"
                }
                if (p == null) continue
                // Skip non-Linux entries (e.g. windows/amd64) when OS is known.
                if (os.isNotEmpty() && os != "linux") continue
                if (archMatches(a, arch) && variantMatches(v, arch)) {
                    return m.getString("digest")
                }
            }
            // Second pass: some single-arch indexes omit the platform object entirely.
            if (manifests.length() == 1) {
                val only = manifests.getJSONObject(0)
                if (only.optJSONObject("platform") == null) {
                    return only.getString("digest")
                }
            }
            throw RegistryException(
                "no ${arch.dockerArch} image in index" +
                    if (available.isNotEmpty()) " (available: ${available.joinToString()})" else "",
            )
        }
    }
}

internal data class AuthChallenge(val realm: String, val service: String, val scope: String)
