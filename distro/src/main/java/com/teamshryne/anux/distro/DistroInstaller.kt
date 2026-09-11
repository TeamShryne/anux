package com.teamshryne.anux.distro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

data class ContainerInfo(
    val alias: String,
    val imageRef: String,
    val canonicalRef: String,
    val arch: CpuArch,
)

data class InstallProgress(
    val stage: String,
    val layerIndex: Int = 0,
    val layerCount: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
)

/**
 * Full OCI install flow: validate -> resolve -> download layers (cached,
 * digest-verified) -> apply -> write manifest.json -> rootfs fixups.
 * Mirrors proot-distro commands/install.py + helpers/rootfs.py.
 */
class DistroInstaller(
    private val filesDir: File,
    private val cacheDir: File,
    private val registry: DockerRegistry = DockerRegistry(),
    private val uid: Int = 0,
    private val gid: Int = 0,
) {
    suspend fun install(
        imageRef: String,
        alias: String? = null,
        arch: CpuArch = CpuArch.AARCH64,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ): ContainerInfo = withContext(Dispatchers.IO) {
        val name = alias ?: OciRef.localName(imageRef)
        requireName(name)
        // Exclusive lock: concurrent install/remove of the same container corrupts it.
        ContainerLock.withLockSuspend(filesDir, name, exclusive = true) {
            installLocked(imageRef, name, arch, onProgress)
        }
    }

    private suspend fun installLocked(
        imageRef: String,
        name: String,
        arch: CpuArch,
        onProgress: ((InstallProgress) -> Unit)?,
    ): ContainerInfo {
        val containerDir = File(filesDir, "containers/$name")
        // Staging dir: a killed process leaves partial output here, which —
        // unlike the final dir — is never treated as installed.
        val stagingDir = File(filesDir, "containers/$name.part")
        if (OciRef.isInstalled(filesDir, name)) {
            throw IllegalStateException("container '$name' is already installed")
        }
        // Clean any failed previous attempt (staging from this version, or a
        // partial final dir left by older versions that wrote in place).
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (containerDir.exists()) containerDir.deleteRecursively()

        return try {
            onProgress?.invoke(InstallProgress("resolving"))
            val image = registry.resolveImage(imageRef, arch)

            val layerCache = File(cacheDir, "oci_layers").apply { mkdirs() }
            val rootfs = File(stagingDir, "rootfs").apply { mkdirs() }
            image.layers.forEachIndexed { i, blob ->
                onProgress?.invoke(InstallProgress("downloading", i, image.layers.size))
                val layerFile = registry.downloadBlob(image.canonicalRef, blob.digest, layerCache) { done, total ->
                    onProgress?.invoke(InstallProgress("downloading", i, image.layers.size, done, total))
                }
                onProgress?.invoke(InstallProgress("extracting", i, image.layers.size))
                LayerExtractor.applyLayer(layerFile, rootfs)
            }

            onProgress?.invoke(InstallProgress("configuring"))
            writeManifest(stagingDir, imageRef, image, arch)
            fixupRootfs(rootfs)
            val stagingContainer = stagingDir
            ProotArgs.ensureShm(stagingContainer)
            ProotArgs.ensureGuestTmp(rootfs)
            ProotArgs.ensureSysdata(rootfs, stagingContainer)

            // Atomic publish: only a fully-written tree ever becomes the container.
            // Same parent dir => same filesystem => dir rename is atomic.
            if (containerDir.exists()) containerDir.deleteRecursively()
            require(stagingDir.renameTo(containerDir)) { "cannot publish container '$name'" }

            onProgress?.invoke(InstallProgress("done"))
            ContainerInfo(name, imageRef, image.canonicalRef, arch)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw e
        }
    }

    fun uninstall(alias: String) {
        requireName(alias)
        ContainerLock.withLock(filesDir, alias, exclusive = true) {
            val containerDir = File(filesDir, "containers/$alias")
            val stagingDir = File(filesDir, "containers/$alias.part")
            if (!containerDir.exists() && !stagingDir.exists()) {
                throw IllegalArgumentException("container '$alias' not found")
            }
            // Fix chmod-000'd files on the fly so the rootfs can always be cleared.
            runCatching { fixPermissions(containerDir) }
            runCatching { fixPermissions(stagingDir) }
            containerDir.deleteRecursively()
            stagingDir.deleteRecursively()
        }
    }

    // -- internals --------------------------------------------------------

    internal fun fixupRootfs(rootfs: File) {
        // resolv.conf: replace (even symlink) with static DNS, never follow links.
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        Files.deleteIfExists(resolv.toPath())
        resolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        // hosts: static localhost block.
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        // Android user injection so `id`/permissions look sane inside.
        val passwd = File(rootfs, "etc/passwd")
        if (passwd.isFile && uid != 0) {
            val line = "aid_user:x:$uid:$gid:Android user:/data/data/com.teamshryne.anux/files/home:/bin/sh\n"
            if (!passwd.readText().contains("aid_user:")) passwd.appendText(line)
        }
        // Fake sysdata stubs (bound over /proc at login, mirrors sysdata.py).
        // Written here so the container is complete even if login never runs
        // setup; login re-validates and re-creates missing entries.
        ProotArgs.ensureSysdata(rootfs, File(rootfs, "../").canonicalFile)
    }

    private fun writeManifest(containerDir: File, imageRef: String, image: ResolvedImage, arch: CpuArch) {
        // NOTE: wrap lists in JSONArray explicitly. JSONObject.put(String, Collection)
        // exists in org.json:json (unit tests) but NOT in Android's org.json —
        // on-device it throws NoSuchMethodError.
        val json = JSONObject()
            .put("imageRef", imageRef)
            .put("canonicalRef", image.canonicalRef)
            .put("arch", arch.name)
            .put("configDigest", image.configDigest)
            .put("layers", org.json.JSONArray(image.layers.map { it.digest }))
            // Image config: login needs Env + WorkingDir (mirrors image_env_pairs).
            .put("env", org.json.JSONArray(image.env))
            .put("workingDir", image.workingDir.ifEmpty { "/root" })
            .put("createdAt", System.currentTimeMillis())
        File(containerDir, "manifest.json").writeText(json.toString(2))
    }

    private fun fixPermissions(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach {
            runCatching { it.setReadable(true) }
            runCatching { it.setWritable(true) }
            if (it.isDirectory) runCatching { it.setExecutable(true) }
        }
    }

    companion object {        private val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

        fun requireName(name: String) {
            require(name.isNotEmpty() && NAME_RE.matches(name)) {
                "invalid container name '$name' (use [A-Za-z0-9_.-], must not start with . or -)"
            }
        }
    }
}
