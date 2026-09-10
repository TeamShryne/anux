package com.teamshryne.anux.distro

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GzipInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup/restore of a container as <alias>/manifest.json? + <alias>/rootfs/...
 * tar.gz (or plain tar). Mirrors proot-distro commands/backup.py + restore.py:
 * uid/gid normalized to 0, sockets/fifos/dev nodes skipped, path traversal rejected.
 */
object DistroBackup {
    fun backup(filesDir: File, alias: String, out: File, gzip: Boolean = true) {
        DistroInstaller.requireName(alias)
        val containerDir = File(filesDir, "containers/$alias")
        val rootfs = File(containerDir, "rootfs")
        if (!rootfs.isDirectory) throw IllegalArgumentException("container '$alias' has no rootfs")
        out.parentFile?.mkdirs()
        val tmp = File.createTempFile("backup-", ".part", out.parentFile ?: filesDir)
        try {
            val fileOut = BufferedOutputStream(tmp.outputStream(), 64 * 1024)
            val gzOut = if (gzip) GZIPOutputStream(fileOut) else fileOut
            TarArchiveOutputStream(gzOut).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                val manifest = File(containerDir, "manifest.json")
                if (manifest.isFile) addFile(tar, manifest, "$alias/manifest.json")
                addTree(tar, rootfs, "$alias/rootfs")
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("cannot move backup into place")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Restore from a backup file. Returns the container alias. */
    fun restore(filesDir: File, input: File): String {
        val stream0 = BufferedInputStream(input.inputStream(), 64 * 1024)
        stream0.mark(2)
        val b0 = stream0.read()
        val b1 = stream0.read()
        stream0.reset()
        val raw: java.io.InputStream =
            if (b0 == 0x1f && b1 == 0x8b) GzipInputStream(stream0) else stream0

        var alias: String? = null
        val pendingManifest = mutableMapOf<String, ByteArray>()
        TarArchiveInputStream(raw).use { tin ->
            var entry = tin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val parts = entry.name.split("/").filter { it.isNotEmpty() }
                    require(parts.size >= 2 && parts[0].isNotEmpty()) {
                        "bad backup layout: ${entry.name}"
                    }
                    val top = parts[0]
                    DistroInstaller.requireName(top)
                    if (alias == null) alias = top
                    require(alias == top) { "backup contains multiple containers" }
                    val rest = parts.drop(1)
                    require(rest[0] == "rootfs" || (rest.size == 1 && rest[0] == "manifest.json")) {
                        "unexpected backup path: ${entry.name}"
                    }
                    if (rest.size == 1) {
                        pendingManifest[top] = readEntryBytes(tin, entry.size)
                    } else {
                        val dest = safeDest(filesDir, top, rest.drop(1))
                        if (entry.isDirectory) {
                            dest.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            dest.parentFile?.mkdirs()
                            dest.delete()
                            runCatching {
                                java.nio.file.Files.createSymbolicLink(dest.toPath(), java.nio.file.Path.of(entry.linkName))
                            }
                        } else if (entry.isFile) {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> tin.copyTo(out) }
                        }
                    }
                }
                entry = tin.nextEntry
            }
        }
        val name = alias ?: throw IOException("empty backup")
        val containerDir = File(filesDir, "containers/$name")
        if (!File(containerDir, "rootfs").isDirectory) {
            throw IOException("backup has no rootfs for '$name'")
        }
        pendingManifest[name]?.let { File(containerDir, "manifest.json").writeBytes(it) }
        File(containerDir, "shm").mkdirs()
        return name
    }

    // -- internals --------------------------------------------------------

    /** Read exactly [size] bytes for the current tar entry (never past it). */
    private fun readEntryBytes(tin: TarArchiveInputStream, size: Long): ByteArray {
        require(size <= 16 * 1024 * 1024) { "manifest too large" }
        val out = java.io.ByteArrayOutputStream()
        var remaining = size
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = tin.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun addTree(tar: TarArchiveOutputStream, rootfs: File, prefix: String) {
        rootfs.walkTopDown().sortedBy { it.path.length }.forEach { f ->
            if (f == rootfs) return@forEach
            val rel = f.relativeTo(rootfs).invariantSeparatorsPath
            val entry = TarArchiveEntry(f, "$prefix/$rel")
            entry.userId = 0
            entry.groupId = 0
            entry.userName = "root"
            entry.groupName = "root"
            if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            } else if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            } else if (f.isFile) {
                tar.putArchiveEntry(entry)
                f.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
            // sockets, fifos, dev nodes: skipped
        }
    }

    private fun addFile(tar: TarArchiveOutputStream, file: File, name: String) {
        val entry = TarArchiveEntry(file, name)
        entry.userId = 0
        entry.groupId = 0
        tar.putArchiveEntry(entry)
        file.inputStream().use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }

    private fun safeDest(filesDir: File, alias: String, relParts: List<String>): File {
        require(relParts.isNotEmpty()) { "empty path in backup" }
        var cur = File(filesDir, "containers/$alias/rootfs").canonicalFile
        val root = cur
        for (p in relParts) {
            require(p != ".." && p.isNotEmpty() && '/' !in p && File.separatorChar !in p) {
                "unsafe backup path: $p"
            }
            cur = File(cur, p)
        }
        // Symlink-attack guard: canonical parent must stay under rootfs.
        val parent = (cur.parentFile ?: root).canonicalFile
        require(parent == root || parent.startsWith(root)) { "backup escapes rootfs" }
        return cur
    }
}
