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
        val containerDir = File(filesDir, "containers/$name")
        if (OciRef.isInstalled(filesDir, name)) {
            throw IllegalStateException("container '$name' is already installed")
        }
        // Clean any failed previous attempt.
        if (containerDir.exists()) containerDir.deleteRecursively()

        try {
            onProgress?.invoke(InstallProgress("resolving"))
            val image = registry.resolveImage(imageRef, arch)

            val layerCache = File(cacheDir, "oci_layers").apply { mkdirs() }
            val rootfs = File(containerDir, "rootfs").apply { mkdirs() }
            image.layers.forEachIndexed { i, blob ->
                onProgress?.invoke(InstallProgress("downloading", i, image.layers.size))
                val layerFile = registry.downloadBlob(image.canonicalRef, blob.digest, layerCache) { done, total ->
                    onProgress?.invoke(InstallProgress("downloading", i, image.layers.size, done, total))
                }
                onProgress?.invoke(InstallProgress("extracting", i, image.layers.size))
                LayerExtractor.applyLayer(layerFile, rootfs)
            }

            onProgress?.invoke(InstallProgress("configuring"))
            writeManifest(containerDir, imageRef, image, arch)
            fixupRootfs(rootfs)
            File(containerDir, "shm").apply {
                mkdirs()
                chmod1777(this)
            }

            onProgress?.invoke(InstallProgress("done"))
            ContainerInfo(name, imageRef, image.canonicalRef, arch)
        } catch (e: Exception) {
            containerDir.deleteRecursively()
            throw e
        }
    }

    fun uninstall(alias: String) {
        requireName(alias)
        val containerDir = File(filesDir, "containers/$alias")
        if (!containerDir.exists()) throw IllegalArgumentException("container '$alias' not found")
        containerDir.deleteRecursively()
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
        // Fake sysdata stubs (uptime/loadavg) bound over /proc at login.
        val sysdata = File(rootfs, "../sysdata").canonicalFile.apply { mkdirs() }
        File(sysdata, "loadavg").writeText("0.0 0.0 0.0 1/1 1\n")
    }

    private fun writeManifest(containerDir: File, imageRef: String, image: ResolvedImage, arch: CpuArch) {
        val json = JSONObject()
            .put("imageRef", imageRef)
            .put("canonicalRef", image.canonicalRef)
            .put("arch", arch.name)
            .put("configDigest", image.configDigest)
            .put("layers", image.layers.map { it.digest })
            .put("createdAt", System.currentTimeMillis())
        File(containerDir, "manifest.json").writeText(json.toString(2))
    }

    private fun chmod1777(dir: File) {
        // Best-effort world rwx (java.io only; avoids NIO POSIX edge cases).
        // The sticky bit is irrelevant inside the proot container.
        runCatching { dir.setReadable(true, false) }
        runCatching { dir.setWritable(true, false) }
        runCatching { dir.setExecutable(true, false) }
    }

    companion object {
        private val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

        fun requireName(name: String) {
            require(name.isNotEmpty() && NAME_RE.matches(name)) {
                "invalid container name '$name' (use [A-Za-z0-9_.-], must not start with . or -)"
            }
        }
    }
}
